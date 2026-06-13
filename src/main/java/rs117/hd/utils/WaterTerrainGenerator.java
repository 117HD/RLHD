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
//		for (int i = 0; i < heights.length; ++i) {
//			if (Float.isNaN(heights[i])) {
//				heights[i] = 0;
//			}
//		}
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
				waterTypes[n] = waterTypes[i];
				queue[tail++] = n;
			}
		}
	}

	public void pinContourLines() {
		float[] originalHeights = copy(heights);

		// Pin contour lines between different water types
		int[] offsets = { -4, 4, -width * 4, width * 4 };
		for (int x = 4; x < width - 4; ++x) {
			for (int y = 4; y < height - 4; ++y) {
				int i = y * width + x;
				if (pinned[i])
					continue;

				float sum = originalHeights[i];
				int count = 1;
				for (int j = 0; j < offsets.length; ++j) {
					int k = i + offsets[j];
					if (waterTypes[k] != WaterType.NONE) {
						sum += originalHeights[k];
						count++;
					}
				}

				float avg = sum / count;
				if (avg != originalHeights[i]) {
					pinned[i] = true;
					heights[i] = avg;
				}
			}
		}
	}

	public void solve(int maxIterations, float tolerance) {
		float[] solution = MultigridLaplace.solve(heights, pinned, width, height);
		copyTo(heights, solution);
		if (true) return;

		// Pin shoreline-adjacent water cells to a value extrapolated from the
		// terrain slope, so the solution transitions smoothly from land.
		int[] dx = { -1, 1, 0, 0 };
		int[] dy = { 0, 0, -1, 1 };
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = y * width + x;
				if (pinned[i] || waterTypes[i] == WaterType.NONE)
					continue;

				float sum = 0;
				int count = 0;

				for (int d = 0; d < 4; d++) {
					int nx = x + dx[d];
					int ny = y + dy[d];
					if (nx < 0 || nx >= width || ny < 0 || ny >= height)
						continue;

					int n = ny * width + nx;
					if (!pinned[n] || waterTypes[n] != WaterType.NONE)
						continue;

					// Walk outward in steps of 4 (tile corners) to find two land
					// sample points with a meaningful slope between them.
					// The first step lands on the immediate tile corner at or beyond
					// the shoreline; the second is one tile further inland.
					int c1x = snapToTileCorner(nx, dx[d]);
					int c1y = snapToTileCorner(ny, dy[d]);
					int c2x = c1x + dx[d] * 4;
					int c2y = c1y + dy[d] * 4;

					if (c1x < 0 || c1x >= width || c1y < 0 || c1y >= height)
						continue;
					if (c2x < 0 || c2x >= width || c2y < 0 || c2y >= height) {
						sum += 0;
						count++;
						continue;
					}

					int idx1 = c1y * width + c1x;
					int idx2 = c2y * width + c2x;

					if (waterTypes[idx1] != WaterType.NONE || waterTypes[idx2] != WaterType.NONE) {
						// Can't get a clean two-point land slope; start at zero depth.
						sum += 0;
						count++;
						continue;
					}

					float extrapolated = 2 * heights[idx1] - heights[idx2];
					sum += clamp(-extrapolated, 0, waterTypes[i].depth);
					count++;
				}

				if (count > 0) {
					heights[i] = sum / count;
					pinned[i] = true;
				}
			}
		}

		int N = width * height;
		float[] r = new float[N];
		float[] p = new float[N];
		float[] Ap = new float[N];

		computeResidual(r);
		System.arraycopy(r, 0, p, 0, N);
		float rsOld = dot(r, r);

		for (int iter = 0; iter < maxIterations; iter++) {
			applyLaplacian(p, Ap);

			float alpha = rsOld / dot(p, Ap);

			for (int i = 0; i < N; i++) {
				if (!pinned[i]) {
					heights[i] += alpha * p[i];
					r[i] -= alpha * Ap[i];
				}
			}

			float rsNew = dot(r, r);
			if (sqrt(rsNew) < tolerance) break;

			float beta = rsNew / rsOld;
			for (int i = 0; i < N; i++)
				if (!pinned[i])
					p[i] = r[i] + beta * p[i];

			rsOld = rsNew;
		}
	}

	// Snap coordinate c to the nearest multiple of 4 in the direction of delta.
	// delta is -1 or +1; we want the first tile corner at or beyond c going outward.
	private int snapToTileCorner(int c, int delta) {
		if (delta < 0) {
			return (c / 4) * 4;        // round down toward 0
		} else {
			return ((c + 3) / 4) * 4;  // round up away from 0
		}
	}

	// L*X for the 5-point Laplacian.
	// Pinned cells: identity row  →  out[i] = X[i]
	// Free cells:   out[i] = X[i] - (sum of water neighbours) / count
	// Land neighbours (pinned, waterType NONE) are excluded from both
	// sum and count; their influence enters only via the RHS in computeResidual.
	private void applyLaplacian(float[] X, float[] out) {
		int[] dx = { -1, 1, 0, 0 };
		int[] dy = { 0, 0, -1, 1 };

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = y * width + x;
				if (pinned[i]) {
					out[i] = X[i];
					continue;
				}

				float sum = 0;
				int count = 0;

				for (int d = 0; d < 4; d++) {
					int nx = x + dx[d];
					int ny = y + dy[d];
					if (nx < 0 || nx >= width || ny < 0 || ny >= height)
						continue;

					int n = ny * width + nx;
					boolean isLand = pinned[n] && waterTypes[n] == WaterType.NONE;
					if (!isLand) {
						sum += X[n];
						count++;
					}
				}

				out[i] = count > 0 ? X[i] - sum / count : 0;
			}
		}
	}

	// r = b - L*h
	// Pinned cells: r[i] = 0 (already satisfied)
	// Free cells:   r[i] = (average of non-land neighbours) - h[i]
	private void computeResidual(float[] r) {
		int[] dx = { -1, 1, 0, 0 };
		int[] dy = { 0, 0, -1, 1 };

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = y * width + x;
				if (pinned[i]) {
					r[i] = 0;
					continue;
				}

				float sum = 0;
				int count = 0;

				for (int d = 0; d < 4; d++) {
					int nx = x + dx[d];
					int ny = y + dy[d];
					if (nx < 0 || nx >= width || ny < 0 || ny >= height)
						continue;

					int n = ny * width + nx;
					boolean isLand = pinned[n] && waterTypes[n] == WaterType.NONE;
					if (!isLand) {
						sum += heights[n];
						count++;
					}
				}

				r[i] = count > 0 ? sum / count - heights[i] : 0;
			}
		}
	}

	public int getHeight(int x, int y) {
		int i = y * width + x;
//		return waterTypes[i] == WaterType.NONE ? 0 : max(1, round(heights[i]));
		return round(heights[i]);
	}
}
