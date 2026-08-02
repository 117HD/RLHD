package rs117.hd.scene;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.config.DayLength;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonBehavior;
import rs117.hd.config.MoonPhase;
import rs117.hd.config.SeasonalHemisphere;
import rs117.hd.utils.AtmosphereUtils;

import static rs117.hd.utils.ColorUtils.linearToSrgb;
import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.ColorUtils.srgbToLinear;
import static rs117.hd.utils.MathUtils.*;

/**
 * Drives the day & night cycle: it owns the simulated clock and turns it into a sun/moon
 * position, from which every time-of-day-dependent value (sky gradient, light and ambient
 * color, brightness, moon phase, aurora) is derived.
 *
 * <h2>Per-frame contract</h2>
 * The renderer calls, in this order, once per frame:
 * <ol>
 *   <li>{@link #update()} - advances the simulated clock and pins {@link #currentInstant}</li>
 *   <li>the {@code set*} frame-state methods (cycle mode, day length, moon phase/behavior,
 *       cycle duration, hemisphere, fixed-angle overrides)</li>
 *   <li>any number of getters</li>
 * </ol>
 * Getters are pure with respect to that state and share a per-frame astronomy snapshot, so
 * calling them repeatedly within a frame is cheap. Each {@code set*} only invalidates the
 * snapshot when the value actually changes, since they are called with the same values
 * every frame.
 *
 * <h2>Angle conventions</h2>
 * Two orderings are in play, and mixing them up is the classic bug here:
 * <ul>
 *   <li><b>Internal / astronomical:</b> {@code {azimuth, altitude}} in radians. Returned by
 *       {@link AtmosphereUtils#getSunAngles} and {@link AtmosphereUtils#getMoonPosition},
 *       and used by every method on this class.</li>
 *   <li><b>Environment-file:</b> {@code {altitude, azimuth}} in radians, matching
 *       {@code Environment.sunAngles}. Only crosses the boundary in
 *       {@link #setFixedAngleOverrides}, which flips it to the internal order.</li>
 * </ul>
 *
 * <h2>Cycle modes</h2>
 * {@link DaylightCycle} splits into three families, and most branching in this file is one
 * of these three:
 * <ul>
 *   <li><b>DYNAMIC</b> - the simulated clock accumulates in {@link #accumulatedCycleTime}
 *       and maps to an hour of day via {@link #cyclePositionToHour}.</li>
 *   <li><b>REAL_TIME / SYNCED_DAYS</b> - stateless: the instant is derived directly from the
 *       player's local clock, or from the UTC clock so all players see the same sky.</li>
 *   <li><b>The fixed modes</b> ({@link #isFixedMode}) - the sun sits at a constant angle,
 *       bypassing the clock entirely. See {@link #getFixedModeSunAngles}.</li>
 * </ul>
 */
@Singleton
@Slf4j
public class TimeOfDay {

	// Pre-linearized deep-night sky color.
	// Read-only: every consumer only reads components into fresh blend arrays.
	private static final float[] NIGHT_SKY_LINEAR = rgb(5, 7, 15);

	// Sky color keyframe tables, as { sunAltitudeDegrees, sRGB 0xRRGGBB }. Read-only
	// constant data; AtmosphereUtils.interpolateSrgb only reads them and returns a fresh
	// linear float[] per call. Rows must stay sorted by ascending altitude.
	private static final float[][] ZENITH_KEYFRAMES = { // top of the sky
		srgbRow(-30, 0x010104), // Deep night - near black
		srgbRow(-15, 0x03040A), // Late night
		srgbRow(-8,  0x2D2346), // Early twilight - purple tint
		srgbRow(-3,  0x503C64), // Twilight
		srgbRow(0,   0x645078), // Horizon sun
		srgbRow(5,   0x788CB4), // Early sunrise
		srgbRow(15,  0x6496C8), // Morning
		srgbRow(30,  0x5A91C8), // Mid-morning
		srgbRow(50,  0x558CC3), // Midday
		srgbRow(90,  0x5087BE), // High noon
	};

	private static final float[][] HORIZON_KEYFRAMES = { // sides/bottom of the sky
		srgbRow(-30, 0x010205), // Deep night - near black
		srgbRow(-15, 0x04050C), // Late night
		srgbRow(-8,  0x3C2D41), // Early twilight
		srgbRow(-3,  0x8C5046), // Twilight - orange/red
		srgbRow(0,   0xDC8250), // Sunrise/sunset - golden
		srgbRow(5,   0xE6AA78), // Early morning golden
		srgbRow(10,  0xC8B4A0), // Morning warm
		srgbRow(20,  0xAAAFB9), // Late morning
		srgbRow(30,  0x96A5BE), // Midday haze
		srgbRow(50,  0x8CA0BE), // Afternoon
		srgbRow(90,  0x879BB9), // High noon
	};

	private static final float[][] SUN_GLOW_KEYFRAMES = { // halo around the sun disk
		srgbRow(-30, 0x000000), // No glow at night
		srgbRow(-10, 0x140A1E), // Very faint purple
		srgbRow(-5,  0x50283C), // Purple/pink
		srgbRow(-2,  0xB45032), // Deep orange/red
		srgbRow(0,   0xFF9650), // Bright orange
		srgbRow(5,   0xFFC882), // Golden yellow
		srgbRow(15,  0xFFE6B4), // Warm white
		srgbRow(30,  0xFFFADC), // Nearly white
		srgbRow(50,  0xFFFFF0), // White with slight warmth
		srgbRow(90,  0xFFFFFA), // Pure white
	};

	/** Builds a keyframe row of { sunAltitudeDegrees, sRGB r, g, b } from a 0xRRGGBB literal. */
	private static float[] srgbRow(float altitudeDegrees, int srgb) {
		return new float[] {
			altitudeDegrees,
			((srgb >> 16) & 0xFF) / 255f,
			((srgb >> 8) & 0xFF) / 255f,
			(srgb & 0xFF) / 255f
		};
	}

	// Length of one Synced Days cycle: a full day & night every real hour, phase-locked
	// to the UTC clock so every player sees the same sun position at the same moment.
	private static final long SYNCED_DAYS_PERIOD_MS = 60L * 60 * 1000;

	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	private static final long HOUR_MS = 60L * 60 * 1000;

