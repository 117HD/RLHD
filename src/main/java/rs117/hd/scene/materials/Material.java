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
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.model.ModelPusher;
import rs117.hd.opengl.uniforms.UBOMaterials;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionPredicate;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.HDVariables;
import rs117.hd.utils.Props;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Setter
@Accessors(fluent = true)
@JsonAdapter(Material.Adapter.class)
@NoArgsConstructor
public class Material {
	public String name;
	@JsonAdapter(Reference.Adapter.class)
	protected Material parent;
	public int vanillaTextureIndex = -1;

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
	private boolean hasTransparency;
	private boolean overrideBaseColor;
	private boolean unlit;
	@JsonAdapter(ColorUtils.LinearAdapter.class)
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

	public transient int uboIndex;
	public transient int textureLayer = -1;
	public transient boolean modifiesVanillaTexture;
	public transient boolean isFallbackVanillaMaterial;
	public transient boolean isValid = true;

	public static final Material NONE = new Material().name("NONE");
	public static final Material[] REQUIRED_MATERIALS = { NONE };

	public static int getTextureLayer(@Nullable Material material) {
		return material == null ? -1 : material.textureLayer;
	}

	public void normalize(Map<String, Material> materials) {
		parent = resolveReference(parent, materials);
		if (parent == this) {
			parent = null;
		} else if (parent != null) {
			parent.normalize(materials);
		}

		normalMap = resolveReference(normalMap, materials);
		displacementMap = resolveReference(displacementMap, materials);
		roughnessMap = resolveReference(roughnessMap, materials);
		ambientOcclusionMap = resolveReference(ambientOcclusionMap, materials);
		flowMap = resolveReference(flowMap, materials);

		if (displacementScale == 0)
			displacementMap = NONE.displacementMap;
		flowMapDuration = ensureDefaults(flowMapDuration, NONE.flowMapDuration);
		scrollSpeed = ensureDefaults(scrollSpeed, NONE.scrollSpeed);
		textureScale = ensureDefaults(textureScale, NONE.textureScale);

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

	public boolean isVanillaReplacement() {
		return vanillaTextureIndex != -1 && (parent == null || parent.vanillaTextureIndex != vanillaTextureIndex);
	}

	public String getTextureName() {
		if (this == NONE || isFallbackVanillaMaterial)
			return null;
		return name.toLowerCase();
	}

	public Material resolveTextureOwner() {
		var base = this;
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

	public boolean isInactiveReplacement(HDVariables vars) {
		if (replacementCondition == null)
			return false;
		return !replacementCondition.test(vars);
	}

	public boolean replaces(String name, HDVariables vars) {
		if (replacementCondition == null || !materialsToReplace.contains(name))
			return false;
		return replacementCondition.test(vars);
	}

	public int packMaterialData(@Nonnull ModelOverride modelOverride, UvType uvType, boolean isOverlay) {
		// This needs to return zero by default, since we often fall back to writing all zeroes to UVs
		assert isValid : String.format("Material %s used after invalidation", this);
		int materialIndex = uboIndex;
		assert materialIndex <= ModelPusher.MAX_MATERIAL_INDEX;
		// The sign bit can't be used without shader changes to correctly unpack the material index
		return (materialIndex & ModelPusher.MAX_MATERIAL_INDEX) << 20
			   | ((int) (modelOverride.shadowOpacityThreshold * 0x3F) & 0x3F) << 14
			   | ((modelOverride.windDisplacementModifier + 3) & 0x7) << 11
			   | (modelOverride.windDisplacementMode.ordinal() & 0x7) << 8
			   | (modelOverride.invertDisplacementStrength ? 1 : 0) << 7
			   | (modelOverride.terrainVertexSnap ? 1 : 0) << 6
			   | (!modelOverride.receiveShadows ? 1 : 0) << 5
			   | (modelOverride.upwardsNormals ? 1 : 0) << 4
			   | (modelOverride.flatNormals ? 1 : 0) << 3
			   | (uvType.worldUvs ? 1 : 0) << 2
			   | (uvType == UvType.VANILLA ? 1 : 0) << 1
			   | (isOverlay ? 1 : 0);
	}

	public void fillMaterialStruct(
		UBOMaterials.MaterialStruct struct,
		float vanillaScrollX,
		float vanillaScrollY
	) {
		float scrollSpeedX = scrollSpeed[0] + vanillaScrollX;
		float scrollSpeedY = scrollSpeed[1] + vanillaScrollY;

		struct.colorMap.set(textureLayer);
		struct.normalMap.set(getTextureLayer(normalMap));
		struct.displacementMap.set(getTextureLayer(displacementMap));
		struct.roughnessMap.set(getTextureLayer(roughnessMap));
		struct.ambientOcclusionMap.set(getTextureLayer(ambientOcclusionMap));
		struct.flowMap.set(getTextureLayer(flowMap));
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

	private Material resolveReference(@Nullable Material material, Map<String, Material> materials) {
		if (material instanceof Reference) {
			String name = material.name;
			var m = materials.get(name);
			if (m != null)
				return m;
			log.error("Error in material '{}': Unknown material referenced: '{}'", this, name);
		}
		return material;
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
