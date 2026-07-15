package rs117.hd.scene.environments;

import com.google.gson.annotations.JsonAdapter;
import java.util.Objects;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.config.DaylightCycle;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.GsonUtils.DegreesToRadians;
import rs117.hd.utils.HDUtils;

import rs117.hd.utils.ColorUtils;
import static rs117.hd.utils.ColorUtils.SrgbToLinearAdapter;
import static rs117.hd.utils.ColorUtils.rgb;

@Slf4j
@Setter(value = AccessLevel.PRIVATE)
public class Environment {
	public static final float[] DEFAULT_SUN_ANGLES = HDUtils.sunAngles(52, 235);
	public static final Environment DEFAULT = new Environment()
		.setKey("DEFAULT")
		.setArea(Area.ALL)
		.setFogColor(rgb("#000000"))
		.setWaterColor(rgb("#66eaff"))
		.setSunAngles(DEFAULT_SUN_ANGLES)
		.normalize();
	public static final Environment NONE = new Environment()
		.setKey("NONE")
		.setFogColor(rgb("#ff00ff"))
		.normalize();

	public static Environment OVERWORLD, AUTUMN, WINTER;

	public String key;
	@JsonAdapter(AreaManager.Adapter.class)
	public Area area = Area.NONE;
	public boolean isOverworld = false;
	public boolean isPohTheme = false;
	public boolean isUnderwater = false;
	public boolean force = false;
	public boolean allowSkyOverride = true;
	public boolean allowRoofShadows = true;
	public boolean lightningEffects = false;
	public boolean instantTransition = false;
	// When true, the game's built-in skybox models (e.g. the one added for Blood Moon
	// Rises) are hidden in this area so the day/night cycle's own sky is shown instead.
	// This only takes effect while the "Override Vanilla Skyboxes" config toggle is on
	// (its default); turning that toggle off forces vanilla skyboxes back on everywhere,
	// including areas that set this flag.
	public boolean hideVanillaSkyboxes = false;
	// Optional varbit gate. When requiredVarbit >= 0, this environment only applies
	// while that varbit holds the required value, IN ADDITION to the area containing
	// the player. Used for areas that overlap the regular overworld and should only
	// take effect in a specific game state — e.g. the Blood Moon Rises cutscene area,
	// which overlaps the overworld and is only active while cutscene varbit 542 == 1.
	// When the gate fails, environment matching falls through to the next area (so the
	// normal overworld sky applies outside the cutscene). requiredVarbitValue defaults
	// to -1, meaning "any nonzero value satisfies the gate"; set it to match an exact
	// value instead.
	public int requiredVarbit = -1;
	public int requiredVarbitValue = -1;
	// When set, forces the day/night cycle mode for this environment, overriding
	// the player's config setting. Null = use the configured mode.
	@Nullable
	public DaylightCycle cycleMode = null;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] ambientColor = rgb("#ffffff");
	public float ambientStrength = 1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] directionalColor = rgb("#ffffff");
	public float directionalStrength = .25f;
	@Nullable
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] waterColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] waterCausticsColor;
	public float waterCausticsStrength = -1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] underglowColor = rgb("#000000");
	public float underglowStrength = 0;
	@Nullable
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonColor;
	// Color of the light the moon casts on the scene (moonlight). When unset,
	// falls back to moonColor so the cast light matches the moon disk (current
	// behavior). Set this to give moonlight a different tint than the visible moon.
	@Nullable
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonLightColor;
	// Color the night sky (zenith/horizon) is tinted toward as the moon rises.
	// When unset, falls back to moonColor so the sky color matches the moon disk
	// (current behavior). Set this to color the night sky independently of the moon.
	@Nullable
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] nightSkyColor;
	// How strongly nightSkyColor tints the night sky. 1 (default) preserves the
	// original subtle tint; higher values push the sky harder toward nightSkyColor for
	// areas where the default is too weak to read.
	public float nightSkyColorStrength = 1;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] sunAngles; // horizontal coordinate system, in radians
	// When set, and the active day/night cycle is a fixed mode (FIXED_DAWN,
	// FIXED_MIDDAY, FIXED_SUNSET, FIXED_NIGHT, ALWAYS_NIGHT), these lock the
	// sun/moon disk and their shadow directions to a fixed point in the sky,
	// overriding the astronomically-derived angles. {azimuth, altitude} in
	// degrees (converted to radians), matching the convention used by
	// AtmosphereUtils.getSunAngles()/getMoonPosition(). Null = astronomical.
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] fixedSunAngles;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] fixedMoonAngles;
	@Nullable
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] fogColor;
	public float fogDepth = 25;
	public int groundFogStart = -200;
	public int groundFogEnd = -500;
	public float groundFogOpacity = 0;
	@JsonAdapter(DegreesToRadians.class)
	public float windAngle = 0.0f;
	public float windSpeed = 15.0f;
	public float windStrength = 0.0f;
	public float windCeiling = 1280.0f;
	public float starVisibility = 1;
	public float moonVisibility = 1;
	// Aurora visibility multiplier for this area, controllable independently of
	// starVisibility. Auroras still only appear on the randomly-selected aurora
	// nights and fade with the night; this just scales how visible they are when they
	// do. When left unset (sentinel -1), it falls back to starVisibility — the
	// original behavior, where hiding stars also hid auroras. Set it explicitly to
	// decouple: e.g. 0 hides auroras while keeping stars, 1 shows full auroras even
	// where stars are dimmed. Resolved to a concrete value in normalize().
	public float auroraVisibility = -1;
	// Moon size multiplier for this area. Scales the moon disk AND its glow and the
	// star-occlusion mask around it, together. 1 (default) = normal size; >1 larger,
	// <1 smaller.
	public float moonSizeMult = 1;
	public float sunStrength = 1;
	// How strongly the procedural day/night sunrise/sunset is allowed to paint this
	// area's sky, in [0, 1]. 1 = full procedural sunrise/sunset (default). Lower
	// values hold the sky at the area's own regional (fogColor) color through the
	// twilight window instead, and fade the procedural sun glow. Use this for areas
	// with a vivid intended sky (e.g. Tolna's blood-red #290000) that shouldn't turn
	// orange/blue at sunrise/sunset. Independent of sunStrength; only affects the
	// sky gradient + sun glow, not daytime or nighttime colors.
	public float sunriseSunsetStrength = 1;
	// Sun altitude (degrees) at which this area's own sky color has FULLY taken over
	// from the procedural sunrise/sunset gradient as the sun climbs. Lower values pull
	// the area color in earlier in the morning (and hold it later in the evening),
	// compressing the procedural twilight window; higher values let the procedural
	// gradient persist further up. 0 means the area color takes over immediately at
	// the horizon (no procedural daytime gradient at all). Governs the daytime
	// regional blend for ALL areas, independent of sunriseSunsetStrength. Default 40
	// preserves prior behavior.
	public float skyColorTakeoverAngle = 40;
	public float sunlightStrength = 1;
	public float minBrightnessBoost = 0;

	public Environment normalize() {
		if (area != Area.ALL && area != Area.NONE) {
			isOverworld = Area.OVERWORLD.intersects(area);
			// Certain nullable fields will fall back to using the current overworld theme's values later,
			// but for environments that aren't part of the overworld, we want to fall back to the default
			// (underground) environment's values for any unspecified fields
			if (!isOverworld && DEFAULT != null) {
				sunAngles = Objects.requireNonNullElse(sunAngles, DEFAULT.sunAngles);
				fogColor = Objects.requireNonNullElse(fogColor, DEFAULT.fogColor);
				waterColor = Objects.requireNonNullElse(waterColor, DEFAULT.waterColor);
			}
		}

		if (sunAngles != null)
			sunAngles = HDUtils.ensureArrayLength(sunAngles, 2);
		if (fixedSunAngles != null)
			fixedSunAngles = HDUtils.ensureArrayLength(fixedSunAngles, 2);
		if (fixedMoonAngles != null)
			fixedMoonAngles = HDUtils.ensureArrayLength(fixedMoonAngles, 2);

		// Default moon color to slightly cool white (~8000K)
		if (moonColor == null)
			moonColor = ColorUtils.colorTemperatureToLinearRgb(8000);

		// When no distinct moonlight color is given, the cast light matches the
		// moon disk (moonColor) — preserving the original single-color behavior.
		if (moonLightColor == null)
			moonLightColor = moonColor;

		// When no distinct night-sky color is given, the sky matches the moon
		// disk (moonColor) — preserving the original single-color behavior.
		if (nightSkyColor == null)
			nightSkyColor = moonColor;

		// Base water caustics on directional lighting by default
		if (waterCausticsColor == null)
			waterCausticsColor = directionalColor;
		if (waterCausticsStrength == -1)
			waterCausticsStrength = directionalStrength;

		// When aurora visibility isn't specified, fall back to star visibility so
		// hiding stars also hides auroras (the original coupled behavior). An explicit
		// value decouples the two.
		if (auroraVisibility == -1)
			auroraVisibility = starVisibility;
		return this;
	}

	/** Whether this environment has an optional varbit gate configured. */
	public boolean hasVarbitGate() {
		return requiredVarbit >= 0;
	}

	/**
	 * Whether the varbit gate is satisfied for the given current varbit value.
	 * With no gate configured, always true. With requiredVarbitValue == -1 (the
	 * default), any nonzero value satisfies the gate; otherwise the value must match
	 * exactly. See requiredVarbit for why this exists.
	 */
	public boolean isVarbitGateSatisfied(int currentVarbitValue) {
		if (!hasVarbitGate())
			return true;
		if (requiredVarbitValue == -1)
			return currentVarbitValue != 0;
		return currentVarbitValue == requiredVarbitValue;
	}

	@Override
	public String toString() {
		if (key != null)
			return key;
		return area.name;
	}
}
