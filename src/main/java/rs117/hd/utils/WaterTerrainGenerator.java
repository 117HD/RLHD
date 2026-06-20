package rs117.hd.utils;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.water_types.WaterType;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class WaterTerrainGenerator {
	public final int width;
	public final int height;
	public final float[] heights;
	public final boolean[] pinned;
	public final WaterType[] waterTypes;

	public WaterTerrainGenerator(int width, int height) {
		this.width = width;
		this.height = height;
		this.heights = new float[width * height];
		this.pinned = new boolean[width * height];
		this.waterTypes = new WaterType[width * height];
	}

	public void reset() {
		Arrays.fill(heights, Float.NaN);
		Arrays.fill(pinned, false);
		Arrays.fill(waterTypes, WaterType.NONE);
	}

	public void setVertex(int x, int y, float height, WaterType waterType) {
		int idx = y * width + x;
		if (pinned[idx])
			return;

		if (waterType == WaterType.NONE) {
			heights[idx] = height;
			pinned[idx] = true;
		} else {
			heights[idx] = height + waterType.depth;
		}
		waterTypes[idx] = waterType;
	}

	// BFS outward from all initialized cells to fill uninitialized (NaN) cells
	// with the value and type of their nearest initialized neighbour.
	public void fillUninitialized() {
//		for (int i = 0; i < heights.length; ++i)
//			if (Float.isNaN(heights[i]))
//				heights[i] = 0;
//		if (true) return;

		int N = width * height;
		int[] queue = new int[N];
		int head = 0, tail = 0;

		for (int i = 0; i < N; i++) {
			if (!Float.isNaN(heights[i]))
				queue[tail++] = i;
		}

		int[] dx = { -1, 1, 0, 0 };
		int[] dy = { 0, 0, -1, 1 };

		while (head < tail) {
			int i = queue[head++];
			int x = i % width;
			int y = i / width;

			for (int d = 0; d < 4; d++) {
				int nx = x + dx[d];
				int ny = y + dy[d];
				if (nx < 0 || nx >= width || ny < 0 || ny >= height)
					continue;

				int n = ny * width + nx;
				if (!Float.isNaN(heights[n]))
					continue;

				heights[n] = heights[i];
				pinned[n] = pinned[i];
				waterTypes[n] = waterTypes[i];
				queue[tail++] = n;
			}
		}
	}

	public void pinContourLines() {
		float[] originalHeights = copy(heights);

		// Pin the centers between adjacent cells of different water types,
		// instead of pinning the grid-aligned corners directly. Every cell
		// already has a water type (waterTypes[i]); the contour lies
		// between cells of DIFFERENT types. For each cell i and each of its
		// 4 grid-aligned neighbours k at distance 4, if waterTypes[k] !=
		// waterTypes[i], pin the midpoint (distance 2) to the average of
		// heights[i] and heights[k]. Multiple writers to the same midpoint
		// (which happens for diagonal contours) are averaged together.
		int[] offsets = { -4, 4, -width * 4, width * 4 };
		int[] midOffsets = { -2, 2, -width * 2, width * 2 };

		float[] pinSum = new float[heights.length];
		int[] pinCount = new int[heights.length];

		for (int x = 4; x < width - 4; ++x) {
			for (int y = 4; y < height - 4; ++y) {
				int i = y * width + x;

				for (int j = 0; j < offsets.length; ++j) {
					int k = i + offsets[j];
					if (waterTypes[k] == waterTypes[i])
						continue;

					int m = i + midOffsets[j];
					pinSum[m] += 0.5f * (originalHeights[i] + originalHeights[k]);
					pinCount[m]++;
				}
			}
		}

		for (int i = 0; i < heights.length; ++i) {
			if (pinCount[i] > 0 && waterTypes[i] != WaterType.NONE) {
				heights[i] = pinSum[i] / pinCount[i];
				pinned[i] = true;
			}
		}
	}

	public void solve() {
		pinContourLines();
		fillUninitialized();
		MultigridLaplace.solve(heights, pinned, width, height);
	}

	public int getHeight(int x, int y) {
		int i = y * width + x;
		return round(heights[i]);
	}
}
