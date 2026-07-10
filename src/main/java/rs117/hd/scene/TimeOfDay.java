package rs117.hd.scene;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.http.api.worlds.WorldRegion;
import rs117.hd.config.DayLength;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonBehavior;
import rs117.hd.config.MoonPhase;
import rs117.hd.utils.AtmosphereUtils;

import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class TimeOfDay {

	// Sky color keyframe tables. These are read-only constant data consumed by
	// AtmosphereUtils.interpolateSrgb (which only reads and builds fresh float[]
	// per call). Hoisted to static final so they aren't reallocated every frame.
	private static final Object[][] ENHANCED_SKY_KEYFRAMES = {
		// Deep night (sun well below horizon) - lightened and gradual progression
		{ -30.0, new java.awt.Color(35, 42, 58) },    // Deepest night (lightened)
		{ -15.0, new java.awt.Color(35, 42, 58) },    // Stable deep night (lightened)
		{ -8.0,  new java.awt.Color(42, 30, 80) },    // Early twilight brightening (lightened)
		{ 0.0,   new java.awt.Color(210, 135, 95) },  // Peak sunset red
		{ 8.0,   new java.awt.Color(220, 170, 115) }, // Late golden hour (dimmed)
		{ 12.0,  new java.awt.Color(220, 180, 145) }, // Warm late afternoon
		{ 15.0,  new java.awt.Color(200, 175, 160) }, // Soft warm light
		{ 18.0,  new java.awt.Color(180, 170, 175) }, // Afternoon transition
		{ 22.0,  new java.awt.Color(165, 167, 185) }, // Subtle warm tint
		{ 25.0,  new java.awt.Color(150, 165, 190) }, // Very subtle warmth
		{ 30.0,  new java.awt.Color(135, 165, 200) }, // Midday blue (natural)
		{ 50.0,  new java.awt.Color(125, 160, 195) }, // Clear blue (muted)
		{ 70.0,  new java.awt.Color(120, 155, 190) }, // High sun blue (subdued)
		{ 90.0,  new java.awt.Color(115, 150, 185) }  // Zenith blue (realistic)
	};

	// Zenith color keyframes (top of sky)
	private static final Object[][] ZENITH_KEYFRAMES = {
		{ -30.0, new java.awt.Color(1, 1, 4) },       // Deep night - near black
		{ -15.0, new java.awt.Color(3, 4, 10) },      // Late night
		{ -8.0,  new java.awt.Color(45, 35, 70) },    // Early twilight - purple tint
		{ -3.0,  new java.awt.Color(80, 60, 100) },   // Twilight
		{ 0.0,   new java.awt.Color(100, 80, 120) },  // Horizon sun
		{ 5.0,   new java.awt.Color(120, 140, 180) }, // Early sunrise
		{ 15.0,  new java.awt.Color(100, 150, 200) }, // Morning
		{ 30.0,  new java.awt.Color(90, 145, 200) },  // Mid-morning
		{ 50.0,  new java.awt.Color(85, 140, 195) },  // Midday
		{ 90.0,  new java.awt.Color(80, 135, 190) }   // High noon
	};

	// Horizon color keyframes (sides/bottom of sky)
	private static final Object[][] HORIZON_KEYFRAMES = {
		{ -30.0, new java.awt.Color(1, 2, 5) },       // Deep night - near black
		{ -15.0, new java.awt.Color(4, 5, 12) },      // Late night
		{ -8.0,  new java.awt.Color(60, 45, 65) },    // Early twilight
		{ -3.0,  new java.awt.Color(140, 80, 70) },   // Twilight - orange/red
		{ 0.0,   new java.awt.Color(220, 130, 80) },  // Sunrise/sunset - golden
		{ 5.0,   new java.awt.Color(230, 170, 120) }, // Early morning golden
		{ 10.0,  new java.awt.Color(200, 180, 160) }, // Morning warm
		{ 20.0,  new java.awt.Color(170, 175, 185) }, // Late morning
		{ 30.0,  new java.awt.Color(150, 165, 190) }, // Midday haze
		{ 50.0,  new java.awt.Color(140, 160, 190) }, // Afternoon
		{ 90.0,  new java.awt.Color(135, 155, 185) }  // High noon
	};

	// Sun glow color keyframes (color of the glow around the sun)
	private static final Object[][] SUN_GLOW_KEYFRAMES = {
		{ -30.0, new java.awt.Color(0, 0, 0) },       // No glow at night
		{ -10.0, new java.awt.Color(20, 10, 30) },    // Very faint purple
		{ -5.0,  new java.awt.Color(80, 40, 60) },    // Purple/pink
		{ -2.0,  new java.awt.Color(180, 80, 50) },   // Deep orange/red
		{ 0.0,   new java.awt.Color(255, 150, 80) },  // Bright orange
		{ 5.0,   new java.awt.Color(255, 200, 130) }, // Golden yellow
		{ 15.0,  new java.awt.Color(255, 230, 180) }, // Warm white
		{ 30.0,  new java.awt.Color(255, 250, 220) }, // Nearly white
		{ 50.0,  new java.awt.Color(255, 255, 240) }, // White with slight warmth
		{ 90.0,  new java.awt.Color(255, 255, 250) }  // Pure white
	};

	// Length of one Synced Days cycle: a full day/night every real hour, phase-locked
	// to the UTC clock so every player sees the same sun position at the same moment.
	private static final long SYNCED_DAYS_PERIOD_MS = 60L * 60 * 1000;

	// The natural (unwarped) cycle position where daytime ends and night begins.
	// 0.0-0.70 maps to 5am-7pm (day, incl. twilight), 0.70-1.0 maps to 7pm-5am (night).
	private static final double NATURAL_DAY_BOUNDARY = 0.70;

	public static final float MINUTES_PER_DAY = 30 / 60.f;

	// Probability that any given simulated night is an "aurora night", in
	// environments flagged aurora-eligible. Rolled deterministically per night.
	private static final double AURORA_NIGHT_CHANCE = 0.03;

	// Fixed Night mode: the moon is locked at a prominent position in the
	// south-east sky and always rendered full. Stored as {azimuth, altitude} radians,
	// matching the convention used by AtmosphereUtils.getMoonPosition().
	// These are the defaults; an environment may override them per-area via
	// fixedMoonAngles (see setFixedAngleOverrides).
	private static final double FIXED_NIGHT_MOON_AZIMUTH = Math.toRadians(135); // south-east
	private static final double FIXED_NIGHT_MOON_ALTITUDE = Math.toRadians(25);  // low in the sky

	// Per-environment fixed-angle overrides {azimuth, altitude} in radians, or
	// null to use astronomical/default angles. Set once per frame by the renderer
	// from the current environment. Only consulted while a fixed cycle mode is
	// active (see isFixedMode) — the dynamic cycle always computes angles.
	private double[] fixedSunAnglesOverride = null;
	private double[] fixedMoonAnglesOverride = null;

	// Night Synced mode: day offset advances only while the moon is below the horizon,
	// so phase changes are never visible. We track pending increments and apply them
	// only when the mirrored moon altitude is negative.
	private long nightSyncedDayOffset = 0;
	private long lastNightSyncedCycles = 0;
	private long pendingDayIncrements = 0;

	// Static variables to maintain cycle state across config changes
	private float lastDayLength = MINUTES_PER_DAY;
	private long lastUpdateTime = 0;
	// Start the dynamic cycle at midday. cyclePosition 0.35 maps to 12:00pm
	// in getModifiedDate()'s afternoon range (0.35-0.55 -> 12pm-5pm).
	private double accumulatedCycleTime = 0.35;
	private long completedCycles = 0; // Each completed cycle = one simulated day

	// Current cycle mode — set once per frame by ZoneRenderer before any TimeOfDay calls
	private DaylightCycle currentCycleMode = DaylightCycle.DYNAMIC;

	// Current day length skew — set once per frame alongside the cycle mode.
	// Warps the linear cycle clock so day/night occupy different shares of the
	// fixed total cycle time (see applyDayLengthWarp).
	private DayLength currentDayLength = DayLength.STANDARD;

	// Current moon phase lock — set once per frame. DYNAMIC = phase advances
	// naturally; any other value locks the moon's illumination fraction.
	private MoonPhase currentMoonPhase = MoonPhase.DYNAMIC;

	@Getter
	private MoonBehavior currentMoonBehavior = MoonBehavior.NIGHT_SYNCED;

	private float currentCycleDuration = 700;

	@Getter
	private final double[] currentLatLong = { 0, 0 };

	/**
	 * Set the per-environment fixed sun/moon angle overrides for this frame.
	 *
	 * Inputs are in the environment-file convention {altitude, azimuth} in radians
	 * (the same order as Environment.sunAngles), or null for no override. They are
	 * stored internally as {azimuth, altitude} to match the convention returned by
	 * AtmosphereUtils.getSunAngles()/getMoonPosition() and consumed by the rest of
	 * this class.
	 *
	 * Call before any other TimeOfDay methods. Only takes effect under a fixed
	 * cycle mode; the dynamic cycle ignores these.
	 */
	public void setFixedAngleOverrides(@Nullable float[] sunAngles, @Nullable float[] moonAngles) {
		// sunAngles/moonAngles are {altitude, azimuth}; store {azimuth, altitude}.
		fixedSunAnglesOverride = sunAngles == null ? null :
			new double[] { sunAngles[1], sunAngles[0] };
		fixedMoonAnglesOverride = moonAngles == null ? null :
			new double[] { moonAngles[1], moonAngles[0] };
	}

	/**
	 * Whether the current cycle mode is one of the fixed modes (the sun/moon sit
	 * at a fixed time of day). Fixed-angle overrides only apply in these modes.
	 * DYNAMIC and REAL_TIME are excluded — both compute a moving astronomical sun.
	 */
	public boolean isFixedMode() {
		switch (currentCycleMode) {
			case FIXED_DAWN:
			case FIXED_MIDDAY:
			case FIXED_SUNSET:
			case FIXED_NIGHT:
			case ALWAYS_NIGHT:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Fixed moon angles {azimuth, altitude} in radians for the current fixed
	 * mode. Returns the environment's fixedMoonAngles override when set,
	 * otherwise the default Fixed Night position. Used both for the sky moon
	 * direction and the shadow-casting light direction so the moon disk and the
	 * shadows it casts stay locked together.
	 */
	public double[] getFixedNightMoonAngles() {
		if (fixedMoonAnglesOverride != null)
			return new double[] { fixedMoonAnglesOverride[0], fixedMoonAnglesOverride[1] };
		return new double[] { FIXED_NIGHT_MOON_AZIMUTH, FIXED_NIGHT_MOON_ALTITUDE };
	}

	/**
	 * Whether the current environment supplies a fixed sun-angle override that
	 * should be honored (i.e. a fixed mode is active and an override is set).
	 */
	public boolean hasFixedSunOverride() {
		return isFixedMode() && fixedSunAnglesOverride != null;
	}

	/**
	 * Whether the current environment supplies a fixed moon-angle override that
	 * should be honored (i.e. a fixed mode is active and an override is set).
	 */
	public boolean hasFixedMoonOverride() {
		return isFixedMode() && fixedMoonAnglesOverride != null;
	}

	/** The fixed sun angles {azimuth, altitude} in radians. Only valid when {@link #hasFixedSunOverride()}. */
	public double[] getFixedSunAngles() {
		return new double[] { fixedSunAnglesOverride[0], fixedSunAnglesOverride[1] };
	}

	/**
	 * Build a normalized direction vector FROM the camera TO the given
	 * {azimuth, altitude} sky position, using the renderer/light convention
	 * (pitch = altitude, yaw = PI - azimuth). Shared by the sun/moon sky
	 * direction getters.
	 */
	private float[] anglesToSkyDirection(double azimuth, double altitude) {
		double yaw = Math.PI - azimuth;

		float x = (float) (Math.sin(yaw) * Math.cos(altitude));
		float y = (float) Math.sin(altitude);
		float z = (float) (-Math.cos(yaw) * Math.cos(altitude));

		float length = (float) Math.sqrt(x * x + y * y + z * z);
		if (length > 0.0001f) {
			x /= length;
			y /= length;
			z /= length;
		}
		return new float[] { x, y, z };
	}

	/**
	 * Set the cycle mode for this frame. Call before any other TimeOfDay methods.
	 */
	public void setCycleMode(DaylightCycle mode) {
		currentCycleMode = mode;
	}

	/**
	 * Set the day length skew for this frame. Call before any other TimeOfDay methods.
	 */
	public void setDayLength(DayLength dayLength) {
		currentDayLength = dayLength;
	}

	public void setCycleDurationMinutes(float cycleDuration) {
		currentCycleDuration = cycleDuration;
	}

	/**
	 * Set the moon phase lock for this frame. Call before any other TimeOfDay methods.
	 */
	public void setMoonPhase(MoonPhase moonPhase) {
		currentMoonPhase = moonPhase;
	}

	public void setMoonBehavior(MoonBehavior moonBehavior) {
		currentMoonBehavior = moonBehavior;
	}

	/**
	 * Warp a linear cycle position (0..1) so day and night occupy a different
	 * share of the cycle, without changing the total cycle length.
	 *
	 * The cycle clock advances at a constant real-time rate; this remaps where
	 * that clock "is" in the day. The day segment [0, dayFraction) is stretched
	 * or compressed onto the natural day segment [0, NATURAL_DAY_BOUNDARY), and
	 * likewise for night. Net effect: the favored period elapses in slow motion
	 * while the other period is fast-forwarded, and a full cycle still takes
	 * exactly cycleDurationMinutes.
	 */
	private double applyDayLengthWarp(double cyclePosition) {
		double dayFraction = currentDayLength.dayFraction;
		// STANDARD (and any config matching the natural split) is the identity map.
		if (Math.abs(dayFraction - NATURAL_DAY_BOUNDARY) < 1e-6)
			return cyclePosition;

		if (cyclePosition < dayFraction) {
			// Within the (re-sized) day: scale into the natural day segment.
			return (cyclePosition / dayFraction) * NATURAL_DAY_BOUNDARY;
		} else {
			// Within the (re-sized) night: scale into the natural night segment.
			double nightProgress = (cyclePosition - dayFraction) / (1.0 - dayFraction);
			return NATURAL_DAY_BOUNDARY + nightProgress * (1.0 - NATURAL_DAY_BOUNDARY);
		}
	}

	/**
	 * Get the current sun or moon angles for a given set of coordinates and simulated day length in minutes.
	 *
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 */
	public double[] getShadowAngles() {
		Instant modifiedDate = getModifiedDate();
		double[] angles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);
		return isNight(angles) ?
			AtmosphereUtils.getMoonPosition(modifiedDate.toEpochMilli(), currentLatLong) :
			angles;
	}

	public double[] getSunAngles() {
		Instant modifiedDate = getModifiedDate();
		return AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);
	}

	public float[] getLightColor() {
		Instant modifiedDate = getModifiedDate();
		return AtmosphereUtils.getDirectionalLight(this, modifiedDate.toEpochMilli(), currentLatLong);
	}

	public float[] getRegionalDirectionalLight(float[] regionalDirectionalColor) {
		Instant modifiedDate = getModifiedDate();
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);

		// Get the dynamic directional light color
		float[] dynamicLight = AtmosphereUtils.getDirectionalLight(this, modifiedDate.toEpochMilli(), currentLatLong);

		// Calculate blend factor - same as skybox and ambient for consistency
		float blendFactor;
		if (sunAltitudeDegrees >= 30) {
			// High sun - use pure regional color (100% regional) to match disabled behavior
			blendFactor = 1.0f;
		} else if (sunAltitudeDegrees >= 15) {
			// Medium sun - strong regional influence (75-100% regional)
			blendFactor = (float) (0.75 + ((sunAltitudeDegrees - 15) / 15.0) * 0.25);
		} else if (sunAltitudeDegrees >= 5) {
			// Sunset/late sunrise - moderate regional influence (50-75% regional)
			blendFactor = (float) (0.50 + ((sunAltitudeDegrees - 5) / 10.0) * 0.25);
		} else if (sunAltitudeDegrees >= 0) {
			// Low sun - moderate regional influence (30-50% regional)
			blendFactor = (float) (0.30 + (sunAltitudeDegrees / 5.0) * 0.20);
		} else {
			// Night/twilight - minimal regional influence (0-30% regional)
			blendFactor = (float) Math.max(0.0, 0.30 + sunAltitudeDegrees / 10.0 * 0.30);
		}

		// Regional color is already in linear space from environment manager
		// Blend the colors in linear space
		float[] blended = new float[3];
		for (int i = 0; i < 3; i++) {
			blended[i] = dynamicLight[i] * (1 - blendFactor) + regionalDirectionalColor[i] * blendFactor;
		}

		return blended;
	}

	public float[] getAmbientColor() {
		Instant modifiedDate = getModifiedDate();
		return AtmosphereUtils.getAmbientColor(modifiedDate.toEpochMilli(), currentLatLong);
	}

	public float[] getRegionalAmbientLight(float[] regionalAmbientColor) {
		Instant modifiedDate = getModifiedDate();
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);

		// Get the dynamic ambient light color
		float[] dynamicAmbient = AtmosphereUtils.getAmbientColor(modifiedDate.toEpochMilli(), currentLatLong);

		// Calculate blend factor based on sun altitude - same as skybox for consistency
		float blendFactor;
		if (sunAltitudeDegrees >= 30) {
			// High sun - use pure regional color (100% regional) to match disabled behavior
			blendFactor = 1.0f;
		} else if (sunAltitudeDegrees >= 15) {
			// Medium sun - strong regional influence (75-100% regional)
			blendFactor = (float) (0.75 + ((sunAltitudeDegrees - 15) / 15.0) * 0.25);
		} else if (sunAltitudeDegrees >= 5) {
			// Sunset/late sunrise - moderate regional influence (50-75% regional)
			blendFactor = (float) (0.50 + ((sunAltitudeDegrees - 5) / 10.0) * 0.25);
		} else if (sunAltitudeDegrees >= 0) {
			// Low sun - moderate regional influence (30-50% regional)
			blendFactor = (float) (0.30 + (sunAltitudeDegrees / 5.0) * 0.20);
		} else {
			// Night/twilight - minimal regional influence (0-30% regional)
			blendFactor = (float) Math.max(0.0, 0.30 + sunAltitudeDegrees / 10.0 * 0.30);
		}

		// Blend the colors in linear space
		float[] blended = new float[3];
		for (int i = 0; i < 3; i++) {
			blended[i] = dynamicAmbient[i] * (1 - blendFactor) + regionalAmbientColor[i] * blendFactor;
		}

		return blended;
	}

	public float[] getSkyColor() {
		Instant modifiedDate = getModifiedDate();
		return AtmosphereUtils.getSkyColor(modifiedDate.toEpochMilli(), currentLatLong);
	}

	public float[] getEnhancedSkyColor(float[] regionalFogColor, float sunStrength) {
		Instant modifiedDate = getModifiedDate();
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);

		// Convert sun altitude to degrees (-90 to +90)
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);

		// Get the enhanced color for current sun altitude (returns sRGB values)
		float[] enhancedColorSrgb = AtmosphereUtils.interpolateSrgb((float) sunAltitudeDegrees, ENHANCED_SKY_KEYFRAMES);
		// Convert to linear for blending
		float[] enhancedColor = rs117.hd.utils.ColorUtils.srgbToLinear(enhancedColorSrgb);

		// Apply sunStrength: suppress procedural sunset colors for dark environments
		// For positive altitudes, keep full suppression — the regional blend will take over.
		// For negative altitudes, fade suppression out by -25° where night colors dominate.
		if (sunStrength < 1.0f && regionalFogColor != null) {
			float[] regionalLin = rs117.hd.utils.ColorUtils.srgbToLinear(regionalFogColor);
			float[] nightSkyLin = rs117.hd.utils.ColorUtils.srgbToLinear(
				new float[] { 5f / 255f, 7f / 255f, 15f / 255f }
			);

			float suppressionWindow;
			if (sunAltitudeDegrees >= 0) {
				suppressionWindow = 1.0f; // Full suppression above horizon
			} else if (sunAltitudeDegrees <= -25) {
				suppressionWindow = 0.0f;
			} else {
				float st = (float) (-sunAltitudeDegrees / 25.0);
				suppressionWindow = 1.0f - st * st * (3.0f - 2.0f * st);
			}

			float suppression = (1.0f - sunStrength) * suppressionWindow;
			if (suppression > 0.0f) {
				// Smooth crossfade between regional and night sky blend targets
				float nightMix;
				if (sunAltitudeDegrees <= -5) {
					nightMix = 1.0f;
				} else if (sunAltitudeDegrees >= 5) {
					nightMix = 0.0f;
				} else {
					float nm = (float) ((5.0 - sunAltitudeDegrees) / 10.0);
					nightMix = nm * nm * (3.0f - 2.0f * nm);
				}
				float[] blendTarget = new float[3];
				for (int i = 0; i < 3; i++) {
					blendTarget[i] = regionalLin[i] * (1 - nightMix) + nightSkyLin[i] * nightMix;
				}

				for (int i = 0; i < 3; i++) {
					enhancedColor[i] = enhancedColor[i] * (1 - suppression) + blendTarget[i] * suppression;
				}
			}
		}

		// Smoothstep blend from peak sunset (0°) to full regional (40°)
		float blendFactor;
		if (sunAltitudeDegrees >= 40) {
			blendFactor = 1.0f;
		} else if (sunAltitudeDegrees >= 0) {
			float t = (float) (sunAltitudeDegrees / 40.0);
			blendFactor = t * t * (3.0f - 2.0f * t); // Smoothstep curve
		} else {
			blendFactor = 0.0f;
		}

		// Convert regional fog color from sRGB to linear RGB for proper blending
		float[] regionalLinear = rs117.hd.utils.ColorUtils.srgbToLinear(regionalFogColor);

		// Use the regional color directly without desaturation to preserve vivid colors
		float[] desaturatedRegional = regionalLinear;

		// Blend between enhanced color and desaturated regional color (in linear space)
		float[] resultLinear = new float[3];
		for (int i = 0; i < 3; i++) {
			resultLinear[i] = enhancedColor[i] * (1 - blendFactor) + desaturatedRegional[i] * blendFactor;
		}

		// Convert back to sRGB for return
		return rs117.hd.utils.ColorUtils.linearToSrgb(resultLinear);
	}

	/**
	 * Get sky gradient colors for the current time of day.
	 * Returns an array of 3 float[3] arrays: [zenithColor, horizonColor, sunGlowColor]
	 * All colors are in sRGB space.
	 * @param regionalFogColor The regional fog color to blend with during peak daytime (sRGB)
	 */
	public float[][] getSkyGradientColors(float[] regionalFogColor, float sunStrength) {
		return getSkyGradientColors(regionalFogColor, sunStrength, 1.0f);
	}

	public float[][] getSkyGradientColors(
		float[] regionalFogColor,
		float sunStrength,
		float sunriseSunsetStrength
	) {
		Instant modifiedDate = getModifiedDate();
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);

		float[] zenithColor = AtmosphereUtils.interpolateSrgb((float) sunAltitudeDegrees, ZENITH_KEYFRAMES);
		float[] horizonColor = AtmosphereUtils.interpolateSrgb((float) sunAltitudeDegrees, HORIZON_KEYFRAMES);
		float[] sunGlowColor = AtmosphereUtils.interpolateSrgb((float) sunAltitudeDegrees, SUN_GLOW_KEYFRAMES);

		// Apply sunStrength: suppress procedural sunset colors for dark environments
		// For positive altitudes, keep full suppression — the regional blend will take over.
		// For negative altitudes, fade suppression out by -25° where night colors dominate.
		if (sunStrength < 1.0f && regionalFogColor != null) {
			float[] regionalLin = rs117.hd.utils.ColorUtils.srgbToLinear(regionalFogColor);
			float[] nightSkyLin = rs117.hd.utils.ColorUtils.srgbToLinear(
				new float[] { 5f / 255f, 7f / 255f, 15f / 255f }
			);

			float suppressionWindow;
			if (sunAltitudeDegrees >= 0) {
				suppressionWindow = 1.0f; // Full suppression above horizon
			} else if (sunAltitudeDegrees <= -25) {
				suppressionWindow = 0.0f;
			} else {
				float st = (float) (-sunAltitudeDegrees / 25.0);
				suppressionWindow = 1.0f - st * st * (3.0f - 2.0f * st);
			}

			float suppression = (1.0f - sunStrength) * suppressionWindow;
			if (suppression > 0.0f) {
				// Smooth crossfade between regional and night sky blend targets
				// around 0° to avoid a hard color jump at the horizon
				float nightMix;
				if (sunAltitudeDegrees <= -5) {
					nightMix = 1.0f;
				} else if (sunAltitudeDegrees >= 5) {
					nightMix = 0.0f;
				} else {
					float nm = (float) ((5.0 - sunAltitudeDegrees) / 10.0);
					nightMix = nm * nm * (3.0f - 2.0f * nm);
				}
				float[] blendTarget = new float[3];
				for (int i = 0; i < 3; i++) {
					blendTarget[i] = regionalLin[i] * (1 - nightMix) + nightSkyLin[i] * nightMix;
				}

				for (int i = 0; i < 3; i++) {
					zenithColor[i] = zenithColor[i] * (1 - suppression) + blendTarget[i] * suppression;
					horizonColor[i] = horizonColor[i] * (1 - suppression) + blendTarget[i] * suppression;
				}
				// Suppress sun glow toward zero (it's additive)
				for (int i = 0; i < 3; i++) {
					sunGlowColor[i] = sunGlowColor[i] * (1 - suppression);
				}
			}
		}

		// Apply sunriseSunsetStrength: an independent per-area knob that stops the
		// procedural sunrise/sunset from overriding a strongly-colored area's own sky.
		//
		// Some areas set a vivid regional sky (e.g. Tolna's blood-red #290000) that is
		// meant to be the mood all day. The day/night cycle's procedural twilight paints
		// its own orange->blue gradient over that, so at sunrise/sunset the intended red
		// "turns blue". Lowering this knob holds the sky at the area's OWN regional color
		// through the twilight window instead — at strength 0 the procedural sunrise/set
		// gradient and sun glow are fully replaced by the regional color, so the area
		// keeps its look. The blend target is the regional color itself (NOT the night
		// sky) so the area's color is preserved rather than muted to black. The separate
		// night blend below still darkens things toward deep night once the sun is well
		// down, so nights stay dark regardless of this knob.
		if (sunriseSunsetStrength < 1.0f && regionalFogColor != null) {
			// Window covers the twilight span where the procedural gradient diverges
			// from the regional color. Full effect from the horizon up, tapering out by
			// +40° and -15° (deep night, owned by the night blend below).
			//
			// The upper edge MUST reach +40° to meet the daytime regional blend below,
			// which only ramps to full regional by +40°. If this window closes earlier
			// (e.g. +20°), there is a gap between +20° and +40° where NEITHER the
			// suppression nor the daytime blend holds the color, so the raw procedural
			// keyframes show through — and those are strongly blue at mid-high sun
			// (e.g. the +15° zenith keyframe is 100,150,200). That gap is the "sky goes
			// blue after sunrise before settling into the environment's color" seen in
			// red-sky areas. Extending to +40° closes it.
			float sunsetWindow;
			if (sunAltitudeDegrees <= -15 || sunAltitudeDegrees >= 40) {
				sunsetWindow = 0.0f;
			} else if (sunAltitudeDegrees < 0) {
				float w = (float) ((sunAltitudeDegrees + 15.0) / 15.0); // 0 at -15°, 1 at 0°
				sunsetWindow = w * w * (3.0f - 2.0f * w);
			} else {
				float w = (float) ((40.0 - sunAltitudeDegrees) / 40.0); // 1 at 0°, 0 at +40°
				sunsetWindow = w * w * (3.0f - 2.0f * w);
			}

			float sunsetSuppression = (1.0f - sunriseSunsetStrength) * sunsetWindow;
			if (sunsetSuppression > 0.0f) {
				float[] regionalLin = rs117.hd.utils.ColorUtils.srgbToLinear(regionalFogColor);

				// Hold the horizon/zenith at the area's regional color.
				for (int i = 0; i < 3; i++) {
					zenithColor[i] = zenithColor[i] * (1 - sunsetSuppression) + regionalLin[i] * sunsetSuppression;
					horizonColor[i] = horizonColor[i] * (1 - sunsetSuppression) + regionalLin[i] * sunsetSuppression;
				}
				// Fade the procedural sun glow (additive orange/red halo) toward zero so
				// it doesn't fight the regional color the sky is being held at.
				for (int i = 0; i < 3; i++) {
					sunGlowColor[i] = sunGlowColor[i] * (1 - sunsetSuppression);
				}
			}
		}

		// Smoothstep blend from peak sunset (0°) to full regional (40°)
		float blendFactor;
		if (sunAltitudeDegrees >= 40) {
			blendFactor = 1.0f;
		} else if (sunAltitudeDegrees >= 0) {
			float t = (float) (sunAltitudeDegrees / 40.0);
			blendFactor = t * t * (3.0f - 2.0f * t); // Smoothstep curve
		} else {
			blendFactor = 0.0f;
		}

		// Blend with regional fog color if we have regional influence
		if (blendFactor > 0.0f && regionalFogColor != null) {
			// Convert regional fog color to linear for blending
			float[] regionalLinear = rs117.hd.utils.ColorUtils.srgbToLinear(regionalFogColor);

			// Blend zenith color with regional color (same intensity as horizon for uniformity)
			for (int i = 0; i < 3; i++) {
				zenithColor[i] = zenithColor[i] * (1 - blendFactor) + regionalLinear[i] * blendFactor;
			}

			// Blend horizon color with regional color
			for (int i = 0; i < 3; i++) {
				horizonColor[i] = horizonColor[i] * (1 - blendFactor) + regionalLinear[i] * blendFactor;
			}
		}

		// Nighttime: blend both zenith and horizon toward flat night sky color
		// Mirror of the daytime regional blend, but for night
		// Ramps from 0° (no blend) to -15° (full flat night sky)
		float nightBlendFactor;
		if (sunAltitudeDegrees <= -15) {
			nightBlendFactor = 1.0f;
		} else if (sunAltitudeDegrees <= 0) {
			float nt = (float) (-sunAltitudeDegrees / 15.0);
			nightBlendFactor = nt * nt * (3.0f - 2.0f * nt);
		} else {
			nightBlendFactor = 0.0f;
		}

		if (nightBlendFactor > 0.0f) {
			// Deep night zenith color (cold blue) converted to linear. The night sky
			// always resolves to this generic night base so that, once the sun is well
			// down, the moon-color night-sky tint (applied downstream in the renderer)
			// and the procedural starfield take over — including in reduced
			// sunriseSunsetStrength areas, where the regional hold only spans the
			// visible sunrise/sunset (above) and must not persist into deep night.
			float[] nightSkyLinear = rs117.hd.utils.ColorUtils.srgbToLinear(
				new float[] { 5f / 255f, 7f / 255f, 15f / 255f }
			);

			for (int i = 0; i < 3; i++) {
				zenithColor[i] = zenithColor[i] * (1 - nightBlendFactor) + nightSkyLinear[i] * nightBlendFactor;
			}
			for (int i = 0; i < 3; i++) {
				horizonColor[i] = horizonColor[i] * (1 - nightBlendFactor) + nightSkyLinear[i] * nightBlendFactor;
			}
		}

		// Convert from linear RGB (what interpolateSrgb returns) back to sRGB for the shader
		zenithColor = rs117.hd.utils.ColorUtils.linearToSrgb(zenithColor);
		horizonColor = rs117.hd.utils.ColorUtils.linearToSrgb(horizonColor);
		sunGlowColor = rs117.hd.utils.ColorUtils.linearToSrgb(sunGlowColor);

		return new float[][] { zenithColor, horizonColor, sunGlowColor };
	}

	/**
	 * Reference horizon color at peak daytime, matching the skybox at high sun.
	 * Returns sRGB, same space as {@link #getSkyGradientColors} horizon output.
	 */
	public float[] getReferenceHorizonColor(float[] regionalFogColor) {
		if (regionalFogColor != null)
			return regionalFogColor;

		float[] horizonLinear = AtmosphereUtils.interpolateSrgb(90f, HORIZON_KEYFRAMES);
		return rs117.hd.utils.ColorUtils.linearToSrgb(horizonLinear);
	}

	/**
	 * Get the sun direction vector for sky gradient rendering.
	 * Returns normalized direction FROM the camera TO the sun.
	 * Uses the same coordinate transformation as the shadow light direction.
	 */
	public float[] getSunDirectionForSky() {
		// Under a fixed mode with a per-environment override, lock the sun disk
		// to the configured angles instead of the astronomical position.
		if (hasFixedSunOverride())
			return anglesToSkyDirection(fixedSunAnglesOverride[0], fixedSunAnglesOverride[1]);

		Instant modifiedDate = getModifiedDate();
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);

		// sunAngles[0] = azimuth, sunAngles[1] = altitude
		// The renderers use: pitch = altitude, yaw = PI - azimuth
		// This matches how lightDir is calculated in ZoneRenderer and LegacyRenderer
		return anglesToSkyDirection(sunAngles[0], sunAngles[1]);
	}

	/**
	 * Get the moon direction vector for sky rendering, respecting moon behavior mode.
	 */
	public float[] getMoonDirectionForSky() {
		// A fixed-mode moon override (or the default Fixed Night position) locks
		// the moon disk to a fixed point regardless of moon behavior.
		if (currentCycleMode == DaylightCycle.FIXED_NIGHT || hasFixedMoonOverride()) {
			double[] angles = getFixedNightMoonAngles();
			return anglesToSkyDirection(angles[0], angles[1]);
		}
		if (currentMoonBehavior == MoonBehavior.NIGHT_SYNCED) {
			double[] angles = getNightSyncedMoonAngles();
			return anglesToSkyDirection(angles[0], angles[1]);
		}

		Instant moonDate = getMoonDate();
		double[] moonAngles = AtmosphereUtils.getMoonPosition(moonDate.toEpochMilli(), currentLatLong);
		// moonAngles[0] = azimuth, moonAngles[1] = altitude
		return anglesToSkyDirection(moonAngles[0], moonAngles[1]);
	}

	/**
	 * Get the moon illumination fraction, respecting the moon phase lock and behavior mode.
	 * A config phase lock takes precedence; otherwise Night Synced mode derives illumination
	 * from the advancing equinox date so the phase cycles naturally (each game cycle = +1 day).
	 */
	public float getMoonIlluminationFraction() {
		if (currentMoonPhase.isLocked()) {
			return currentMoonPhase.illumination; // Phase locked via config
		}
		if (currentCycleMode == DaylightCycle.FIXED_NIGHT) {
			return 1.0f; // Always a full moon
		}
		if (currentMoonBehavior == MoonBehavior.NIGHT_SYNCED) {
			// Real Time: use today's real lunar phase so illumination matches the
			// real-clock moon position (mirrored sun) and the realistic moon.
			if (currentCycleMode == DaylightCycle.REAL_TIME) {
				return (float) AtmosphereUtils.getMoonIllumination(System.currentTimeMillis())[0];
			}
			long equinoxEpochMs = 1742428800000L;
			long dayMs = 24L * 60 * 60 * 1000;
			// Synced Days: advance the phase by the UTC-synced day count so the phase
			// is identical for all players; otherwise use the stateful night offset.
			long phaseDay = currentCycleMode == DaylightCycle.SYNCED_DAYS
				? System.currentTimeMillis() / SYNCED_DAYS_PERIOD_MS
				: nightSyncedDayOffset;
			long phaseMillis = equinoxEpochMs + phaseDay * dayMs;
			return (float) AtmosphereUtils.getMoonIllumination(phaseMillis)[0];
		}

		Instant moonDate = getMoonDate();
		return (float) AtmosphereUtils.getMoonIllumination(moonDate.toEpochMilli())[0];
	}

	/**
	 * Get the moon altitude in degrees, respecting moon behavior mode.
	 */
	public double getMoonAltitudeDegrees() {
		if (currentCycleMode == DaylightCycle.FIXED_NIGHT || hasFixedMoonOverride()) {
			// getFixedNightMoonAngles() returns {azimuth, altitude}; use the override
			// altitude when present so shadow visibility tracks the locked moon.
			return Math.toDegrees(getFixedNightMoonAngles()[1]);
		}
		if (currentMoonBehavior == MoonBehavior.NIGHT_SYNCED) {
			double[] angles = getNightSyncedMoonAngles();
			return Math.toDegrees(angles[1]);
		}

		Instant moonDate = getMoonDate();
		double[] moonAngles = AtmosphereUtils.getMoonPosition(moonDate.toEpochMilli(), currentLatLong);
		return Math.toDegrees(moonAngles[1]);
	}

	/**
	 * Whether the current simulated night is an "aurora night".
	 *
	 * Each cycle contains one night; we hash that night's index to a stable
	 * pseudo-random value in [0,1) and compare against AURORA_NIGHT_CHANCE.
	 * The result is constant for the whole night (no flicker) and re-rolled once
	 * per cycle. Deterministic — no Math.random() — so it survives config changes
	 * and resumes consistently.
	 *
	 * The index increments at cycle position 0.35 (~midday), the point furthest
	 * from any night, so the roll never flips while auroras are on screen — the
	 * switch happens in broad daylight where nightSkyBlend (and thus the aurora)
	 * is already zero. This avoids a pop at the natural 5am cycle boundary.
	 */
	public boolean isAuroraNight() {
		// Continuous simulated-day time, with the integer boundary shifted to
		// midday (cycle pos 0.35) so a night and its index never straddle a flip.
		double continuousTime = completedCycles + accumulatedCycleTime;
		long nightIndex = (long) Math.floor(continuousTime - 0.35);

		// Cheap integer hash (splitmix64-style finalizer) -> uniform 53-bit mantissa.
		long h = nightIndex * 0x9E3779B97F4A7C15L;
		h ^= (h >>> 30);
		h *= 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 27);
		h *= 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		double roll = (h >>> 11) * (1.0 / (1L << 53)); // [0, 1)

		return roll < AURORA_NIGHT_CHANCE;
	}

	/**
	 * Get night synced moon angles {azimuth, altitude} by mirroring the sun.
	 * The moon is placed opposite the sun (azimuth + PI) with negated altitude,
	 * so it rises when the sun sets and vice versa.
	 *
	 * Uses a fixed equinox base date plus a day offset that only advances
	 * while the moon is below the horizon. This means the moon's phase
	 * changes cycle-to-cycle, but the shift is never visible because it
	 * only happens when the moon can't be seen.
	 */
	public double[] getNightSyncedMoonAngles() {
		// Call getModifiedDate to keep accumulatedCycleTime/completedCycles updated
		getModifiedDate();

		// March 20, 2025 00:00 UTC (spring equinox)
		final long equinoxEpochMs = 1742428800000L;
		final long dayMs = 24L * 60 * 60 * 1000;

		// Real Time: mirror the sun computed from the player's real local clock —
		// the same instant the REAL_TIME sun/realistic-moon use — so moonrise tracks
		// the real sunset and the moon spans the real night's length. Bypasses the
		// cycle-duration accumulator entirely; without this, the night-synced moon
		// would follow Cycle Duration while the sky follows the real clock.
		if (currentCycleMode == DaylightCycle.REAL_TIME) {
			double localHour = getLocalHourOfDay();
			Instant startOfDay = Instant.ofEpochMilli(System.currentTimeMillis())
				.truncatedTo(ChronoUnit.DAYS);
			long fixedMillis = startOfDay.toEpochMilli() + (long) (localHour * 60 * 60 * 1000);
			double[] sa = AtmosphereUtils.getSunAngles(fixedMillis, currentLatLong);
			return new double[] { sa[0] + Math.PI, -sa[1] };
		}

		// Synced Days: derive the moon's mirror position and phase purely from the
		// UTC clock so the night-synced moon is identical for every player, matching
		// the UTC-synced sun. Stateless — bypasses the pending-increment machinery.
		if (currentCycleMode == DaylightCycle.SYNCED_DAYS) {
			long currentTimeMillis = System.currentTimeMillis();
			double cyclePosition = getSyncedDaysCyclePosition(currentTimeMillis);
			double mappedHour = 3.4 + cyclePosition * 24.0;
			if (mappedHour >= 24.0) mappedHour -= 24.0;
			long syncedDay = currentTimeMillis / SYNCED_DAYS_PERIOD_MS;
			long fixedMillis = equinoxEpochMs + syncedDay * dayMs
				+ (long) (mappedHour * 60 * 60 * 1000);
			double[] sa = AtmosphereUtils.getSunAngles(fixedMillis, currentLatLong);
			return new double[] { sa[0] + Math.PI, -sa[1] };
		}

		// Warp identically to the sun so the night-synced moon stays aligned with
		// the (now re-sized) day/night periods — moonrise still tracks visual sunset.
		double cyclePosition = applyDayLengthWarp(accumulatedCycleTime);

		// Use a uniform linear mapping: cycle 0→1 maps to a full 24-hour day.
		// This gives the moon constant angular speed across its whole arc,
		// unlike the piecewise mapping used for the sun which slows at twilight.
		//
		// The start hour is chosen so that the equinox sunset (~19:00) falls
		// at cycle position ~0.65, matching when the piecewise sun visually
		// reaches the horizon. This keeps moonrise aligned with visual sunset.
		// 19 = start + 0.65 * 24  =>  start ≈ 3.4
		double mappedHour = 3.4 + cyclePosition * 24.0;
		if (mappedHour >= 24.0) mappedHour -= 24.0;

		// Detect newly completed cycles and queue them as pending
		long newCycles = completedCycles - lastNightSyncedCycles;
		if (newCycles > 0) {
			pendingDayIncrements += newCycles;
			lastNightSyncedCycles = completedCycles;
		}

		long fixedMillis = equinoxEpochMs + nightSyncedDayOffset * dayMs
			+ (long) (mappedHour * 60 * 60 * 1000);

		double[] sunAngles = AtmosphereUtils.getSunAngles(fixedMillis, currentLatLong);
		double moonAltitude = -sunAngles[1];

		// Apply pending day increments only while the moon is below the horizon
		if (pendingDayIncrements > 0 && moonAltitude < 0) {
			nightSyncedDayOffset += pendingDayIncrements;
			pendingDayIncrements = 0;
		}

		return new double[] { sunAngles[0] + Math.PI, moonAltitude };
	}

	public float[] getNightAmbientColor() {
		return multiply(rgb(56, 99, 161), 2);
	}

	public float[] getNightLightColor() {
		return multiply(rgb(181, 205, 255), 0.25f);
	}

	/**
	 * The player's local wall-clock time as a fractional hour in [0, 24).
	 * Uses the system default time zone so REAL_TIME mode matches the clock on
	 * the player's machine (noon on their clock -> sun at its peak in-game).
	 */
	private double getLocalHourOfDay() {
		LocalTime now = LocalTime.now();
		return now.getHour()
			+ now.getMinute() / 60.0
			+ now.getSecond() / 3600.0
			+ now.getNano() / 3.6e12;
	}

	/**
	 * Map a normalized cycle position [0, 1) to an hour-of-day [0, 24) using the
	 * project's twilight-weighted mapping (extended dawn/dusk, compressed deep
	 * night). Shared by the Dynamic cycle and Synced Days so both share the same
	 * sun arc shape.
	 */
	private double cyclePositionToHour(double cyclePosition) {
		// 0.0-0.15  dawn/sunrise twilight -> 5am-7am
		// 0.15-0.35 morning               -> 7am-12pm
		// 0.35-0.55 afternoon             -> 12pm-5pm
		// 0.55-0.70 sunset twilight       -> 5pm-7pm
		// 0.70-0.85 early night           -> 7pm-12am
		// 0.85-1.0  late night/pre-dawn   -> 12am-5am
		if (cyclePosition < 0.15) {
			return 5.0 + (cyclePosition / 0.15) * 2.0;
		} else if (cyclePosition < 0.35) {
			return 7.0 + ((cyclePosition - 0.15) / 0.20) * 5.0;
		} else if (cyclePosition < 0.55) {
			return 12.0 + ((cyclePosition - 0.35) / 0.20) * 5.0;
		} else if (cyclePosition < 0.70) {
			return 17.0 + ((cyclePosition - 0.55) / 0.15) * 2.0;
		} else if (cyclePosition < 0.85) {
			return 19.0 + ((cyclePosition - 0.70) / 0.15) * 5.0;
		} else {
			return ((cyclePosition - 0.85) / 0.15) * 5.0;
		}
	}

	/**
	 * Synced Days cycle position in [0, 1): where we are within the current UTC
	 * hour. Stateless and identical for every player at a given UTC instant.
	 */
	private double getSyncedDaysCyclePosition(long currentTimeMillis) {
		return (currentTimeMillis % SYNCED_DAYS_PERIOD_MS) / (double) SYNCED_DAYS_PERIOD_MS;
	}

	public Instant getModifiedDate() {
		long currentTimeMillis = System.currentTimeMillis();
		Instant currentInstant = Instant.ofEpochMilli(currentTimeMillis);

		// Initialize on first call
		if (lastUpdateTime == 0) {
			lastUpdateTime = currentTimeMillis;
			lastDayLength = currentCycleDuration;
		}

		// Calculate elapsed real time since last update
		long realTimeElapsed = currentTimeMillis - lastUpdateTime;

		// Convert cycle duration from minutes to milliseconds for the full cycle
		double cycleDurationMillis = currentCycleDuration * 60.0 * 1000.0; // minutes to milliseconds

		// Calculate how much cycle time has progressed based on current day length
		double cycleTimeElapsed = realTimeElapsed / cycleDurationMillis;

		// Add to accumulated cycle time to maintain continuity
		accumulatedCycleTime += cycleTimeElapsed;

		// Track completed cycles (each = one simulated day) for moon phase progression
		while (accumulatedCycleTime >= 1.0) {
			accumulatedCycleTime -= 1.0;
			completedCycles++;
		}

		// Update tracking variables for next call
		lastUpdateTime = currentTimeMillis;
		lastDayLength = currentCycleDuration;

		// Real Time mode: drive the sun directly from the player's local clock.
		// We map today's real local hour onto today's UTC start-of-day, the same
		// construction the dynamic path uses (a local hour interpreted at latLong),
		// so noon on the player's clock puts the sun at its peak in-game.
		if (currentCycleMode == DaylightCycle.REAL_TIME) {
			double localHour = getLocalHourOfDay();
			Instant startOfDay = currentInstant.truncatedTo(ChronoUnit.DAYS);
			return startOfDay.plusMillis((long) (localHour * 60 * 60 * 1000));
		}

		// Synced Days mode: a full day/night every real UTC hour, phase-locked to the
		// UTC clock and independent of Cycle Duration. Purely a function of the UTC
		// epoch, so every player worldwide sees the same sun position at the same
		// instant. Stateless — no accumulatedCycleTime — so it can't drift.
		if (currentCycleMode == DaylightCycle.SYNCED_DAYS) {
			double cyclePosition = getSyncedDaysCyclePosition(currentTimeMillis);
			double mappedHour = cyclePositionToHour(cyclePosition);
			// Advance the date one simulated day per completed UTC hour so the moon's
			// phase progresses; this is also identical for all users.
			long syncedDay = currentTimeMillis / SYNCED_DAYS_PERIOD_MS;
			Instant startOfDay = Instant.EPOCH.plus(syncedDay, ChronoUnit.DAYS);
			return startOfDay.plusMillis((long) (mappedHour * 60 * 60 * 1000));
		}

		// For non-dynamic modes, return a fixed date at the appropriate time of day.
		// Cycle tracking above still runs so getMoonDate() advances normally.
		if (currentCycleMode != DaylightCycle.DYNAMIC) {
			// March 20, 2025 (spring equinox) — balanced day/night lengths.
			// Used by all fixed modes except Fixed Midday (see below).
			long equinoxEpochMs = 1742428800000L;
			// June 10, 2025 (near summer solstice) — higher midday sun arc.
			long fixedMiddayEpochMs = 1749513600000L;
			long baseEpochMs = currentCycleMode == DaylightCycle.FIXED_MIDDAY ? fixedMiddayEpochMs : equinoxEpochMs;
			Instant baseDay = Instant.ofEpochMilli(baseEpochMs);
			double fixedHour;
			switch (currentCycleMode) {
				case FIXED_DAWN:
					fixedHour = 6.65;  // 6:00 AM
					break;
				case FIXED_MIDDAY:
					fixedHour = 14;  // Mid-afternoon — sun high but not at its peak
					break;
				case FIXED_SUNSET:
					fixedHour = 18.3; // 5:30 PM — sun near horizon at equinox latitude
					break;
				case FIXED_NIGHT:
				case ALWAYS_NIGHT:
					fixedHour = 0.0;  // Midnight — sun well below horizon
					break;
				default:
					fixedHour = 12.0;
					break;
			}
			return baseDay.plusMillis((long) (fixedHour * 60 * 60 * 1000));
		}

		// Warp the linear cycle clock so day/night occupy the configured share
		// of the cycle, then feed the result into the twilight-weighted mapping.
		double cyclePosition = applyDayLengthWarp(accumulatedCycleTime);
		double mappedHour = cyclePositionToHour(cyclePosition);

		// Convert mapped hour to actual time
		// Use completedCycles to advance the base date by 1 day per finished cycle,
		// so the moon's phase and rise/set times progress naturally.
		// The mappedHour handles the time-of-day within each cycle.
		Instant startOfDay = currentInstant.truncatedTo(ChronoUnit.DAYS)
			.plus(completedCycles, ChronoUnit.DAYS);
		long mappedMillis = (long) (mappedHour * 60 * 60 * 1000);
		return startOfDay.plusMillis(mappedMillis);
	}

	/**
	 * Get a continuously advancing date for moon calculations.
	 * Unlike getModifiedDate() which uses non-linear time mapping for the sun,
	 * this returns a timestamp that advances smoothly based on total elapsed cycles.
	 * Each cycle = 1 simulated day, so the moon's phase and position change gradually
	 * without discrete jumps at cycle boundaries.
	 */
	public Instant getMoonDate() {
		// Ensure getModifiedDate has been called to update accumulatedCycleTime/completedCycles
		getModifiedDate();

		Instant currentInstant = Instant.ofEpochMilli(System.currentTimeMillis());
		Instant startOfDay = currentInstant.truncatedTo(ChronoUnit.DAYS);

		// Real Time mode: the moon's phase and position are astronomically real for
		// today at the player's local hour, matching the real-clock sun.
		if (currentCycleMode == DaylightCycle.REAL_TIME) {
			double localHour = getLocalHourOfDay();
			return startOfDay.plusMillis((long) (localHour * 60 * 60 * 1000));
		}

		// Synced Days mode: use the same UTC-derived instant as the sun so the moon
		// stays coherent with it and is identical for every player. One simulated
		// day advances per completed UTC hour.
		if (currentCycleMode == DaylightCycle.SYNCED_DAYS) {
			long currentTimeMillis = System.currentTimeMillis();
			double cyclePosition = getSyncedDaysCyclePosition(currentTimeMillis);
			double mappedHour = cyclePositionToHour(cyclePosition);
			long syncedDay = currentTimeMillis / SYNCED_DAYS_PERIOD_MS;
			Instant syncedStartOfDay = Instant.EPOCH.plus(syncedDay, ChronoUnit.DAYS);
			return syncedStartOfDay.plusMillis((long) (mappedHour * 60 * 60 * 1000));
		}

		// Total simulated days elapsed = completed whole cycles + current cycle progress.
		// Warp only the within-cycle fraction so the realistic moon's position tracks
		// the re-sized day/night, while whole completed cycles still advance the lunar
		// phase linearly (preventing phase jitter from the warp).
		double totalSimulatedDays = completedCycles + applyDayLengthWarp(accumulatedCycleTime);
		long totalOffsetMillis = (long) (totalSimulatedDays * 24 * 60 * 60 * 1000);

		return startOfDay.plusMillis(totalOffsetMillis);
	}

	public double[] getLatLongForRegion(WorldRegion currentRegion) {
		double latitude;
		double longitude;
		switch (currentRegion) {
			case AUSTRALIA:
				// Sydney
				latitude = -33.8478035;
				longitude = 150.6016588;
				break;
			case GERMANY:
				// Berlin
				latitude = 52.5063843;
				longitude = 13.0944281;
				break;
			case UNITED_STATES_OF_AMERICA:
				// Chicago
				latitude = 41.8337329;
				longitude = -87.7319639;
				break;
			case UNITED_KINGDOM:
			default:
				// Cambridge
				latitude = 52.1951;
				longitude = .1313;
				break;
		}
		return new double[] { latitude, longitude };
	}

	public boolean isNight(double[] angles) {
		double angleFromZenith = Math.abs(angles[1] - Math.PI / 2);
		return angleFromZenith > Math.PI / 2;
	}

	public float getNightLightFactor() {
		switch (currentCycleMode) {
			case FIXED_DAWN:
			case FIXED_MIDDAY:
			case FIXED_SUNSET:
				return 0f;
			case FIXED_NIGHT:
			case ALWAYS_NIGHT:
				return 1f;
			default:
				break;
		}

		Instant modifiedDate = getModifiedDate();
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);

		if (sunAltitudeDegrees >= 5)
			return 0f;
		if (sunAltitudeDegrees <= -18)
			return 1f;

		float t = (float) ((5.0 - sunAltitudeDegrees) / 23.0);
		return t * t * (3.0f - 2.0f * t);
	}

	public float getDynamicBrightnessMultiplier(int minimumBrightness) {
		Instant modifiedDate = getModifiedDate();
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), currentLatLong);

		// Calculate sun altitude in degrees (-90 to 90, where 90 is directly overhead)
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);

		// Convert minimum brightness from percentage to decimal
		float minBrightness = minimumBrightness / 100.0f;
		float horizonBrightness = minBrightness + 0.10f; // 10% brighter at horizon

		if (sunAltitudeDegrees <= -18) {
			// Deep night: minimum brightness
			return minBrightness;
		} else if (sunAltitudeDegrees <= -5) {
			// Night: smoothstep from minBrightness at -18° to twilightBrightness at -5°
			// twilightBrightness is partway between min and horizon
			float twilightBrightness = minBrightness + 0.07f;
			float t = (float) ((sunAltitudeDegrees + 18.0) / 13.0); // 0 at -18°, 1 at -5°
			float s = t * t * (3.0f - 2.0f * t);
			return minBrightness + (twilightBrightness - minBrightness) * s;
		} else if (sunAltitudeDegrees <= 5) {
			// Horizon transition: smoothstep from twilightBrightness at -5° to earlyDayBrightness at +5°
			// This zone spans the critical 0° boundary with a single smooth curve
			float twilightBrightness = minBrightness + 0.07f;
			float earlyDayBrightness = horizonBrightness + 0.05f;
			float t = (float) ((sunAltitudeDegrees + 5.0) / 10.0); // 0 at -5°, 1 at +5°
			float s = t * t * (3.0f - 2.0f * t);
			return twilightBrightness + (earlyDayBrightness - twilightBrightness) * s;
		} else {
			// Daytime: sine curve from earlyDayBrightness at +5° to peak at 90°.
			// Peak is 1.0 so the brightest part of the day matches the environment's
			// base strengths (i.e. how the world looks with the cycle disabled).
			float earlyDayBrightness = horizonBrightness + 0.05f;
			float peakBrightness = 1.2f;
			double sineFactor = Math.sin(Math.toRadians(sunAltitudeDegrees));
			// Scale so that at 5°, we match earlyDayBrightness
			double sineAt5 = Math.sin(Math.toRadians(5.0));
			double normalizedSine = (sineFactor - sineAt5) / (1.0 - sineAt5);
			normalizedSine = Math.max(0, normalizedSine);
			return (float) (earlyDayBrightness + (peakBrightness - earlyDayBrightness) * normalizedSine);
		}
	}
}
