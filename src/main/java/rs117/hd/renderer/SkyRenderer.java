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
import static rs117.hd.utils.ColorUtils.ATMOSPHERIC_OPTICAL_DEPTH;
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
		private float moonShadowHandoff;

		private LightingFrame() {
			zenithLinear = new float[3];
			horizonLinear = new float[3];
			sunGlowLinear = new float[3];
		}

		private void interpolate(LightingFrame from, LightingFrame to, float t) {
			moonShadowHandoff = mix(from.moonShadowHandoff, to.moonShadowHandoff, t);
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
		usesMoonShadows = currentFrame.moonShadowHandoff > 0;
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
		// fogDepth is an artistic density control, not a physical extinction coefficient.
		float defaultDensity = .6f + (exp(6.7f * env.fogDepth / 100) - 1);
		out.fogDensity = max(0, sky.skyFogDensity < 0 ? defaultDensity : sky.skyFogDensity);
		out.visibility = saturate(sky.skyVisibility);
		copyTo(out.fog, endpointSample.horizonLinear);
		if (sky.skyFogColor != null)
			mix(out.fog, out.fog, sky.skyFogColor, saturate(sky.skyFogColorMix));

		multiply(out.moonDirectionalLight, sky.moonDirectionalColor, state.moonDirectionalStrength * moonLightIllumination);
		float[] nightAmbientLight = multiply(sky.moonAmbientColor, sky.moonAmbientStrength * moonLightIllumination);
		applyAtmosphere(out.sunDirectionalLight, out.ambientLight, sunAltDeg);
		applyAtmosphere(out.moonDirectionalLight, nightAmbientLight, moonAltDeg);
		// Airglow/starlight is independent of the environment's daytime lighting and moon phase.
		for (int i = 0; i < nightAmbientLight.length; i++)
			nightAmbientLight[i] += sky.nightAmbientColor[i] * sky.nightAmbientStrength;
		float moonLuminance = linearSrgbLuminance(out.moonDirectionalLight);
		// Adapt only night sources, independently of sunlight and shadow ownership.
		float adaptationLuminance =
			linearSrgbLuminance(nightAmbientLight) +
			moonLuminance * max(0, sin(moonAltDeg * DEG_TO_RAD));
		float exposure = getNightExposure(adaptationLuminance, env.nightExposure * 1.5f);
		multiply(nightAmbientLight, nightAmbientLight, exposure);
		multiply(out.moonDirectionalLight, out.moonDirectionalLight, exposure);
		add(out.ambientLight, out.ambientLight, nightAmbientLight);

		float shadowedLuminance = linearSrgbLuminance(out.ambientLight);
		// The moon's directional contribution is metered above, but not rendered until handoff.
		float litLuminance = shadowedLuminance + linearSrgbLuminance(out.sunDirectionalLight);
		// Switch sources while the disappearing sun shadow spans only a few display values.
		float sunShadowContrast = linearToSrgb(litLuminance) - linearToSrgb(shadowedLuminance);
		out.moonShadowHandoff = moonAltDeg > 0 && moonLightIllumination > 0 ?
			1 - smoothstep(SHADOW_HANDOFF_MIN_CONTRAST, SHADOW_HANDOFF_MAX_CONTRAST, sunShadowContrast) : 0;
		if (out.moonShadowHandoff > 0) {
			multiply(out.directionalLight, out.moonDirectionalLight, out.moonShadowHandoff);
		} else {
			copyTo(out.directionalLight, out.sunDirectionalLight);
		}
		copyTo(out.zenithLinear, endpointSample.zenithLinear);
		copyTo(out.horizonLinear, endpointSample.horizonLinear);
		copyTo(out.sunGlowLinear, endpointSample.sunGlowLinear);
		multiply(out.moonDisk, sky.moonDiskColor, sky.moonDiskStrength);
		out.customGradient = sky.customGradient ? 1 : 0;
		out.configuration.interpolateLightingParameters(sky, sky, 1);
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

	private float getNightExposure(float luminance, float target) {
		final float softFloor = 0.006f;
		// Above 100%, raise the target rather than extrapolating the blend past full adaptation.
		float adaptedExposure = max(1, (target * max(plugin.configNightBrightness, 1) + softFloor) / (max(0, luminance) + softFloor));
		return mix(1, adaptedExposure, saturate(plugin.configNightBrightness));
	}

	/**
	 * Attenuate overhead-calibrated linear lighting through a shared reference atmosphere, in place.
	 * Authored ambient/direct ratios do not determine atmospheric density.
	 */
	public static void applyAtmosphere(float[] directional, float[] ambient, float altitudeDegrees) {
		float elevation = sin(max(0, altitudeDegrees) * DEG_TO_RAD);
		float curvature = 1 / 38.f;
		float airMass = sqrt(1 + curvature * curvature) / sqrt(elevation * elevation + curvature * curvature);
		float directVisibility = smoothstep(0, .5f, altitudeDegrees);
		float twilight = smoothstep(-18, -12, altitudeDegrees);
		for (int i = 0; i < 3; i++) {
			float depth = ATMOSPHERIC_OPTICAL_DEPTH[i];
			directional[i] *= exp(-depth * (airMass - 1)) * directVisibility;
			float scattering = (1 - exp(-depth * airMass)) / (1 - exp(-depth)) / airMass;
			// Approximate upper-atmosphere twilight: red fades faster than blue below sunset.
			float twilightDecay = exp(min(0, altitudeDegrees) * (.65f - .1f * i));
			ambient[i] *= scattering * twilightDecay * twilight;
		}
	}
}