	/**
	 * March 20, 2025 00:00 UTC - spring equinox, i.e. balanced day & night lengths.
	 */
	private static final long EQUINOX_EPOCH_MS = 1742428800000L;
	/** June 10, 2025 - near the summer solstice, for a higher midday sun arc. */
	private static final long SOLSTICE_EPOCH_MS = 1749513600000L;

	/** An hour-of-day in [0, 24) as a millisecond offset from the start of that day. */
	private static long hoursToMillis(double hourOfDay) {
		return (long) (hourOfDay * HOUR_MS);
	}

	/**
	 * Smoothstep over [edge0, edge1], clamped outside it. Every ramp in this file is a
	 * smoothstep on sun altitude; going through here keeps them readable and consistent.
	 * Handles a descending range (edge0 &gt; edge1) so ramps that fade out as the sun
	 * climbs read in their natural direction.
	 *
	 * <p>A degenerate range (edge0 == edge1) collapses to 0 rather than a step. The
	 * takeover-angle ramps below can be given a zero-width range when an area sets
	 * skyColorTakeoverAngle to 0, and 0 is the value that makes those windows vanish -
	 * i.e. the regional color takes over immediately at the horizon.
	 */
	private static float smoothstep(double edge0, double edge1, double x) {
		if (edge0 == edge1)
			return 0;
		float t = (float) clamp((x - edge0) / (edge1 - edge0), 0, 1);
		return t * t * (3f - 2f * t);
	}

	/** In-place {@code dst = mix(dst, src, t)} over the first 3 components. */
	private static void blendTowards(float[] dst, float[] src, float t) {
		for (int i = 0; i < 3; i++)
			dst[i] = dst[i] * (1 - t) + src[i] * t;
	}

	/** In-place {@code dst *= 1 - t} over the first 3 components, for fading additive colors out. */
	private static void fadeOut(float[] dst, float t) {
		for (int i = 0; i < 3; i++)
			dst[i] *= 1 - t;
	}

	/**
	 * How much an area's own (regional) light color should win over the procedurally
	 * computed one, as a function of sun altitude: fully regional with the sun high,
	 * tapering to almost none at night so the cycle's own night colors take over.
	 * Shared by the directional and ambient blends so they stay in step.
	 */
	private static float regionalBlendFactor(double sunAltitudeDegrees) {
		if (sunAltitudeDegrees >= 30)
			return 1; // High sun - pure regional, matching the cycle-disabled look
		if (sunAltitudeDegrees >= 15)
			return (float) (0.75 + (sunAltitudeDegrees - 15) / 15 * 0.25); // Strong regional
		if (sunAltitudeDegrees >= 5)
			return (float) (0.50 + (sunAltitudeDegrees - 5) / 10 * 0.25); // Sunset/late sunrise
		if (sunAltitudeDegrees >= 0)
			return (float) (0.30 + sunAltitudeDegrees / 5 * 0.20); // Low sun
		return (float) Math.max(0, 0.30 + sunAltitudeDegrees / 10 * 0.30); // Night/twilight
	}

	/** Linear blend of two colors: {@code mix(a, b, t)}, as a fresh array. */
	private static float[] mixColor(float[] a, float[] b, float t) {
		float[] result = new float[3];
		for (int i = 0; i < 3; i++)
			result[i] = a[i] * (1 - t) + b[i] * t;
		return result;
	}

	// The natural (unwarped) cycle position where daytime ends and night begins.
	// 0.0-0.70 maps to 5am-7pm (day, incl. twilight), 0.70-1.0 maps to 7pm-5am (night).
	private static final double NATURAL_DAY_BOUNDARY = 0.70;

	// Probability that any given simulated night is an "aurora night", in
	// environments flagged aurora-eligible. Rolled deterministically per night.
	private static final double AURORA_NIGHT_CHANCE = 0.02;

	// ---------------------------------------------------------------------------------
	// Built-in fixed-mode sun/moon positions, as {azimuth, altitude} in radians.
	//
	// IMPORTANT - these are stored PRE-ROTATED relative to environment-file angles.
	// anglesToSkyDirection maps azimuth with (PI + azimuth); that form was chosen to make
	// the real astronomical sun rise in the east, and it rotates any fixed azimuth by 180°
	// compared to the older (PI - azimuth) form these values were originally authored
	// against. setFixedAngleOverrides compensates by adding 180° to environment-supplied
	// angles, but the constants below feed getSunAngles/getFixedNightMoonAngles directly
	// and skip that step - so each one has the 180° already baked into its literal value.
	//
	// Net effect: to convert an environment-file azimuth to a constant here, add 180°.
	// These angles are empirical - verify any change in-game rather than deriving it, since
	// the sign conventions have been misleading offline more than once.
	// ---------------------------------------------------------------------------------

	// Fixed Night's moon: locked to a prominent spot in the south-east sky and always
	// rendered full. An environment may override this per-area via fixedMoonAngles.
	// 135° south-east + the 180° described above.
	private static final double FIXED_NIGHT_MOON_AZIMUTH = Math.toRadians(135 + 180);
	private static final double FIXED_NIGHT_MOON_ALTITUDE = Math.toRadians(25); // low in the sky

	// Reproduces the look the old date-based Fixed Dawn produced at the equator.
	private static final double[] FIXED_DAWN_SUN = { Math.toRadians(-89.8), Math.toRadians(7.8) };
	// Matches the static sun used when the cycle is OFF (Environment.DEFAULT_SUN_ANGLES =
	// altitude 52°, azimuth 235°). Azimuth 55° (= 235° - 180°) makes the cycle-on shadow
	// yaw equal the cycle-off yaw, so light and shadows are identical between the two.
	private static final double[] FIXED_MIDDAY_SUN = { Math.toRadians(55.0), Math.toRadians(52.0) };
	// Sun on the horizon in the west. Authored as environment-file angles [0, 272], so the
	// azimuth is stored as 272 + 180 = 452 = 92° (mod 360).
	private static final double[] FIXED_SUNSET_SUN = { Math.toRadians(92.0), Math.toRadians(0.0) };
	// Sun just below the horizon - the position Fixed Sunset used before it was moved onto
	// the horizon proper.
	private static final double[] FIXED_TWILIGHT_SUN = { Math.toRadians(90.0), Math.toRadians(-2.5) };
	// FIXED_NIGHT / ALWAYS_NIGHT: sun well below the horizon. The azimuth is irrelevant
	// (the sun isn't rendered) - only the negative altitude matters, for night detection
	// and shadow fade.
	private static final double[] FIXED_NIGHT_SUN = { Math.toRadians(81.1), Math.toRadians(-88.0) };

