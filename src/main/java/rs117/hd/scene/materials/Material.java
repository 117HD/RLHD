package rs117.hd.scene.materials;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.MaterialManager;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionPredicate;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;

@Slf4j
@Accessors(fluent = true)
@Setter(AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Material {
	public String name;
	@JsonAdapter(Reference.Adapter.class)
	public Material parent;
	@JsonAdapter(Reference.Adapter.class)
	public Material normalMap;
	@JsonAdapter(Reference.Adapter.class)
	public Material displacementMap;
	@JsonAdapter(Reference.Adapter.class)
	public Material roughnessMap;
	@JsonAdapter(Reference.Adapter.class)
	public Material ambientOcclusionMap;
	@JsonAdapter(Reference.Adapter.class)
	public Material flowMap;
	public int vanillaTextureIndex = -1;
	public boolean hasTransparency;
	public boolean overrideBaseColor;
	public boolean unlit;
	public float brightness = 1;
	public float displacementScale = .1f;
	public float flowMapStrength;
	public float[] flowMapDuration = { 0, 0 };
	public float specularStrength;
	public float specularGloss;
	public float[] scrollSpeed = { 0, 0 };
	public float[] textureScale = { 1, 1, 1 };
	public List<String> materialsToReplace = Collections.emptyList();
	@JsonAdapter(ExpressionParser.PredicateAdapter.class)
	public ExpressionPredicate replacementCondition;

	public transient int index;
	public transient boolean modifiesVanillaTexture;

	public static final Material NONE = new Material().name("NONE");
	public static final Material VANILLA = new Material().parent(NONE).name("VANILLA").hasTransparency(true);
	public static final Material[] REQUIRED_MATERIALS = { NONE, VANILLA };

	public static Material UNLIT;
	public static Material BLACK;
	public static Material WATER_FLAT;
	public static Material WATER_FLAT_2;
	public static Material SWAMP_WATER_FLAT;
	public static Material WATER_FOAM;
	public static Material WATER_FLOW_MAP;
	public static Material WATER_NORMAL_MAP_1;
	public static Material DIRT_1;
	public static Material DIRT_2;

	@GsonUtils.ExcludeDefaults
	public static class Definition extends Material implements GsonUtils.ExcludeDefaultsProvider<Material> {
		@Override
		public Material provideDefaults() {
			return parent;
		}
	}

	public static class Reference extends Material {
		public Reference(@Nonnull String name) {
			name(name);
		}

		@Slf4j
		private static class Adapter extends TypeAdapter<Material> {
			@Override
			public Material read(JsonReader in) throws IOException {
				return in.peek() == JsonToken.NULL ? null : new Reference(in.nextString());
			}

			@Override
			public void write(JsonWriter out, Material material) throws IOException {
				out.value(material == null ? null : material.name);
			}
		}
	}

	public void normalize(int index, Map<String, Material> materials) {
		if (index != -1) {
			this.index = index;
			if (name == null)
				name = "UNNAMED_" + index;
		}

		parent = resolveReference(parent, materials);
		if (parent != null)
			parent.normalize(-1, materials);

		normalMap = resolveReference(normalMap, materials);
		displacementMap = resolveReference(displacementMap, materials);
		roughnessMap = resolveReference(roughnessMap, materials);
		ambientOcclusionMap = resolveReference(ambientOcclusionMap, materials);
		flowMap = resolveReference(flowMap, materials);

		if (displacementScale == 0)
			displacementMap = NONE.displacementMap;

		if (!materialsToReplace.isEmpty() && materialsToReplace.removeIf(Objects::isNull))
			log.error("Error in material '{}': Null is not allowed as a replacement material", this);

		// Unwrap the predicate in release mode, since it never needs to be serialized
		if (!Props.DEVELOPMENT && replacementCondition instanceof ExpressionParser.SerializableExpressionPredicate)
			replacementCondition = ((ExpressionParser.SerializableExpressionPredicate) replacementCondition).predicate;

		// Determine whether the material contains some form of non-vanilla texture change
		var base = this;
		while (base.parent != null)
			base = base.parent;
		modifiesVanillaTexture =
			base != NONE ||
			normalMap != null ||
			displacementMap != null ||
			roughnessMap != null ||
			ambientOcclusionMap != null ||
			flowMap != null;
	}

	@Override
	public String toString() {
		return name; // Used by Gson to map from String to Material
	}

	public Material resolveReference(@Nullable Material material, Map<String, Material> materials) {
		if (material instanceof Reference) {
			String name = material.name;
			var m = materials.get(name);
			if (m != null)
				return m;
			log.error("Error in material '{}': Unknown material referenced: '{}'", this, name);
		}
		return material;
	}

	/**
	 * Returns the final material after all replacements have been made.
	 *
	 * @return the material after resolving all replacements
	 */
	public Material resolveReplacements() {
		return MaterialManager.REPLACEMENT_MAPPING[index];
	}

	public Material resolveTextureMaterial() {
		var base = this.resolveReplacements();
		while (base.parent != null)
			base = base.parent;
		return base;
	}

	public Material findParent(Function<Material, Object> propertyGetter) {
		var property = propertyGetter.apply(this);
		var source = this;
		while (source.parent != null && Objects.deepEquals(property, propertyGetter.apply(source.parent)))
			source = source.parent;
		return source;
	}

	@Slf4j
	public static class Adapter extends TypeAdapter<Material> {
		@Override
		public Material read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;

			if (in.peek() == JsonToken.STRING) {
				String name = in.nextString();
				var match = MaterialManager.MATERIAL_MAP.get(name);
				if (match != null)
					return match;
				log.error("Missing material '{}' at {}", name, GsonUtils.location(in), new Throwable());
				return null;
			}

			log.error("Unexpected type {} at {}", in.peek(), GsonUtils.location(in), new Throwable());
			return null;
		}

		@Override
		public void write(JsonWriter out, Material material) throws IOException {
			if (material == null) {
				out.nullValue();
			} else {
				out.value(material.name);
			}
		}
	}

	@Slf4j
	public static class ListAdapter extends TypeAdapter<List<Material>> {
		private static final Adapter ADAPTER = new Adapter();

		@Override
		public List<Material> read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return Collections.emptyList();

			if (in.peek() == JsonToken.STRING)
				return List.of(ADAPTER.read(in));

			if (in.peek() == JsonToken.BEGIN_ARRAY) {
				in.beginArray();
				List<Material> materials = new ArrayList<>();
				while (in.peek() != JsonToken.END_ARRAY)
					materials.add(ADAPTER.read(in));
				in.endArray();
				return materials;
			}

			log.error("Unexpected type {} at {}", in.peek(), GsonUtils.location(in), new Throwable());
			return Collections.emptyList();
		}

		@Override
		public void write(JsonWriter out, List<Material> materials) throws IOException {
			if (materials == null || materials.isEmpty()) {
				out.nullValue();
			} else {
				out.beginArray();
				for (var material : materials)
					out.value(material.name);
				out.endArray();
			}
		}
	}

	@Slf4j
	public static class ArrayAdapter extends TypeAdapter<Material[]> {
		private static final ListAdapter ADAPTER = new ListAdapter();

		@Override
		public Material[] read(JsonReader in) throws IOException {
			return ADAPTER.read(in).toArray(Material[]::new);
		}

		@Override
		public void write(JsonWriter out, Material[] materials) throws IOException {
			ADAPTER.write(out, List.of(materials));
		}
	}
}
