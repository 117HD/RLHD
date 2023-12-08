package rs117.hd.test.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Arrays;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import rs117.hd.data.environments.Area;
import rs117.hd.data.environments.Environment;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.ResourcePath.path;

public class EnvironmentExporter {
	@NoArgsConstructor
	private static class EnvironmentSerializer implements JsonSerializer<Environment> {
		private final Environment defaultEnv = Environment.DEFAULT;

		@Override
		@SneakyThrows
		public JsonElement serialize(Environment env, Type typeOfSrc, JsonSerializationContext context) {
			JsonObject o = new JsonObject();

			if (env.ordinal() >= Environment.OVERWORLD.ordinal())
				o.add("key", context.serialize(env.name()));

			var clazz = Environment.class;
			for (Field field : clazz.getDeclaredFields()) {
				if (field.isEnumConstant() || field.isSynthetic())
					continue;

				field.setAccessible(true);
				var name = field.getName();
				Object out = field.get(env);
				if (out == null)
					continue;

				Object defaultValue = field.get(defaultEnv);
				if (out.equals(defaultValue))
					continue;
				if (name.equals("area") && out == Area.NONE)
					continue;
				if (name.equals("waterCausticsColor") && out == env.getDirectionalColor())
					continue;
				if (name.equals("waterCausticsStrength") && (float) out == env.getDirectionalStrength())
					continue;

				if (out instanceof float[]) {
					float[] src = (float[]) out;
					float[] def = (float[]) defaultValue;
					if (src.length != def.length)
						throw new RuntimeException(
							"Unexpected difference in " + name + ": src=" + Arrays.toString(src) + ", def=" + Arrays.toString(def));
					boolean equal = true;
					for (int i = 0; i < src.length; i++) {
						if (src[i] != def[i]) {
							equal = false;
							break;
						}
					}
					if (equal)
						continue;
				}

				final float EPS = .005f;

				if (out instanceof float[]) {
					float[] src = (float[]) out;

					// Color
					if (src.length == 3) {
//						float max = HDUtils.max(src);
//						if (max > 1) {
//							try {
//								float scale = 1 / max;
//								var strengthField = clazz.getDeclaredField(name.replace("Color", "Strength"));
//								strengthField.setAccessible(true);
//								strengthField.set(env, strengthField.getFloat(env) * scale);
//
//								// If there was a strength field to multiply the scale into, normalize the color values
//								for (int i = 0; i < 3; i++)
//									src[i] *= scale;
//							} catch (NoSuchFieldException ignored) {
//							}
//						}

						src = ColorUtils.linearToSrgb((float[]) out);
						for (int i = 0; i < 3; i++)
							src[i] *= 255;

						// See if it can fit in a hex color code
						boolean canfit = true;
						int[] c = new int[3];
						for (int i = 0; i < 3; i++) {
							float f = src[i];
							c[i] = Math.round(f);
							if (Math.abs(f - c[i]) > EPS) {
								canfit = false;
								break;
							}
						}

						if (canfit) {
							// Serialize it as a hex color code
							out = String.format("#%02x%02x%02x", c[0], c[1], c[2]);
						} else {
							out = src;
						}
					}

					// Angles
					if (src.length == 2) {
						boolean wholeNumbers = true;
						int[] rounded = new int[2];
						for (int i = 0; i < 2; i++) {
							src[i] = (float) Math.toDegrees(src[i]);
							rounded[i] = Math.round(src[i]);
							if (Math.abs(rounded[i] - src[i]) > EPS)
								wholeNumbers = false;
						}

						out = wholeNumbers ? rounded : src;
					}
				}

				if (out instanceof Float) {
					float f = (float) out;
					if (Math.abs(Math.round(f) - f) <= EPS) {
						out = (int) f;
					}
				}

				o.add(name, context.serialize(out));
			}

			return o;
		}
	}

	@SneakyThrows
	public static void main(String... args) {
		Gson gson = new GsonBuilder()
			.setPrettyPrinting()
			.registerTypeAdapter(Environment.class, new EnvironmentSerializer())
			.create();

		path("src/main/resources/rs117/hd/scene/environments.json")
			.writeString(gson.toJson(Arrays
				.stream(Environment.values())
				.filter(env -> env != Environment.DEFAULT && env != Environment.NONE)
				.toArray()));
	}
}