	// Latitudes used for the seasonal-hemisphere-based sun/moon arc: New York City
	// (northern) and Rio de Janeiro (southern). Only latitude affects the sun's
	// altitude/seasonal arc; longitude is left at 0 since the cycle drives its own
	// time-of-day rather than a real clock/timezone.
	private static final double[] NORTHERN_LAT_LONG = { 40.7128, 0.0 };  // New York City
	private static final double[] SOUTHERN_LAT_LONG = { -22.9068, 0.0 }; // Rio de Janeiro

	// Per-environment fixed-angle overrides {azimuth, altitude} in radians, or
	// null to use astronomical/default angles. Set once per frame by the renderer
	// from the current environment. Only consulted while a fixed cycle mode is
	// active (see isFixedMode) - the dynamic cycle always computes angles.
	private double[] fixedSunAnglesOverride = null;
	private double[] fixedMoonAnglesOverride = null;

	// Night Synced mode: day offset advances only while the moon is below the horizon,
	// so phase changes are never visible. We track pending increments and apply them
	// only when the mirrored moon altitude is negative.
	private long nightSyncedDayOffset = 0;
	private long lastNightSyncedCycles = 0;
	private long pendingDayIncrements = 0;

	// Simulated-clock state, preserved across config changes.
	private long lastUpdateTime = 0;
	// Start the dynamic cycle at midday. cyclePosition 0.35 maps to 12:00pm
	// in cyclePositionToHour()'s afternoon range (0.35-0.55 -> 12pm-5pm).
	private double accumulatedCycleTime = 0.35;
	private long completedCycles = 0; // Each completed cycle = one simulated day

	// Current cycle mode - set once per frame by ZoneRenderer before any TimeOfDay calls
	private DaylightCycle currentCycleMode = DaylightCycle.DYNAMIC;

	// Current day length skew - set once per frame alongside the cycle mode.
	// Warps the linear cycle clock so day & night occupy different shares of the
	// fixed total cycle time (see applyDayLengthWarp).
	private DayLength currentDayLength = DayLength.STANDARD;

	// Current moon phase lock - set once per frame. DYNAMIC = phase advances
	// naturally; any other value locks the moon's illumination fraction.
	private MoonPhase currentMoonPhase = MoonPhase.DYNAMIC;

	@Getter
	private MoonBehavior currentMoonBehavior = MoonBehavior.NIGHT_SYNCED;

	@Getter
	private float currentCycleDuration = 700;

	@Getter
	private final double[] currentLatLong = { 0, 0 };

	@Getter
	private Instant currentInstant;

	// Per-frame astronomy snapshot. update() already pins the wall-clock instant
	// once per frame; these cache the ephemeris solves derived from it, so the
	// ~12 getter calls per frame share one solve instead of re-deriving.
	// Callers must treat the returned arrays as read-only - they are shared.
	private double[] frameSunAngles;
	private double[] frameNightSyncedMoonAngles;
	private float[] frameSunDirectionForSky;
	private float[] frameMoonDirectionForSky;
	private Float frameMoonIllumination;
	private Double frameMoonAltitudeDegrees;

	// ===== Per-frame state =======================================================

	/**
	 * Invalidate the per-frame astronomy snapshot. Called from update() once per
	 * rendered frame before any TimeOfDay getters, and by the frame-state setters
	 * whenever an input actually changes mid-frame.
	 */
	public void beginFrame() {
		frameSunAngles = null;
		frameNightSyncedMoonAngles = null;
		frameSunDirectionForSky = null;
		frameMoonDirectionForSky = null;
		frameMoonIllumination = null;
		frameMoonAltitudeDegrees = null;
	}

	/**
	 * Set the per-environment fixed sun/moon angle overrides for this frame.
	 * <p>
	 * Inputs are in the environment-file convention {altitude, azimuth} in radians
	 * (the same order as Environment.sunAngles), or null for no override. They are
	 * stored internally as {azimuth, altitude} to match the convention returned by
	 * AtmosphereUtils.getSunAngles()/getMoonPosition() and consumed by the rest of
	 * this class.
	 * <p>
	 * Call before any other TimeOfDay methods. Only takes effect under a fixed
	 * cycle mode; the dynamic cycle ignores these.
	 */
	public void setFixedAngleOverrides(@Nullable float[] sunAngles, @Nullable float[] moonAngles) {
		// sunAngles/moonAngles are {altitude, azimuth}; store {azimuth, altitude}.
		// Add PI to the azimuth: anglesToSkyDirection was changed (PI - az -> PI + az,
		// with the north/south component negated) to correct the real astronomical sun,
		// which rotates any fixed angle 180° in azimuth. These overrides were hand-
		// authored to look right under the old transform, so we rotate them back 180°
		// here - a single point that feeds both the disk and its shadow - so every
		// existing fixedSunAngles/fixedMoonAngles renders exactly as before.
		double[] newSun = sunAngles == null ? null :
			new double[] { sunAngles[1] + Math.PI, sunAngles[0] };
		double[] newMoon = moonAngles == null ? null :
			new double[] { moonAngles[1] + Math.PI, moonAngles[0] };
		// Only invalidate the frame snapshot when the overrides actually change;
		// this is called redundantly every frame with the same values.
		if (!Arrays.equals(newSun, fixedSunAnglesOverride)
			|| !Arrays.equals(newMoon, fixedMoonAnglesOverride)) {
			fixedSunAnglesOverride = newSun;
			fixedMoonAnglesOverride = newMoon;
			beginFrame();
		}
	}

	/** Set the cycle mode for this frame. */
	public void setCycleMode(DaylightCycle mode) {
		if (currentCycleMode != mode) {
			currentCycleMode = mode;
			beginFrame();
		}
	}

