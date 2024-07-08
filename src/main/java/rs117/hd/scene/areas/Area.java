package rs117.hd.scene.areas;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import rs117.hd.scene.AreaManager;

public class Area {
	public static final Area NONE = new Area("NONE", 0, 0, 0, 0);
	public static final Area ALL = new Area("ALL", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
	public static Area OVERWORLD = NONE;

	public String name;
	public boolean hideOtherAreas;

	private String[] areas;
	private int[] regions;
	@JsonAdapter(RegionBox.JsonAdapter.class)
	private RegionBox[] regionBoxes;
	@JsonAdapter(AABB.JsonAdapter.class)
	@SerializedName("aabbs")
	private AABB[] rawAabbs;

	public transient AABB[] aabbs;
	private transient boolean normalized;

	public Area(String name, int x1, int y1, int x2, int y2) {
		this.name = name;
		aabbs = new AABB[] { new AABB(x1, y1, x2, y2) };
	}

	public void normalize() {
		if (normalized)
			return;
		normalized = true;

		ArrayList<AABB> aabbs = new ArrayList<>();
		if (rawAabbs != null)
			Collections.addAll(aabbs, rawAabbs);
		if (regions != null)
			for (int regionId : regions)
				aabbs.add(new AABB(regionId));
		if (regionBoxes != null)
			for (var box : regionBoxes)
				aabbs.add(box.toAabb());
		if (areas != null) {
			for (String area : areas) {
				for (Area other : AreaManager.AREAS) {
					if (area.equals(other.name)) {
						other.normalize();
						Collections.addAll(aabbs, other.aabbs);
						break;
					}
				}
			}
		}

		this.aabbs = aabbs.toArray(AABB[]::new);

		// Free up a bit of memory
		areas = null;
		regions = null;
		regionBoxes = null;
		rawAabbs = null;
	}

	public boolean containsPoint(int worldX, int worldY, int plane) {
		for (AABB aabb : aabbs) {
			if (aabb.contains(worldX, worldY, plane)) {
				return true;
			}
		}
		return false;
	}

	public boolean containsPoint(int[] worldPoint) {
		return containsPoint(worldPoint[0], worldPoint[1], worldPoint[2]);
	}

	public boolean intersects(Area otherArea) {
		if (otherArea == null)
			return false;
		for (AABB other : otherArea.aabbs)
			for (AABB self : aabbs)
				if (self.intersects(other))
					return true;
		return false;
	}
}
