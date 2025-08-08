package rs117.hd.scene.materials;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UBOMaterials;
import rs117.hd.scene.MaterialManager;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionPredicate;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Accessors(fluent = true)
@Setter(AccessLevel.PROTECTED)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Material {
	public String name;
	@JsonAdapter(Reference.Adapter.class)
	public Material parent;
	@JsonAdapter(Reference.Adapter.class)
	private Material normalMap;
	@JsonAdapter(Reference.Adapter.class)
	private Material displacementMap;
	@JsonAdapter(Reference.Adapter.class)
	private Material roughnessMap;
	@JsonAdapter(Reference.Adapter.class)
	private Material ambientOcclusionMap;
	@JsonAdapter(Reference.Adapter.class)
	private Material flowMap;
	public int vanillaTextureIndex = -1;
	private boolean hasTransparency;
	private boolean overrideBaseColor;
	private boolean unlit;
	private float brightness = 1;
	private float displacementScale = .1f;
	private float flowMapStrength;
	private float[] flowMapDuration = { 0, 0 };
	private float specularStrength;
	private float specularGloss;
	private float[] scrollSpeed = { 0, 0 };
	private float[] textureScale = { 1, 1, 1 };
	public List<String> materialsToReplace = Collections.emptyList();
	@JsonAdapter(ExpressionParser.PredicateAdapter.class)
	public ExpressionPredicate replacementCondition;

	public transient int index;
	public transient boolean modifiesVanillaTexture;

	public static final Material NONE = new Material().name("NONE");
	public static final Material VANILLA = new Material().parent(NONE).name("VANILLA").hasTransparency(true);
	public static final Material[] REQUIRED_MATERIALS = { NONE, VANILLA };

	public static Material BLACK;
	public static Material WATER_FLAT;
	public static Material WATER_FLAT_2;
	public static Material SWAMP_WATER_FLAT;
	public static Material WATER_FOAM;
	public static Material WATER_FLOW_MAP;
	public static Material WATER_NORMAL_MAP_1;
	public static Material DIRT_1;
	public static Material DIRT_2;

	public static Material fromVanillaTexture(int vanillaTextureId) {
		if (vanillaTextureId < 0 || vanillaTextureId >= MaterialManager.VANILLA_TEXTURE_MAPPING.length)
			return NONE;
		return MaterialManager.VANILLA_TEXTURE_MAPPING[vanillaTextureId];
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
		return name;
	}

	public String getTextureName() {
		if (this == VANILLA)
			return null;
		return name.toLowerCase();
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

	public static void checkForReplacementLoops(Material[] materials) {
		Map<String, Material> map = new HashMap<>();
		for (var mat : materials)
			if (!mat.materialsToReplace.isEmpty())
				map.put(mat.name, mat);

		Set<String> alreadyChecked = new HashSet<>();
		for (var mat : map.values())
			checkForReplacementLoops(alreadyChecked, map, mat);
	}

	public void fillMaterialStruct(
		MaterialManager materialManager,
		UBOMaterials.MaterialStruct struct,
		int vanillaIndex,
		float vanillaScrollX,
		float vanillaScrollY
	) {
		float scrollSpeedX = scrollSpeed[0] + vanillaScrollX;
		float scrollSpeedY = scrollSpeed[1] + vanillaScrollY;

		struct.colorMap.set(this == Material.VANILLA ?
			materialManager.vanillaTextureIndexToTextureLayer[vanillaIndex] : materialManager.getTextureLayer(this));
		struct.normalMap.set(materialManager.getTextureLayer(normalMap));
		struct.displacementMap.set(materialManager.getTextureLayer(displacementMap));
		struct.roughnessMap.set(materialManager.getTextureLayer(roughnessMap));
		struct.ambientOcclusionMap.set(materialManager.getTextureLayer(ambientOcclusionMap));
		struct.flowMap.set(materialManager.getTextureLayer(flowMap));
		struct.flags.set(
			(overrideBaseColor ? 1 : 0) << 2 |
			(unlit ? 1 : 0) << 1 |
			(hasTransparency ? 1 : 0)
		);
		struct.brightness.set(brightness);
		struct.displacementScale.set(displacementScale);
		struct.specularStrength.set(specularStrength);
		struct.specularGloss.set(specularGloss);
		struct.flowMapStrength.set(flowMapStrength);
		struct.flowMapDuration.set(flowMapDuration);
		struct.scrollDuration.set(scrollSpeedX, scrollSpeedY);
		struct.textureScale.set(divide(vec(1), textureScale));
	}

	private static void checkForReplacementLoops(Set<String> alreadyChecked, Map<String, Material> map, Material entryMaterial) {
		if (alreadyChecked.add(entryMaterial.name))
			checkForReplacementLoops(alreadyChecked, map, new ArrayDeque<>(), entryMaterial.name, entryMaterial);
	}

	private static void checkForReplacementLoops(
		Set<String> alreadyChecked,
		Map<String, Material> map,
		ArrayDeque<String> loop,
		String entryPointName,
		Material toCheck
	) {
		loop.addLast(toCheck.findParent(m -> m.materialsToReplace).name);

		for (int i = toCheck.materialsToReplace.size() - 1; i >= 0; i--) {
			String nameToReplace = toCheck.materialsToReplace.get(i);
			// Check if the replacement introduces a loop
			if (entryPointName.equals(nameToReplace)) {
				var original = map.get(nameToReplace).findParent(m -> m.materialsToReplace).name;
				if (!nameToReplace.equals(original))
					nameToReplace += " (parent=" + original + ")";
				log.warn("Materials contain replacement loop: {} -> {}", String.join(" -> ", loop), nameToReplace);
				// Remove the loop
				toCheck.materialsToReplace.remove(i);
				continue;
			}

			var toReplace = map.get(nameToReplace);
			if (toReplace == null)
				continue;

			// Before continuing to check for loops back to the entrypoint name,
			// we need to rule out any loops within the next material to check,
			// so we don't get stuck in a loop there
			checkForReplacementLoops(alreadyChecked, map, toReplace);

			// The replacement might've already been removed to prevent a loop in the step above
			if (!toCheck.materialsToReplace.contains(nameToReplace))
				continue;

			// Check if any further replacements result in a loop
			checkForReplacementLoops(alreadyChecked, map, loop, entryPointName, toReplace);
		}

		loop.removeLast();
	}

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