	/** Set the day length skew for this frame. */
	public void setDayLength(DayLength dayLength) {
		if (currentDayLength != dayLength) {
			currentDayLength = dayLength;
			beginFrame();
		}
	}

	/** Set how long one full day & night cycle takes, in real minutes. */
	public void setCycleDurationMinutes(float cycleDuration) {
		if (currentCycleDuration != cycleDuration) {
			currentCycleDuration = cycleDuration;
			beginFrame();
		}
	}

	/** Set the moon phase lock for this frame. DYNAMIC lets the phase advance naturally. */
	public void setMoonPhase(MoonPhase moonPhase) {
		if (currentMoonPhase != moonPhase) {
			currentMoonPhase = moonPhase;
			beginFrame();
		}
	}

	/** Set how the moon is positioned relative to the sun for this frame. */
	public void setMoonBehavior(MoonBehavior moonBehavior) {
		if (currentMoonBehavior != moonBehavior) {
			currentMoonBehavior = moonBehavior;
			beginFrame();
		}
	}

	/**
	 * Set the observer latitude from the player's seasonal hemisphere: northern -> New
	 * York City, southern -> Rio de Janeiro. Must be called after {@link #setCycleMode}.
	 *
	 * <p>Synced Days is a special case: it is UTC-locked so every player sees the same sky
	 * at the same moment, so it always uses the northern latitude regardless of this
	 * setting, which would otherwise make the two hemispheres diverge.
	 */
	public void setSeasonalHemisphere(SeasonalHemisphere hemisphere) {
		double[] latLong = currentCycleMode == DaylightCycle.SYNCED_DAYS || hemisphere != SeasonalHemisphere.SOUTHERN
			? NORTHERN_LAT_LONG
			: SOUTHERN_LAT_LONG;
		if (currentLatLong[0] != latLong[0] || currentLatLong[1] != latLong[1]) {
			currentLatLong[0] = latLong[0];
			currentLatLong[1] = latLong[1];
			beginFrame();
		}
	}

	// ===== Fixed cycle modes =====================================================

