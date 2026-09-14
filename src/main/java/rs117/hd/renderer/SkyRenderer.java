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
import rs117.hd.scene.daylight_cycle.SkyConfiguration.SkyProfile;
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
import static rs117.hd.utils.ColorUtils.linearToSrgb;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class SkyRenderer {
	private static final float[] BLACK = { 0, 0, 0 };
	private static final float MOON_HORIZON_CUTOFF_DEG = -10;
	private static final float MIN_MOON_ILLUMINATION = .01f;
	private static final float MOON_ELEVATION_FADE_START_DEG = -10;
	private static final float MOON_ELEVATION_FADE_END_DEG = 20;
	private static final float MIN_BRIGHTNESS_BOOST_RESIDUAL = .2f;
	private static final float MAX_MOON_COLOR_INFLUENCE = .8f;
	private static final float MOON_INFLUENCE_AT_HORIZON = .05f;
	private static final float MOON_TINT_SUN_START_DEG = 5;
	private static final float MOON_TINT_SUN_END_DEG = -15;

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
	private final float[] directionalColor = new float[3];
	private final float[] ambientColor = new float[3];
	private final float[] waterColor = new float[3];
	private final float[] endpointFogColor = new float[3];
	private final SkyState.LightingSample endpointSample = new SkyState.LightingSample();
	private final LightingFrame fromFrame = new LightingFrame();
	private final LightingFrame toFrame = new LightingFrame();
	private final LightingFrame currentFrame = new LightingFrame();
	private long transitionId;
	private float previousTransition = 1;
	private boolean interruptedTransition;
	private final float[] fogColorSrgb = new float[3];
	private float directionalStrength;
	private float ambientStrength;
	private boolean skyEnabled;
	public boolean castsShadows;

	private static final class LightingFrame extends GradientSample {
		private final float[] ambient = new float[3];
		private final float[] directional = new float[3];
		private final float[] fog = new float[3];
		private final float[] moonDisk = new float[3];
		private final SkyConfiguration configuration = new SkyConfiguration();
		private float fogDensity;
		private float visibility;
		private float customGradient;
		private float ambientStrength;
		private float directionalStrength;

		private LightingFrame() {
			zenithLinear = new float[3];
			horizonLinear = new float[3];
			sunGlowLinear = new float[3];
		}

		private void interpolate(LightingFrame from, LightingFrame to, float t) {
			ambientStrength = mix(from.ambientStrength, to.ambientStrength, t);
			directionalStrength = mix(from.directionalStrength, to.directionalStrength, t);
			// Weight colors by their contributions without taking a potentially overflowing reciprocal.
			mix(ambient, from.ambient, to.ambient, ambientStrength > 0 ? to.ambientStrength * t / ambientStrength : t);
			mix(directional, from.directional, to.directional, directionalStrength > 0 ? to.directionalStrength * t / directionalStrength : t);
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
		if (keys.contains(KEY_NEBULAS))
			starField.resetStarfield();
		if (keys.contains(KEY_STARS))
			commandBuffer.reset();
	}

	/**
	 * Complete lighting and shadow eligibility after SkyManager.update, before drawing shadows.
	 * Upload sky resources here; the caller owns the global UBO upload.
	 */
	public void prepareFrame(UBOGlobal uboGlobal) {
		skyEnabled = skyManager.getState().cycleActive;

		Environment env = environmentManager.getCurrentEnvironment();
		copyTo(directionalColor, env.getDirectionalColor());
		copyTo(ambientColor, env.getAmbientColor());
		copyTo(waterColor, env.getWaterColor());
		copyTo(fogColorSrgb, ColorUtils.linearToSrgb(env.getFogColor()));
		directionalStrength = env.directionalStrength;
		ambientStrength = env.ambientStrength;

		if (skyEnabled)
			updateSky(skyManager.getState());
		else {
			previousTransition = 1;
			interruptedTransition = false;
			plugin.uboSky.skyGradientEnabled.set(0);
			plugin.uboSky.upload();
		}
		updateGlobalUbo(uboGlobal);

		if (skyEnabled)
			updateCommandBuffer();
	}

	public boolean shouldRenderSky(boolean hasVanillaSkybox) {
		return
			skyEnabled &&
			skyProgram.isValid() &&
			!plugin.orthographicProjection &&
			!hasVanillaSkybox;
	}

	public void clear(boolean hasVanillaSkybox) {
		frameTimer.begin(Timer.CLEAR_SCENE);

		glClearDepth(0);

		if (shouldRenderSky(hasVanillaSkybox)) {
			glClear(GL_DEPTH_BUFFER_BIT);
		} else {
			float[] fogColor = hasVanillaSkybox ? BLACK : fogColorSrgb;
			float[] gammaCorrectedFogColor = pow(fogColor, plugin.getGammaCorrection());
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
		if (shouldRenderSky(false)) {
			localRenderState.reset();
			commandBuffer.execute(localRenderState);
		}
	}

	private void updateCommandBuffer() {
		boolean starfieldChanged = starField.rebuildIfNeeded();
		if (!starfieldChanged && !commandBuffer.isEmpty())
			return;

		commandBuffer.reset();
		commandBuffer.PushTimer(Timer.RENDER_SKY);
		commandBuffer.Disable(GL_BLEND);
		commandBuffer.SetShader(skyProgram);
		commandBuffer.DepthMask(false);
		commandBuffer.BindVertexArray(plugin.vaoTri);
		commandBuffer.DrawArrays(GL_TRIANGLES, 0, 3);

		if (config.starMode() != StarMode.OFF && starProgram.isValid() && starField.getVaoStars() != 0) {
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
		ubo.fogColor.set(fogColorSrgb);
		float[] waterColorHsv = ColorUtils.srgbToHsv(waterColor);
		ubo.waterColorLight.set(linearToSrgb(ColorUtils.hsvToSrgb(waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .8f)));
		ubo.waterColorMid.set(linearToSrgb(ColorUtils.hsvToSrgb(waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .45f)));
		ubo.waterColorDark.set(linearToSrgb(ColorUtils.hsvToSrgb(waterColorHsv[0], waterColorHsv[1], waterColorHsv[2] * .05f)));

		float effectiveAmbientStrength = ambientStrength;
		float effectiveDirectionalStrength = directionalStrength;
		if (config.useLegacyBrightness()) {
			float factor = (float) config.legacyBrightness() / 20;
			effectiveAmbientStrength *= factor;
			effectiveDirectionalStrength *= factor;
		}
		castsShadows = effectiveDirectionalStrength > 0;
		ubo.ambientStrength.set(effectiveAmbientStrength);
		ubo.ambientColor.set(ambientColor);
		ubo.lightStrength.set(effectiveDirectionalStrength);
		ubo.lightColor.set(directionalColor);
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
		// Blend linear-light contributions, including strength, before encoding the global UBO.
		copyTo(directionalColor, currentFrame.directional);
		copyTo(ambientColor, currentFrame.ambient);
		directionalStrength = currentFrame.directionalStrength;
		ambientStrength = currentFrame.ambientStrength;
		copyTo(fogColorSrgb, linearToSrgb(currentFrame.horizonLinear));
		copyTo(waterColor, currentFrame.horizonLinear);
		plugin.uboSky.skyFogDensity.set(currentFrame.fogDensity);
		plugin.uboSky.skyVisibility.set(currentFrame.visibility);
		plugin.uboSky.skyFogColor.set(currentFrame.fog);
		plugin.uboSky.skyCustomGradient.set(currentFrame.customGradient);
		plugin.uboSky.skyMoonDiskColor.set(currentFrame.moonDisk);
		updateSkyUbo(currentFrame.configuration, state, currentFrame);
	}

	private void evaluateLighting(LightingFrame out, Environment env) {
		SkyConfiguration sky = env.getSky();
		copyTo(endpointFogColor, env.getFogColor());
		environmentManager.applyLightning(endpointFogColor);
		skyManager.sampleLighting(endpointSample, env, endpointFogColor, plugin.configMinimumBrightness);
		SkyState state = endpointSample.sky;
		float sunAltDeg = state.sunAltitudeDegrees;
		{
			SkyProfile profile = sky.profile;
			float regionalBlend = profile.getRegionalBlend(sunAltDeg);
			mix(directionalColor, profile.getDirectionalLight(state.sunAngles[0]), env.getDirectionalColor(), regionalBlend);
			mix(ambientColor, profile.getAmbientLight(sunAltDeg), env.getAmbientColor(), regionalBlend);
		}
		float moonAltDeg = state.moonAltitudeDegrees;
		float brightnessMultiplier = endpointSample.brightnessMultiplier;
		ambientStrength = brightnessMultiplier;
		directionalStrength = env.directionalStrength;

		float moonLightIllumination = state.moonLightIllumination;
		float moonPresence = isMoonLighting(moonAltDeg, moonLightIllumination) ?
			moonLightIllumination * smoothstep(MOON_ELEVATION_FADE_START_DEG, MOON_ELEVATION_FADE_END_DEG, moonAltDeg) : 0;
		float moonInfluence = sunAltDeg >= 0 ?
			smoothstep(MOON_TINT_SUN_START_DEG, 0, sunAltDeg) * MOON_INFLUENCE_AT_HORIZON :
			mix(MOON_INFLUENCE_AT_HORIZON, MAX_MOON_COLOR_INFLUENCE, smoothstep(0, MOON_TINT_SUN_END_DEG, sunAltDeg));
		moonInfluence *= moonPresence;
		// fogDepth is an artistic density control, not a physical extinction coefficient.
		float defaultDensity = max(0, env.fogDepth) / 100;
		out.fogDensity = max(0, sky.skyFogDensity < 0 ? defaultDensity : sky.skyFogDensity);
		out.visibility = saturate(sky.skyVisibility);
		copyTo(out.fog, endpointSample.horizonLinear);
		if (sky.skyFogColor != null)
			mix(out.fog, out.fog, sky.skyFogColor, saturate(sky.skyFogColorMix));
		float boostFraction = mix(1, MIN_BRIGHTNESS_BOOST_RESIDUAL, saturate(moonPresence));
		ambientStrength = max(ambientStrength, plugin.configMinimumBrightness * (1 + sky.minBrightnessBoost * boostFraction));

		float lightingScale = brightnessMultiplier * sky.sunlightStrength;
		float sunStrength = applyShadowBlur(directionalColor, directionalStrength * lightingScale, sunAltDeg, .533f, 1);
		// Only one source can cast shadows. Keep moonlight ambient until sunset,
		// then introduce its directional component smoothly over the next five degrees.
		float moonStrength = applyShadowBlur(
			sky.moonLightColor,
			state.moonDirectionalStrength * moonInfluence / MAX_MOON_COLOR_INFLUENCE * lightingScale,
			moonAltDeg,
			2 * acos(.99945f) * RAD_TO_DEG * sky.moonSizeMult,
			saturate(sky.moonShadowStrength) * smoothstep(0, -5, sunAltDeg)
		);
		directionalStrength = sunStrength + moonStrength;
		if (directionalStrength > 0) {
			// Strength can be subnormal near the horizon; avoid overflowing its reciprocal.
			mix(directionalColor, directionalColor, sky.moonLightColor, moonStrength / directionalStrength);
		}
		copyTo(out.ambient, ambientColor);
		copyTo(out.directional, directionalColor);
		out.ambientStrength = ambientStrength;
		out.directionalStrength = directionalStrength;
		copyTo(out.zenithLinear, endpointSample.zenithLinear);
		copyTo(out.horizonLinear, endpointSample.horizonLinear);
		copyTo(out.sunGlowLinear, endpointSample.sunGlowLinear);
		multiply(out.moonDisk, sky.moonDiskColor, sky.moonDiskStrength);
		out.customGradient = sky.customGradient ? 1 : 0;
		out.configuration.interpolateLightingParameters(sky, sky, 1);
	}

	private float applyShadowBlur(float[] color, float strength, float altitudeDegrees, float diameterDegrees, float shadowStrength) {
		float visibility = 0;
		if (altitudeDegrees > 0) {
			// A 10 m caster projects a disk-shaped penumbra. Approximate its long-axis
			// variance with a Gaussian and retain its contrast at a 1 m feature wavelength.
			float elevation = sin(altitudeDegrees * DEG_TO_RAD);
			float sigma = 10 * diameterDegrees * DEG_TO_RAD / (4 * elevation * elevation);
			visibility = exp(-2 * PI * PI * sigma * sigma) * shadowStrength;
		}
		float transferredStrength = strength * (1 - visibility);
		// The spherical average of max(dot(normal, light), 0) is 1/4. Preserve that
		// average irradiance, including both colors' magnitudes, when making it ambient.
		float ambientTransfer = transferredStrength * .25f;
		float combinedStrength = ambientStrength + ambientTransfer;
		if (combinedStrength > 0) {
			mix(ambientColor, ambientColor, color, ambientTransfer / combinedStrength);
		}
		ambientStrength = combinedStrength;
		return strength * visibility;
	}

	private void updateSkyUbo(SkyConfiguration configuration, SkyState state, GradientSample sky) {
		var ubo = plugin.uboSky;
		ubo.skyGradientEnabled.set(1);
		ubo.skyZenithColor.set(sky.zenithLinear);
		ubo.skyHorizonColor.set(sky.horizonLinear);
		ubo.skySunColor.set(sky.sunGlowLinear);
		ubo.skyHorizonWidth.set(sin(clamp(configuration.horizonWidth, .001f, 90) * DEG_TO_RAD));
		ubo.skySunDir.set(state.sunDirection);
		ubo.skyCelestialPole.set(state.celestialPole[0], -state.celestialPole[1], state.celestialPole[2]);
		ubo.skyCelestialRotation.set(state.celestialRotation);
		ubo.skyStarRotationMode.set(config.starMode().ordinal());
		ubo.skyMoonDir.set(state.moonDirection);
		ubo.skyMoonIllumination.set(state.moonIllumination);
		ubo.skyMoonIlluminationDirection.set(state.moonIlluminationDirection);
		ubo.skyMoonLibration.set(state.moonLibration);
		ubo.moonVisibility.set(state.moonVisibility);
		ubo.moonSizeMult.set(configuration.moonSizeMult);
		ubo.starHorizonHeight.set(configuration.starHorizonHeight);
		ubo.starVisibility.set(config.starMode() == StarMode.OFF ? 0 : configuration.starVisibility);
		ubo.nebulaVisibility.set(config.enableNebulas() ? configuration.nebulaVisibility : 0);
		ubo.auroraVisibility.set(state.auroraStrength * configuration.auroraVisibility);
		ubo.upload();
	}

	private static boolean isMoonLighting(float moonAltDeg, float moonIllumination) {
		return moonAltDeg > MOON_HORIZON_CUTOFF_DEG && moonIllumination > MIN_MOON_ILLUMINATION;
	}
}
