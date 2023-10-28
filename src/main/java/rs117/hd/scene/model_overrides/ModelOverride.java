package rs117.hd.scene.model_overrides;

import com.google.gson.annotations.JsonAdapter;
import java.util.HashSet;
import java.util.Set;
import lombok.NoArgsConstructor;
import net.runelite.api.*;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.UvType;
import rs117.hd.utils.AABB;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.HDUtils;

import static net.runelite.api.Perspective.*;

@NoArgsConstructor
public class ModelOverride
{
    public static ModelOverride NONE = new ModelOverride();

	private static final Set<Integer> EMPTY = new HashSet<>();

	public String description = "UNKNOWN";

	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public Set<Integer> npcIds = EMPTY;
	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public Set<Integer> objectIds = EMPTY;

	public Material baseMaterial = Material.NONE;
	public Material textureMaterial = Material.NONE;
	public UvType uvType = UvType.VANILLA;
	public float uvScale = 1;
	public int uvOrientation = 0;
	public int uvOrientationX = 0;
	public int uvOrientationY = 0;
	public int uvOrientationZ = 0;
	public boolean retainVanillaUvs = true;
	public boolean forceOverride = false;
	public boolean flatNormals = false;
	public boolean removeBakedLighting = false;
	public boolean castShadows = true;
	public boolean receiveShadows = true;
	public float shadowOpacityThreshold = 0;
	public TzHaarRecolorType tzHaarRecolorType = TzHaarRecolorType.NONE;
	public InheritTileColorType inheritTileColorType = InheritTileColorType.NONE;

	@JsonAdapter(AABB.JsonAdapter.class)
	public AABB[] hideInAreas = {};

	public void gsonReallyShouldSupportThis() {
		// Ensure there are no nulls in case of invalid configuration during development
		if (baseMaterial == null)
			baseMaterial = ModelOverride.NONE.baseMaterial;
		if (textureMaterial == null)
			textureMaterial = ModelOverride.NONE.textureMaterial;
		if (uvType == null)
			uvType = ModelOverride.NONE.uvType;
		if (tzHaarRecolorType == null)
			tzHaarRecolorType = ModelOverride.NONE.tzHaarRecolorType;
		if (inheritTileColorType == null)
			inheritTileColorType = ModelOverride.NONE.inheritTileColorType;
		if (hideInAreas == null)
			hideInAreas = new AABB[0];
		if (!castShadows && shadowOpacityThreshold == 0)
			shadowOpacityThreshold = 1;
		if (uvOrientationX == 0)
			uvOrientationX = uvOrientation;
		if (uvOrientationY == 0)
			uvOrientationY = uvOrientation;
		if (uvOrientationZ == 0)
			uvOrientationZ = uvOrientation;
	}

    public void computeModelUvw(float[] out, int i, float x, float y, float z, int orientation) {
		double rad, cos, sin;
		float temp;
		if (orientation % 2048 != 0) {
			// Reverse baked vertex rotation
			rad = orientation * UNIT;
			cos = Math.cos(rad);
			sin = Math.sin(rad);
			temp = (float) (x * sin + z * cos);
			x = (float) (x * cos - z * sin);
			z = temp;
		}

		x = (x / LOCAL_TILE_SIZE + .5f) / uvScale;
		y = (y / LOCAL_TILE_SIZE + .5f) / uvScale;
		z = (z / LOCAL_TILE_SIZE + .5f) / uvScale;

		uvType.computeModelUvw(out, i, x, y, z);

		if (uvOrientation % 2048 != 0) {
			rad = uvOrientation * UNIT;
			cos = Math.cos(rad);
			sin = Math.sin(rad);
			x = out[i] - .5f;
			z = out[i + 1] - .5f;
			temp = (float) (x * sin + z * cos);
			x = (float) (x * cos - z * sin);
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
				final int[] vertexX = model.getVerticesX();
				final int[] vertexY = model.getVerticesY();
				final int[] vertexZ = model.getVerticesZ();
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

					final int[] vertexX = model.getVerticesX();
					final int[] vertexY = model.getVerticesY();
					final int[] vertexZ = model.getVerticesZ();
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
		final int[][] vertexXYZ = {
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

		double rad, cos, sin;
		float temp;
		if (modelOrientation % 2048 != 0) {
			// Reverse baked vertex rotation
			rad = modelOrientation * UNIT;
			cos = Math.cos(rad);
			sin = Math.sin(rad);

			for (int i = 0; i < 3; i++) {
				temp = (float) (v[i][0] * sin + v[i][2] * cos);
				v[i][0] = (float) (v[i][0] * cos - v[i][2] * sin);
				v[i][2] = temp;
			}
		}

		for (int i = 0; i < 3; i++) {
			v[i][0] = (v[i][0] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i][1] = (v[i][1] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i][2] = (v[i][2] / LOCAL_TILE_SIZE + .5f) / uvScale;
		}

		// Compute face normal
		float[] a = new float[3];
		float[] b = new float[3];
		HDUtils.subtract(a, v[1], v[0]);
		HDUtils.subtract(b, v[2], v[0]);
		float[] n = new float[3];
		HDUtils.cross(n, a, b);
		float[] absN = HDUtils.abs(a, n);

		out[2] = out[6] = out[10] = 0;
		if (absN[0] > absN[1] && absN[0] > absN[2]) {
			// YZ plane
			float flip = Math.signum(n[0]);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * -v[tri][2];
				out[tri * 4 + 1] = v[tri][1];
			}

			if (uvOrientationX % 2048 != 0) {
				rad = uvOrientationX * UNIT;
				cos = Math.cos(rad);
				sin = Math.sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i][0] = out[j] - .5f;
					v[i][2] = out[j + 1] - .5f;
					temp = (float) (v[i][0] * sin + v[i][2] * cos);
					v[i][0] = (float) (v[i][0] * cos - v[i][2] * sin);
					v[i][2] = temp;
					out[j] = v[i][0] + .5f;
					out[j + 1] = v[i][2] + .5f;
				}
			}
		} else if (absN[1] > absN[0] && absN[1] > absN[2]) {
			// XZ
			float flip = Math.signum(n[1]);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * -v[tri][0];
				out[tri * 4 + 1] = v[tri][2];
			}

			if (uvOrientationY % 2048 != 0) {
				rad = uvOrientationY * UNIT;
				cos = Math.cos(rad);
				sin = Math.sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i][0] = out[j] - .5f;
					v[i][2] = out[j + 1] - .5f;
					temp = (float) (v[i][0] * sin + v[i][2] * cos);
					v[i][0] = (float) (v[i][0] * cos - v[i][2] * sin);
					v[i][2] = temp;
					out[j] = v[i][0] + .5f;
					out[j + 1] = v[i][2] + .5f;
				}
			}
		} else {
			// XY
			float flip = Math.signum(n[2]);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * v[tri][0];
				out[tri * 4 + 1] = v[tri][1];
			}

			if (uvOrientationZ % 2048 != 0) {
				rad = uvOrientationZ * UNIT;
				cos = Math.cos(rad);
				sin = Math.sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i][0] = out[j] - .5f;
					v[i][2] = out[j + 1] - .5f;
					temp = (float) (v[i][0] * sin + v[i][2] * cos);
					v[i][0] = (float) (v[i][0] * cos - v[i][2] * sin);
					v[i][2] = temp;
					out[j] = v[i][0] + .5f;
					out[j + 1] = v[i][2] + .5f;
				}
			}
		}
	}
}
