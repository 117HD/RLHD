package rs117.hd.scene.skybox;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import rs117.hd.utils.GsonUtils;

@Getter
public class SkyboxConfig {
	public List<SkyboxEntry> skyboxes;

	@Getter
	@Setter
	@JsonAdapter(SkyboxEntryAdapter.class)
	public class SkyboxEntry {
		private String name;
		private String dir;
		private float rotation;
		private float rotationSpeed;
		private SkyboxPostProcessingConfig postProcessing;

	}

	@Getter
	@Setter
	public static class SkyboxPostProcessingConfig {
		private float saturation = 0f;
		private float hue = 0f;
		private float lightness = 0f;

		private float contrast = 0f;
		public float[] tintColor = {0f, 0f, 0f};

	}

	public static class SkyboxEntryAdapter extends TypeAdapter<SkyboxEntry> {

		private final SkyboxPostProcessingConfigAdapter postProcessingAdapter = new SkyboxPostProcessingConfigAdapter();


		private static final float EPSILON = 1e-6f;

		private boolean isZero(float value) {
			return Math.abs(value) < EPSILON;
		}

		@Override
		public void write(JsonWriter out, SkyboxEntry value) throws IOException {
			if (value == null) {
				out.nullValue();
				return;
			}

			out.beginObject();

			if (value.getName() != null && !value.getName().isEmpty()) {
				out.name("name").value(value.getName());
			}

			if (value.getDir() != null && !value.getDir().isEmpty()) {
				out.name("dir").value(value.getDir());
			}

			if (Math.abs(value.getRotation()) > 1e-6f) {
				out.name("rotation").jsonValue(String.format("%.1f", Math.toDegrees(value.getRotation())));
			}

			if (!isZero(value.getRotationSpeed())) {
				out.name("rotationSpeed");
				out.jsonValue(String.format("%.3f", value.getRotationSpeed()));
			}

			if (value.getPostProcessing() != null) {
				out.name("postProcessing");
				postProcessingAdapter.write(out, value.getPostProcessing());
			}

			out.endObject();
		}

		@Override
		public SkyboxEntry read(JsonReader in) throws IOException {
			SkyboxEntry entry = new SkyboxConfig().new SkyboxEntry();

			in.beginObject();
			while (in.hasNext()) {
				String name = in.nextName();
				switch (name) {
					case "name":
						entry.setName(in.nextString());
						break;
					case "dir":
						entry.setDir(in.nextString());
						break;
					case "rotation":
						float degrees = (float) in.nextDouble();
						entry.setRotation((float) Math.toRadians(degrees));
						break;
					case "rotationSpeed":
						entry.setRotationSpeed((float) in.nextDouble());
						break;
					case "postProcessing":
						// Use GsonUtils or Gson instance to read postProcessing with its own adapter
						entry.setPostProcessing(postProcessingAdapter.read(in));
						break;
					default:
						in.skipValue();
						break;
				}
			}
			in.endObject();

			return entry;
		}
	}

	public static class SkyboxPostProcessingConfigAdapter extends TypeAdapter<SkyboxPostProcessingConfig> {
		private static final float EPSILON = 1e-6f;

		private boolean isZero(float value) {
			return Math.abs(value) < EPSILON;
		}

		private boolean isDefaultTintColor(float[] tintColor) {
			if (tintColor == null || tintColor.length != 3) return false;
			return isZero(tintColor[0]) && isZero(tintColor[1]) && isZero(tintColor[2]);
		}

		@Override
		public void write(JsonWriter out, SkyboxPostProcessingConfig value) throws IOException {
			if (value == null) {
				out.nullValue();
				return;
			}

			out.beginObject();

			if (!isZero(value.getHue())) {
				out.name("hue").jsonValue(String.format("%.1f", value.getHue()));
			}
			if (!isZero(value.getSaturation())) {
				out.name("saturation").jsonValue(String.format("%.1f", value.getSaturation()));
			}
			if (!isZero(value.getLightness())) {
				out.name("lightness").jsonValue(String.format("%.1f", value.getLightness()));
			}
			if (!isZero(value.getContrast())) {
				out.name("contrast").jsonValue(String.format("%.1f", value.getContrast()));
			}
			if (!isDefaultTintColor(value.tintColor)) {
				out.name("tintColor");
				out.jsonValue(String.format("[%.1f, %.1f, %.1f]",
					value.tintColor[0], value.tintColor[1], value.tintColor[2]));
			}

			out.endObject();
		}

		@Override
		public SkyboxPostProcessingConfig read(JsonReader in) throws IOException {
			SkyboxPostProcessingConfig config = new SkyboxPostProcessingConfig();

			in.beginObject();
			while (in.hasNext()) {
				String name = in.nextName();
				switch (name) {
					case "saturation":
						config.setSaturation((float) in.nextDouble());
						break;
					case "hue":
						config.setHue((float) in.nextDouble());
						break;
					case "lightness":
						config.setLightness((float) in.nextDouble());
						break;
					case "contrast":
						config.setContrast((float) in.nextDouble());
						break;
					case "tintColor":
						in.beginArray();
						float[] color = new float[3];
						int i = 0;
						while (in.hasNext() && i < 3) {
							color[i++] = (float) in.nextDouble();
						}
						in.endArray();
						config.tintColor = color;
						break;
					default:
						in.skipValue();
						break;
				}
			}
			in.endObject();

			return config;
		}
	}

}