	/**
	 * Whether the current cycle mode is one of the fixed modes (the sun/moon sit
	 * at a fixed time of day). Fixed-angle overrides only apply in these modes.
	 * DYNAMIC and REAL_TIME are excluded - both compute a moving astronomical sun.
	 */
	public boolean isFixedMode() {
		switch (currentCycleMode) {
			case FIXED_DAWN:
			case FIXED_MIDDAY:
			case FIXED_SUNSET:
			case FIXED_TWILIGHT:
			case FIXED_NIGHT:
			case ALWAYS_NIGHT:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Resolve the fixed sun angles {azimuth, altitude} (radians, internal convention)
	 * for the active fixed cycle mode: the environment's fixedSunAngles override when
	 * present, otherwise the built-in per-mode constant. Only valid while isFixedMode().
	 * Drives everything sun-related in fixed modes (disk, shadow, sky colors, brightness)
	 * so those modes no longer depend on incremented time.
	 */
	public double[] getFixedModeSunAngles() {
		if (fixedSunAnglesOverride != null)
			return new double[] { fixedSunAnglesOverride[0], fixedSunAnglesOverride[1] };
		switch (currentCycleMode) {
			case FIXED_DAWN:   return FIXED_DAWN_SUN.clone();
			case FIXED_MIDDAY: return FIXED_MIDDAY_SUN.clone();
			case FIXED_SUNSET: return FIXED_SUNSET_SUN.clone();
			case FIXED_TWILIGHT: return FIXED_TWILIGHT_SUN.clone();
			case FIXED_NIGHT:
			case ALWAYS_NIGHT:
			default:           return FIXED_NIGHT_SUN.clone();
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
		// yaw = PI + azimuth maps the (now real, non-reversed) astronomical azimuth to
		// the renderer's sky direction so the sun/moon rise in the east. The north/south
		// (z) component is negated on top of that: without it the season rendered
		// inverted (equatorial June sun appeared south instead of north). x (east/west)
		// is left untouched so the correct sunrise-east direction is preserved.
		double yaw = Math.PI + azimuth;

		float x = (float) (Math.sin(yaw) * Math.cos(altitude));
		float y = (float) Math.sin(altitude);
		float z = (float) (Math.cos(yaw) * Math.cos(altitude));

		float length = (float) Math.sqrt(x * x + y * y + z * z);
		if (length > 0.0001f) {
			x /= length;
			y /= length;
			z /= length;
		}
		return new float[] { x, y, z };
	}

	/**
	 * Warp a linear cycle position (0..1) so day and night occupy a different
	 * share of the cycle, without changing the total cycle length.
	 * <p>
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

	// ===== Sun position, light & sky colors ======================================

	/**
	 * The sun's {azimuth, altitude} in radians for this frame - the value nearly everything
	 * else in this class derives from. Cached per frame; treat the result as read-only.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 */
	public double[] getSunAngles() {
		if (frameSunAngles == null)
			frameSunAngles = computeSunAngles();
		return frameSunAngles;
	}

	private double[] computeSunAngles() {
		// Fixed modes return their fixed angle directly, bypassing the time machinery.
		// Every sun-position-dependent value (sky gradient colors, brightness, blend
		// factors) reads this, so they all use the fixed position automatically.
		if (isFixedMode())
			return getFixedModeSunAngles();
		return AtmosphereUtils.getSunAngles(currentInstant.toEpochMilli(), currentLatLong);
	}

	/**
	 * The scene's directional (sun/moon) light color: the cycle's own color for the current
	 * sun altitude, blended toward the area's regional color as the sun climbs.
	 * Both inputs and the result are in linear space.
	 */
	public float[] getRegionalDirectionalLight(float[] regionalDirectionalColor) {
		double[] sunAngles = getSunAngles();
		float[] dynamicLight = AtmosphereUtils.getDirectionalLightForAngles(this, sunAngles);
		return mixColor(dynamicLight, regionalDirectionalColor, regionalBlendFactor(Math.toDegrees(sunAngles[1])));
	}

	/**
	 * The scene's ambient light color. Mirrors {@link #getRegionalDirectionalLight}, sharing
	 * its blend factor so ambient and directional light stay consistent with the skybox.
	 */
	public float[] getRegionalAmbientLight(float[] regionalAmbientColor) {
		double[] sunAngles = getSunAngles();
		float[] dynamicAmbient = AtmosphereUtils.getAmbientColorForAngles(sunAngles);
		return mixColor(dynamicAmbient, regionalAmbientColor, regionalBlendFactor(Math.toDegrees(sunAngles[1])));
	}

	/**
	 * Sky gradient colors for the current time of day, as
	 * { zenithColor, horizonColor, sunGlowColor } in sRGB.
	 *
	 * <p>The base colors come from the procedural keyframe tables, indexed by sun altitude.
	 * Four adjustments are then layered on, in order:
	 * <ol>
	 *   <li><b>sunStrength</b> - pulls a dark area's sky away from the procedural sunset
	 *       colors, toward the area's own color by day and the night sky after dusk.</li>
	 *   <li><b>sunriseSunsetStrength</b> - holds a strongly-colored area at its own color
	 *       right through the twilight window, so e.g. a blood-red sky doesn't turn blue at
	 *       sunrise.</li>
	 *   <li><b>the daytime regional blend</b> - as the sun climbs, the area's own color takes
	 *       over from the procedural gradient, completely by {@code skyColorTakeoverAngle}.</li>
	 *   <li><b>the night blend</b> - once the sun is well down, everything resolves to the
	 *       generic night sky so the moon tint and starfield (applied downstream) take over.</li>
	 * </ol>
	 *
	 * @param regionalFogColor      the area's own sky/fog color (sRGB), or null for none
	 * @param sunStrength           1 = full procedural sun, 0 = fully suppressed
	 * @param sunriseSunsetStrength 1 = full procedural twilight, 0 = hold the regional color
	 * @param skyColorTakeoverAngle sun altitude (degrees) at which the regional color fully wins
	 */
	public float[][] getSkyGradientColors(
		float[] regionalFogColor,
		float sunStrength,
		float sunriseSunsetStrength,
		float skyColorTakeoverAngle
	) {
		double sunAltitude = Math.toDegrees(getSunAngles()[1]);

		// Sun altitude at which the area's own color has fully taken over from the
		// procedural sunrise/sunset gradient. Shared by the sunrise/sunset suppression
		// window and the daytime regional blend so they stay in sync. Clamped to >= 0;
		// 0 means the regional color takes over immediately at the horizon.
		float takeover = Math.max(0, skyColorTakeoverAngle);
		float[] regionalLin = regionalFogColor != null ? srgbToLinear(regionalFogColor) : null;

		float[] zenith = AtmosphereUtils.interpolateSrgb((float) sunAltitude, ZENITH_KEYFRAMES);
		float[] horizon = AtmosphereUtils.interpolateSrgb((float) sunAltitude, HORIZON_KEYFRAMES);
		float[] sunGlow = AtmosphereUtils.interpolateSrgb((float) sunAltitude, SUN_GLOW_KEYFRAMES);

		// 1. sunStrength: suppress the procedural sunset colors for dark environments.
		// Full suppression above the horizon (the regional blend below takes over from
		// there); below it, fade out by -25° where the night colors dominate anyway.
		if (regionalLin != null && sunStrength < 1) {
			float window = sunAltitude >= 0 ? 1 : smoothstep(-25, 0, sunAltitude);
			float suppression = (1 - sunStrength) * window;
			if (suppression > 0) {
				// Crossfade the blend target between regional and night sky around the
				// horizon, so there's no hard color jump as the sun crosses it.
				float[] target = mixColor(regionalLin, NIGHT_SKY_LINEAR, smoothstep(5, -5, sunAltitude));
				blendTowards(zenith, target, suppression);
				blendTowards(horizon, target, suppression);
				fadeOut(sunGlow, suppression); // the glow is additive, so suppress toward zero
			}
		}

		// 2. sunriseSunsetStrength: an independent per-area knob that stops the procedural
		// sunrise/sunset from overriding a strongly-colored area's own sky.
		//
		// Some areas set a vivid regional sky (e.g. Tolna's blood-red #290000) that is meant
		// to be the mood all day. The cycle's procedural twilight paints its own orange->blue
		// gradient over that, so at sunrise/sunset the intended red "turns blue". Lowering
		// this knob holds the sky at the area's OWN color through the twilight window
		// instead. The blend target is the regional color (NOT the night sky) so the area's
		// color is preserved rather than muted to black; the night blend in step 4 still
		// darkens things once the sun is well down, so nights stay dark regardless.
		//
		// The window's upper edge MUST be the same takeover angle used by step 3. If this
		// window closed earlier, there would be a gap where neither this suppression nor the
		// daytime blend holds the color, letting the raw keyframes show through - and those
		// are strongly blue at mid-high sun (the +15° zenith keyframe is 0x6496C8). That gap
		// was the "sky goes blue after sunrise before settling into the area's color" bug.
		if (regionalLin != null && sunriseSunsetStrength < 1) {
			float window = sunAltitude < 0
				? smoothstep(-15, 0, sunAltitude)  // ramp in over deep night -> horizon
				: smoothstep(takeover, 0, sunAltitude); // ramp out from horizon -> takeover
			float suppression = (1 - sunriseSunsetStrength) * window;
			if (suppression > 0) {
				blendTowards(zenith, regionalLin, suppression);
				blendTowards(horizon, regionalLin, suppression);
				// Fade the additive orange/red halo so it doesn't fight the held color.
				fadeOut(sunGlow, suppression);
			}
		}

		// 3. Daytime regional blend, from peak sunset (0°) to fully regional at the takeover
		// angle. Lowering the takeover angle per-area pulls the regional color in earlier as
		// the sun climbs, so a strongly-colored sky wins sooner in the morning.
		if (regionalLin != null) {
			// takeover == 0 is the degenerate case: the regional color wins the moment the
			// sun clears the horizon, so there is no ramp to walk up.
			float blend = sunAltitude < 0 ? 0 : (takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitude));
			if (blend > 0) {
				blendTowards(zenith, regionalLin, blend);
				blendTowards(horizon, regionalLin, blend);
			}
		}

		// 4. Night blend, mirroring step 3: ramp from 0° (none) to -15° (full night sky).
		// The night sky always resolves to this generic base so that, once the sun is well
		// down, the moon-color tint (applied downstream in the renderer) and the procedural
		// starfield take over - including in reduced sunriseSunsetStrength areas, where the
		// regional hold only spans the visible sunrise/sunset and must not persist into
		// deep night.
		float nightBlend = smoothstep(0, -15, sunAltitude);
		if (nightBlend > 0) {
			blendTowards(zenith, NIGHT_SKY_LINEAR, nightBlend);
			blendTowards(horizon, NIGHT_SKY_LINEAR, nightBlend);
		}

		// interpolateSrgb returns linear; the shader wants sRGB.
		return new float[][] { linearToSrgb(zenith), linearToSrgb(horizon), linearToSrgb(sunGlow) };
	}

	/**
	 * Reference horizon color at peak daytime, matching the skybox at high sun.
	 * Returns sRGB, same space as {@link #getSkyGradientColors} horizon output.
	 */
	public float[] getReferenceHorizonColor(float[] regionalFogColor) {
		if (regionalFogColor != null)
			return regionalFogColor;

		float[] horizonLinear = AtmosphereUtils.interpolateSrgb(90f, HORIZON_KEYFRAMES);
		return linearToSrgb(horizonLinear);
	}

	/**
	 * Get the sun direction vector for sky gradient rendering.
	 * Returns normalized direction FROM the camera TO the sun.
	 * Uses the same coordinate transformation as the shadow light direction.
	 */
	public float[] getSunDirectionForSky() {
		if (frameSunDirectionForSky == null)
			frameSunDirectionForSky = computeSunDirectionForSky();
		return frameSunDirectionForSky;
	}

	private float[] computeSunDirectionForSky() {
		// getSunAngles() already handles the fixed modes (per-environment override or
		// the built-in per-mode constant) and shares the per-frame snapshot, so the
		// sun disk direction is derived from the same solve as everything else.
		double[] sunAngles = getSunAngles();

		// sunAngles[0] = azimuth, sunAngles[1] = altitude
		// The renderers use: pitch = altitude, yaw = PI - azimuth
		// This matches how lightDir is calculated in ZoneRenderer and LegacyRenderer
		return anglesToSkyDirection(sunAngles[0], sunAngles[1]);
	}

	// ===== Moon ==================================================================

	/**
	 * Get the moon direction vector for sky rendering, respecting moon behavior mode.
	 */
	public float[] getMoonDirectionForSky() {
		if (frameMoonDirectionForSky == null)
			frameMoonDirectionForSky = computeMoonDirectionForSky();
		return frameMoonDirectionForSky;
	}

	private float[] computeMoonDirectionForSky() {
		// A fixed-mode moon override (or the default Fixed Night position) locks
		// the moon disk to a fixed point regardless of moon behavior. ALWAYS_NIGHT is
		// deliberately excluded: it keeps a permanent night but the moon still moves
		// and cycles phases like the dynamic moon (only the sun stays down).
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
		if (frameMoonIllumination == null)
			frameMoonIllumination = computeMoonIlluminationFraction();
		return frameMoonIllumination;
	}

	private float computeMoonIlluminationFraction() {
		if (currentMoonPhase.isLocked()) {
			return currentMoonPhase.illumination; // Phase locked via config
		}
		if (currentCycleMode == DaylightCycle.FIXED_NIGHT) {
			return 1.0f; // Always a full moon
		}
		// Real Time: use the actual current real-world lunar phase, regardless of moon
		// behavior, so it matches the moon you'd see outside. getMoonIllumination now
		// uses real (non-reversed) time, so this is simply today's phase.
		if (currentCycleMode == DaylightCycle.REAL_TIME) {
			return (float) AtmosphereUtils.getMoonIllumination(System.currentTimeMillis())[0];
		}
		if (currentMoonBehavior == MoonBehavior.NIGHT_SYNCED) {

			// Synced Days: advance the phase by the UTC-synced day count so the phase
			// is identical for all players; otherwise use the stateful night offset.
			long phaseDay = currentCycleMode == DaylightCycle.SYNCED_DAYS
				? System.currentTimeMillis() / SYNCED_DAYS_PERIOD_MS
				: nightSyncedDayOffset;
			long phaseMillis = EQUINOX_EPOCH_MS + phaseDay * DAY_MS;
			return (float) AtmosphereUtils.getMoonIllumination(phaseMillis)[0];
		}

		Instant moonDate = getMoonDate();
		return (float) AtmosphereUtils.getMoonIllumination(moonDate.toEpochMilli())[0];
	}

	/**
	 * Get the moon altitude in degrees, respecting moon behavior mode.
	 */
	public double getMoonAltitudeDegrees() {
		if (frameMoonAltitudeDegrees == null)
			frameMoonAltitudeDegrees = computeMoonAltitudeDegrees();
		return frameMoonAltitudeDegrees;
	}

	private double computeMoonAltitudeDegrees() {
		if (currentCycleMode == DaylightCycle.FIXED_NIGHT || hasFixedMoonOverride()) {
			// getFixedNightMoonAngles() returns {azimuth, altitude}; use the override
			// altitude when present so shadow visibility tracks the locked moon.
			// ALWAYS_NIGHT is excluded - its moon keeps moving (dynamic altitude).
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
	 * <p>
	 * Each cycle contains one night; we hash that night's index to a stable
	 * pseudo-random value in [0,1) and compare against AURORA_NIGHT_CHANCE.
	 * The result is constant for the whole night (no flicker) and re-rolled once
	 * per cycle. Deterministic - no Math.random() - so it survives config changes
	 * and resumes consistently.
	 * <p>
	 * The index increments at cycle position 0.35 (~midday), the point furthest
	 * from any night, so the roll never flips while auroras are on screen - the
	 * switch happens in broad daylight where nightSkyBlend (and thus the aurora)
	 * is already zero. This avoids a pop at the natural 5am cycle boundary.
	 */
	private boolean isAuroraNight() {
		// Continuous simulated-day time, with the integer boundary shifted to
		// midday (cycle pos 0.35) so a night and its index never straddle a flip.
		double continuousTime = completedCycles + accumulatedCycleTime;
		int nightIndex = max(1, (int)Math.floor(continuousTime - 0.35) + 1);

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
	 * Aurora intensity envelope in [0, 1] for the current frame, combining the
	 * per-cycle aurora roll with a time-of-cycle shape.
	 * <p>
	 * In modes with a natural day & night arc, the sun goes down and comes back up, so
	 * the sky's own nightFactor fades auroras in and out - here we just return 1 on an
	 * aurora night and let the shader's nightFactor do the shaping.
	 * <p>
	 * In the always-night modes (Fixed Night / Always Night) the sun is pinned below
	 * the horizon, so nightFactor is ~1 the whole cycle and a binary on/off would leave
	 * auroras blazing for the entire cycle. Instead we apply an explicit envelope: on an
	 * aurora cycle the auroras ramp up and back down within the cycle (peaking mid-cycle,
	 * zero at the edges) so they come and go; off-cycle it's zero.
	 */
	public float getAuroraStrength() {
		if (!isAuroraNight())
			return 0f;

		boolean alwaysNight = currentCycleMode == DaylightCycle.FIXED_NIGHT
			|| currentCycleMode == DaylightCycle.ALWAYS_NIGHT;
		if (!alwaysNight)
			return 1f;

		// Position within the current cycle. The night index flips at 0.35 (midday),
		// so re-center the envelope on that boundary: auroras are absent right after a
		// flip, swell to full a bit past mid-cycle, then fade back out before the next
		// flip. phase in [0,1) measured from the 0.35 flip point.
		double phase = accumulatedCycleTime - 0.35;
		phase -= Math.floor(phase); // wrap into [0, 1)

		// Smooth bump: only visible over a fraction of the cycle. Ramp in over
		// [0.15, 0.40], hold near full through mid-cycle, ramp out over [0.60, 0.85].
		float env;
		if (phase < 0.15 || phase > 0.85) {
			env = 0f;
		} else if (phase < 0.40) {
			float t = (float) ((phase - 0.15) / 0.25);
			env = t * t * (3.0f - 2.0f * t);
		} else if (phase <= 0.60) {
			env = 1f;
		} else {
			float t = (float) ((0.85 - phase) / 0.25);
			env = t * t * (3.0f - 2.0f * t);
		}
		return env;
	}

	/**
	 * Get night synced moon angles {azimuth, altitude} by mirroring the sun.
	 * The moon is placed opposite the sun (azimuth + PI) with negated altitude,
	 * so it rises when the sun sets and vice versa.
	 * <p>
	 * Uses a fixed equinox base date plus a day offset that only advances
	 * while the moon is below the horizon. This means the moon's phase
	 * changes cycle-to-cycle, but the shift is never visible because it
	 * only happens when the moon can't be seen.
	 */
	public double[] getNightSyncedMoonAngles() {
		if (frameNightSyncedMoonAngles == null)
			frameNightSyncedMoonAngles = computeNightSyncedMoonAngles();
		return frameNightSyncedMoonAngles;
	}

	private double[] computeNightSyncedMoonAngles() {


		// Real Time: mirror the sun computed from the player's real local clock -
		// the same instant the REAL_TIME sun/realistic-moon use - so moonrise tracks
		// the real sunset and the moon spans the real night's length. Bypasses the
		// cycle-duration accumulator entirely; without this, the night-synced moon
		// would follow Cycle Duration while the sky follows the real clock.
		if (currentCycleMode == DaylightCycle.REAL_TIME) {
			double localHour = getLocalHourOfDay();
			Instant startOfDay = Instant.ofEpochMilli(System.currentTimeMillis())
				.truncatedTo(ChronoUnit.DAYS);
			long fixedMillis = startOfDay.toEpochMilli() + hoursToMillis(localHour);
			double[] sa = AtmosphereUtils.getSunAngles(fixedMillis, currentLatLong);
			return new double[] { sa[0] + Math.PI, -sa[1] };
		}

		// Synced Days: derive the moon's mirror position and phase purely from the
		// UTC clock so the night-synced moon is identical for every player, matching
		// the UTC-synced sun. Stateless - bypasses the pending-increment machinery.
		if (currentCycleMode == DaylightCycle.SYNCED_DAYS) {
			long currentTimeMillis = System.currentTimeMillis();
			double cyclePosition = getSyncedDaysCyclePosition(currentTimeMillis);
			double mappedHour = 3.4 + cyclePosition * 24.0;
			if (mappedHour >= 24.0) mappedHour -= 24.0;
			long syncedDay = currentTimeMillis / SYNCED_DAYS_PERIOD_MS;
			long fixedMillis = EQUINOX_EPOCH_MS + syncedDay * DAY_MS
				+ hoursToMillis(mappedHour);
			double[] sa = AtmosphereUtils.getSunAngles(fixedMillis, currentLatLong);
			return new double[] { sa[0] + Math.PI, -sa[1] };
		}

		// Warp identically to the sun so the night-synced moon stays aligned with
		// the (now re-sized) day & night periods - moonrise still tracks visual sunset.
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

		long fixedMillis = EQUINOX_EPOCH_MS + nightSyncedDayOffset * DAY_MS
			+ hoursToMillis(mappedHour);

		double[] sunAngles = AtmosphereUtils.getSunAngles(fixedMillis, currentLatLong);
		double moonAltitude = -sunAngles[1];

		// Apply pending day increments only while the moon is below the horizon
		if (pendingDayIncrements > 0 && moonAltitude < 0) {
			nightSyncedDayOffset += pendingDayIncrements;
			pendingDayIncrements = 0;
		}

		return new double[] { sunAngles[0] + Math.PI, moonAltitude };
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

	// ===== Simulated clock =======================================================

	// Stateful: advances accumulatedCycleTime/completedCycles, and re-pins the
	// instant every getter derives from. Called once per frame, so the astronomy
	// snapshot invalidated here is rebuilt at most once per frame.
	public void update() {
		beginFrame();

		long currentTimeMillis = System.currentTimeMillis();
		currentInstant = Instant.ofEpochMilli(currentTimeMillis);

		// Initialize on first call
		if (lastUpdateTime == 0)
			lastUpdateTime = currentTimeMillis;

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

		lastUpdateTime = currentTimeMillis;

		// Real Time mode: drive the sun directly from the player's local clock.
		// We map today's real local hour onto today's UTC start-of-day, the same
		// construction the dynamic path uses (a local hour interpreted at latLong),
		// so noon on the player's clock puts the sun at its peak in-game.
		if (currentCycleMode == DaylightCycle.REAL_TIME) {
			double localHour = getLocalHourOfDay();
			Instant startOfDay = currentInstant.truncatedTo(ChronoUnit.DAYS);
			currentInstant = startOfDay.plusMillis(hoursToMillis(localHour));
			return;
		}

		// Synced Days mode: a full day & night every real UTC hour, phase-locked to the
		// UTC clock and independent of Cycle Duration. Purely a function of the UTC
		// epoch, so every player worldwide sees the same sun position at the same
		// instant. Stateless - no accumulatedCycleTime - so it can't drift.
		if (currentCycleMode == DaylightCycle.SYNCED_DAYS) {
			double cyclePosition = getSyncedDaysCyclePosition(currentTimeMillis);
			double mappedHour = cyclePositionToHour(cyclePosition);
			// Advance the date one simulated day per completed UTC hour so the moon's
			// phase progresses; this is also identical for all users.
			long syncedDay = currentTimeMillis / SYNCED_DAYS_PERIOD_MS;
			Instant startOfDay = Instant.EPOCH.plus(syncedDay, ChronoUnit.DAYS);
			currentInstant = startOfDay.plusMillis(hoursToMillis(mappedHour));
			return;
		}

		// For non-dynamic modes, return a fixed date at the appropriate time of day.
		// Cycle tracking above still runs so getMoonDate() advances normally.
		if (currentCycleMode != DaylightCycle.DYNAMIC) {
			// Fixed Midday sits near the solstice for a higher sun arc; the rest use the
			// equinox, where day and night are balanced.
			long baseEpochMs = currentCycleMode == DaylightCycle.FIXED_MIDDAY ? SOLSTICE_EPOCH_MS : EQUINOX_EPOCH_MS;
			double fixedHour;
			switch (currentCycleMode) {
				case FIXED_DAWN:
					fixedHour = 6.65; // Just after sunrise
					break;
				case FIXED_MIDDAY:
					fixedHour = 14;   // Mid-afternoon - sun high but not at its peak
					break;
				case FIXED_SUNSET:
					fixedHour = 18.1; // Sun right on the horizon at equinox latitude
					break;
				case FIXED_TWILIGHT:
					fixedHour = 18.3; // Sun just below the horizon
					break;
				case FIXED_NIGHT:
				case ALWAYS_NIGHT:
					fixedHour = 0;    // Midnight - sun well below the horizon
					break;
				default:
					fixedHour = 12;
					break;
			}
			currentInstant = Instant.ofEpochMilli(baseEpochMs).plusMillis(hoursToMillis(fixedHour));
			return;
		}

		// Warp the linear cycle clock so day & night occupy the configured share
		// of the cycle, then feed the result into the twilight-weighted mapping.
		double cyclePosition = applyDayLengthWarp(accumulatedCycleTime);
		double mappedHour = cyclePositionToHour(cyclePosition);

		// Convert mapped hour to actual time
		// Use completedCycles to advance the base date by 1 day per finished cycle,
		// so the moon's phase and rise/set times progress naturally.
		// The mappedHour handles the time-of-day within each cycle.
		Instant startOfDay = currentInstant.truncatedTo(ChronoUnit.DAYS)
			.plus(completedCycles, ChronoUnit.DAYS);
		long mappedMillis = hoursToMillis(mappedHour);
		currentInstant = startOfDay.plusMillis(mappedMillis);
	}

	/**
	 * Get a continuously advancing date for moon calculations.
	 * Unlike getModifiedDate() which uses non-linear time mapping for the sun,
	 * this returns a timestamp that advances smoothly based on total elapsed cycles.
	 * Each cycle = 1 simulated day, so the moon's phase and position change gradually
	 * without discrete jumps at cycle boundaries.
	 */
	public Instant getMoonDate() {
		Instant currentInstant = Instant.ofEpochMilli(System.currentTimeMillis());
		Instant startOfDay = currentInstant.truncatedTo(ChronoUnit.DAYS);

		// Real Time mode: the moon's phase and position are astronomically real for
		// today at the player's local hour, matching the real-clock sun.
		if (currentCycleMode == DaylightCycle.REAL_TIME) {
			double localHour = getLocalHourOfDay();
			return startOfDay.plusMillis(hoursToMillis(localHour));
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
			return syncedStartOfDay.plusMillis(hoursToMillis(mappedHour));
		}

		// Total simulated days elapsed = completed whole cycles + current cycle progress.
		// Warp only the within-cycle fraction so the realistic moon's position tracks
		// the re-sized day & night, while whole completed cycles still advance the lunar
		// phase linearly (preventing phase jitter from the warp).
		double totalSimulatedDays = completedCycles + applyDayLengthWarp(accumulatedCycleTime);
		long totalOffsetMillis = (long) (totalSimulatedDays * DAY_MS);

		return startOfDay.plusMillis(totalOffsetMillis);
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
			case FIXED_TWILIGHT:
				return 0f;
			case FIXED_NIGHT:
			case ALWAYS_NIGHT:
				return 1f;
			default:
				break;
		}

		// All fixed modes returned above, so getSunAngles() takes the dynamic path
		// here and shares the per-frame snapshot instead of re-solving.
		double[] sunAngles = getSunAngles();
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);

		if (sunAltitudeDegrees >= 5)
			return 0f;
		if (sunAltitudeDegrees <= -18)
			return 1f;

		float t = (float) ((5.0 - sunAltitudeDegrees) / 23.0);
		return t * t * (3.0f - 2.0f * t);
	}

	public float getDynamicBrightnessMultiplier(int minimumBrightness) {
		// getSunAngles() returns the fixed angle in fixed modes, so brightness tracks the
		// fixed sun altitude there instead of an incremented-time position.
		double[] sunAngles = getSunAngles();

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
