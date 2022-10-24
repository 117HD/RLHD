package rs117.hd.scene.model_overrides;

import com.google.gson.annotations.JsonAdapter;
import lombok.NoArgsConstructor;
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
    public boolean flatNormals = false;
    public boolean removeBakedLighting = false;
    public boolean disableShadows = false;
    public TzHaarRecolorType tzHaarRecolorType = TzHaarRecolorType.NONE;
    public InheritTileColorType inheritTileColorType = InheritTileColorType.NONE;

    @JsonAdapter(AABB.JsonAdapter.class)
    public AABB[] hideInAreas = {};

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
}
