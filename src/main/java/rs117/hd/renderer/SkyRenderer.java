package rs117.hd.renderer;

import java.io.IOException;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
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
import rs117.hd.scene.daylight_cycle.SkyConfiguration.Keyframe;
import rs117.hd.scene.daylight_cycle.SkyConfiguration.SkyProfile;
import rs117.hd.scene.daylight_cycle.SkyState;
import rs117.hd.scene.daylight_cycle.StarField;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
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
	private static final float SUN_SHADOW_CUTOFF_DEG = 2;
	private static final float SUN_SHADOW_MIDPOINT_DEG = 12;
	private static final float SUN_SHADOW_FULL_DEG = 15;
	private static final float SUN_SHADOW_MIDPOINT_VISIBILITY = .6f;
	private static final float SUN_SHADOW_DAYTIME_FLOOR = .9f;
	private static final float MOON_HORIZON_CUTOFF_DEG = -10;
	private static final float MIN_MOON_ILLUMINATION = .01f;
	private static final float MOON_ELEVATION_FADE_START_DEG = -10;
	private static final float MOON_ELEVATION_FADE_END_DEG = 20;
	private static final float MOON_SHADOW_STRENGTH = .2f;
	private static final float MIN_BRIGHTNESS_BOOST_RESIDUAL = .2f;
	private static final float MAX_MOON_COLOR_INFLUENCE = .8f;
	private static final float MOON_INFLUENCE_AT_HORIZON = .05f;
	private static final float MOON_TINT_SUN_START_DEG = 5;
	private static final float MOON_TINT_SUN_END_DEG = -15;
	private static final float NIGHT_SKY_TINT_SCALE = .05f;
	private static final float SKY_FILL_FADE_END_DEG = 45;
	private static final float MAX_OUTDOOR_LIGHT_SCALE = 4;

	/**
	 * Reusable output from the sky's gradient and lighting curves.
	 */
	private static final class Sample {
		private Environment environment;
		private float minBrightness;
		private int frame;
		private float[] zenithSrgb;
		private float[] horizonSrgb;
		private float[] sunGlowSrgb;
		private float[] horizonLinear;
		private float[] noonHorizonLinear;
		private float brightnessMultiplier;
	}

	@Inject
	private Client client;

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
	private final Sample skySample = new Sample();
	private final Sample transitionSkySample = new Sample();
	private final Sample environmentSample = new Sample();
	@Getter
	@Accessors(fluent = true)
	private boolean castsShadows;
	private final float[] fogColorSrgb = new float[3];
	private float directionalStrength;
	private float ambientStrength;
	private boolean shouldRenderSky;

	public void initialize() {
		commandBuffer.setFrameTimer(frameTimer);
		commandBuffer.reset();
		starField.initialize();
	}

	public void destroy() {
		starField.destroy();
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		skyProgram.compile(includes);
		starField.initializeShaders(includes);
		starProgram.compile(includes);
		starField.resetStarfield();
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

	public void update(UBOGlobal uboGlobal) {
		shouldRenderSky = skyManager.isCycleActive();

		Environment env = environmentManager.getCurrentEnvironment();
		copyTo(directionalColor, env.getDirectionalColor());
		copyTo(ambientColor, env.getAmbientColor());
		copyTo(waterColor, env.getWaterColor());
		copyTo(fogColorSrgb, ColorUtils.linearToSrgb(env.getFogColor()));
		directionalStrength = env.directionalStrength;
		ambientStrength = env.ambientStrength;

		if (shouldRenderSky)
			updateSky(skyManager.getState());
		else {
			plugin.uboSky.skyGradientEnabled.set(0);
			plugin.uboSky.upload();
		}
		updateGlobalUbo(uboGlobal);

		if (shouldRenderSky)
			updateCommandBuffer();
	}

	public boolean shouldRender() {
		return shouldRenderSky && skyProgram.isValid();
	}

	private boolean canRenderSky(boolean hasVanillaSkybox) {
		return shouldRender() && !plugin.orthographicProjection && !hasVanillaSkybox;
	}

	public void clear(boolean hasVanillaSkybox) {
		frameTimer.begin(Timer.CLEAR_SCENE);

		glClearDepth(0);

		if (canRenderSky(hasVanillaSkybox)) {
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

	public void renderTo(CommandBuffer target) {
		target.ExecuteSubCommandBuffer(commandBuffer);
	}

	public void render() {
		clear(false);
		if (canRenderSky(false)) {
			localRenderState.reset();
			commandBuffer.execute(localRenderState);
		}
	}

	private void updateCommandBuffer() {
		boolean starfieldChanged = starField.update();
		if (!starfieldChanged && !commandBuffer.isEmpty())
			return;

		commandBuffer.reset();
		commandBuffer.PushTimer(Timer.RENDER_SKY);
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
		Environment env = environmentManager.getCurrentEnvironment();
		SkyConfiguration fromSky = state.fromConfiguration;
		SkyConfiguration toSky = state.toConfiguration;
		float transition = state.configurationTransition;
		SkyConfiguration sky = transition < 1 ? currentSky.interpolate(fromSky, toSky, transition) : toSky;
		SkyProfile fromProfile = fromSky.profile;
		SkyProfile toProfile = toSky.profile;
		float sunAltDeg = state.sunAltitudeDegrees;
		float regionalBlend = mix(
			interpolate(sunAltDeg, fromProfile.regionalBlend)[0],
			interpolate(sunAltDeg, toProfile.regionalBlend)[0],
			transition
		);
		float[] directionalLight = mix(
			getDirectionalLight(state.sunAngles[0], fromProfile),
			getDirectionalLight(state.sunAngles[0], toProfile),
			transition
		);
		float[] ambientLight = mix(
			interpolate(sunAltDeg, fromProfile.ambientColor),
			interpolate(sunAltDeg, toProfile.ambientColor),
			transition
		);
		mix(directionalColor, directionalLight, directionalColor, regionalBlend);
		mix(ambientColor, ambientLight, ambientColor, regionalBlend);

		float brightnessMultiplier = mix(
			getBrightnessMultiplier(sunAltDeg, fromProfile),
			getBrightnessMultiplier(sunAltDeg, toProfile),
			transition
		);
		ambientStrength = brightnessMultiplier;
		float moonAltDeg = state.moonAltitudeDegrees;
		float moonIllumination = state.moonIllumination;
		sampleSkyGradient(
			skySample, sunAltDeg, toProfile,
			env.getFogColor(), toSky.sunStrength, toSky.sunriseSunsetStrength, toSky.skyColorTakeoverAngle
		);
		if (transition < 1) {
			sampleSkyGradient(
				transitionSkySample, sunAltDeg, fromProfile,
				env.getFogColor(), fromSky.sunStrength, fromSky.sunriseSunsetStrength, fromSky.skyColorTakeoverAngle
			);
			mix(skySample.zenithSrgb, transitionSkySample.zenithSrgb, skySample.zenithSrgb, transition);
			mix(skySample.horizonSrgb, transitionSkySample.horizonSrgb, skySample.horizonSrgb, transition);
			mix(skySample.sunGlowSrgb, transitionSkySample.sunGlowSrgb, skySample.sunGlowSrgb, transition);
		}

		float litMoonIllumination = max(moonIllumination, sky.minMoonIllumination) * state.moonVisibility;
		float shadowVisibility = computeShadowVisibility(sky, sunAltDeg, moonAltDeg, litMoonIllumination);
		float moonInfluence = computeMoonInfluence(sunAltDeg, moonAltDeg, litMoonIllumination);
		applyMoonLighting(sky, state.moonDirectionalStrength, skySample, moonInfluence);
		directionalStrength *= brightnessMultiplier * sky.sunlightStrength;
		copyTo(fogColorSrgb, skySample.horizonSrgb);
		copyTo(waterColor, ColorUtils.srgbToLinear(skySample.horizonSrgb));
		applyAmbientFloor(sky, moonAltDeg, litMoonIllumination);
		applySkyFill(sunAltDeg, shadowVisibility);
		updateSkyUbo(sky, state, skySample, moonIllumination);
	}

	private void applyMoonLighting(SkyConfiguration configuration, float moonDirectionalStrength, Sample sky, float moonInfluence) {
		if (moonInfluence == 0)
			return;
		mix(directionalColor, directionalColor, configuration.moonLightColor, moonInfluence);
		tintNightSky(configuration, sky, moonInfluence);
		directionalStrength = mix(
			directionalStrength, moonDirectionalStrength,
			min(1, moonInfluence / MAX_MOON_COLOR_INFLUENCE)
		);
	}

	private void applyAmbientFloor(SkyConfiguration configuration, float moonAltDeg, float moonIllumination) {
		float boostFraction = MIN_BRIGHTNESS_BOOST_RESIDUAL +
							  (1 - MIN_BRIGHTNESS_BOOST_RESIDUAL) * (1 - moonPresence(moonAltDeg, moonIllumination));
		float boostedFloor = plugin.configMinimumBrightness / 100f *
							 (1 + configuration.minBrightnessBoost * boostFraction);
		ambientStrength = max(ambientStrength, boostedFloor);
	}

	private void applySkyFill(float sunAltDeg, float shadowVisibility) {
		float skyFill = 1 - smoothstep(0, SKY_FILL_FADE_END_DEG, sunAltDeg);
		add(ambientColor, ambientColor, multiply(directionalColor, (1 - shadowVisibility) * skyFill));
		directionalStrength *= shadowVisibility;
	}

	private void sampleSkyGradient(
		Sample out, float sunAltitude, SkyProfile profile, float[] fogColor, float sunStrength,
		float sunriseSunsetStrength, float skyColorTakeoverAngle
	) {
		float takeover = max(0, skyColorTakeoverAngle);
		float[] zenith = interpolate(sunAltitude, profile.zenith);
		float[] horizon = interpolate(sunAltitude, profile.horizon);
		float[] sunGlow = interpolate(sunAltitude, profile.sunGlow);
		if (fogColor != null && sunStrength < 1) {
			float window = sunAltitude >= 0 ? 1 : smoothstep(-25, 0, sunAltitude);
			float suppression = (1 - sunStrength) * window;
			if (suppression > 0) {
				float[] target = mix(fogColor, profile.nightSkyColor, smoothstep(5, -5, sunAltitude));
				blendSky(zenith, horizon, target, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null && sunriseSunsetStrength < 1) {
			float window = sunAltitude < 0 ? smoothstep(-15, 0, sunAltitude) : takeover == 0 ? 0 : smoothstep(takeover, 0, sunAltitude);
			float suppression = (1 - sunriseSunsetStrength) * window;
			if (suppression > 0) {
				blendSky(zenith, horizon, fogColor, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null) {
			float blend = sunAltitude < 0 ? 0 : takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitude);
			if (blend > 0)
				blendSky(zenith, horizon, fogColor, blend);
		}
		float nightBlend = smoothstep(0, -15, sunAltitude);
		if (nightBlend > 0)
			blendSky(zenith, horizon, profile.nightSkyColor, nightBlend);
		out.zenithSrgb = linearToSrgb(zenith);
		out.horizonSrgb = linearToSrgb(horizon);
		out.sunGlowSrgb = linearToSrgb(sunGlow);
	}

	private float getBrightnessMultiplier(float sunAltitudeDegrees, SkyProfile profile) {
		float minBrightness = plugin.configMinimumBrightness / 100f;
		var curve = profile.brightness;
		float horizonBrightness = minBrightness + curve.horizonBoost;
		if (sunAltitudeDegrees <= curve.nightAltitude)
			return minBrightness;
		if (sunAltitudeDegrees <= curve.lowSunAltitude) {
			float lowSunBrightness = minBrightness + curve.lowSunBoost;
			return mix(minBrightness, lowSunBrightness, smoothstep(curve.nightAltitude, curve.lowSunAltitude, sunAltitudeDegrees));
		}
		if (sunAltitudeDegrees <= curve.horizonAltitude) {
			float lowSunBrightness = minBrightness + curve.lowSunBoost;
			float earlyDayBrightness = horizonBrightness + curve.earlyDayBoost;
			return mix(lowSunBrightness, earlyDayBrightness, smoothstep(curve.lowSunAltitude, curve.horizonAltitude, sunAltitudeDegrees));
		}
		float earlyDayBrightness = horizonBrightness + curve.earlyDayBoost;
		float sineAtHorizon = sin(curve.horizonAltitude * DEG_TO_RAD);
		float normalizedSine = max(0, (sin(sunAltitudeDegrees * DEG_TO_RAD) - sineAtHorizon) / (1 - sineAtHorizon));
		return mix(earlyDayBrightness, curve.daytimeStrength, normalizedSine);
	}

	public void applyOutdoorLighting(Light light) {
		SkyState state = skyManager.getState();
		copyTo(light.color, light.def.color);
		if (light.def.outdoorLighting == null || skyManager.isCycleDisabled())
			return;

		Environment environment = environmentManager.getOverworldEnvironment();
		int[] sampleWorldPos = light.def.outdoorLighting.sampleWorldPos;
		if (sampleWorldPos != null) {
			Environment sampledEnvironment = environmentManager.getEnvironmentAt(sampleWorldPos);
			if (sampledEnvironment != null)
				environment = sampledEnvironment;
		}
		Sample lighting = sampleEnvironmentalLighting(environment);
		SkyConfiguration sky = environment.getSky();
		float[] authoredColor = light.def.color;
		float defLuma = linearSrgbLuminance(authoredColor);
		float noonLuma = max(linearSrgbLuminance(lighting.noonHorizonLinear), 1e-4f);
		float[] lightColor = copy(lighting.horizonLinear);
		float sunAltDeg = state.sunAltitudeDegrees;

		float moonStrengthFloor = 0;
		if (sunAltDeg < 5) {
			float moonAltDeg = state.moonAltitudeDegrees;
			float moonIllumination = state.moonIllumination * state.moonVisibility;
			if (moonAltDeg > -5 && moonIllumination > .01f) {
				float sunFade = saturate((5 - sunAltDeg) / 10);
				float moonElevation = saturate((moonAltDeg + 5) / 25);
				float moonElevationSmooth = moonElevation * moonElevation * (3 - 2 * moonElevation);
				float moonBlend = moonIllumination * .25f * moonElevationSmooth * sunFade;
				lightColor = mix(lightColor, sky.moonLightColor, moonBlend);
				moonStrengthFloor = moonIllumination * .12f * moonElevationSmooth;
			}
		}

		if (sunAltDeg > 0) {
			float desaturation = smoothstep(0, 90, sunAltDeg) * .75f;
			float luma = linearSrgbLuminance(lightColor);
			mix(lightColor, lightColor, vec(luma), desaturation);
		}

		float horizonLuma = linearSrgbLuminance(lightColor);
		float middayFactor = smoothstep(15, 30, sunAltDeg);
		if (middayFactor > 0)
			lightColor = mix(lightColor, authoredColor, middayFactor);

		copyTo(light.color, lightColor);
		float peakScale = defLuma / noonLuma;
		float timeScale = max(min(horizonLuma / noonLuma, 1) * lighting.brightnessMultiplier, moonStrengthFloor);
		float outdoorLightScale = peakScale * timeScale;
		if (outdoorLightScale > 1) {
			float scaleRange = MAX_OUTDOOR_LIGHT_SCALE - 1;
			outdoorLightScale = 1 + scaleRange * (1 - exp(-(outdoorLightScale - 1) / scaleRange));
		}
		light.strength *= mix(outdoorLightScale, 1, middayFactor);
	}

	private Sample sampleEnvironmentalLighting(Environment env) {
		assert client.isClientThread() : "Not thread-safe, as the sample is reused";
		if (env == environmentSample.environment &&
			plugin.configMinimumBrightness == environmentSample.minBrightness &&
			plugin.frame == environmentSample.frame)
			return environmentSample;
		environmentSample.environment = env;
		environmentSample.minBrightness = plugin.configMinimumBrightness;
		environmentSample.frame = plugin.frame;

		var sky = env.getSky();
		SkyProfile profile = sky.profile;
		float[] fogColor = environmentManager.getFogColor(env);
		float sunAltitudeDegrees = skyManager.getSunAltitude(sky) * RAD_TO_DEG;
		sampleSkyGradient(
			environmentSample, sunAltitudeDegrees, profile, fogColor,
			sky.sunStrength, sky.sunriseSunsetStrength, sky.skyColorTakeoverAngle
		);
		environmentSample.horizonLinear = ColorUtils.srgbToLinear(environmentSample.horizonSrgb);
		environmentSample.noonHorizonLinear = fogColor;
		environmentSample.brightnessMultiplier = getBrightnessMultiplier(sunAltitudeDegrees, profile);
		return environmentSample;
	}

	private static float[] getDirectionalLight(float sunAltitude, SkyProfile profile) {
		float[] directionalLight = multiply(
			ColorUtils.colorTemperatureToLinearRgb(profile.directionalBaseTemperature),
			profile.directionalBaseStrength
		);
		if (sunAltitude >= 0) {
			float temperature = interpolate(sunAltitude * RAD_TO_DEG, profile.directionalTemperature)[0];
			float strength = sin(sunAltitude);
			strength *= strength * 3;
			add(directionalLight, directionalLight, multiply(ColorUtils.colorTemperatureToLinearRgb(temperature), strength));
		}
		return directionalLight;
	}

	private static float[] interpolate(float x, Keyframe[] keyframes) {
		int end = keyframes.length - 1;
		int i = 0;
		while (i < end && x > keyframes[i + 1].altitude)
			i++;
		Keyframe from = keyframes[i];
		if (i == end)
			return copy(from.values());
		Keyframe to = keyframes[i + 1];
		return mix(from.values(), to.values(), clamp((x - from.altitude) / (to.altitude - from.altitude), 0, 1));
	}

	private static void blendSky(float[] zenith, float[] horizon, float[] color, float t) {
		mix(zenith, zenith, color, t);
		mix(horizon, horizon, color, t);
	}

	private float computeShadowVisibility(SkyConfiguration configuration, float sunAltDeg, float moonAltDeg, float moonIllumination) {
		return sunAltDeg >= 0 ?
			getSunShadowVisibility(sunAltDeg) :
			getMoonShadowVisibility(configuration, sunAltDeg, moonAltDeg, moonIllumination);
	}

	private static float getSunShadowVisibility(float sunAltitude) {
		if (sunAltitude <= SUN_SHADOW_MIDPOINT_DEG)
			return sunAltitude / SUN_SHADOW_MIDPOINT_DEG * SUN_SHADOW_MIDPOINT_VISIBILITY;
		if (sunAltitude <= SUN_SHADOW_FULL_DEG)
			return mix(
				SUN_SHADOW_MIDPOINT_VISIBILITY, SUN_SHADOW_DAYTIME_FLOOR,
				(sunAltitude - SUN_SHADOW_MIDPOINT_DEG) / (SUN_SHADOW_FULL_DEG - SUN_SHADOW_MIDPOINT_DEG)
			);
		return clamp(sin(sunAltitude * DEG_TO_RAD), SUN_SHADOW_DAYTIME_FLOOR, 1);
	}

	private static float getMoonShadowVisibility(
		SkyConfiguration configuration,
		float sunAltitude,
		float moonAltitude,
		float moonIllumination
	) {
		float moonBaseShadow = 0;
		if (isMoonLighting(moonAltitude, moonIllumination))
			moonBaseShadow =
				sqrt(moonIllumination) * MOON_SHADOW_STRENGTH * moonElevationFade(moonAltitude) * configuration.moonShadowStrength;
		return saturate(smoothstep(SUN_SHADOW_CUTOFF_DEG, MOON_TINT_SUN_END_DEG, sunAltitude) * moonBaseShadow);
	}

	private float computeMoonInfluence(float sunAltDeg, float moonAltDeg, float moonIllumination) {
		if (sunAltDeg >= MOON_TINT_SUN_START_DEG || !isMoonLighting(moonAltDeg, moonIllumination))
			return 0;
		float influence = sunAltDeg >= 0
			? smoothstep(MOON_TINT_SUN_START_DEG, 0, sunAltDeg) * MOON_INFLUENCE_AT_HORIZON
			: mix(MOON_INFLUENCE_AT_HORIZON, MAX_MOON_COLOR_INFLUENCE, smoothstep(0, MOON_TINT_SUN_END_DEG, sunAltDeg));
		return influence * moonElevationFade(moonAltDeg) * moonIllumination;
	}

	private static void tintNightSky(SkyConfiguration configuration, Sample sky, float moonInfluence) {
		float skyTint = min(1, moonInfluence * NIGHT_SKY_TINT_SCALE * configuration.nightSkyColorStrength);
		mix(sky.zenithSrgb, sky.zenithSrgb, configuration.nightSkyColor, skyTint);
		mix(sky.horizonSrgb, sky.horizonSrgb, configuration.nightSkyColor, skyTint);
	}

	private void updateSkyUbo(
		SkyConfiguration configuration,
		SkyState state,
		Sample sky,
		float moonIllumination
	) {
		var ubo = plugin.uboSky;
		ubo.skyGradientEnabled.set(1);
		ubo.skyZenithColor.set(sky.zenithSrgb);
		ubo.skyHorizonColor.set(sky.horizonSrgb);
		ubo.skySunColor.set(sky.sunGlowSrgb);
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
		ubo.skyVisibility.set(configuration.skyVisibility);
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
		return isMoonLighting(moonAltDeg, moonIllumination) ? clamp(moonIllumination * moonElevationFade(moonAltDeg), 0, 1) : 0;
	}

	private static float moonElevationFade(float moonAltDeg) {
		return smoothstep(MOON_ELEVATION_FADE_START_DEG, MOON_ELEVATION_FADE_END_DEG, moonAltDeg);
	}
}
