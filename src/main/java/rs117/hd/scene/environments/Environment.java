package rs117.hd.scene.environments;

import com.google.gson.annotations.JsonAdapter;
import java.util.Objects;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.Setter;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.GsonUtils.DegreesToRadians;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.ColorUtils.SrgbToLinearAdapter;
import static rs117.hd.utils.ColorUtils.rgb;

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
	public boolean isUnderwater = false;
	public boolean force = false;
	public boolean allowSkyOverride = true;
	public boolean lightningEffects = false;
	public boolean instantTransition = false;
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
	@JsonAdapter(DegreesToRadians.class)
	public float[] sunAngles; // horizontal coordinate system, in radians
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
