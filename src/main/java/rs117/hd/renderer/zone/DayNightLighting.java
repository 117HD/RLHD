
package rs117.hd.renderer.zone;

import javax.inject.Inject;
import javax.inject.Singleton;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonBehavior;
import rs117.hd.config.MoonPhase;
import rs117.hd.opengl.uniforms.UBOSkybox;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.TimeOfDay;
import rs117.hd.utils.AtmosphereUtils;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.MathUtils.*;

// Day & night cycle lighting for the zone renderer. Owns everything the cycle contributes to a
// frame, so ZoneRenderer is left with ordinary render plumbing. Three things happen here, in the
// order the renderer calls them:
//   1. syncConfig - push config & per-area overrides into TimeOfDay
//   2. resolveDirectionalAngles - pick the pitch/yaw for the shadow-casting directional camera,
//      from the sun, the moon, or a fixed override
//   3. computeLighting - derive sky/fog/light colors and strengths, and upload the skybox UBO
//
// Shadow azimuth convention: the renderer drives the directional camera with yaw = PI - azimuth,
// while anglesToSkyDirection renders the sun/moon disk at PI + azimuth with north/south negated.
// A raw astronomical azimuth fed straight into that shadow line would point shadows toward the
// disk, so adding PI cancels it out and shadows fall away from the light. Every astronomical
// angle here therefore goes through shadowYaw. Fixed-angle overrides from an environment already
// carry the half turn, since setFixedAngleOverrides bakes it into the stored azimuth, so those
// are used verbatim.
@Singleton
public class DayNightLighting {
	// Sun altitude below which sun shadows are gone and the moon takes over
	private static final double SUN_SHADOW_CUTOFF_DEG = 2;

	// Moon altitude below which the moon neither casts light nor shadows
	private static final double MOON_HORIZON_CUTOFF_DEG = -10;

	// Moon illumination below which the moon is treated as dark (new moon)
	private static final float MIN_MOON_ILLUMINATION = 0.01f;

	// Moonlight fades in with altitude across this range
	private static final float MOON_ELEVATION_FADE_START_DEG = -10;
	private static final float MOON_ELEVATION_FADE_END_DEG = 20;

	// Strongest shadow the moon alone can cast, before phase and elevation scaling
	private static final float MOON_SHADOW_STRENGTH = 0.2f;

	// Ceiling on how far moonlight can tint the directional color
	private static final float MAX_MOON_COLOR_INFLUENCE = 0.8f;

	// Moon color influence at the moment the sun crosses the horizon
	private static final float MOON_INFLUENCE_AT_HORIZON = 0.05f;

	// Sun altitudes between which the directional color crossfades from sunlight to moonlight
	private static final float MOON_TINT_SUN_START_DEG = 5;
	private static final float MOON_TINT_SUN_END_DEG = -15;

	// Fraction of moonInfluence applied as a night-sky tint, before environment scaling
	private static final float NIGHT_SKY_TINT_SCALE = 0.05f;

	// Sun altitude at which sky-fill ambient has fully faded out
	private static final float SKY_FILL_FADE_END_DEG = 45;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private TimeOfDay timeOfDay;

	// The lighting values a frame is built from. Seeded from the environment via seedFrom, then
	// computeLighting overwrites whatever the cycle drives. Colors are linear except
	// fogColorSrgb, which is sRGB to match the skybox.
	//
	// The color arrays are owned by this object and reused every frame, so a frame allocates
	// nothing and, more importantly, nothing here ever aliases EnvironmentManager's arrays.
	// Writing through one of those would silently corrupt the environment's own state, and the
	// damage would surface somewhere unrelated. Copying on seed makes that impossible rather
	// than merely unlikely, so callers are free to modify these in place.
	public static class Lighting {
		public final float[] directionalColor = new float[3];
		public final float[] ambientColor = new float[3];
		public final float[] fogColorSrgb = new float[3];
		public final float[] waterColor = new float[3];
		public float directionalStrength;
		public float ambientStrength;

