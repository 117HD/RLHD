package rs117.hd.scene.areas;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import net.runelite.api.coords.*;
import rs117.hd.scene.AreaManager;

public class Area {
	public static final Area NONE = new Area("NONE", 0, 0, 0, 0);
	public static final Area ALL = new Area("ALL", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
	public static Area OVERWORLD = NONE;

	public String name;
	public boolean hideOtherAreas;
	public boolean fillGaps = true;

	public String[] areas;
	public int[] regions;
	@JsonAdapter(RegionBox.Adapter.class)
	public RegionBox[] regionBoxes;
	@JsonAdapter(AABB.ArrayAdapter.class)
	@SerializedName("aabbs")
	public AABB[] rawAabbs;

	@SerializedName("polyAreas")
	@JsonAdapter(Polygon.Adapter.class)
	public Polygon polygon;
	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] unhideAreas = {};

	public transient AABB[] aabbs;
	private transient boolean normalized;

	public Area(String name) {
		this.name = name;
	}

	public Area(String name, int x1, int y1, int x2, int y2) {
		this(name);
		aabbs = new AABB[] { new AABB(x1, y1, x2, y2) };
	}

	public void normalize() {
		if (normalized)
			return;
		normalized = true;

		if (polygon != null) {
			boolean hasAabbFields = (rawAabbs != null && rawAabbs.length > 0) ||
				(regions != null && regions.length > 0) ||
				(regionBoxes != null && regionBoxes.length > 0) ||
				(areas != null && areas.length > 0);
			
			if (hasAabbFields) {
				throw new IllegalStateException(
					"Area '" + name + "' cannot have both polygon and AABB fields (aabbs, regions, regionBoxes, or areas) set");
			}
		}

		ArrayList<AABB> aabbs = new ArrayList<>();
		if (rawAabbs != null)
			Collections.addAll(aabbs, rawAabbs);
		if (regions != null)
			for (int regionId : regions)
				aabbs.add(AABB.fromRegionId(regionId));
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

		if (unhideAreas == null)
			unhideAreas = new AABB[0];
	}

	public boolean containsPoint(boolean includeUnhiding, int... worldPoint) {
		if (polygon != null) {
			boolean result = polygon.contains(worldPoint);
			if (includeUnhiding) {
				for (var aabb : unhideAreas)
					if (aabb.contains(worldPoint))
						return true;
			}
			return result;
		}

		for (var aabb : aabbs)
			if (aabb.contains(worldPoint))
				return true;
		if (includeUnhiding)
			for (var aabb : unhideAreas)
				if (aabb.contains(worldPoint))
					return true;
		return false;
	}

	public boolean containsPoint(int... worldPoint) {
		return containsPoint(true, worldPoint);
	}

	public boolean intersects(boolean includeUnhiding, int minX, int minY, int maxX, int maxY) {
		if (polygon != null) {
			return polygon.intersects(minX, minY, maxX, maxY);
		}

		for (AABB aabb : aabbs)
			if (aabb.intersects(minX, minY, maxX, maxY))
				return true;
		if (includeUnhiding)
			for (var aabb : unhideAreas)
				if (aabb.intersects(minX, minY, maxX, maxY))
					return true;
		return false;
	}

	public boolean intersects(Area otherArea) {
		if (otherArea == null)
			return false;
		return intersects(otherArea.aabbs);
	}

	public boolean intersects(AABB... otherAabbs) {
		if (polygon != null) {
			return polygon.intersects(otherAabbs);
		}

		for (AABB aabb : aabbs)
			if (aabb.intersects(otherAabbs))
				return true;
		return false;
	}

	@Override
	public String toString() {
		return name;
	}
}
