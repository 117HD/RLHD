package rs117.hd.scene.areas;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import rs117.hd.scene.AreaManager;

public class Area {
	private static final ThreadLocal<AABB[]> SORT_SCRATCH = ThreadLocal.withInitial(() -> new AABB[16]);

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

	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] unhideAreas = {};

	public transient AABB[] aabbs;
	public transient AABB areaBounds;

	private transient AABB[] sortedAabbs;
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

		ArrayList<AABB> list = new ArrayList<>();

		if (rawAabbs != null)
			Collections.addAll(list, rawAabbs);

		if (regions != null)
			for (int regionId : regions)
				list.add(AABB.fromRegionId(regionId));

		if (regionBoxes != null)
			for (var box : regionBoxes)
				list.add(box.toAabb());

		if (areas != null) {
			for (String areaName : areas) {
				for (Area other : AreaManager.AREAS) {
					if (areaName.equals(other.name)) {
						other.normalize();
						Collections.addAll(list, other.aabbs);
						break;
					}
				}
			}
		}

		this.aabbs = list.toArray(AABB[]::new);

		// Compute bounds
		if (aabbs.length > 0) {
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;

			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;

			for (AABB aabb : aabbs) {
				minX = Math.min(minX, aabb.minX);
				minY = Math.min(minY, aabb.minY);
				minZ = Math.min(minZ, aabb.minZ);
				maxX = Math.max(maxX, aabb.maxX);
				maxY = Math.max(maxY, aabb.maxY);
				maxZ = Math.max(maxZ, aabb.maxZ);
			}

			areaBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

			if (aabbs.length > 4) {
				sortedAabbs = new AABB[4 * aabbs.length];

				buildCorner(0, areaBounds.minX, areaBounds.minY);
				buildCorner(1, areaBounds.minX, areaBounds.maxY);
				buildCorner(2, areaBounds.maxX, areaBounds.minY);
				buildCorner(3, areaBounds.maxX, areaBounds.maxY);
			}
		}

		if (unhideAreas == null)
			unhideAreas = new AABB[0];
	}

	private void buildCorner(int c, int cx, int cy) {
		AABB[] scratch = SORT_SCRATCH.get();
		if (scratch.length < aabbs.length)
			SORT_SCRATCH.set(scratch = new AABB[aabbs.length]);

		System.arraycopy(aabbs, 0, scratch, 0, aabbs.length);

		Arrays.sort(scratch, 0, aabbs.length, (a1, a2) -> {
			final float s1 = score(a1, cx, cy);
			final float s2 = score(a2, cx, cy);
			return Float.compare(s1, s2);
		});

		System.arraycopy(scratch, 0, sortedAabbs, c * aabbs.length, aabbs.length);
	}

	private static float score(AABB aabb, int cx, int cy) {
		float distance = aabb.distanceToPoint(cx, cy);

		int w = aabb.maxX - aabb.minX;
		int h = aabb.maxY - aabb.minY;
		int area = w * h;

		final float SIZE_WEIGHT = 1e-6F;
		return distance - (area * SIZE_WEIGHT);
	}

	private int getClosestCorner(int x, int y) {
		int dx = x - areaBounds.minX;
		int dy = y - areaBounds.minY;
		int best = dx * dx + dy * dy;
		int corner = 0;

		dx = x - areaBounds.minX;
		dy = y - areaBounds.maxY;
		int d = dx * dx + dy * dy;
		if (d < best) {
			best = d;
			corner = 1;
		}

		dx = x - areaBounds.maxX;
		dy = y - areaBounds.minY;
		d = dx * dx + dy * dy;
		if (d < best) {
			best = d;
			corner = 2;
		}

		dx = x - areaBounds.maxX;
		dy = y - areaBounds.maxY;
		d = dx * dx + dy * dy;
		if (d < best)
			corner = 3;

		return corner;
	}

	public boolean containsPoint(boolean includeUnhiding, int... worldPoint) {
		if (areaBounds != null && !areaBounds.contains(worldPoint))
			return false;

		final int length = aabbs.length;
		if (sortedAabbs != null && areaBounds != null) {
			final int corner = getClosestCorner(worldPoint[0], worldPoint[1]);
			final int offset = corner * length;

			for (int i = 0; i < length; i++) {
				if (sortedAabbs[offset + i].contains(worldPoint))
					return true;
			}
		} else {
			for (int i = 0; i < length; i++) {
				if (aabbs[i].contains(worldPoint))
					return true;
			}
		}

		if (includeUnhiding) {
			for (AABB aabb : unhideAreas) {
				if (aabb.contains(worldPoint))
					return true;
			}
		}

		return false;
	}

	public boolean containsPoint(int... worldPoint) {
		return containsPoint(true, worldPoint);
	}

	public boolean intersects(boolean includeUnhiding, int minX, int minY, int maxX, int maxY) {
		for (AABB aabb : aabbs)
			if (aabb.intersects(minX, minY, maxX, maxY))
				return true;

		if (includeUnhiding)
			for (AABB aabb : unhideAreas)
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