		// Copy the environment's current lighting in as this frame's starting point
		public void seedFrom(EnvironmentManager env) {
			copyTo(directionalColor, env.currentDirectionalColor);
			copyTo(ambientColor, env.currentAmbientColor);
			copyTo(waterColor, env.currentWaterColor);
			copyTo(fogColorSrgb, ColorUtils.linearToSrgb(env.currentFogColor));
			directionalStrength = env.currentDirectionalStrength;
			ambientStrength = env.currentAmbientStrength;
		}
	}

	// Whether the cycle is driving this frame: enabled in config, and in an area that opts in.
	// Everything else in this class is only meaningful when this is true.
	public boolean isActive() {
		return environmentManager.isOverworld() && plugin.configEnableDayNightCycle;
	}

	// Push config and per-area overrides into TimeOfDay, and return the cycle mode in effect.
	// An environment may force a mode, which wins over the user's config setting. Call before
	// reading any TimeOfDay getter, so every consumer in the frame sees the same settings.
	// Every setter is equality-guarded, so calling this more than once per frame is a no-op.
	public DaylightCycle syncConfig() {
		DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
		DaylightCycle daylightCycle = forcedMode != null ? forcedMode : config.daylightCycle();

		MoonPhase forcedMoonPhase = environmentManager.getForcedMoonPhase();
		MoonPhase moonPhase = forcedMoonPhase != null ? forcedMoonPhase : config.moonPhase();

		timeOfDay.setCycleMode(daylightCycle);
		timeOfDay.setDayLength(config.dayLength());
		timeOfDay.setMoonPhase(moonPhase);
		timeOfDay.setMoonBehavior(config.moonBehavior());
		timeOfDay.setCycleDurationMinutes(config.cycleDurationMinutes());
		timeOfDay.setSeasonalHemisphere(config.seasonalHemisphere());
		timeOfDay.setFixedAngleOverrides(
			environmentManager.getForcedFixedSunAngles(),
			environmentManager.getForcedFixedMoonAngles()
		);

		return daylightCycle;
	}

	// Writes the shadow-casting directional camera's angles into out as { pitch, yaw }, and
	// returns it. Picks whichever body is lighting the scene, in priority order: a fixed sun
	// override, a fixed moon (FIXED_NIGHT or an environment override), the moon once the sun has
	// dropped below SUN_SHADOW_CUTOFF_DEG, otherwise the sun. Whichever is chosen, the disk and
	// its shadows stay aligned - shadows drifting away from a stationary sun or moon are the bug
	// this ordering prevents.
	public float[] resolveDirectionalAngles(float[] out) {
		DaylightCycle daylightCycle = syncConfig();

		double[] sunAngles = timeOfDay.getSunAngles();

		if (timeOfDay.hasFixedSunOverride()) {
			// The half turn shadowYaw would add is already baked into the stored azimuth
			double[] fixedSun = timeOfDay.getFixedSunAngles();
			return setAngles(out, altitudeOf(fixedSun), (float) azimuthOf(fixedSun));
		}

		if (daylightCycle == DaylightCycle.FIXED_NIGHT || timeOfDay.hasFixedMoonOverride()) {
			double[] moonAngles = timeOfDay.getFixedNightMoonAngles();
			return setAngles(out, altitudeOf(moonAngles), shadowYaw(azimuthOf(moonAngles)));
		}

		// Below the cutoff sun shadows have faded out. Switch to the moon early so the shadow
		// map is already oriented by the time moon shadows fade in, avoiding a brightness pop.
		if (Math.toDegrees(altitudeOf(sunAngles)) < SUN_SHADOW_CUTOFF_DEG
			&& timeOfDay.getMoonAltitudeDegrees() > MOON_HORIZON_CUTOFF_DEG) {
			double[] moonAngles = timeOfDay.getCurrentMoonBehavior() == MoonBehavior.NIGHT_SYNCED
				? timeOfDay.getNightSyncedMoonAngles()
				: AtmosphereUtils.getMoonPosition(timeOfDay.getMoonDate().toEpochMilli(), timeOfDay.getCurrentLatLong());
			return setAngles(out, altitudeOf(moonAngles), shadowYaw(azimuthOf(moonAngles)));
		}

		return setAngles(out, altitudeOf(sunAngles), shadowYaw(azimuthOf(sunAngles)));
	}

