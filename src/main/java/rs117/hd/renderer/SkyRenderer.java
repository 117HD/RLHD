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
		private final float[] ambientLight = new float[3];
		private final float[] sunDirectionalLight = new float[3];
		private final float[] moonDirectionalLight = new float[3];
		private final float[] fog = new float[3];
		private final float[] groundFogLight = new float[3];
		private final float[] moonDisk = new float[3];
		private final SkyConfiguration configuration = new SkyConfiguration();
		private float fogDensity;
		private float visibility;
		private float customGradient;
		private float dayLuminance;
		private float nightLuminance;
		private float moonReflectionVisibility;

		private void interpolate(LightingFrame from, LightingFrame to, float t) {
			mix(sunDirectionalLight, from.sunDirectionalLight, to.sunDirectionalLight, t);
			mix(moonDirectionalLight, from.moonDirectionalLight, to.moonDirectionalLight, t);
			mix(ambientLight, from.ambientLight, to.ambientLight, t);
			mix(fog, from.fog, to.fog, t);
			mix(groundFogLight, from.groundFogLight, to.groundFogLight, t);
			mix(moonDisk, from.moonDisk, to.moonDisk, t);
			mix(zenith, from.zenith, to.zenith, t);
			mix(horizon, from.horizon, to.horizon, t);
			mix(sunGlow, from.sunGlow, to.sunGlow, t);
			fogDensity = mix(from.fogDensity, to.fogDensity, t);
			visibility = mix(from.visibility, to.visibility, t);
			customGradient = mix(from.customGradient, to.customGradient, t);
			dayLuminance = mix(from.dayLuminance, to.dayLuminance, t);
			nightLuminance = mix(from.nightLuminance, to.nightLuminance, t);
			moonReflectionVisibility = mix(from.moonReflectionVisibility, to.moonReflectionVisibility, t);
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

		if (skyEnabled) {
			updateSky(skyManager.getState());
		} else {
			usesMoonShadows = false;
			previousTransition = 1;
			interruptedTransition = false;
			plugin.uboSky.enabled.set(0);
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
		copyTo(ambientLight, currentFrame.ambientLight);
		// Sunlight owns shadows above the horizon; atmospheric attenuation already
		// fades it to zero at sunset. Below it, use adapted contrast for the handoff.
		float totalLuminance = currentFrame.dayLuminance + currentFrame.nightLuminance;
		float handoff = totalLuminance > 0 ? currentFrame.nightLuminance / totalLuminance : 0;
		if (state.sunAltitudeDegrees >= 0)
			handoff = 0;
		usesMoonShadows = state.sunAltitudeDegrees < 0 && state.moonAltitudeDegrees > 0 && handoff >= .5f;
		if (usesMoonShadows) {
			// Hide the camera switch even if strong night lighting already dominates
			// at sunset. Lunar atmospheric attenuation handles the moon's own horizon.
			float visibility = smoothstep(.5f, .8f, handoff) * (1 - smoothstep(-.5f, 0, state.sunAltitudeDegrees));
			visibility *= getShadowVisibility(state.moonAltitudeDegrees, .517f);
			multiply(directionalLight, currentFrame.moonDirectionalLight, visibility);
		} else {
			float visibility = (1 - smoothstep(.2f, .5f, handoff)) * getShadowVisibility(state.sunAltitudeDegrees, .533f);
			multiply(directionalLight, currentFrame.sunDirectionalLight, visibility);
		}
		// Redistribute both sources' suppressed directional light into ambient.
		// max(dot(normal, lightDir), 0) averages to 1/4 over the sphere of normals;
		// this preserves average diffuse illumination while shadow contrast fades.
		for (int i = 0; i < ambientLight.length; i++)
			ambientLight[i] += .25f * (currentFrame.sunDirectionalLight[i] + currentFrame.moonDirectionalLight[i] - directionalLight[i]);
		copyTo(fogColor, currentFrame.horizon);
		plugin.uboSky.fogDensity.set(currentFrame.fogDensity);
		plugin.uboSky.visibility.set(currentFrame.visibility);
		plugin.uboSky.fogColor.set(currentFrame.fog);
		plugin.uboSky.groundFogLight.set(currentFrame.groundFogLight);
		plugin.uboSky.customGradient.set(currentFrame.customGradient);
		plugin.uboSky.moonDiskColor.set(currentFrame.moonDisk);
		plugin.uboSky.moonReflectionVisibility.set(currentFrame.moonReflectionVisibility);
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
		float defaultDensity = max(0, env.fogDepth) / 100;
		out.fogDensity = max(0, sky.skyFogDensity < 0 ? defaultDensity : sky.skyFogDensity);
		out.visibility = saturate(sky.skyVisibility);
		copyTo(out.fog, endpointSample.horizon);
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
		float nightLuminance =
			linearSrgbLuminance(nightAmbientLight) +
			moonLuminance * max(0, sin(moonAltDeg * DEG_TO_RAD));
		// Use the same upward-facing reference surface as adaptation, before any
		// shadow handoff attenuation, so fading shadows cannot drive their own fade.
		out.dayLuminance =
			linearSrgbLuminance(out.ambientLight) +
			linearSrgbLuminance(out.sunDirectionalLight) * max(0, sin(sunAltDeg * DEG_TO_RAD));
		// Meter all illumination, but amplify only night sources. Ignoring daylight
		// here lets adapted airglow overpower the sun in darker environments.
		float exposure = getNightExposure(out.dayLuminance + nightLuminance, env.nightExposure * 1.5f);
		out.nightLuminance = nightLuminance * exposure;
		multiply(nightAmbientLight, nightAmbientLight, exposure);
		multiply(out.moonDirectionalLight, out.moonDirectionalLight, exposure);
		// Moon reflections lose contrast as twilight brightens the sky.
		out.moonReflectionVisibility = 1 - smoothstep(-12, 0, sunAltDeg);
		add(out.ambientLight, out.ambientLight, nightAmbientLight);
		// Broad local scattering, independent of shadow ownership. The quarter is
		// the spherical average of a directional source in our diffuse-light units;
		// this omits forward scattering and local lights. Night exposure is already applied.
		for (int i = 0; i < out.groundFogLight.length; i++)
			out.groundFogLight[i] = out.ambientLight[i] + .25f * (out.sunDirectionalLight[i] + out.moonDirectionalLight[i]);
		// Transition environments already contain the resolved authored/default fog color,
		// not the definition's override flags. Use it as an artistic tint, preserving magnitude.
		multiply(out.groundFogLight, out.groundFogLight, env.getFogColor());

		copyTo(out.zenith, endpointSample.zenith);
		copyTo(out.horizon, endpointSample.horizon);
		copyTo(out.sunGlow, endpointSample.sunGlow);
		multiply(out.moonDisk, sky.moonDiskColor, sky.moonDiskStrength);
		out.customGradient = sky.customGradient ? 1 : 0;
		out.configuration.interpolateLightingParameters(sky, sky, 1);
	}

	private void updateSkyUbo(SkyConfiguration configuration, SkyState state, GradientSample sky) {
		var ubo = plugin.uboSky;
		ubo.enabled.set(1);
		ubo.zenithColor.set(sky.zenith);
		ubo.horizonColor.set(sky.horizon);
		ubo.sunColor.set(sky.sunGlow);
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

	private static float getShadowVisibility(float altitudeDegrees, float diameterDegrees) {
		if (altitudeDegrees <= 0)
			return 0;
		// A 1 m caster projects a disk-shaped penumbra. Approximate its long-axis
		// variance with a Gaussian and retain its contrast at a 1 m feature wavelength.
		float elevation = sin(altitudeDegrees * DEG_TO_RAD);
		float sigma = 1 * diameterDegrees * DEG_TO_RAD / (4 * elevation * elevation);
		return exp(-2 * PI * PI * sigma * sigma);
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
