package rs117.hd.scene.areas;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.*;
import rs117.hd.utils.GsonUtils;

public class Polygon {

	private final ImmutablePolygon immutablePolygon;
	// Cache bounds to avoid Rectangle allocation
	private final int boundsMinX, boundsMinY, boundsMaxX, boundsMaxY;
	private final int level; // Z coordinate/height/plane, or -1 if not specified

	public Polygon(int[][] nPoints) {
		int[] xPoints = new int[nPoints.length];
		int[] yPoints = new int[nPoints.length];
		int level = -1;

		// Extract and validate Z coordinate
		for (int i = 0; i < nPoints.length; i++) {
			xPoints[i] = nPoints[i][0];
			yPoints[i] = nPoints[i][1];
			if (nPoints[i].length >= 3) {
				int z = nPoints[i][2];
				if (level == -1) {
					level = z;
				} else if (level != z) {
					throw new IllegalArgumentException(
						"All polygon points must have the same Z coordinate. Found " + level + " and " + z);
				}
			}
		}

		this.immutablePolygon = new ImmutablePolygon(xPoints, yPoints, nPoints.length);
		// Cache bounds directly to avoid Rectangle allocation
		java.awt.Rectangle bounds = this.immutablePolygon.getBounds();
		this.boundsMinX = bounds.x;
		this.boundsMinY = bounds.y;
		this.boundsMaxX = bounds.x + bounds.width;
		this.boundsMaxY = bounds.y + bounds.height;
		this.level = level;
	}

	public Polygon(List<WorldPoint> points) {
		int size = points.size();
		int[] xPoints = new int[size];
		int[] yPoints = new int[size];
		int level = -1;

		for (int i = 0; i < size; i++) {
			WorldPoint p = points.get(i);
			xPoints[i] = p.getX();
			yPoints[i] = p.getY();
			int z = p.getPlane();
			if (level == -1) {
				level = z;
			} else if (level != z) {
				throw new IllegalArgumentException(
					"All polygon points must have the same Z coordinate. Found " + level + " and " + z);
			}
		}

		this.immutablePolygon = new ImmutablePolygon(xPoints, yPoints, size);
		// Cache bounds directly to avoid Rectangle allocation
		java.awt.Rectangle bounds = this.immutablePolygon.getBounds();
		this.boundsMinX = bounds.x;
		this.boundsMinY = bounds.y;
		this.boundsMaxX = bounds.x + bounds.width;
		this.boundsMaxY = bounds.y + bounds.height;
		this.level = level;
	}

	public boolean contains(int x, int y) {
		return immutablePolygon.containsFast(x, y);
	}

	public boolean contains(int x, int y, int z) {
		// If polygon has a level specified, check it matches
		if (level != -1 && level != z) {
			return false;
		}
		return contains(x, y);
	}

	public boolean contains(WorldPoint position) {
		return contains(position.getX(), position.getY(), position.getPlane());
	}

	public boolean contains(int... pos) {
		if (pos.length < 2) {
			return false;
		}
		if (pos.length >= 3) {
			return contains(pos[0], pos[1], pos[2]);
		}
		return contains(pos[0], pos[1]);
	}

	public boolean intersects(int minX, int minY, int maxX, int maxY) {
		// Use cached bounds instead of creating Rectangle
		if (maxX < boundsMinX || minX > boundsMaxX ||
			maxY < boundsMinY || minY > boundsMaxY) {
			return false;
		}

		// Check corners first (fast early exit)
		if (contains(minX, minY) || contains(maxX, minY) ||
			contains(minX, maxY) || contains(maxX, maxY)) {
			return true;
		}

		// Check center point
		int centerX = (minX + maxX) / 2;
		int centerY = (minY + maxY) / 2;
		if (contains(centerX, centerY)) {
			return true;
		}

		// Access arrays directly from ImmutablePolygon to avoid allocation
		java.awt.Polygon poly = immutablePolygon;
		int nPoints = poly.npoints;
		int[] xPoints = poly.xpoints;
		int[] yPoints = poly.ypoints;

		// Check if any polygon vertex is inside the rectangle
		for (int i = 0; i < nPoints; i++) {
			int px = xPoints[i];
			int py = yPoints[i];
			if (px >= minX && px <= maxX && py >= minY && py <= maxY) {
				return true;
			}
		}

		// Check edge intersections
		for (int i = 0; i < nPoints; i++) {
			int x1 = xPoints[i];
			int y1 = yPoints[i];
			int x2 = xPoints[(i + 1) % nPoints];
			int y2 = yPoints[(i + 1) % nPoints];

			// Check if polygon edge intersects rectangle edges
			if (lineIntersectsRect(x1, y1, x2, y2, minX, minY, maxX, maxY)) {
				return true;
			}
		}

		// Sample points if rectangle is small enough (avoid checking every point for large rectangles)
		int width = maxX - minX + 1;
		int height = maxY - minY + 1;
		if (width * height <= 100) {
			// Small rectangle - check all points
			for (int x = minX; x <= maxX; x++) {
				for (int y = minY; y <= maxY; y++) {
					if (contains(x, y)) {
						return true;
					}
				}
			}
		} else {
			int stepX = Math.max(1, width / 10);
			int stepY = Math.max(1, height / 10);
			for (int x = minX; x <= maxX; x += stepX) {
				for (int y = minY; y <= maxY; y += stepY) {
					if (contains(x, y)) {
						return true;
					}
				}
			}
		}

		return false;
	}


