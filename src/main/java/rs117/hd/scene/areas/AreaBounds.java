package rs117.hd.scene.areas;

import net.runelite.api.coords.WorldPoint;

/**
 * Common interface for area boundary definitions.
 * Both AABB (rectangular boxes) and PolygonAABB (polygon points) implement this interface,
 * allowing them to be used interchangeably in the Area system without forcing inheritance.
 */
public interface AreaBounds {
	/**
	 * Checks if a world point is contained within this boundary.
	 * @param worldPos World position as [x, y] or [x, y, z]
	 * @return true if the point is contained, false otherwise
	 */
	boolean contains(int... worldPos);

	/**
	 * Checks if a world point is contained within this boundary.
	 * @param location WorldPoint to check
	 * @return true if the point is contained, false otherwise
	 */
	boolean contains(WorldPoint location);

	/**
	 * Checks if this boundary intersects with a rectangular region.
	 * @param minX Minimum X coordinate
	 * @param minY Minimum Y coordinate
	 * @param maxX Maximum X coordinate
	 * @param maxY Maximum Y coordinate
	 * @return true if the boundary intersects the region, false otherwise
	 */
	boolean intersects(int minX, int minY, int maxX, int maxY);

	/**
	 * Gets the minimum X coordinate of the bounding box.
	 * @return minimum X coordinate
	 */
	int getMinX();

	/**
	 * Gets the minimum Y coordinate of the bounding box.
	 * @return minimum Y coordinate
	 */
	int getMinY();

	/**
	 * Gets the maximum X coordinate of the bounding box.
	 * @return maximum X coordinate
	 */
	int getMaxX();

	/**
	 * Gets the maximum Y coordinate of the bounding box.
	 * @return maximum Y coordinate
	 */
	int getMaxY();
}

