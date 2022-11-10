package rs117.hd.scene.area;

import com.google.gson.annotations.JsonAdapter;
import lombok.Data;
import lombok.NoArgsConstructor;
import rs117.hd.utils.AABB;

@NoArgsConstructor
public class AreaData {

    @JsonAdapter(AABB.JsonAdapter.class)
    public AABB[] aabbs = {};
    public boolean hideOtherRegions = false;
    public String description = "UNKNOWN";
    public LargeTile largeTile = null;

}

@Data
class LargeTile {
    private final String material;
    private final String materialBelow;
    private final Boolean isOverlay;
    private final String waterType;
    private final int height;
    private final int color;
}

