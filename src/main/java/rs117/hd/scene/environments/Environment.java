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
	public float sunStrength = 1;
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

		// Base water caustics on directional lighting by default
		if (waterCausticsColor == null)
			waterCausticsColor = directionalColor;
		if (waterCausticsStrength == -1)
			waterCausticsStrength = directionalStrength;
		return this;
	}

	@Override
	public String toString() {
		if (key != null)
			return key;
		return area.name;
	}
}
