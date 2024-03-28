package rs117.hd.data;

import com.google.gson.annotations.JsonAdapter;
import lombok.NoArgsConstructor;
import rs117.hd.utils.AABB;

@NoArgsConstructor
public class AreaDefinition {
	// TODO: Unify Area and AreaDefinition, ideally without losing auto completion of areas
	public String description = "UNKNOWN";
	@JsonAdapter(AABB.JsonAdapter.class)
	public AABB[] aabbs = {};
	public int region = -1;
	public boolean hideOtherRegions = true;

}