	private boolean lineIntersectsRect(int x1, int y1, int x2, int y2, int minX, int minY, int maxX, int maxY) {
		// Quick rejection - if both endpoints are outside on same side, no intersection
		if ((x1 < minX && x2 < minX) || (x1 > maxX && x2 > maxX) ||
			(y1 < minY && y2 < minY) || (y1 > maxY && y2 > maxY)) {
			return false;
		}

		// If one endpoint is inside, there's intersection
		if ((x1 >= minX && x1 <= maxX && y1 >= minY && y1 <= maxY) ||
			(x2 >= minX && x2 <= maxX && y2 >= minY && y2 <= maxY)) {
			return true;
		}

		// Check if line segment intersects rectangle edges using parametric line equation
		int dx = x2 - x1;
		int dy = y2 - y1;

		// Avoid division by zero checks - compute once and reuse
		if (dx != 0) {
			// Check intersection with left edge (x = minX)
			double tLeft = (minX - x1) / (double) dx;
			if (tLeft >= 0 && tLeft <= 1) {
				double y = y1 + tLeft * dy;
				if (y >= minY && y <= maxY) {
					return true;
				}
			}

			// Check intersection with right edge (x = maxX)
			double tRight = (maxX - x1) / (double) dx;
			if (tRight >= 0 && tRight <= 1) {
				double y = y1 + tRight * dy;
				if (y >= minY && y <= maxY) {
					return true;
				}
			}
		}

		if (dy != 0) {
			// Check intersection with bottom edge (y = minY)
			double tBottom = (minY - y1) / (double) dy;
			if (tBottom >= 0 && tBottom <= 1) {
				double x = x1 + tBottom * dx;
				if (x >= minX && x <= maxX) {
					return true;
				}
			}

			// Check intersection with top edge (y = maxY)
			double tTop = (maxY - y1) / (double) dy;
			if (tTop >= 0 && tTop <= 1) {
				double x = x1 + tTop * dx;
				if (x >= minX && x <= maxX) {
					return true;
				}
			}
		}

		return false;
	}

	public boolean intersects(AABB... otherAabbs) {
		for (AABB aabb : otherAabbs) {
			// Always use 2D intersection (X and Y only, ignore Z)
			if (intersects(aabb.minX, aabb.minY, aabb.maxX, aabb.maxY)) {
				return true;
			}
		}
		return false;
	}


	public int[][] getPoints() {
		java.awt.Polygon poly = immutablePolygon;
		int nPoints = poly.npoints;
		int[][] points;
		if (level != -1) {
			points = new int[nPoints][3];
			for (int i = 0; i < nPoints; i++) {
				points[i][0] = poly.xpoints[i];
				points[i][1] = poly.ypoints[i];
				points[i][2] = level;
			}
		} else {
			points = new int[nPoints][2];
			for (int i = 0; i < nPoints; i++) {
				points[i][0] = poly.xpoints[i];
				points[i][1] = poly.ypoints[i];
			}
		}
		return points;
	}

	public int getLevel() {
		return level;
	}

	@Slf4j
	public static class Adapter extends TypeAdapter<Polygon> {
		@Override
		public Polygon read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL) {
				in.nextNull();
				return null;
			}

			in.beginArray();
			ArrayList<int[]> points = new ArrayList<>();

			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
				if (in.peek() == JsonToken.NULL) {
					in.skipValue();
					continue;
				}

				in.beginArray();
				int[] point = new int[3]; // Allow up to 3 coordinates
				int i = 0;
				while (in.hasNext()) {
					switch (in.peek()) {
						case NUMBER:
							if (i >= 3) {
								throw new IOException(
									"Too many coordinates in polygon point (> 3) at " + GsonUtils.location(in));
							}
							point[i++] = in.nextInt();
							break;
						case END_ARRAY:
							break;
						case NULL:
							in.skipValue();
							continue;
						default:
							throw new IOException(
								"Malformed polygon point. Unexpected token: " + in.peek() + " at " + GsonUtils.location(in));
					}
				}
				in.endArray();

				if (i < 2) {
					throw new IOException(
						"Polygon point must have at least 2 coordinates (x, y) at " + GsonUtils.location(in));
				}

				// Store point with actual number of coordinates
				int[] finalPoint = i == 2 ? new int[] { point[0], point[1] } : new int[] { point[0], point[1], point[2] };
				points.add(finalPoint);
			}
			in.endArray();

			if (points.isEmpty()) {
				return null;
			}

			int[][] pointsArray = points.toArray(new int[0][]);

			return new Polygon(pointsArray);
		}

		@Override
		public void write(JsonWriter out, Polygon polygon) throws IOException {
			if (polygon == null) {
				out.nullValue();
				return;
			}

			int[][] points = polygon.getPoints();
			int level = polygon.level;
			out.beginArray();
			for (int[] point : points) {
				out.beginArray();
				out.value(point[0]); // x
				out.value(point[1]); // y
				if (level != -1) {
					out.value(level); // z/height if specified
				}
				out.endArray();
			}
			out.endArray();
		}
	}
}