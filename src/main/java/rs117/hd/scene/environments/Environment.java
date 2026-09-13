package rs117.hd.scene.environments;

import com.google.gson.annotations.JsonAdapter;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.daylight_cycle.SkyConfiguration;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionPredicate;
import rs117.hd.utils.GsonUtils.DegreesToRadians;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.ColorUtils.SrgbToLinearAdapter;
import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Setter(value = AccessLevel.PRIVATE)
public class Environment {
	private static final float[] DEFAULT_SHADOW_ANGLES = HDUtils.sunAngles(52, 235);
	private static final float[] DEFAULT_FOG_COLOR = rgb("#000000");
	private static final float[] DEFAULT_WATER_COLOR = rgb("#66eaff");
	public static final Environment DEFAULT = new Environment()
		.setKey("DEFAULT")
		.setArea(Area.ALL)
		.setFogColor(DEFAULT_FOG_COLOR)
		.setWaterColor(DEFAULT_WATER_COLOR)
		.setShadowAngles(DEFAULT_SHADOW_ANGLES)
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
	@JsonAdapter(ExpressionParser.PredicateAdapter.class)
	public ExpressionPredicate varbitCondition = ExpressionPredicate.TRUE;
	@JsonAdapter(ExpressionParser.PredicateAdapter.class)
	public ExpressionPredicate varpCondition = ExpressionPredicate.TRUE;
	@JsonAdapter(SrgbToLinearAdapter.class)
	@Getter
	private float[] ambientColor = rgb("#ffffff");
	public float ambientStrength = 1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	@Getter
	private float[] directionalColor = rgb("#ffffff");
	public float directionalStrength = .25f;
	@JsonAdapter(SrgbToLinearAdapter.class)
	@Getter
	private float[] waterColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	@Getter
	private float[] waterCausticsColor;
	public float waterCausticsStrength = -1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	@Getter
	private float[] underglowColor = rgb("#000000");
	public float underglowStrength = 0;
	@JsonAdapter(DegreesToRadians.class)
	@Getter
	private float[] shadowAngles; // horizontal coordinate system, in radians
	@JsonAdapter(SrgbToLinearAdapter.class)
	@Getter
	private float[] fogColor;
	public float fogDepth = 25;
	public float groundFogStart = -200;
	public float groundFogEnd = -500;
	public float groundFogOpacity = 0;
	@JsonAdapter(DegreesToRadians.class)
	public float windAngle = 0.0f;
	public float windSpeed = 15.0f;
	public float windStrength = 0.0f;
	public float windCeiling = 1280.0f;
	@JsonAdapter(SkyConfiguration.Adapter.class)
	@Nullable
	private SkyConfiguration sky;
	public boolean hideVanillaSkyboxes = false;

	public transient boolean hasWaterColorOverride;
	public transient boolean hasFogColorOverride;
	private transient boolean normalized;

	public Environment normalize() {
		if (area == null)
			area = Area.NONE;
		if (varbitCondition == null)
			varbitCondition = ExpressionPredicate.TRUE;
		if (varpCondition == null)
			varpCondition = ExpressionPredicate.TRUE;
		if (ambientColor == null)
			ambientColor = rgb("#ffffff");
		if (directionalColor == null)
			directionalColor = rgb("#ffffff");
		if (underglowColor == null)
			underglowColor = rgb("#000000");

		if (!normalized) {
			hasFogColorOverride = fogColor != null;
			hasWaterColorOverride = waterColor != null;
		}
		if (fogColor == null) {
			fogColor = DEFAULT_FOG_COLOR;
		}
		if (waterColor == null) {
			waterColor = DEFAULT_WATER_COLOR;
		}

		if (area != Area.ALL && area != Area.NONE) {
			isOverworld = Area.OVERWORLD.intersects(area);
			if (!isOverworld) {
				if (!hasFogColorOverride) {
					fogColor = DEFAULT.fogColor;
					hasFogColorOverride = true;
				}
				if (!hasWaterColorOverride) {
					waterColor = DEFAULT.waterColor;
					hasWaterColorOverride = true;
				}
			}
		}

		if (shadowAngles == null) {
			shadowAngles = DEFAULT_SHADOW_ANGLES;
		} else {
			shadowAngles = HDUtils.ensureArrayLength(shadowAngles, 2);
		}

		if (waterCausticsColor == null)
			waterCausticsColor = directionalColor;
		if (waterCausticsStrength == -1)
			waterCausticsStrength = directionalStrength;

		if (sky != null)
			sky.normalize();

		normalized = true;
		return this;
	}

	public SkyConfiguration getSky() {
		return sky != null ? sky : SkyConfiguration.DEFAULT_PRESET;
	}

	public Environment copy() {
		var env = new Environment();
		env.fogColor = new float[3];
		env.waterColor = new float[3];
		env.ambientColor = new float[3];
		env.directionalColor = new float[3];
		env.underglowColor = new float[3];
		env.shadowAngles = new float[3];
		env.waterCausticsColor = new float[3];
		this.copyTo(env);
		return env;
	}

	public void copyTo(Environment target) {
		target.interpolate(this, this, 0);
	}

	public Environment interpolate(Environment from, Environment to, float t) {
		mix(fogColor, from.fogColor, to.fogColor, t);
		mix(waterColor, from.waterColor, to.waterColor, t);
		mix(ambientColor, from.ambientColor, to.ambientColor, t);
		mix(directionalColor, from.directionalColor, to.directionalColor, t);
		mix(underglowColor, from.underglowColor, to.underglowColor, t);
		mix(shadowAngles, from.shadowAngles, to.shadowAngles, t);
		mix(waterCausticsColor, from.waterCausticsColor, to.waterCausticsColor, t);
		fogDepth = mix(from.fogDepth, to.fogDepth, t);
		ambientStrength = mix(from.ambientStrength, to.ambientStrength, t);
		directionalStrength = mix(from.directionalStrength, to.directionalStrength, t);
		underglowStrength = mix(from.underglowStrength, to.underglowStrength, t);
		groundFogStart = mix(from.groundFogStart, to.groundFogStart, t);
		groundFogEnd = mix(from.groundFogEnd, to.groundFogEnd, t);
		groundFogOpacity = mix(from.groundFogOpacity, to.groundFogOpacity, t);
		waterCausticsStrength = mix(from.waterCausticsStrength, to.waterCausticsStrength, t);
		windAngle = mix(from.windAngle, to.windAngle, t);
		windSpeed = mix(from.windSpeed, to.windSpeed, t);
		windStrength = mix(from.windStrength, to.windStrength, t);
		windCeiling = mix(from.windCeiling, to.windCeiling, t);
		sky = t == 1 ? to.sky : from.sky;
		return this;
	}

	@Override
	public String toString() {
		return key != null ? key : area.name;
	}
}
