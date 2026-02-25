package rs117.hd.scene.model_overrides;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.config.VanillaShadowMode;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.Props;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.ExpressionParser.asExpression;
import static rs117.hd.utils.ExpressionParser.parseExpression;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class ModelOverride
{
	public static final ModelOverride NONE = new ModelOverride(true);

	private static final Set<Integer> EMPTY = new HashSet<>();

	public String description = "UNKNOWN";

	// When, where or what the override should apply to
	public SeasonalTheme seasonalTheme;
	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] areas = {};
	@JsonAdapter(GamevalManager.NpcAdapter.class)
	public Set<Integer> npcIds = EMPTY;
	@JsonAdapter(GamevalManager.ObjectAdapter.class)
	public Set<Integer> objectIds = EMPTY;
	@JsonAdapter(GamevalManager.SpotanimAdapter.class)
	public Set<Integer> projectileIds = EMPTY;
	@JsonAdapter(GamevalManager.SpotanimAdapter.class)
	public Set<Integer> graphicsObjectIds = EMPTY;

	public Material baseMaterial = Material.NONE;
	public Material textureMaterial = Material.NONE;
	public UvType uvType = UvType.VANILLA;
	public float uvScale = 1;
	public int uvOrientation = 0;
	public int uvOrientationX = 0;
	public int uvOrientationY = 0;
	public int uvOrientationZ = 0;
	public int rotate = 0;
	public boolean hide = false;
	public boolean disableDetailCulling = false;
	public boolean retainVanillaUvs = true;
	public boolean forceMaterialChanges = false;
	public boolean flatNormals = false;
	public boolean upwardsNormals = false;
	public boolean hideVanillaShadows = false;
	public boolean retainVanillaShadowsInPvm = false;
	public boolean hideHdShadowsInPvm = false;
	public boolean castShadows = true;
	public boolean receiveShadows = true;
	public boolean terrainVertexSnap = false;
	public boolean undoVanillaShading = true;
	public boolean isWaterEffect = false;
	public float terrainVertexSnapThreshold = 0.125f;
	public float shadowOpacityThreshold = 0;
	public TzHaarRecolorType tzHaarRecolorType = TzHaarRecolorType.NONE;
	public InheritTileColorType inheritTileColorType = InheritTileColorType.NONE;
	public WindDisplacement windDisplacementMode = WindDisplacement.DISABLED;
	public int windDisplacementModifier = 0;
	public boolean invertDisplacementStrength = false;
	public int depthBias = -1;
	public boolean disablePrioritySorting = false;

	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] hideInAreas = {};

	public Map<Material, ModelOverride> materialOverrides;
	public ModelOverride[] colorOverrides;

	private JsonElement colors;

	public transient boolean isDummy;
	public transient Map<AABB, ModelOverride> areaOverrides;
	public transient AhslPredicate ahslCondition;
	public transient boolean hasTransparency;
	public transient boolean mightHaveTransparency;
	public transient boolean modifiesVanillaTexture;

	// Transient not volatile, since access order can be random as it'll mean we'll just fall back to the full lookup
	private transient long cachedColorOverrideAhsl = -1;

	@FunctionalInterface
	public interface AhslPredicate {
		boolean test(int ahsl);
	}

	public void normalize(HdPlugin plugin) {
		// Ensure there are no nulls in case of invalid configuration during development
		if (baseMaterial == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid baseMaterial");
			baseMaterial = ModelOverride.NONE.baseMaterial;
		}
		if (textureMaterial == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid textureMaterial");
			textureMaterial = ModelOverride.NONE.textureMaterial;
		}
		if (uvType == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid uvType");
			uvType = ModelOverride.NONE.uvType;
		}
		if (tzHaarRecolorType == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid tzHaarRecolorType");
			tzHaarRecolorType = ModelOverride.NONE.tzHaarRecolorType;
		}
		if (inheritTileColorType == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid inheritTileColorType");
			inheritTileColorType = ModelOverride.NONE.inheritTileColorType;
		}
		if (windDisplacementMode == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid windDisplacementMode");
			windDisplacementMode = ModelOverride.NONE.windDisplacementMode;
		}

		if (windDisplacementModifier < -3 || windDisplacementModifier > 3) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid windDisplacementModifier (range is -3 to 3)");
			windDisplacementModifier = clamp(windDisplacementModifier, -3, 3);
		}

		modifiesVanillaTexture = textureMaterial.modifiesVanillaTexture;

		boolean disableTextures = !plugin.configModelTextures && !forceMaterialChanges;
		if (disableTextures) {
			if (baseMaterial.modifiesVanillaTexture)
				baseMaterial = Material.NONE;
			if (textureMaterial.modifiesVanillaTexture)
				textureMaterial = Material.NONE;
		}

		if (areas == null)
			areas = new AABB[0];
		if (hideInAreas == null)
			hideInAreas = new AABB[0];

		hasTransparency = mightHaveTransparency =
			baseMaterial.hasTransparency ||
			textureMaterial.hasTransparency ||
			tzHaarRecolorType != TzHaarRecolorType.NONE;

		if (materialOverrides != null) {
			var normalized = new HashMap<Material, ModelOverride>();
			for (var entry : materialOverrides.entrySet()) {
				var override = entry.getValue();
				override.normalize(plugin);
				if (disableTextures && override.modifiesVanillaTexture)
					continue;
				mightHaveTransparency |= override.mightHaveTransparency;
				normalized.put(entry.getKey(), override);
			}
			if (normalized.isEmpty())
				normalized = null;
			materialOverrides = normalized;
		}

		if (colorOverrides != null) {
			for (var override : colorOverrides) {
				override.normalize(plugin);
				mightHaveTransparency |= override.mightHaveTransparency;
				override.ahslCondition = parseAhslConditions(override.colors);
			}
		}

		if (uvOrientationX == 0)
			uvOrientationX = uvOrientation;
		if (uvOrientationY == 0)
			uvOrientationY = uvOrientation;
		if (uvOrientationZ == 0)
			uvOrientationZ = uvOrientation;

		if (retainVanillaShadowsInPvm) {
			if (plugin.configVanillaShadowMode.retainInPvm)
				hideVanillaShadows = false;
			if (plugin.configVanillaShadowMode == VanillaShadowMode.PREFER_IN_PVM && hideHdShadowsInPvm)
				castShadows = false;
		}

		if (!castShadows && shadowOpacityThreshold == 0)
			shadowOpacityThreshold = 1;
	}

	public ModelOverride copy() {
		return new ModelOverride(
			description,
			seasonalTheme,
			areas,
			npcIds,
			objectIds,
			projectileIds,
			graphicsObjectIds,
			baseMaterial,
			textureMaterial,
			uvType,
			uvScale,
			uvOrientation,
			uvOrientationX,
			uvOrientationY,
			uvOrientationZ,
			rotate,
			hide,
			disableDetailCulling,
			retainVanillaUvs,
			forceMaterialChanges,
			flatNormals,
			upwardsNormals,
			hideVanillaShadows,
			retainVanillaShadowsInPvm,
			hideHdShadowsInPvm,
			castShadows,
			receiveShadows,
			terrainVertexSnap,
			undoVanillaShading,
			isWaterEffect,
			terrainVertexSnapThreshold,
			shadowOpacityThreshold,
			tzHaarRecolorType,
			inheritTileColorType,
			windDisplacementMode,
			windDisplacementModifier,
			invertDisplacementStrength,
			depthBias,
			disablePrioritySorting,
			hideInAreas,
			materialOverrides,
			colorOverrides,
			colors,
			isDummy,
			areaOverrides,
			ahslCondition,
			hasTransparency,
			mightHaveTransparency,
			modifiesVanillaTexture,
			// Runtime caching fields
			-1
		);
	}

	private ModelOverride(boolean isDummy) {
		this();
		this.isDummy = isDummy;
	}

	private AhslPredicate parseAhslConditions(JsonElement element) {
		if (element == null)
			return ahsl -> false;

		JsonArray arr;
		if (element.isJsonArray()) {
			arr = element.getAsJsonArray();
		} else {
			arr = new JsonArray();
			arr.add(element);
		}

		AhslPredicate combinedPredicate = null;

		for (var el : arr) {
			if (el.isJsonNull())
				continue;
			if (!el.isJsonPrimitive()) {
				log.warn("Skipping unexpected HSL condition '{}' in override '{}'", el, description);
				continue;
			}

			AhslPredicate condition;
			var prim = el.getAsJsonPrimitive();
			if (prim.isBoolean()) {
				boolean bool = prim.getAsBoolean();
				condition = ahsl -> bool;
			} else if (prim.isNumber()) {
				try {
					int targetHsl = prim.getAsInt();
					condition = ahsl -> (ahsl & 0xFFFF) == targetHsl;
				} catch (Exception ex) {
					log.warn("Expected integer, but got {} in override '{}'", el, description);
					continue;
				}
			} else if (prim.isString()) {
				var expr = asExpression(parseExpression(prim.getAsString()));

				if (Props.DEVELOPMENT) {
					// Ensure all variables are defined
					final Set<String> knownVariables = Set.of("a", "h", "s", "l", "hsl", "ahsl");
					for (var variable : expr.variables)
						if (!knownVariables.contains(variable))
							throw new IllegalStateException(
								"Expression '" + prim.getAsString() + "' contains unknown variable '" + variable + "'");
				}

				var predicate = expr.toPredicate();
				condition = ahsl -> predicate.test(key -> {
					switch (key) {
						case "a":
							return ahsl >>> 16 & 0xFF;
						case "h":
							return ahsl >>> 10 & 0x3F;
						case "s":
							return ahsl >>> 7 & 0x7;
						case "l":
							return ahsl & 0x7F;
						case "ahsl":
							return ahsl;
						case "hsl":
							return ahsl & 0xFFFF;
						default:
							assert false : "Unexpected variable: " + key;
							return 0;
					}
				});
			} else {
				log.warn("Skipping unexpected HSL condition primitive '{}' in override '{}'", el, description);
				continue;
			}

			if (combinedPredicate == null) {
				combinedPredicate = condition;
			} else {
				var prev = combinedPredicate;
				combinedPredicate = ahsl -> prev.test(ahsl) || condition.test(ahsl);
			}
		}

		if (combinedPredicate == null)
			return ahsl -> false;

		return combinedPredicate;
	}

	public void computeModelUvw(float[] out, int i, float x, float y, float z, int orientation) {
		float rad, cos, sin;
		float temp;
		if (orientation % 2048 != 0) {
			// Reverse baked vertex rotation
			rad = orientation * JAU_TO_RAD;
			cos = cos(rad);
			sin = sin(rad);
			temp = x * sin + z * cos;
			x = x * cos - z * sin;
			z = temp;
		}

		x = (x / LOCAL_TILE_SIZE + .5f) / uvScale;
		y = (y / LOCAL_TILE_SIZE + .5f) / uvScale;
		z = (z / LOCAL_TILE_SIZE + .5f) / uvScale;

		uvType.computeModelUvw(out, i, x, y, z);

		if (uvOrientation % 2048 != 0) {
			rad = uvOrientation * JAU_TO_RAD;
			cos = cos(rad);
			sin = sin(rad);
			x = out[i] - .5f;
			z = out[i + 1] - .5f;
			temp = x * sin + z * cos;
			x = x * cos - z * sin;
			z = temp;
			out[i] = x + .5f;
			out[i + 1] = z + .5f;
		}
	}

	public void fillUvsForFace(float[] out, Model model, int orientation, UvType uvType, int face, float[] workingSpace) {
		switch (uvType) {
			case WORLD_XY:
			case WORLD_XZ:
			case WORLD_YZ:
				uvType.computeWorldUvw(out, 0, uvScale);
				uvType.computeWorldUvw(out, 4, uvScale);
				uvType.computeWorldUvw(out, 8, uvScale);
				break;
			case MODEL_XY:
			case MODEL_XY_MIRROR_A:
			case MODEL_XY_MIRROR_B:
			case MODEL_XZ:
			case MODEL_XZ_MIRROR_A:
			case MODEL_XZ_MIRROR_B:
			case MODEL_YZ:
			case MODEL_YZ_MIRROR_A:
			case MODEL_YZ_MIRROR_B: {
				final float[] vertexX = model.getVerticesX();
				final float[] vertexY = model.getVerticesY();
				final float[] vertexZ = model.getVerticesZ();
				final int triA = model.getFaceIndices1()[face];
				final int triB = model.getFaceIndices2()[face];
				final int triC = model.getFaceIndices3()[face];

				computeModelUvw(out, 0, vertexX[triA], vertexY[triA], vertexZ[triA], orientation);
				computeModelUvw(out, 4, vertexX[triB], vertexY[triB], vertexZ[triB], orientation);
				computeModelUvw(out, 8, vertexX[triC], vertexY[triC], vertexZ[triC], orientation);
				break;
			}
			case BOX:
				computeBoxUvw(out, model, orientation, face, workingSpace);
				break;
			case VANILLA: {
				final byte[] textureFaces = model.getTextureFaces();
				int texFace = textureFaces == null ? -1 : textureFaces[face];
				if (texFace != -1) {
					texFace &= 0xff;

					final float[] vertexX = model.getVerticesX();
					final float[] vertexY = model.getVerticesY();
					final float[] vertexZ = model.getVerticesZ();
					final int texA = model.getTexIndices1()[texFace];
					final int texB = model.getTexIndices2()[texFace];
					final int texC = model.getTexIndices3()[texFace];

					out[0] = vertexX[texA];
					out[1] = vertexY[texA];
					out[2] = vertexZ[texA];
					out[4] = vertexX[texB];
					out[5] = vertexY[texB];
					out[6] = vertexZ[texB];
					out[8] = vertexX[texC];
					out[9] = vertexY[texC];
					out[10] = vertexZ[texC];
				}
				break;
			}
			case GEOMETRY:
			default:
				out[0] = 0;
				out[1] = 0;
				out[2] = 0;
				out[4] = 1;
				out[5] = 0;
				out[6] = 0;
				out[8] = 0;
				out[9] = 1;
				out[10] = 0;
				break;
		}
	}

	@SuppressWarnings({ "PointlessArithmeticExpression", "UnnecessaryLocalVariable" })
	private void computeBoxUvw(float[] out, Model model, int modelOrientation, int face, float[] workingSpace) {
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final float[] v = workingSpace;
		int vidx;
		vidx = model.getFaceIndices1()[face];
		v[0 * 3 + 0] = verticesX[vidx];
		v[0 * 3 + 1] = verticesY[vidx];
		v[0 * 3 + 2] = verticesZ[vidx];
		vidx = model.getFaceIndices2()[face];
		v[1 * 3 + 0] = verticesX[vidx];
		v[1 * 3 + 1] = verticesY[vidx];
		v[1 * 3 + 2] = verticesZ[vidx];
		vidx = model.getFaceIndices3()[face];
		v[2 * 3 + 0] = verticesX[vidx];
		v[2 * 3 + 1] = verticesY[vidx];
		v[2 * 3 + 2] = verticesZ[vidx];

		float rad, cos, sin;
		float temp;
		if (modelOrientation % 2048 != 0) {
			// Reverse baked vertex rotation
			rad = modelOrientation * JAU_TO_RAD;
			cos = cos(rad);
			sin = sin(rad);

			for (int i = 0; i < 3; i++) {
				temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
				v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
				v[i * 3 + 2] = temp;
			}
		}

		for (int i = 0; i < 3; i++) {
			v[i * 3] = (v[i * 3] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i * 3 + 1] = (v[i * 3 + 1] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i * 3 + 2] = (v[i * 3 + 2] / LOCAL_TILE_SIZE + .5f) / uvScale;
		}

		// Compute face normal as cross(v[1] - v[0], v[2] - v[0])
		float nx = (v[3 + 1] - v[1]) * (v[6 + 2] - v[2]) - (v[3 + 2] - v[2]) * (v[6 + 1] - v[1]);
		float ny = (v[3 + 2] - v[2]) * (v[6 + 0] - v[0]) - (v[3 + 0] - v[0]) * (v[6 + 2] - v[2]);
		float nz = (v[3 + 0] - v[0]) * (v[6 + 1] - v[1]) - (v[3 + 1] - v[1]) * (v[6 + 0] - v[0]);
		float absNx = abs(nx);
		float absNy = abs(ny);
		float absNz = abs(nz);

		out[2] = out[6] = out[10] = 0;
		if (absNx > absNy && absNx > absNz) {
			// YZ plane
			float flip = sign(nx);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * -v[tri * 3 + 2];
				out[tri * 4 + 1] = v[tri * 3 + 1];
			}

			if (uvOrientationX % 2048 != 0) {
				rad = uvOrientationX * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i * 3] = out[j] - .5f;
					v[i * 3 + 2] = out[j + 1] - .5f;
					temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
					v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
					v[i * 3 + 2] = temp;
					out[j] = v[i * 3] + .5f;
					out[j + 1] = v[i * 3 + 2] + .5f;
				}
			}
		} else if (absNy > absNx && absNy > absNz) {
			// XZ
			float flip = sign(ny);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * -v[tri * 3];
				out[tri * 4 + 1] = v[tri * 3 + 2];
			}

			if (uvOrientationY % 2048 != 0) {
				rad = uvOrientationY * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i * 3] = out[j] - .5f;
					v[i * 3 + 2] = out[j + 1] - .5f;
					temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
					v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
					v[i * 3 + 2] = temp;
					out[j] = v[i * 3] + .5f;
					out[j + 1] = v[i * 3 + 2] + .5f;
				}
			}
		} else {
			// XY
			float flip = sign(nz);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * v[tri * 3];
				out[tri * 4 + 1] = v[tri * 3 + 1];
			}

			if (uvOrientationZ % 2048 != 0) {
				rad = uvOrientationZ * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i * 3] = out[j] - .5f;
					v[i * 3 + 2] = out[j + 1] - .5f;
					temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
					v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
					v[i * 3 + 2] = temp;
					out[j] = v[i * 3] + .5f;
					out[j + 1] = v[i * 3 + 2] + .5f;
				}
			}
		}
	}

	public void applyRotation(Model model) {
		switch (rotate) {
			case 0:
				break;
			case 90:
				model.rotateY90Ccw();
				break;
			case 180:
				model.rotateY180Ccw();
				break;
			case 270:
				model.rotateY270Ccw();
				break;
			default:
				log.debug(
					"Unsupported rotation of {} degrees in model override: '{}'",
					rotate,
					description
				);
				break;
		}
	}

	public void revertRotation(Model model) {
		switch (rotate) {
			case 90:
				model.rotateY270Ccw();
				break;
			case 180:
				model.rotateY180Ccw();
				break;
			case 270:
				model.rotateY90Ccw();
				break;
		}
	}

	public final ModelOverride testColorOverrides(int ahsl, boolean hideWaterEffects) {
		ModelOverride override = null;
		final long packedAhl = cachedColorOverrideAhsl;
		if (packedAhl != -1 && ahsl == (int) packedAhl)
			override = colorOverrides[(int) (packedAhl >> 32)];

		if (override == null) {
			final int len = colorOverrides.length;
			for (int i = 0; i < len; ++i) {
				final var inner = colorOverrides[i];
				if (inner.ahslCondition.test(ahsl)) {
					cachedColorOverrideAhsl = ahsl | (long) i << 32;
					override = inner;
					break;
				}
			}
		}

		if (!hideWaterEffects && override != null && override.isWaterEffect)
			override = null;

		return override;
	}
}