	// Derives the frame's lighting from the current sun and moon and uploads the skybox UBO.
	// The caller seeds lighting from the environment first; this modifies it in place. On
	// return, lighting.fogColorSrgb holds the sky horizon color, which doubles as the fog color.
	public void computeLighting(Lighting lighting) {
		DaylightCycle daylightCycle = syncConfig();

		copyTo(lighting.directionalColor, timeOfDay.getRegionalDirectionalLight(environmentManager.currentDirectionalColor));
		copyTo(lighting.ambientColor, timeOfDay.getRegionalAmbientLight(environmentManager.currentAmbientColor));

		float brightnessMultiplier = timeOfDay.getDynamicBrightnessMultiplier(plugin.configMinimumBrightness);
		lighting.directionalStrength = environmentManager.currentDirectionalStrength
			* brightnessMultiplier
			* environmentManager.currentSunlightStrength;
		// Deliberately ignore the environment's ambientStrength (e.g. WINTER=3.5, AUTUMN=0.3)
		// while the cycle is active, so the cycle's own brightness multiplier alone controls
		// how dark nights get. Seasonal values would otherwise fight it.
		lighting.ambientStrength = brightnessMultiplier;

		double sunAltDeg = Math.toDegrees(altitudeOf(timeOfDay.getSunAngles()));
		double moonAltDeg = timeOfDay.getMoonAltitudeDegrees();
		float moonIllumination = timeOfDay.getMoonIlluminationFraction();

		// Sky gradient as { zenith, horizon, sunGlow }, in sRGB
		float[][] sky = timeOfDay.getSkyGradientColors(
			lighting.fogColorSrgb,
			environmentManager.currentSunStrength,
			environmentManager.currentSunriseSunsetStrength,
			environmentManager.currentSkyColorTakeoverAngle
		);

		uploadSkyUniforms(sky, daylightCycle, moonIllumination);

		float shadowVisibility = computeShadowVisibility(sunAltDeg, moonAltDeg, moonIllumination);
		float moonInfluence = computeMoonInfluence(sunAltDeg, moonAltDeg, moonIllumination);

		if (moonInfluence > 0) {
			// Tint the directional light toward moonlight
			float[] moonLightColor = environmentManager.currentMoonLightColor;
			for (int i = 0; i < 3; i++)
				lighting.directionalColor[i] = mix(lighting.directionalColor[i], moonLightColor[i], moonInfluence);

			tintNightSky(sky, moonInfluence);
		}

		// The horizon color doubles as fog, so geometry fading into fog meets the skybox
		// seamlessly at the horizon.
		copyTo(lighting.fogColorSrgb, sky[1]);
		copyTo(lighting.waterColor, ColorUtils.srgbToLinear(sky[1]));

		// Raise the ambient floor, scaling the boost down as moonlight takes over so the two
		// don't stack into an over-bright night.
		float boostedFloor = (plugin.configMinimumBrightness / 100.0f)
			* (1 + environmentManager.currentMinBrightnessBoost * (1 - shadowVisibility));
		lighting.ambientStrength = Math.max(lighting.ambientStrength, boostedFloor);

		// Fold some of the unshadowed directional light into ambient to stand in for sky-fill
		// in shadows. Physically this is strongest at night and twilight, where soft moon and
		// sky light fill shadows, and minimal under a high sun, where light is harsh and
		// shadows crisp. Fading it out as the sun climbs keeps high-noon shadows as dark as
		// they are with the cycle off, while preserving the soft look near the horizon.
		float skyFill = 1 - smoothstep(0, SKY_FILL_FADE_END_DEG, (float) sunAltDeg);
		add(lighting.ambientColor, lighting.ambientColor, multiply(lighting.directionalColor, (1 - shadowVisibility) * skyFill));
		lighting.directionalStrength *= shadowVisibility;
	}

