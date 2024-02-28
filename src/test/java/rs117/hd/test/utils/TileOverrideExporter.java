package rs117.hd.test.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import javax.annotation.Nullable;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import net.runelite.api.*;
import org.apache.commons.lang3.builder.EqualsBuilder;
import rs117.hd.HdPlugin;
import rs117.hd.data.environments.Area;
import rs117.hd.data.materials.Overlay;
import rs117.hd.data.materials.TileOverrideBuilder;
import rs117.hd.data.materials.Underlay;
import rs117.hd.scene.tile_overrides.ExpressionBasedReplacement;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.Props;

import static rs117.hd.utils.ResourcePath.path;

@SuppressWarnings("deprecation")
public class TileOverrideExporter {
	@SneakyThrows
	public static void main(String... args) {
		Props.DEVELOPMENT = true;
		Gson gson = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.registerTypeAdapter(TileOverride.class, new Serializer())
			.create();

		var converted = convertOldTileOverrides();

		path("src/main/resources/rs117/hd/scene/tile_overrides.json")
			.writeString(gson.toJson(converted));
	}

	@NoArgsConstructor
	public static class Serializer implements JsonSerializer<TileOverride> {
		private final TileOverride defaultOverride = new TileOverride();

		@SuppressWarnings({ "unchecked", "rawtypes" })
		@Override
		@SneakyThrows
		public JsonElement serialize(TileOverride override, Type typeOfSrc, JsonSerializationContext context) {
			JsonObject o = new JsonObject();

			for (Field field : TileOverride.class.getDeclaredFields()) {
				var modifiers = field.getModifiers();
				if (field.isEnumConstant() ||
					field.isSynthetic() ||
					Modifier.isStatic(modifiers) ||
					Modifier.isTransient(modifiers))
					continue;

				field.setAccessible(true);
				var name = field.getName();
				Object out = field.get(override);
				if (out == null)
					continue;

				Object defaultValue = field.get(defaultOverride);
				if (out.equals(defaultValue))
					continue;

				var serializedNameAnnotation = field.getAnnotation(SerializedName.class);
				if (serializedNameAnnotation != null)
					name = serializedNameAnnotation.value();

				// Default to not applying the override anywhere, instead of having to specify an empty IDs list
				if (name.equals("area") && out == Area.NONE)
					continue;

				var jsonAdapterAnnotation = field.getAnnotation(JsonAdapter.class);
				if (jsonAdapterAnnotation != null) {
					Class clazz = jsonAdapterAnnotation.value();
					var ctr = clazz.getDeclaredConstructor();
					ctr.setAccessible(true);
					var jsonAdapter = (TypeAdapter) ctr.newInstance();
					o.add(name, jsonAdapter.toJsonTree(out));
					continue;
				}

				o.add(name, context.serialize(out));
			}

			return o;
		}
	}

	private static class UnserializableReplacement<T> extends TileOverride.Replacement<T> {
		private final TileOverride.IReplacement<T> resolver;

		public UnserializableReplacement(TileOverride.IReplacement<T> resolver) {
			super(null);
			this.resolver = resolver;
		}

		@Nullable
		@Override
		public T resolve(HdPlugin plugin, Scene scene, Tile tile, T original) {
			return resolver.resolve(plugin, scene, tile, original);
		}
	}

