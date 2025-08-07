package rs117.hd.scene.water_types;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.GsonUtils;

import static rs117.hd.utils.ColorUtils.rgb;

@NoArgsConstructor
@GsonUtils.ExcludeDefaults
public class WaterType {
	public String name;
	public boolean flat = false;
	public float specularStrength = .5f;
	public float specularGloss = 500;
	public float normalStrength = .09f;
	public float baseOpacity = .5f;
	public float fresnelAmount = 1;
	public Material normalMap = Material.WATER_NORMAL_MAP_1;
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	public float[] surfaceColor = { 1, 1, 1 };
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	public float[] foamColor = rgb(176, 164, 146);
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	public float[] depthColor = rgb(0, 117, 142);
	public boolean hasFoam = true;
	public float duration = 1;
	public int fishingSpotRecolor = -1;

	public transient int index;

	public static final WaterType NONE = new WaterType("NONE");
	public static WaterType WATER;
	public static WaterType WATER_FLAT;
	public static WaterType SWAMP_WATER_FLAT;
	public static WaterType ICE;

	private WaterType(String name) {
		this.name = name;
	}

	public void normalize(int index) {
		this.index = index;
		if (name == null)
			name = "UNNAMED_" + index;
		if (normalMap == null)
			normalMap = NONE.normalMap;
		if (surfaceColor == null)
			surfaceColor = NONE.surfaceColor;
		if (foamColor == null)
			foamColor = NONE.foamColor;
		if (depthColor == null)
			depthColor = NONE.depthColor;
	}

	@Override
	public String toString() {
		return name;
	}

	@Slf4j
	public static class Adapter extends TypeAdapter<WaterType> {
		@Override
		public WaterType read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;

			if (in.peek() == JsonToken.STRING) {
				String name = in.nextString();
				for (var match : WaterTypeManager.WATER_TYPES)
					if (name.equals(match.name))
						return match;

				log.warn("No water type exists with the name '{}' at {}", name, GsonUtils.location(in), new Throwable());
			} else {
				log.warn("Unexpected type {} at {}", in.peek(), GsonUtils.location(in), new Throwable());
			}

			return null;
		}

		@Override
		public void write(JsonWriter out, WaterType waterType) throws IOException {
			if (waterType == null) {
				out.nullValue();
			} else {
				out.value(waterType.name);
			}
		}
	}
}
