package rs117.hd.scene.model_overrides;

import com.google.gson.annotations.JsonAdapter;
import lombok.NoArgsConstructor;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import rs117.hd.data.NpcID;
import rs117.hd.data.ObjectID;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.UvType;
import rs117.hd.utils.AABB;

import java.util.HashSet;
import java.util.Set;

@NoArgsConstructor
public class ModelOverride
{
    public static ModelOverride NONE = new ModelOverride();

    private static final Set<Integer> EMPTY = new HashSet<>();

    public String description = "UNKNOWN";

    @JsonAdapter(NpcID.JsonAdapter.class)
    public Set<Integer> npcIds = EMPTY;
    @JsonAdapter(ObjectID.JsonAdapter.class)
    public Set<Integer> objectIds = EMPTY;

    public Material baseMaterial = Material.NONE;
    public Material textureMaterial = Material.NONE;
    public UvType uvType = UvType.VANILLA;
    public float uvScale = 1;
    public int uvOrientation = 0;
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
	}

    public void computeModelUvw(float[] out, int i, float x, float y, float z, int orientation) {
        double rad, cos, sin;
        float temp;
        if (orientation % 2048 != 0) {
            // Reverse baked vertex rotation
            rad = orientation * Perspective.UNIT;
            cos = Math.cos(rad);
            sin = Math.sin(rad);
            temp = (float) (x * sin + z * cos);
            x = (float) (x * cos - z * sin);
            z = temp;
        }

        x = (x / Perspective.LOCAL_TILE_SIZE + .5f) / uvScale;
        y = (y / Perspective.LOCAL_TILE_SIZE + .5f) / uvScale;
        z = (z / Perspective.LOCAL_TILE_SIZE + .5f) / uvScale;

        uvType.computeModelUvw(out, i, x, y, z);

        if (uvOrientation % 2048 != 0) {
            rad = uvOrientation * Perspective.UNIT;
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
			case VANILLA: {
				final int[] vertexX = model.getVerticesX();
				final int[] vertexY = model.getVerticesY();
				final int[] vertexZ = model.getVerticesZ();
				final int texFace = model.getTextureFaces()[face] & 0xff;

				if (texFace != 255) {
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
					break;
				}
				// fall back to geometry UVs
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
}