	// Restore the non-cycle defaults, for when the cycle stops driving the frame
	public void reset() {
		plugin.uboSkybox.reset();
	}

	// How strongly the directional light casts shadows, in 0..1. By day this tracks the sun's
	// height, ramping in from SUN_SHADOW_CUTOFF_DEG so shadows don't snap on at sunrise. At night
	// the moon takes over at a fraction of the strength, scaled by phase and its own altitude.
	// Both ends use smoothstep so there's no visible pop as one hands off to the other.
	private float computeShadowVisibility(double sunAltDeg, double moonAltDeg, float moonIllumination) {
		if (sunAltDeg > SUN_SHADOW_CUTOFF_DEG) {
			if (sunAltDeg <= 12)
				return (float) ((sunAltDeg - 2) / 10.0 * 0.6);
			if (sunAltDeg <= 15)
				return (float) (0.6 + ((sunAltDeg - 12) / 3.0) * 0.3);
			return clamp(Math.sin(Math.toRadians(sunAltDeg)), 0.9f, 1);
		}

		float moonBaseShadow = 0;
		if (isMoonLighting(moonAltDeg, moonIllumination))
			moonBaseShadow = moonIllumination * MOON_SHADOW_STRENGTH * moonElevationFade(moonAltDeg);

		// Blend in over the twilight window, from the sun cutoff down to -15 degrees
		return smoothstep((float) SUN_SHADOW_CUTOFF_DEG, MOON_TINT_SUN_END_DEG, (float) sunAltDeg) * moonBaseShadow;
	}

	// How far the directional light color has crossfaded from sunlight to moonlight, in
	// 0..MAX_MOON_COLOR_INFLUENCE. Moonlight starts bleeding in while the sun is still up, at
	// MOON_TINT_SUN_START_DEG, overlapping the sun's own warm-color fade near the horizon.
	// Without that overlap the warm tones vanish exactly as the cool ones arrive and the color
	// visibly pops at 0 degrees. Capped below 1 so the moon tints the base night color rather
	// than replacing it.
	private float computeMoonInfluence(double sunAltDeg, double moonAltDeg, float moonIllumination) {
		if (sunAltDeg >= MOON_TINT_SUN_START_DEG || !isMoonLighting(moonAltDeg, moonIllumination))
			return 0;

		float influence;
		if (sunAltDeg >= 0) {
			// Pre-horizon: a gentle ramp up to the horizon value
			influence = smoothstep(MOON_TINT_SUN_START_DEG, 0, (float) sunAltDeg) * MOON_INFLUENCE_AT_HORIZON;
		} else {
			// Twilight: from the horizon value up to the cap, then held there through the night
			influence = mix(
				MOON_INFLUENCE_AT_HORIZON,
				MAX_MOON_COLOR_INFLUENCE,
				smoothstep(0, MOON_TINT_SUN_END_DEG, (float) sunAltDeg)
			);
		}

		// Fade with the moon's own altitude, and with phase, so a new moon tints nothing
		return influence * moonElevationFade(moonAltDeg) * moonIllumination;
	}

	// Pull the sky's zenith and horizon toward the environment's night-sky color as moonlight
	// takes hold. The base tint is deliberately subtle; nightSkyColorStrength scales it up for
	// areas where that reads too weakly, clamped so a large value can't overshoot past the
	// night-sky color itself.
	private void tintNightSky(float[][] sky, float moonInfluence) {
		float[] nightSkyColor = environmentManager.currentNightSkyColor;
		float skyTint = Math.min(1, moonInfluence * NIGHT_SKY_TINT_SCALE * environmentManager.currentNightSkyColorStrength);
		for (int i = 0; i < 3; i++) {
			sky[0][i] = mix(sky[0][i], nightSkyColor[i], skyTint);
			sky[1][i] = mix(sky[1][i], nightSkyColor[i], skyTint);
		}

		plugin.uboSkybox.skyZenithColor.set(sky[0]);
		plugin.uboSkybox.skyHorizonColor.set(sky[1]);
	}

