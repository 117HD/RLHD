package rs117.hd.scene.environments;

import com.google.gson.annotations.JsonAdapter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rs117.hd.data.environments.Area;
import rs117.hd.utils.DegreesToRadiansAdapter;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.ColorUtils.SrgbToLinearAdapter;
import static rs117.hd.utils.ColorUtils.rgb;

@Setter(value = AccessLevel.PRIVATE)
@NoArgsConstructor
public class Environment {
	public static final Environment DEFAULT = new Environment().setKey("DEFAULT");
	public static final Environment NONE = new Environment().setKey("NONE").setFogColor(rgb("#ff00ff"));

	public static Environment OVERWORLD, AUTUMN, WINTER;

	public String key;
	public Area area;
	public boolean isUnderwater = false;
	public boolean allowSkyOverride = true;
	public boolean lightningEffects = false;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] ambientColor = rgb("#ffffff");
	public float ambientStrength = 1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] directionalColor = rgb("#ffffff");
	public float directionalStrength = .25f;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] waterColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] waterCausticsColor;
	public float waterCausticsStrength = -1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] underglowColor = rgb("#000000");
	public float underglowStrength = 0;
	@JsonAdapter(DegreesToRadiansAdapter.class)
	public float[] sunAngles = HDUtils.sunAngles(52, 235); // horizontal coordinate system, in radians
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] fogColor;
	public float fogDepth = 25;
	public int groundFogStart = -200;
	public int groundFogEnd = -500;
	public float groundFogOpacity = 0;

	public void normalize() {
		// Default to black sky and default water for non-overworld environments
		if (fogColor == null && !Area.OVERWORLD.intersects(area)) {
			fogColor = rgb("#000000");
			waterColor = rgb("#66eaff");
		}
		if (waterCausticsColor == null)
			waterCausticsColor = directionalColor;
		if (waterCausticsStrength == -1)
			waterCausticsStrength = directionalStrength;
	}

	@Override
	public String toString() {
		if (key != null)
			return key;
		if (area != null)
			return area.name();
		return super.toString();
	}
}
