package rs117.hd.scene.objects;

import com.google.gson.annotations.JsonAdapter;
import lombok.Data;
import lombok.Getter;
import rs117.hd.data.ObjectID;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.UvType;

import java.util.HashSet;
import java.util.Set;

@Getter
@Data
public class ObjectProperties
{
    @JsonAdapter(ObjectID.ObjectIDAdapter.class)
    public Set<Integer> objectIds = new HashSet<>();
    private String description;
    private Material material = Material.NONE;
    private boolean flatNormals = false;
    private UvType uvType = UvType.GEOMETRY;
    private TzHaarRecolorType tzHaarRecolorType = TzHaarRecolorType.NONE;
    private boolean inheritTileColor = false;

    public ObjectProperties(Material material) {
        this.objectIds = new HashSet<Integer>(){{ add(-1); }};
        this.material = material;
    }
}