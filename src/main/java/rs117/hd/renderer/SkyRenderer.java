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
	private final SkyConfiguration currentSky = new SkyConfiguration();
	private final GradientSample skySample = new GradientSample();
	private final GradientSample transitionSkySample = new GradientSample();
	private final float[] fogColorSrgb = new float[3];
	private float directionalStrength;
	private float ambientStrength;
	private boolean skyEnabled;

	public void initialize() {
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
		skyManager.getState().castsShadows = effectiveDirectionalStrength > 0;
		ubo.ambientStrength.set(effectiveAmbientStrength);
		ubo.ambientColor.set(ambientColor);
		ubo.lightStrength.set(effectiveDirectionalStrength);
		ubo.lightColor.set(directionalColor);
	}

	private void updateSky(SkyState state) {
		Environment env = environmentManager.getCurrentEnvironment();
		SkyConfiguration fromSky = state.fromConfiguration;
		SkyConfiguration toSky = state.toConfiguration;
		float transition = state.configurationTransition;
		SkyConfiguration sky = transition < 1 ? currentSky.interpolateLightingParameters(fromSky, toSky, transition) : toSky;
		SkyProfile fromProfile = fromSky.profile;
		SkyProfile toProfile = toSky.profile;
		float sunAltDeg = state.sunAltitudeDegrees;
		float regionalBlend = mix(fromProfile.getRegionalBlend(sunAltDeg), toProfile.getRegionalBlend(sunAltDeg), transition);
		float[] directionalLight = mix(
			fromProfile.getDirectionalLight(state.sunAngles[0]),
			toProfile.getDirectionalLight(state.sunAngles[0]),
			transition
		);
		float[] ambientLight = mix(fromProfile.getAmbientLight(sunAltDeg), toProfile.getAmbientLight(sunAltDeg), transition);
		mix(directionalColor, directionalLight, directionalColor, regionalBlend);
		mix(ambientColor, ambientLight, ambientColor, regionalBlend);

		float moonAltDeg = state.moonAltitudeDegrees;
		float moonIllumination = state.moonIllumination;
		toSky.evaluateGradient(skySample, sunAltDeg, env.getFogColor(), plugin.configMinimumBrightness);
		if (transition < 1) {
			fromSky.evaluateGradient(transitionSkySample, sunAltDeg, env.getFogColor(), plugin.configMinimumBrightness);
			mix(skySample.zenithLinear, transitionSkySample.zenithLinear, skySample.zenithLinear, transition);
			mix(skySample.horizonLinear, transitionSkySample.horizonLinear, skySample.horizonLinear, transition);
			mix(skySample.sunGlowLinear, transitionSkySample.sunGlowLinear, skySample.sunGlowLinear, transition);
			skySample.brightnessMultiplier = mix(transitionSkySample.brightnessMultiplier, skySample.brightnessMultiplier, transition);
		}
		float brightnessMultiplier = skySample.brightnessMultiplier;
		ambientStrength = brightnessMultiplier;

		float moonLightIllumination = state.moonLightIllumination;
		float moonInfluence = computeMoonInfluence(sunAltDeg, moonAltDeg, moonLightIllumination);
		// fogDepth is an artistic density control, not a physical extinction coefficient.
		float defaultDensity = max(0, env.fogDepth) / 100;
		float fogDensity = mix(
			fromSky.skyFogDensity < 0 ? defaultDensity : fromSky.skyFogDensity,
			toSky.skyFogDensity < 0 ? defaultDensity : toSky.skyFogDensity,
			transition
		);
		plugin.uboSky.skyFogDensity.set(max(0, fogDensity));
		plugin.uboSky.skyVisibility.set(mix(saturate(fromSky.skyVisibility), saturate(toSky.skyVisibility), transition));
		float[] lightingFogColor = skySample.horizonLinear;
		float[] fromFogColor = fromSky.skyFogColor == null ? lightingFogColor : mix(
			lightingFogColor,
			fromSky.skyFogColor,
			saturate(fromSky.skyFogColorMix)
		);
		float[] toFogColor = toSky.skyFogColor == null ? lightingFogColor : mix(
			lightingFogColor,
			toSky.skyFogColor,
			saturate(toSky.skyFogColorMix)
		);
		plugin.uboSky.skyFogColor.set(mix(
			fromFogColor,
			toFogColor,
			transition
		));
		if (moonInfluence > 0) {
			// A single directional channel carries both sources. Add their radiances before
			// converting back to the color-and-strength representation used by the renderer.
			float moonStrength = state.moonDirectionalStrength * moonInfluence / MAX_MOON_COLOR_INFLUENCE;
			float combinedStrength = directionalStrength + moonStrength;
			if (combinedStrength > 0) {
				float[] radiance = add(
					multiply(directionalColor, directionalStrength),
					multiply(sky.moonLightColor, moonStrength)
				);
				divide(directionalColor, radiance, combinedStrength);
				directionalStrength = combinedStrength;
			}
		}
		directionalStrength *= brightnessMultiplier * sky.sunlightStrength;
		copyTo(fogColorSrgb, linearToSrgb(skySample.horizonLinear));
		copyTo(waterColor, skySample.horizonLinear);
		float moonPresenceFactor = moonPresence(moonAltDeg, moonLightIllumination);
		float boostFraction = MIN_BRIGHTNESS_BOOST_RESIDUAL + (1 - MIN_BRIGHTNESS_BOOST_RESIDUAL) * (1 - moonPresenceFactor);
		ambientStrength = max(ambientStrength, plugin.configMinimumBrightness * (1 + sky.minBrightnessBoost * boostFraction));

		applyShadowBlur(
			sunAltDeg >= 0 ? sunAltDeg : moonAltDeg,
			sunAltDeg >= 0 ? .533f : 2 * acos(.99945f) * RAD_TO_DEG * sky.moonSizeMult,
			sunAltDeg >= 0 ? 1 : isMoonLighting(moonAltDeg, moonLightIllumination) ? saturate(sky.moonShadowStrength) : 0
		);
		updateSkyUbo(sky, state, skySample, moonIllumination);
	}

	private void applyShadowBlur(float altitudeDegrees, float diameterDegrees, float shadowStrength) {
		float visibility = 0;
		if (altitudeDegrees > 0) {
			// A 10 m caster projects a disk-shaped penumbra. Approximate its long-axis
			// variance with a Gaussian and retain its contrast at a 1 m feature wavelength.
			float elevation = sin(altitudeDegrees * DEG_TO_RAD);
			float sigma = 10 * diameterDegrees * DEG_TO_RAD / (4 * elevation * elevation);
			visibility = exp(-2 * PI * PI * sigma * sigma) * shadowStrength;
		}
		float transferredStrength = directionalStrength * (1 - visibility);
		// The spherical average of max(dot(normal, light), 0) is 1/4. Preserve that
		// average irradiance, including both colors' magnitudes, when making it ambient.
		float ambientTransfer = transferredStrength * .25f;
		float combinedStrength = ambientStrength + ambientTransfer;
		if (combinedStrength > 0) {
			multiply(ambientColor, ambientColor, ambientStrength);
			add(ambientColor, ambientColor, multiply(directionalColor, ambientTransfer));
			divide(ambientColor, ambientColor, combinedStrength);
		}
		ambientStrength = combinedStrength;
		directionalStrength *= visibility;
	}

	private static float computeMoonInfluence(float sunAltDeg, float moonAltDeg, float moonIllumination) {
		if (sunAltDeg >= MOON_TINT_SUN_START_DEG || !isMoonLighting(moonAltDeg, moonIllumination))
			return 0;
		float influence = sunAltDeg >= 0 ?
			smoothstep(MOON_TINT_SUN_START_DEG, 0, sunAltDeg) * MOON_INFLUENCE_AT_HORIZON :
			mix(MOON_INFLUENCE_AT_HORIZON, MAX_MOON_COLOR_INFLUENCE, smoothstep(0, MOON_TINT_SUN_END_DEG, sunAltDeg));
		return influence * moonElevationFade(moonAltDeg) * moonIllumination;
	}

	private void updateSkyUbo(SkyConfiguration configuration, SkyState state, GradientSample sky, float moonIllumination) {
		var ubo = plugin.uboSky;
		ubo.skyGradientEnabled.set(1);
		ubo.skyZenithColor.set(sky.zenithLinear);
		ubo.skyHorizonColor.set(sky.horizonLinear);
		ubo.skySunColor.set(sky.sunGlowLinear);
		ubo.skyCustomGradient.set(mix(
			state.fromConfiguration.customGradient ? 1f : 0,
			state.toConfiguration.customGradient ? 1f : 0,
			state.configurationTransition
		));
		ubo.skyHorizonWidth.set(sin(clamp(configuration.horizonWidth, .001f, 90) * DEG_TO_RAD));
		ubo.skySunDir.set(state.sunDirection);
		ubo.skyCelestialPole.set(state.celestialPole[0], -state.celestialPole[1], state.celestialPole[2]);
		ubo.skyCelestialRotation.set(state.celestialRotation);
		ubo.skyStarRotationMode.set(config.starMode().ordinal());
		ubo.skyMoonDir.set(state.moonDirection);
		ubo.skyMoonDiskColor.set(
			configuration.moonDiskColor[0] * configuration.moonDiskStrength,
			configuration.moonDiskColor[1] * configuration.moonDiskStrength,
			configuration.moonDiskColor[2] * configuration.moonDiskStrength
		);
		ubo.skyMoonIllumination.set(moonIllumination);
		ubo.skyMoonPhaseLightDirection.set(state.moonPhaseLightDirection);
		ubo.skyMoonLibration.set(state.moonLibration);
		ubo.skyMoonPhaseReversed.set(state.moonPhaseReversed ? 1 : 0);
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

	private static float moonPresence(float moonAltDeg, float moonIllumination) {
		if (isMoonLighting(moonAltDeg, moonIllumination))
			return clamp(moonIllumination * moonElevationFade(moonAltDeg), 0, 1);
		return 0;
	}

	private static float moonElevationFade(float moonAltDeg) {
		return smoothstep(MOON_ELEVATION_FADE_START_DEG, MOON_ELEVATION_FADE_END_DEG, moonAltDeg);
	}
}
