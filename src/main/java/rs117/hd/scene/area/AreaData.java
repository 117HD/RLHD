package rs117.hd.scene.area;

import com.google.gson.annotations.JsonAdapter;
import lombok.NoArgsConstructor;
import rs117.hd.utils.AABB;

@NoArgsConstructor
public class AreaData {
	public String description = "UNKNOWN";
	@JsonAdapter(AABB.JsonAdapter.class)
    public AABB[] aabbs = {};
	public int region = -1;
	public boolean hideOtherRegions = true;
    public HorizonTile horizonTile = null;
}
