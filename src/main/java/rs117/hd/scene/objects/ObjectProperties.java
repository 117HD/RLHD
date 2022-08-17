package rs117.hd.scene.objects;

import com.google.gson.annotations.JsonAdapter;
import lombok.NoArgsConstructor;
import rs117.hd.data.ObjectID;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.UvType;

import java.util.HashSet;
import java.util.Set;

@NoArgsConstructor
public class ObjectProperties
{
    public final String description = "UNKNOWN";
    public Material material = Material.NONE;
    @JsonAdapter(ObjectID.JsonAdapter.class)
    public Set<Integer> objectIds = new HashSet<>();
    public boolean flatNormals = false;
    public UvType uvType = UvType.GEOMETRY;
    public TzHaarRecolorType tzHaarRecolorType = TzHaarRecolorType.NONE;
    public boolean inheritTileColor = false;

    public ObjectProperties(Material material) {
        this.objectIds = new HashSet<Integer>(){{ add(-1); }};
        this.material = material;
    }

}