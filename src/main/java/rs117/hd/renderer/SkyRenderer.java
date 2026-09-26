package rs117.hd.renderer;

import java.io.IOException;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.StarMode;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.SkyShaderProgram;
import rs117.hd.opengl.shader.StarShaderProgram;
import rs117.hd.opengl.uniforms.UBOGlobal;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.SkyManager;
import rs117.hd.scene.daylight_cycle.SkyConfiguration;
import rs117.hd.scene.daylight_cycle.SkyState;
import rs117.hd.scene.daylight_cycle.SkyState.GradientSample;
import rs117.hd.scene.daylight_cycle.StarField;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.utils.ColorUtils.linearSrgbLuminance;
import static rs117.hd.utils.ColorUtils.linearToSrgb;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class SkyRenderer {
	private static final float[] BLACK = { 0, 0, 0 };
	private static final float SHADOW_HANDOFF_MIN_CONTRAST = 1 / 255.f;
	private static final float SHADOW_HANDOFF_MAX_CONTRAST = 3 / 255.f;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SkyManager skyManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private StarField starField;

	@Inject
	private SkyShaderProgram skyProgram;

	@Inject
	private StarShaderProgram starProgram;

	private final CommandBuffer commandBuffer = new CommandBuffer("Sky");
	private final RenderState localRenderState = new RenderState();
	private final float[] directionalLight = new float[3];
	private final float[] ambientLight = new float[3];
	private final float[] waterColor = new float[3];
	private final float[] endpointFogColor = new float[3];
	private final SkyState.LightingSample endpointSample = new SkyState.LightingSample();
	private final LightingFrame fromFrame = new LightingFrame();
	private final LightingFrame toFrame = new LightingFrame();
	private final LightingFrame currentFrame = new LightingFrame();
	private long transitionId;
	private float previousTransition = 1;
	private boolean interruptedTransition;
	private final float[] fogColor = new float[3];
	private boolean skyEnabled;
	public boolean castsShadows;
	public boolean usesMoonShadows;

	private static final class LightingFrame extends GradientSample {
		private final float[] directionalLight = new float[3];
		private final float[] ambientLight = new float[3];
		private final float[] sunDirectionalLight = new float[3];
		private final float[] moonDirectionalLight = new float[3];
		private final float[] fog = new float[3];
		private final float[] moonDisk = new float[3];
		private final SkyConfiguration configuration = new SkyConfiguration();
		private float fogDensity;
		private float visibility;
		private float customGradient;
		private float moonShadowFade;
		private float adaptationLuminance;

		private LightingFrame() {
			zenithLinear = new float[3];
			horizonLinear = new float[3];
			sunGlowLinear = new float[3];
		}

		private void interpolate(LightingFrame from, LightingFrame to, float t) {
			moonShadowFade = mix(from.moonShadowFade, to.moonShadowFade, t);
			adaptationLuminance = mix(from.adaptationLuminance, to.adaptationLuminance, t);
			mix(directionalLight, from.directionalLight, to.directionalLight, t);
			mix(ambientLight, from.ambientLight, to.ambientLight, t);
			mix(fog, from.fog, to.fog, t);
			mix(moonDisk, from.moonDisk, to.moonDisk, t);
			mix(zenithLinear, from.zenithLinear, to.zenithLinear, t);
			mix(horizonLinear, from.horizonLinear, to.horizonLinear, t);
			mix(sunGlowLinear, from.sunGlowLinear, to.sunGlowLinear, t);
			fogDensity = mix(from.fogDensity, to.fogDensity, t);
			visibility = mix(from.visibility, to.visibility, t);
			customGradient = mix(from.customGradient, to.customGradient, t);
			configuration.interpolateLightingParameters(from.configuration, to.configuration, t);
		}
	}

	public void initialize() {
		previousTransition = 1;
		interruptedTransition = false;
		commandBuffer.setFrameTimer(frameTimer);
		commandBuffer.reset();
		starField.initialize();
	}

	public void destroy() {
		starField.destroy();
		commandBuffer.reset();
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		skyProgram.compile(includes);
		starField.initializeShaders(includes);
		starProgram.compile(includes);
		starField.resetStarfield();
		commandBuffer.reset();
	}

	public void destroyShaders() {
		skyProgram.destroy();
		starField.destroyShaders();
		starProgram.destroy();
	}

	public void processConfigChanges(Set<String> keys) {
		if (keys.contains(KEY_NEBULAE))
			starField.resetStarfield();
		if (keys.contains(KEY_STARS))
			commandBuffer.reset();
	}

	/**
	 * Complete lighting and shadow eligibility after SkyManager.update, before drawing shadows.
	 * Upload sky resources here; the caller owns the global UBO upload.
	 */
	public void prepareFrame(UBOGlobal uboGlobal) {
		boolean wasSkyEnabled = skyEnabled;
		skyEnabled = skyManager.getState().cycleActive;
		if (skyEnabled != wasSkyEnabled)
			commandBuffer.reset();

		Environment env = environmentManager.getCurrentEnvironment();
		copyTo(directionalLight, env.getDirectionalColor());
		multiply(directionalLight, directionalLight, env.directionalStrength);
		copyTo(ambientLight, env.getAmbientColor());
		multiply(ambientLight, ambientLight, env.ambientStrength);
		copyTo(waterColor, env.getWaterColor());
		copyTo(fogColor, env.getFogColor());

		if (skyEnabled)
			updateSky(skyManager.getState());
		else {
			usesMoonShadows = false;
			previousTransition = 1;
			interruptedTransition = false;
			plugin.uboSky.gradientEnabled.set(0);
			plugin.uboSky.upload();
		}
		updateGlobalUbo(uboGlobal);

		updateCommandBuffer();
	}

	public boolean shouldReplaceVanillaSkybox() {
		return
			skyEnabled &&
			skyProgram.isValid() &&
			!plugin.orthographicProjection;
	}

	public boolean shouldRender(boolean hasVanillaSkybox) {
		return skyProgram.isValid() && !hasVanillaSkybox;
	}

	public void clear(boolean hasVanillaSkybox) {
		frameTimer.begin(Timer.CLEAR_SCENE);

		glClearDepth(0);

		if (shouldRender(hasVanillaSkybox)) {
			glClear(GL_DEPTH_BUFFER_BIT);
		} else {
			float[] fogColorSrgb = hasVanillaSkybox ? BLACK : linearToSrgb(fogColor);
			float[] gammaCorrectedFogColor = pow(fogColorSrgb, plugin.getGammaCorrection());
			glClearColor(
				gammaCorrectedFogColor[0],
				gammaCorrectedFogColor[1],
				gammaCorrectedFogColor[2],
				1f
			);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		}

		frameTimer.end(Timer.CLEAR_SCENE);
	}

	public void appendTo(CommandBuffer target) {
		target.ExecuteSubCommandBuffer(commandBuffer);
	}

	public void renderImmediately() {
		clear(false);
		if (shouldRender(false)) {
			localRenderState.reset();
			commandBuffer.execute(localRenderState);
		}
	}

	private void updateCommandBuffer() {
		boolean starfieldChanged = skyEnabled && starField.rebuildIfNeeded();
		if (!starfieldChanged && !commandBuffer.isEmpty())
			return;

		commandBuffer.reset();
		commandBuffer.PushTimer(Timer.RENDER_SKY);
		commandBuffer.Disable(GL_BLEND);
		commandBuffer.SetShader(skyProgram);
		commandBuffer.DepthMask(false);
		commandBuffer.BindVertexArray(plugin.vaoTri);
		commandBuffer.DrawArrays(GL_TRIANGLES, 0, 3);

		if (skyEnabled && config.starMode() != StarMode.OFF && starProgram.isValid() && starField.getVaoStars() != 0) {
			commandBuffer.SetShader(starProgram);
			commandBuffer.Enable(GL_PROGRAM_POINT_SIZE);
			if (!GL_CAPS.forwardCompatible)
				commandBuffer.Enable(GL20.GL_POINT_SPRITE);
			commandBuffer.Enable(GL_BLEND);
			commandBuffer.BlendFunc(GL_ONE, GL_ONE, GL_ONE, GL_ONE);
			commandBuffer.BindVertexArray(starField.getVaoStars());
			commandBuffer.DrawArrays(GL_POINTS, 0, starField.starCount);
			commandBuffer.BlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
			commandBuffer.Disable(GL_BLEND);
			if (!GL_CAPS.forwardCompatible)
				commandBuffer.Disable(GL20.GL_POINT_SPRITE);
			commandBuffer.Disable(GL_PROGRAM_POINT_SIZE);
		}

		commandBuffer.DepthMask(true);
		commandBuffer.PopTimer(Timer.RENDER_SKY);
	}

	private void updateGlobalUbo(UBOGlobal ubo) {
		ubo.fogColor.set(fogColor);
		float[] waterColorHsv = ColorUtils.srgbToHsv(waterColor);
		ubo.waterColorLight.set(linearToSrgb(ColorUtils.hsvToSrgb(waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .8f)));
		ubo.waterColorMid.set(linearToSrgb(ColorUtils.hsvToSrgb(waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .45f)));
		ubo.waterColorDark.set(linearToSrgb(ColorUtils.hsvToSrgb(waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .05f)));

		if (config.useLegacyBrightness()) {
			float factor = (float) config.legacyBrightness() / 20;
			multiply(ambientLight, ambientLight, factor);
			multiply(directionalLight, directionalLight, factor);
		}
		float effectiveAmbientStrength = linearSrgbLuminance(ambientLight);
		float effectiveDirectionalStrength = linearSrgbLuminance(directionalLight);
		divide(ambientLight, ambientLight, effectiveAmbientStrength);
		divide(directionalLight, directionalLight, effectiveDirectionalStrength);
		castsShadows = effectiveDirectionalStrength > 0;
		ubo.ambientStrength.set(effectiveAmbientStrength);
		ubo.ambientColor.set(ambientLight);
		ubo.lightStrength.set(effectiveDirectionalStrength);
		ubo.lightColor.set(directionalLight);
	}

	private void updateSky(SkyState state) {
		float transition = state.transitionProgress;
		if (transitionId != state.transitionId) {
			transitionId = state.transitionId;
			interruptedTransition = previousTransition < 1 && transition < 1;
			if (interruptedTransition)
				fromFrame.interpolate(currentFrame, currentFrame, 1);
		}
		evaluateLighting(toFrame, state.toEnvironment);
		if (transition < 1) {
			if (!interruptedTransition)
				evaluateLighting(fromFrame, state.fromEnvironment);
			currentFrame.interpolate(fromFrame, toFrame, transition);
		} else {
			currentFrame.interpolate(toFrame, toFrame, 1);
		}
		previousTransition = transition;
		// Blend complete HDR light contributions before encoding the global UBO.
		copyTo(directionalLight, currentFrame.directionalLight);
		copyTo(ambientLight, currentFrame.ambientLight);
		float exposure = getNightExposure(currentFrame.adaptationLuminance);
		log.debug(
			"ambientLight: {}, directionalLight: {}, exposure: {}",
			ambientLight,
			directionalLight,
			exposure
		);
		multiply(ambientLight, ambientLight, exposure);
		multiply(directionalLight, directionalLight, exposure);
		usesMoonShadows = currentFrame.moonShadowFade > 0;
		copyTo(fogColor, currentFrame.horizonLinear);
		copyTo(waterColor, currentFrame.horizonLinear);
		plugin.uboSky.fogDensity.set(currentFrame.fogDensity);
		plugin.uboSky.visibility.set(currentFrame.visibility);
		plugin.uboSky.fogColor.set(currentFrame.fog);
		plugin.uboSky.customGradient.set(currentFrame.customGradient);
		plugin.uboSky.moonDiskColor.set(currentFrame.moonDisk);
		updateSkyUbo(currentFrame.configuration, state, currentFrame);
	}

	private void evaluateLighting(LightingFrame out, Environment env) {
		SkyConfiguration sky = env.getSky();
		copyTo(endpointFogColor, env.getFogColor());
		environmentManager.applyLightning(endpointFogColor);
		skyManager.sampleLighting(endpointSample, env, endpointFogColor);
		SkyState state = endpointSample.sky;
		float sunAltDeg = state.sunAltitudeDegrees;
		multiply(out.sunDirectionalLight, env.getDirectionalColor(), env.directionalStrength);
		multiply(out.ambientLight, env.getAmbientColor(), env.ambientStrength);
		float moonAltDeg = state.moonAltitudeDegrees;

		float moonLightIllumination = state.moonLightIllumination;
		// Moonlight is already negligible beside daylight. Bring it to its natural strength
		// before the shadow camera changes source, then vary only its shadow contrast.
		float moonLighting = moonLightIllumination * smoothstep(5, 0, sunAltDeg);
		// fogDepth is an artistic density control, not a physical extinction coefficient.
		float defaultDensity = .6f + (exp(6.7f * env.fogDepth / 100) - 1);
		out.fogDensity = max(0, sky.skyFogDensity < 0 ? defaultDensity : sky.skyFogDensity);
		out.visibility = saturate(sky.skyVisibility);
		copyTo(out.fog, endpointSample.horizonLinear);
		if (sky.skyFogColor != null)
			mix(out.fog, out.fog, sky.skyFogColor, saturate(sky.skyFogColorMix));

		multiply(out.moonDirectionalLight, sky.moonDirectionalColor, state.moonDirectionalStrength * moonLighting);
		float[] moonAmbientLight = multiply(sky.moonAmbientColor, sky.moonAmbientStrength * moonLighting);
		// Infer effective extinction from the authored overhead lighting. Half of the
		// scattered energy is assumed to travel downward: T = D / (D + 2A).
		// These artistic inputs cannot distinguish scattering from absorption or bounce.
		float[] opticalDepth = new float[3];
		for (int i = 0; i < opticalDepth.length; i++) {
			float direct = out.sunDirectionalLight[i];
			float incident = direct + 2 * out.ambientLight[i];
			opticalDepth[i] = incident > 0 ? -log(max(direct / incident, 1e-4f)) : 0;
		}
		applyAtmosphere(out.sunDirectionalLight, out.ambientLight, opticalDepth, sunAltDeg);
		applyAtmosphere(out.moonDirectionalLight, moonAmbientLight, opticalDepth, moonAltDeg);
		add(out.ambientLight, out.ambientLight, moonAmbientLight);
		// Artistic airglow/starlight baseline, independent of the sun and moon's illumination.
		// Include it in exposure metering; endpoint HDR interpolation handles environment transitions.
		for (int i = 0; i < out.ambientLight.length; i++)
			out.ambientLight[i] += sky.nightAmbientColor[i] * sky.nightAmbientStrength;
		float ambientLuminance = linearSrgbLuminance(out.ambientLight);
		float sunLuminance = linearSrgbLuminance(out.sunDirectionalLight);
		float moonLuminance = linearSrgbLuminance(out.moonDirectionalLight);
		// Meter the established lighting so adaptation does not compensate for the handoff.
		out.adaptationLuminance = ambientLuminance + (sunLuminance + moonLuminance) * .25f;
		float exposure = getNightExposure(out.adaptationLuminance);
		float litLuminance = (ambientLuminance + sunLuminance + moonLuminance) * exposure;
		float shadowedLuminance = max(0, litLuminance - sunLuminance * getShadowVisibility(sunAltDeg, .533f) * exposure);
		// Switch sources while the disappearing sun shadow spans only a few display values.
		// Applying exposure first keeps eye adaptation from hiding an otherwise visible shadow.
		float sunShadowContrast = linearToSrgb(litLuminance) - linearToSrgb(shadowedLuminance);
		out.moonShadowFade = moonAltDeg > 0 && moonLightIllumination > 0 ?
			1 - smoothstep(SHADOW_HANDOFF_MIN_CONTRAST, SHADOW_HANDOFF_MAX_CONTRAST, sunShadowContrast) : 0;

		copyTo(out.directionalLight, BLACK);

		distributeDirectionalLight(
			out,
			out.sunDirectionalLight,
			sunAltDeg,
			.533f,
			1,
			out.moonShadowFade > 0 ? 0 : 1
		);

		distributeDirectionalLight(
			out,
			out.moonDirectionalLight,
			moonAltDeg,
			.517f,
			saturate(sky.moonShadowStrength),
			out.moonShadowFade
		);
		copyTo(out.zenithLinear, endpointSample.zenithLinear);
		copyTo(out.horizonLinear, endpointSample.horizonLinear);
		copyTo(out.sunGlowLinear, endpointSample.sunGlowLinear);
		multiply(out.moonDisk, sky.moonDiskColor, sky.moonDiskStrength);
		out.customGradient = sky.customGradient ? 1 : 0;
		out.configuration.interpolateLightingParameters(sky, sky, 1);
	}

	private static void applyAtmosphere(float[] directional, float[] ambient, float[] opticalDepth, float altitudeDegrees) {
		// Approximate air mass with a finite horizon path: one overhead, approximately 38 at the horizon.
		float elevation = sin(max(0, altitudeDegrees) * DEG_TO_RAD);
		float curvature = 1 / 38.f;
		float airMass = sqrt(1 + curvature * curvature) / sqrt(elevation * elevation + curvature * curvature);
		float directVisibility = smoothstep(0, .5f, altitudeDegrees);
		// Below the horizon only the upper atmosphere remains illuminated. This deliberately
		// approximate twilight tail reaches zero at astronomical dusk, independent of exposure.
		float twilight = exp(min(0, altitudeDegrees) * .45f) * smoothstep(-18, -12, altitudeDegrees);
		for (int i = 0; i < directional.length; i++) {
			float depth = opticalDepth[i];
			directional[i] *= exp(-depth * (airMass - 1)) * directVisibility;
			// Normalize to the authored overhead ambient. 1 / airMass approximates
			// the projected illumination; the zero-depth limit avoids cancellation and 0/0.
			float scattering = depth > 1e-4f ? (1 - exp(-depth * airMass)) / (1 - exp(-depth)) / airMass : 1;
			ambient[i] *= scattering * twilight;
		}
	}

	private static void distributeDirectionalLight(
		LightingFrame out,
		float[] directionalLight,
		float altitudeDegrees,
		float diameterDegrees,
		float shadowStrength,
		float directionalFade
	) {
		float visibility = getShadowVisibility(altitudeDegrees, diameterDegrees) * shadowStrength;
		// The spherical average of max(dot(normal, light), 0) is 1/4. Transfer the
		// non-shadow-casting part without changing its average incident energy.
		float ambientTransfer = (1 - visibility) * .25f;
		for (int i = 0; i < out.ambientLight.length; i++)
			out.ambientLight[i] += directionalLight[i] * ambientTransfer;

		// Handoff suppression is temporary: unlike physical softening, it adds no ambient light.
		float directionalTransfer = visibility * directionalFade;
		for (int i = 0; i < out.directionalLight.length; i++)
			out.directionalLight[i] += directionalLight[i] * directionalTransfer;
	}

	private static float getShadowVisibility(float altitudeDegrees, float diameterDegrees) {
		if (altitudeDegrees <= 0)
			return 0;
		// A 10 m caster projects a disk-shaped penumbra. Approximate its long-axis
		// variance with a Gaussian and retain its contrast at a 1 m feature wavelength.
		float elevation = sin(altitudeDegrees * DEG_TO_RAD);
		float sigma = 10 * diameterDegrees * DEG_TO_RAD / (4 * elevation * elevation);
		return exp(-2 * PI * PI * sigma * sigma);
	}

	private float getNightExposure(float luminance) {
		final float target = 1;
		final float softFloor = 0.006f;
		// Metering already follows dusk. A separate altitude ramp makes illumination fall
		// before adaptation catches up, causing a dip followed by a brightness increase.
		// Above 100%, raise the target rather than extrapolating the blend past full adaptation.
		float adaptedExposure = max(1,
			(target * max(plugin.configNightBrightness, 1) + softFloor) / (max(0, luminance) + softFloor));
		return mix(1, adaptedExposure, saturate(plugin.configNightBrightness));
	}

	private void updateSkyUbo(SkyConfiguration configuration, SkyState state, GradientSample sky) {
		var ubo = plugin.uboSky;
		ubo.gradientEnabled.set(1);
		ubo.zenithColor.set(sky.zenithLinear);
		ubo.horizonColor.set(sky.horizonLinear);
		ubo.sunColor.set(sky.sunGlowLinear);
		ubo.horizonWidth.set(sin(clamp(configuration.horizonWidth, .001f, 90) * DEG_TO_RAD));
		ubo.sunDir.set(state.sunDirection);
		ubo.celestialPole.set(state.celestialPole[0], -state.celestialPole[1], state.celestialPole[2]);
		ubo.celestialRotation.set(state.celestialRotation);
		ubo.moonDir.set(state.moonDirection);
		ubo.moonIllumination.set(state.moonIllumination);
		ubo.moonSurfaceLightDirection.set(state.moonSurfaceLightDirection);
		ubo.moonLibration.set(state.moonLibration);
		ubo.moonVisibility.set(state.moonVisibility);
		ubo.moonSizeMult.set(configuration.moonSizeMult);
		ubo.starHorizonHeight.set(configuration.starHorizonHeight);
		ubo.starVisibility.set(configuration.starVisibility);
		ubo.nebulaVisibility.set(configuration.nebulaVisibility);
		ubo.auroraVisibility.set(state.auroraStrength * configuration.auroraVisibility);
		ubo.upload();
	}
}
