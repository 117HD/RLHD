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
	private final int[] levels;
	private final boolean hasLevels;
	private final int singleLevel; // Single level uses equality check instead of array scan

	public Polygon(int[][] nPoints, int... levels) {
		int[] xPoints = new int[nPoints.length];
		int[] yPoints = new int[nPoints.length];

		for (int i = 0; i < nPoints.length; i++) {
			xPoints[i] = nPoints[i][0];
			yPoints[i] = nPoints[i][1];
		}

		this.immutablePolygon = new ImmutablePolygon(xPoints, yPoints, nPoints.length);
		
		if (levels.length == 0) {
			this.levels = new int[0];
			this.hasLevels = false;
			this.singleLevel = 0;
		} else if (levels.length == 1) {
			this.levels = null;
			this.hasLevels = true;
			this.singleLevel = levels[0];
		} else {
			this.levels = levels.clone();
			this.hasLevels = true;
			this.singleLevel = 0;
		}
	}

	public Polygon(List<WorldPoint> points, int... levels) {
		int size = points.size();
		int[] xPoints = new int[size];
		int[] yPoints = new int[size];

		if (levels.length > 0) {
			if (levels.length == 1) {
				this.levels = null;
				this.hasLevels = true;
				this.singleLevel = levels[0];
			} else {
				this.levels = levels.clone();
				this.hasLevels = true;
				this.singleLevel = 0;
			}
		} else {
			// Extract levels from WorldPoints - no autoboxing
			int[] tempLevels = new int[size];
			for (int i = 0; i < size; i++) {
				tempLevels[i] = points.get(i).getPlane();
			}
			int[] distinct = distinctSorted(tempLevels);
			
			if (distinct.length == 1) {
				this.levels = null;
				this.hasLevels = true;
				this.singleLevel = distinct[0];
			} else if (distinct.length > 1) {
				this.levels = distinct;
				this.hasLevels = true;
				this.singleLevel = 0;
			} else {
				this.levels = new int[0];
				this.hasLevels = false;
				this.singleLevel = 0;
			}
		}

		for (int i = 0; i < size; i++) {
			WorldPoint p = points.get(i);
			xPoints[i] = p.getX();
			yPoints[i] = p.getY();
		}

		this.immutablePolygon = new ImmutablePolygon(xPoints, yPoints, size);
	}

	// Primitive-only deduplication - faster than HashSet
	private static int[] distinctSorted(int[] input) {
		if (input.length <= 1) {
			return input.length == 0 ? new int[0] : new int[] { input[0] };
		}

		int[] sorted = input.clone();
		java.util.Arrays.sort(sorted);

		int count = 1;
		for (int i = 1; i < sorted.length; i++) {
			if (sorted[i] != sorted[i - 1]) {
				count++;
			}
		}

		if (count == sorted.length) {
			return sorted;
		}

		int[] out = new int[count];
		out[0] = sorted[0];
		for (int i = 1, j = 1; i < sorted.length; i++) {
			if (sorted[i] != sorted[i - 1]) {
				out[j++] = sorted[i];
			}
		}
		return out;
	}

	public boolean contains(int x, int y) {
		return immutablePolygon.containsFast(x, y);
	}

	public boolean contains(int x, int y, int z) {
		if (!hasLevels) {
			return contains(x, y);
		}

		if (levels == null) {
			if (z != singleLevel) {
				return false;
			}
			return contains(x, y);
		}

		for (int level : levels) {
			if (level == z) {
				return contains(x, y);
			}
		}
		return false;
	}

	public boolean contains(WorldPoint position) {
		return contains(position.getX(), position.getY(), position.getPlane());
	}

	public boolean contains(int... pos) {
		if (pos.length < 2) {
			return false;
		}
		if (pos.length < 3) {
			return contains(pos[0], pos[1]);
		}
		return contains(pos[0], pos[1], pos[2]);
	}

	public boolean intersects(int minX, int minY, int maxX, int maxY) {
		// Check if any corner of the rectangle is contained in the polygon
		return contains(minX, minY) || contains(maxX, minY) ||
			contains(minX, maxY) || contains(maxX, maxY);
	}

	public boolean intersects(AABB... otherAabbs) {
		// Check if any corner of any AABB is contained in the polygon
		for (AABB aabb : otherAabbs) {
			// Check all 8 corners of the AABB
			if (contains(aabb.minX, aabb.minY, aabb.minZ) ||
				contains(aabb.maxX, aabb.minY, aabb.minZ) ||
				contains(aabb.minX, aabb.maxY, aabb.minZ) ||
				contains(aabb.maxX, aabb.maxY, aabb.minZ) ||
				contains(aabb.minX, aabb.minY, aabb.maxZ) ||
				contains(aabb.maxX, aabb.minY, aabb.maxZ) ||
				contains(aabb.minX, aabb.maxY, aabb.maxZ) ||
				contains(aabb.maxX, aabb.maxY, aabb.maxZ)) {
				return true;
			}
		}
		return false;
	}

	public int[][] getPoints() {
		java.awt.Polygon poly = immutablePolygon;
		int nPoints = poly.npoints;
		int[][] points = new int[nPoints][2];
		for (int i = 0; i < nPoints; i++) {
			points[i][0] = poly.xpoints[i];
			points[i][1] = poly.ypoints[i];
		}
		return points;
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
			int[] tempLevels = new int[16];
			int levelCount = 0;

			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
				if (in.peek() == JsonToken.NULL) {
					in.skipValue();
					continue;
				}

				in.beginArray();
				int[] point = new int[3];
				int i = 0;
				while (in.hasNext()) {
					switch (in.peek()) {
						case NUMBER:
							if (i >= point.length) {
								throw new IOException(
									"Too many numbers in polygon point (> " + point.length + ") at " + GsonUtils.location(in));
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

				// Extract level if present (3rd element) - no autoboxing
				if (i >= 3) {
					if (levelCount >= tempLevels.length) {
						int[] newLevels = new int[tempLevels.length * 2];
						System.arraycopy(tempLevels, 0, newLevels, 0, tempLevels.length);
						tempLevels = newLevels;
					}
					tempLevels[levelCount++] = point[2];
				}

				// Store only x, y for the point
				points.add(new int[] { point[0], point[1] });
			}
			in.endArray();

			if (points.isEmpty()) {
				return null;
			}

			int[][] pointsArray = points.toArray(new int[0][]);
			
			// Extract distinct levels if any were provided
			int[] levels;
			if (levelCount == 0) {
				levels = new int[0];
			} else {
				int[] actualLevels = new int[levelCount];
				System.arraycopy(tempLevels, 0, actualLevels, 0, levelCount);
				levels = distinctSorted(actualLevels);
			}

			return new Polygon(pointsArray, levels);
		}

		@Override
		public void write(JsonWriter out, Polygon polygon) throws IOException {
			if (polygon == null) {
				out.nullValue();
				return;
			}

			// For serialization, we'd need access to the original points
			// Since we don't store them, we can't serialize back
			throw new UnsupportedOperationException("Polygon serialization not supported");
		}

		// Primitive-only deduplication - faster than HashSet
		private static int[] distinctSorted(int[] input) {
			if (input.length <= 1) {
				return input.length == 0 ? new int[0] : new int[] { input[0] };
			}

			int[] sorted = input.clone();
			java.util.Arrays.sort(sorted);

			int count = 1;
			for (int i = 1; i < sorted.length; i++) {
				if (sorted[i] != sorted[i - 1]) {
					count++;
				}
			}

			if (count == sorted.length) {
				return sorted;
			}

			int[] out = new int[count];
			out[0] = sorted[0];
			for (int i = 1, j = 1; i < sorted.length; i++) {
				if (sorted[i] != sorted[i - 1]) {
					out[j++] = sorted[i];
				}
			}
			return out;
		}
	}
}