	// Write the sky, moon, star, nebula and aurora uniforms for this frame
	private void uploadSkyUniforms(float[][] sky, DaylightCycle daylightCycle, float moonIllumination) {
		UBOSkybox ubo = plugin.uboSkybox;

		ubo.skyGradientEnabled.set(1);
		ubo.skyZenithColor.set(sky[0]);
		ubo.skyHorizonColor.set(sky[1]);
		ubo.skySunColor.set(sky[2]);
		ubo.skySunDir.set(timeOfDay.getSunDirectionForSky());

		ubo.skyMoonDir.set(timeOfDay.getMoonDirectionForSky());
		// The visible disk keeps moonColor; moonLightColor drives the light on geometry
		ubo.skyMoonColor.set(environmentManager.currentMoonColor);
		ubo.skyMoonIllumination.set(moonIllumination);
		// An environment may force the moon on for a cutscene, overriding the player's toggle.
		// The daytime fixed modes still hide it either way - a moon has no place in a locked
		// daylit sky - so forcing only bypasses the config gate.
		boolean moonEnabled = config.enableMoon() || environmentManager.forceMoonActive();
		ubo.moonVisibility.set(!hidesMoon(daylightCycle) && moonEnabled ? environmentManager.currentMoonVisibility : 0);
		ubo.moonSizeMult.set(environmentManager.currentMoonSizeMult);

		ubo.starVisibility.set(config.enableStarMap() ? environmentManager.currentStarVisibility : 0);
		// Float suffixes matter: an all-int ternary would bind set(int), which silently no-ops
		// on a Float property rather than failing to compile
		ubo.nebulaVisibility.set(config.enableNebulas() ? 1f : 0f);

		// Auroras appear on nights a per-night random roll selects. In modes with a real
		// day/night arc the roll flips during daytime, where it's hidden behind nightSkyBlend;
		// in always-night modes getAuroraStrength applies a time-of-cycle envelope so they
		// come and go rather than blazing for the whole cycle. The per-environment visibility
		// scales how strong they are when they do appear, independently of starVisibility.
		ubo.auroraVisibility.set(timeOfDay.getAuroraStrength() * environmentManager.currentAuroraVisibility);
	}

	// Daytime fixed modes hide the moon, since it has no place in a locked daylit sky
	private static boolean hidesMoon(DaylightCycle daylightCycle) {
		switch (daylightCycle) {
			case FIXED_DAWN:
			case FIXED_MIDDAY:
			case FIXED_SUNSET:
			case FIXED_TWILIGHT:
				return true;
			default:
				return false;
		}
	}

	// Whether the moon is up and lit enough to contribute light or shadows
	private static boolean isMoonLighting(double moonAltDeg, float moonIllumination) {
		return moonAltDeg > MOON_HORIZON_CUTOFF_DEG && moonIllumination > MIN_MOON_ILLUMINATION;
	}

	// Moonlight fade with altitude, in 0..1. Smoothstep, so the onset at the horizon is C1
	// continuous and the moon's light doesn't pop in as it rises.
	private static float moonElevationFade(double moonAltDeg) {
		return smoothstep(MOON_ELEVATION_FADE_START_DEG, MOON_ELEVATION_FADE_END_DEG, (float) moonAltDeg);
	}

	// TimeOfDay and AtmosphereUtils return horizontal coordinates as { azimuth, altitude }.
	// EnvironmentManager.currentSunAngles uses the opposite order, { pitch, yaw }, so a bare
	// [0]/[1] means different things depending on where the array came from. Always go through
	// these, so a mix-up is visible instead of silent.
	private static double azimuthOf(double[] angles) {
		return angles[0];
	}

	private static double altitudeOf(double[] angles) {
		return angles[1];
	}

	// See the note on the shadow azimuth convention at the top of this file
	private static float shadowYaw(double azimuth) {
		return (float) (azimuth + PI);
	}

	private static float[] setAngles(float[] out, double pitch, float yaw) {
		out[0] = (float) pitch;
		out[1] = yaw;
		return out;
	}
}
