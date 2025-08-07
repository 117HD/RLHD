/*
 * Gson hacks.
 * Written in 2025 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package rs117.hd.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Objects;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.materials.Material;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class GsonUtils {
	public static String location(JsonReader in) {
		var str = in.toString();
		int i = str.indexOf(" at ");
		if (i != -1)
			str = str.substring(i + 4);
		return str;
	}

	public static Gson wrap(Gson gson) {
		return gson.newBuilder()
			.setLenient()
			.setPrettyPrinting()
			.disableHtmlEscaping() // Disable HTML escaping for JSON exports (Gson never escapes when parsing regardless)
			.registerTypeAdapter(Material.class, new Material.Adapter())
			.registerTypeAdapterFactory(new ExcludeDefaultsFactory(gson))
			.create();
	}

	/**
	 * Because of the way this works internally, custom formatting by calling `JsonWriter#jsonValue` is not supported.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface ExcludeDefaults {}

	public interface ExcludeDefaultsProvider<T> {
		@Nullable
		T provideDefaults();
	}

	@RequiredArgsConstructor
	private static class ExcludeDefaultsAdapter<T> extends TypeAdapter<T> {
		private final Gson gson;
		private final TypeAdapter<T> type;
		private final JsonObject defaults;

		@Override
		public void write(JsonWriter out, T t) throws IOException {
			var json = type.toJsonTree(t);
			if (!json.isJsonObject()) {
				gson.toJson(json, out);
				return;
			}

			var defaults = this.defaults;
			if (t instanceof ExcludeDefaultsProvider) {
				var provided = ((ExcludeDefaultsProvider<?>) t).provideDefaults();
				if (provided != null) {
					try {
						// noinspection unchecked
						defaults = type.toJsonTree((T) provided).getAsJsonObject();
					} catch (ClassCastException ex) {
						log.error("Incorrect type provided by DefaultsProvider: {}, expected {}", provided.getClass(), t.getClass());
					}
				}
			}

			var obj = json.getAsJsonObject();
			for (var e : defaults.entrySet())
				if (Objects.equals(obj.get(e.getKey()), e.getValue()))
					obj.remove(e.getKey());

			// Make it possible to replace inherited non-null default values with explicit nulls
			out.setSerializeNulls(true);
			out.beginObject();
			for (var e : obj.entrySet()) {
				out.name(e.getKey());
				if (e.getValue().isJsonNull()) {
					out.nullValue();
				} else {
					gson.toJson(e.getValue(), out);
				}
			}
			out.endObject();
		}

		@Override
		public T read(JsonReader in) throws IOException {
			return type.read(in);
		}
	}

	@RequiredArgsConstructor
	private static class ExcludeDefaultsFactory implements TypeAdapterFactory {
		private final Gson unmodifiedGson;

		@Override
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
			for (var annotation : type.getRawType().getAnnotations()) {
				if (annotation.annotationType() == ExcludeDefaults.class) {
					try {
						var defaultDelegate = unmodifiedGson.getDelegateAdapter(this, type);
						T defaultObj;
						try {
							defaultObj = defaultDelegate.fromJson("{}");
						} catch (IOException ex) {
							return null; // Can't skip defaults for non-object types
						}

						if (defaultObj == null)
							return null; // No defaults available

						var defaults = defaultDelegate.toJsonTree(defaultObj).getAsJsonObject();
						return new ExcludeDefaultsAdapter<>(gson, gson.getDelegateAdapter(this, type), defaults);
					} catch (Exception ex) {
						log.error("Unable to exclude defaults for {}", type, ex);
						break;
					}
				}
			}
			return null;
		}
	}

	@Slf4j
	public static class DegreesToRadians extends TypeAdapter<Object> {
		@Override
		public Object read(JsonReader in) throws IOException {
			var token = in.peek();
			if (token == JsonToken.NULL)
				return null;

			if (token == JsonToken.NUMBER)
				return (float) in.nextDouble() * DEG_TO_RAD;

			if (token == JsonToken.BEGIN_ARRAY) {
				ArrayList<Float> list = new ArrayList<>();
				in.beginArray();
				while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
					if (in.peek() == JsonToken.BEGIN_ARRAY)
						throw new IOException("Expected an array of numbers. Got nested arrays.");
					list.add((float) read(in));
				}
				in.endArray();

				float[] result = new float[list.size()];
				for (int i = 0; i < list.size(); i++)
					result[i] = list.get(i);
				return result;
			}

			throw new IOException("Expected a number or array of numbers. Got " + token);
		}

		@Override
		public void write(JsonWriter out, Object src) throws IOException {
			if (src == null) {
				out.nullValue();
				return;
			}

			if (src instanceof float[]) {
				out.beginArray();
				for (float f : (float[]) src)
					out.value(f * RAD_TO_DEG);
				out.endArray();
				return;
			}

			if (src instanceof Float)
				out.value((float) src * RAD_TO_DEG);

			throw new IOException("Expected a float or float array. Got " + src);
		}
	}
}
