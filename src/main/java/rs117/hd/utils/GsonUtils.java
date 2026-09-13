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
import com.google.gson.JsonElement;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
			.registerTypeAdapterFactory(new ExcludeDefaultsFactory())
			.registerTypeAdapter(Float.class, new RoundingAdapter(3))
			.create();
	}

	public static void deepInheritFrom(JsonObject overrides, JsonObject base) {
		for (var entry : base.entrySet()) {
			JsonElement value = overrides.get(entry.getKey());
			if (value == null) {
				overrides.add(entry.getKey(), entry.getValue());
			} else if (value.isJsonObject() && entry.getValue().isJsonObject()) {
				deepInheritFrom(value.getAsJsonObject(), entry.getValue().getAsJsonObject());
			}
		}
	}

	public static void shallowInheritFrom(JsonObject overrides, JsonObject base) {
		for (var entry : base.entrySet())
			if (!overrides.has(entry.getKey()))
				overrides.add(entry.getKey(), entry.getValue());
	}

	public static Map<String, JsonObject> resolveParentDefinitions(
		Map<String, JsonObject> definitions,
		String type,
		@Nullable String rootParent,
		BiConsumer<JsonObject, JsonObject> inherit
	) {
		var resolved = new HashMap<String, JsonObject>();
		for (String name : definitions.keySet())
			resolveParentDefinition(name, definitions, resolved, new HashSet<>(), type, rootParent, inherit);
		resolved.values().removeIf(Objects::isNull);
		return resolved;
	}

	@Nullable
	private static JsonObject resolveParentDefinition(
		String name,
		Map<String, JsonObject> definitions,
		Map<String, JsonObject> resolved,
		Set<String> resolving,
		String type,
		@Nullable String rootParent,
		BiConsumer<JsonObject, JsonObject> inherit
	) {
		JsonObject result = resolved.get(name);
		if (resolved.containsKey(name))
			return result;
		JsonObject definition = definitions.get(name);
		if (definition == null)
			return null;
		if (!resolving.add(name)) {
			log.error("{} '{}' contains a parent loop", type, name);
			return null;
		}

		result = definition;
		JsonElement parent = definition.get("parent");
		if (parent != null) {
			if (!parent.isJsonPrimitive() || !parent.getAsJsonPrimitive().isString()) {
				log.error("{} '{}' has a non-string parent", type, name);
				result = null;
			} else {
				String parentName = parent.getAsString();
				if (!parentName.equals(rootParent)) {
					if (!definitions.containsKey(parentName)) {
						log.error("{} '{}' has an unknown parent '{}'", type, name, parentName);
						result = null;
					} else {
						JsonObject base = resolveParentDefinition(
							parentName, definitions, resolved, resolving, type, rootParent, inherit);
						if (base != null)
							inherit.accept(result, base);
						else
							result = null;
					}
				}
			}
		}
		resolving.remove(name);
		resolved.put(name, result);
		return result;
	}

	public static void removeNulls(JsonElement element) {
		if (element.isJsonObject()) {
			var iter = element.getAsJsonObject().entrySet().iterator();
			while (iter.hasNext()) {
				JsonElement value = iter.next().getValue();
				if (value.isJsonNull()) {
					iter.remove();
				} else {
					removeNulls(value);
				}
			}
		} else if (element.isJsonArray()) {
			var array = element.getAsJsonArray();
			for (int i = 0; i < array.size(); i++)
				removeNulls(array.get(i));
		}
	}

	public static void removeMatching(JsonObject target, JsonObject base) {
		var iter = target.entrySet().iterator();
		while (iter.hasNext()) {
			var entry = iter.next();
			JsonElement baseValue = base.get(entry.getKey());
			if (baseValue == null)
				continue;
			JsonElement value = entry.getValue();
			if (value.isJsonObject() && baseValue.isJsonObject()) {
				removeMatching(value.getAsJsonObject(), baseValue.getAsJsonObject());
				if (value.getAsJsonObject().size() == 0)
					iter.remove();
			} else if (value.equals(baseValue)) {
				iter.remove();
			}
		}
	}

	public static class RoundingAdapter extends TypeAdapter<Float> {
		private final float rounding;

		public RoundingAdapter(int decimals) {
			rounding = pow(10, decimals);
		}

		@Override
		public Float read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;
			return (float) in.nextDouble();
		}

		@Override
		public void write(JsonWriter out, Float src) throws IOException {
			if (src == null) {
				out.nullValue();
			} else {
				var result = round(src * rounding) / rounding;
				if (round(result) == result) {
					out.value((int) result); // Remove decimals when possible
				} else {
					out.value((Number) result); // Cast to Number so Gson removes unnecessary precision
				}
			}
		}
	}

	/**
	 * Make it less cumbersome to implement a TypeAdapter which respects the default Float adapter.
	 */
	@NoArgsConstructor
	@SuppressWarnings("unchecked")
	public static abstract class DelegateFloatAdapter<T> implements TypeAdapterFactory {
		protected TypeAdapter<Float> FLOAT_ADAPTER;
		protected boolean unwrapContainers; // Only apply directly to numbers, letting Gson handle any composite types

		@Override
		public <U> TypeAdapter<U> create(Gson gson, TypeToken<U> typeToken) {
			FLOAT_ADAPTER = gson.getAdapter(TypeToken.get(Float.class));
			var impl = this;
			var adapter = new TypeAdapter<U>() {
				@Override
				public U read(JsonReader in) throws IOException {
					return (U) impl.read(in);
				}

				@Override
				public void write(JsonWriter out, U value) throws IOException {
					impl.write(out, (T) value);
				}
			};

			if (!unwrapContainers)
				return adapter;

			// Register this as a TypeAdapterFactory for numbers downstream
			return gson.newBuilder()
				.registerTypeAdapterFactory(new TypeAdapterFactory() {
					@Override
					public <S> TypeAdapter<S> create(Gson gson, TypeToken<S> typeToken) {
						var type = typeToken.getRawType();
						if (type.isPrimitive()) {
							if (type != float.class)
								return null;
						} else if (!Float.class.isAssignableFrom(type)) {
							return null;
						}
						return (TypeAdapter<S>) adapter;
					}
				})
				.create()
				.getAdapter(typeToken);
		}

		public abstract T read(JsonReader in) throws IOException;
		public abstract void write(JsonWriter out, T value) throws IOException;
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

	private static class ExcludeDefaultsFactory implements TypeAdapterFactory {
		@Override
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
			for (var annotation : type.getRawType().getAnnotations()) {
				if (annotation.annotationType() == ExcludeDefaults.class) {
					try {
						var defaultDelegate = gson.getDelegateAdapter(this, type);
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

	public static class DegreesToRadians extends DelegateFloatAdapter<Float> {
		{
			unwrapContainers = true;
		}

		@Override
		public Float read(JsonReader in) throws IOException {
			var value = FLOAT_ADAPTER.read(in);
			return value == null ? null : value * DEG_TO_RAD;
		}

		@Override
		public void write(JsonWriter out, Float value) throws IOException {
			FLOAT_ADAPTER.write(out, value == null ? null : value * RAD_TO_DEG);
		}
	}
}
