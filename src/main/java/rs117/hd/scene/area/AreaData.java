package rs117.hd.scene.area;

import com.google.gson.annotations.JsonAdapter;
import lombok.NoArgsConstructor;
import rs117.hd.utils.AABB;

@NoArgsConstructor
public class AreaData {

    @JsonAdapter(AABB.JsonAdapter.class)
    public AABB[] aabbs = {};
    public boolean hideOtherRegions = false;
    public String description = "UNKNOWN";
    public HorizonTile horizonTile = null;

}