	private static ArrayList<TileOverride> convertOldTileOverrides() throws NoSuchFieldException, IllegalAccessException {
		ArrayList<TileOverride> overlayOverrides = new ArrayList<>();
		ArrayList<TileOverride> underlayOverrides = new ArrayList<>();

		int index = 0;

		for (var overlay : Overlay.values()) {
			if (overlay == Overlay.NONE)
				continue;

			var override = new TileOverride();
			override.name = overlay.name();
			override.area = overlay.area;
			override.groundMaterial = overlay.groundMaterial;
			override.waterType = overlay.waterType;
			override.blended = overlay.blended;
			override.blendedAsOpposite = overlay.blendedAsUnderlay;
			override.shiftHue = overlay.shiftHue;
			override.minHue = overlay.minHue;
			override.maxHue = overlay.maxHue;
			override.shiftSaturation = overlay.shiftSaturation;
			override.minSaturation = overlay.minSaturation;
			override.maxSaturation = overlay.maxSaturation;
			override.shiftLightness = overlay.shiftLightness;
			override.minLightness = overlay.minLightness;
			override.maxLightness = overlay.maxLightness;
			override.uvOrientation = overlay.uvOrientation;
			override.uvScale = overlay.uvScale;
			override.index = index++;

			if (overlay.filterIds != null) {
				if (overlay.filterIds.length == 0) {
					// Change empty ID lists to use Area.NONE instead
					override.area = Area.NONE;
				} else {
					override.overlayIds = Arrays.stream(overlay.filterIds).mapToInt(i -> {
						assert i != null;
						return i;
					}).toArray();
				}
			}

			if (overlay.replacements != null) {
				override.replacements = new ArrayList<>();
				for (var resolver : overlay.replacements) {
					if (resolver instanceof ExpressionBasedReplacement) {
						var r = (ExpressionBasedReplacement<Overlay>) resolver;
						override.replacements.add(new ExpressionBasedReplacement<>(
							r.replacement == null ? TileOverride.NONE : overlayOverrides.get(r.replacement.ordinal()),
							TileOverrideBuilder.SEASON_CONSTANTS,
							r.expressions
						));
					} else {
						override.replacements.add(new UnserializableReplacement<>((plugin, scene, tile, original) -> {
							override.name = overlay.name() + "#";
							var replacement = resolver.resolve(plugin, scene, tile, overlay);
							return replacement == null ? TileOverride.NONE : overlayOverrides.get(replacement.ordinal());
						}));
						override.REQUIRES_MANUAL_TRANSLATION = true;
					}
				}
			}

			overlayOverrides.add(override);
		}

		for (var underlay : Underlay.values()) {
			if (underlay == Underlay.NONE)
				continue;

			var override = new TileOverride();
			override.name = underlay.name();
			override.area = underlay.area;
			override.groundMaterial = underlay.groundMaterial;
			override.waterType = underlay.waterType;
			override.blended = underlay.blended;
			override.blendedAsOpposite = underlay.blendedAsOverlay;
			override.shiftHue = underlay.shiftHue;
			override.minHue = underlay.minHue;
			override.maxHue = underlay.maxHue;
			override.shiftSaturation = underlay.shiftSaturation;
			override.minSaturation = underlay.minSaturation;
			override.maxSaturation = underlay.maxSaturation;
			override.shiftLightness = underlay.shiftLightness;
			override.minLightness = underlay.minLightness;
			override.maxLightness = underlay.maxLightness;
			override.uvOrientation = underlay.uvOrientation;
			override.uvScale = underlay.uvScale;
			override.index = index++;

			if (underlay.filterIds != null) {
				if (underlay.filterIds.length == 0) {
					// Change empty ID lists to use Area.NONE instead
					override.area = Area.NONE;
				} else {
					override.underlayIds = Arrays.stream(underlay.filterIds).mapToInt(i -> {
						assert i != null;
						return i;
					}).toArray();
				}
			}

			if (underlay.replacements != null) {
				override.replacements = new ArrayList<>();
				for (var resolver : underlay.replacements) {
					if (resolver instanceof ExpressionBasedReplacement) {
						var r = (ExpressionBasedReplacement<Underlay>) resolver;
						override.replacements.add(new ExpressionBasedReplacement<>(
							r.replacement == null ? TileOverride.NONE : underlayOverrides.get(r.replacement.ordinal()),
							TileOverrideBuilder.SEASON_CONSTANTS,
							r.expressions
						));
					} else {
						override.replacements.add(new UnserializableReplacement<>((plugin, scene, tile, original) -> {
							override.name = underlay.name() + "#";
							var replacement = resolver.resolve(plugin, scene, tile, underlay);
							return replacement == null ? TileOverride.NONE : underlayOverrides.get(replacement.ordinal());
						}));
						override.REQUIRES_MANUAL_TRANSLATION = true;
					}
				}
			}

			if (override.ids != null && override.ids.length == 0) {
				// Change empty ID lists to use Area.NONE instead
				override.area = Area.NONE;
				override.ids = null;
			}

			underlayOverrides.add(override);
		}

		HashSet<TileOverride> duplicateOverlays = new HashSet<>();
		for (var underlay : underlayOverrides) {
			for (var overlay : overlayOverrides) {
				if (overlay.name.equals(underlay.name)) {
					if (EqualsBuilder.reflectionEquals(underlay, overlay, false)) {
						System.out.println("Removing duplicate underlay: " + underlay.name);
					} else {
						underlay.name += "_UNDERLAY";
						duplicateOverlays.add(overlay);
					}
				}
			}
		}
		for (var overlay : duplicateOverlays) {
			overlay.name += "_OVERLAY";
		}

		ArrayList<TileOverride> combined = new ArrayList<>();
		combined.addAll(overlayOverrides);
		combined.addAll(underlayOverrides);

		for (var override : combined) {
			if (override.overlayIds != null)
				Arrays.sort(override.overlayIds);
			if (override.underlayIds != null)
				Arrays.sort(override.underlayIds);

			if (override.replacements != null && !override.replacements.isEmpty()) {
				var replacementExpressions = new LinkedHashMap<>(override.replacements.size());
				for (var raw : override.replacements) {
					var replacement = (ExpressionBasedReplacement<TileOverride>) raw;
					Object expressions;
					if (replacement.expressions.length == 0) {
						expressions = new Object[] { true };
					} else if (replacement.expressions.length == 1) {
						expressions = expressionToObject(replacement.expressions[0]);
					} else {
						var arr = new Object[replacement.expressions.length];
						for (int i = 0; i < arr.length; i++)
							arr[i] = expressionToObject(replacement.expressions[i]);
						expressions = arr;
					}
					replacementExpressions.put(replacement.replacement.name, expressions);
				}

				var field = TileOverride.class.getDeclaredField("replacementExpressions");
				field.setAccessible(true);
				field.set(override, replacementExpressions);
			}
		}

		return combined;
	}

	private static Object expressionToObject(String expression) {
		var expr = ExpressionParser.parseExpression(expression);
		if (expr instanceof Boolean)
			return expr;
		return expression;
	}
}
