package rs117.hd.scene.objects;

import com.google.gson.annotations.JsonAdapter;
import rs117.hd.data.ObjectID;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.UvType;

import java.util.HashSet;
import java.util.Set;

public class ObjectProperties
{
    public final String description = "UNKNOWN";
    public Material material = Material.NONE;
    @JsonAdapter(ObjectID.JsonAdapter.class)
    public Set<Integer> objectIds = new HashSet<>();
    public final boolean flatNormals = false;
    public final UvType uvType = UvType.GEOMETRY;
    public final TzHaarRecolorType tzHaarRecolorType = TzHaarRecolorType.NONE;
    public final boolean inheritTileColor = false;

    public ObjectProperties(Material material) {
        this.objectIds = new HashSet<Integer>(){{ add(-1); }};
        this.material = material;
    }
}