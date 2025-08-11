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
	public float shadowOpacityThreshold = 0;
	public TzHaarRecolorType tzHaarRecolorType = TzHaarRecolorType.NONE;
	public InheritTileColorType inheritTileColorType = InheritTileColorType.NONE;
	public WindDisplacement windDisplacementMode = WindDisplacement.DISABLED;
	public int windDisplacementModifier = 0;
	public boolean invertDisplacementStrength = false;

	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] hideInAreas = {};

	public Map<Material, ModelOverride> materialOverrides;
	public ModelOverride[] colorOverrides;

	private JsonElement colors;

	public transient boolean isDummy;
	public transient Map<AABB, ModelOverride> areaOverrides;
	public transient AhslPredicate ahslCondition;

	@FunctionalInterface
	public interface AhslPredicate {
		boolean test(int ahsl);
	}

	public void normalize(VanillaShadowMode vanillaShadowMode) {
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

		if (areas == null)
			areas = new AABB[0];
		if (hideInAreas == null)
			hideInAreas = new AABB[0];

		if (materialOverrides != null) {
			var normalized = new HashMap<Material, ModelOverride>();
			for (var entry : materialOverrides.entrySet()) {
				var override = entry.getValue();
				override.normalize(vanillaShadowMode);
				normalized.put(entry.getKey(), override);
			}
			materialOverrides = normalized;
		}

		if (colorOverrides != null) {
			for (var override : colorOverrides) {
				override.normalize(vanillaShadowMode);
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
			if (vanillaShadowMode.retainInPvm)
				hideVanillaShadows = false;
			if (vanillaShadowMode == VanillaShadowMode.PREFER_IN_PVM && hideHdShadowsInPvm)
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
			shadowOpacityThreshold,
			tzHaarRecolorType,
			inheritTileColorType,
			windDisplacementMode,
			windDisplacementModifier,
			invertDisplacementStrength,
			hideInAreas,
			materialOverrides,
			colorOverrides,
			colors,
			isDummy,
			areaOverrides,
			ahslCondition
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

	public void fillUvsForFace(float[] out, Model model, int orientation, UvType uvType, int face) {
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
				computeBoxUvw(out, model, orientation, face);
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

	private void computeBoxUvw(float[] out, Model model, int modelOrientation, int face) {
		final float[][] vertexXYZ = {
			model.getVerticesX(),
			model.getVerticesY(),
			model.getVerticesZ()
		};
		final int[] triABC = {
			model.getFaceIndices1()[face],
			model.getFaceIndices2()[face],
			model.getFaceIndices3()[face]
		};

		float[][] v = new float[3][3];
		for (int tri = 0; tri < 3; tri++)
			for (int i = 0; i < 3; i++)
				v[tri][i] = vertexXYZ[i][triABC[tri]];

		float rad, cos, sin;
		float temp;
		if (modelOrientation % 2048 != 0) {
			// Reverse baked vertex rotation
			rad = modelOrientation * JAU_TO_RAD;
			cos = cos(rad);
			sin = sin(rad);

			for (int i = 0; i < 3; i++) {
				temp = v[i][0] * sin + v[i][2] * cos;
				v[i][0] = v[i][0] * cos - v[i][2] * sin;
				v[i][2] = temp;
			}
		}

		for (int i = 0; i < 3; i++) {
			v[i][0] = (v[i][0] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i][1] = (v[i][1] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i][2] = (v[i][2] / LOCAL_TILE_SIZE + .5f) / uvScale;
		}

		// Compute face normal
		float[] a = subtract(v[1], v[0]);
		float[] b = subtract(v[2], v[0]);
		float[] n = cross(a, b);
		float[] absN = abs(n);

		out[2] = out[6] = out[10] = 0;
		if (absN[0] > absN[1] && absN[0] > absN[2]) {
			// YZ plane
			float flip = sign(n[0]);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * -v[tri][2];
				out[tri * 4 + 1] = v[tri][1];
			}

			if (uvOrientationX % 2048 != 0) {
				rad = uvOrientationX * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i][0] = out[j] - .5f;
					v[i][2] = out[j + 1] - .5f;
					temp = v[i][0] * sin + v[i][2] * cos;
					v[i][0] = v[i][0] * cos - v[i][2] * sin;
					v[i][2] = temp;
					out[j] = v[i][0] + .5f;
					out[j + 1] = v[i][2] + .5f;
				}
			}
		} else if (absN[1] > absN[0] && absN[1] > absN[2]) {
			// XZ
			float flip = sign(n[1]);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * -v[tri][0];
				out[tri * 4 + 1] = v[tri][2];
			}

			if (uvOrientationY % 2048 != 0) {
				rad = uvOrientationY * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i][0] = out[j] - .5f;
					v[i][2] = out[j + 1] - .5f;
					temp = v[i][0] * sin + v[i][2] * cos;
					v[i][0] = v[i][0] * cos - v[i][2] * sin;
					v[i][2] = temp;
					out[j] = v[i][0] + .5f;
					out[j + 1] = v[i][2] + .5f;
				}
			}
		} else {
			// XY
			float flip = sign(n[2]);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * v[tri][0];
				out[tri * 4 + 1] = v[tri][1];
			}

			if (uvOrientationZ % 2048 != 0) {
				rad = uvOrientationZ * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i][0] = out[j] - .5f;
					v[i][2] = out[j + 1] - .5f;
					temp = v[i][0] * sin + v[i][2] * cos;
					v[i][0] = v[i][0] * cos - v[i][2] * sin;
					v[i][2] = temp;
					out[j] = v[i][0] + .5f;
					out[j + 1] = v[i][2] + .5f;
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
}
