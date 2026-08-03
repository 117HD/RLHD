package rs117.hd.utils;

import java.util.Arrays;
import rs117.hd.utils.collections.PooledArrayType;

import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.quickSort;

// https://lisyarus.github.io/blog/posts/texture-packing.html
public final class TextureAtlasPacker {
	public static final class Rect {
		public int x, y, size;
	}

	public static boolean pack(int atlasSize, int n, int[] sizes, Rect[] outRects) {
		if (n <= 0)
			return true;

		// Atlas must be a power of two.
		if (atlasSize <= 0 || (atlasSize & (atlasSize - 1)) != 0)
			throw new IllegalArgumentException("atlasSize must be a power of two");

		if(n > sizes.length || n > outRects.length)
			throw new IllegalArgumentException("n is greater than provided rects");

		if (totalArea(sizes, n) > (long) atlasSize * atlasSize)
			return false;

		final int maxLadderDepth = Integer.numberOfTrailingZeros(atlasSize) + 1;
		final int[] ladderX = PooledArrayType.INT.borrow(maxLadderDepth);
		final int[] ladderY = PooledArrayType.INT.borrow(maxLadderDepth);
		Arrays.fill(ladderY, 0, maxLadderDepth + 1, 0);

		// Sort indices by descending size.
		final int[] order = PooledArrayType.INT.borrow(n);
		for (int i = 0; i < n; i++)
			order[i] = i;

		try {
			quickSort(order, 0, n - 1, (a, b) -> Integer.compare(sizes[b], sizes[a]));

			int ladderTop = -1;

			int penX = 0;
			int penY = 0;

			for (int i = 0; i < n; i++) {
				final int idx = order[i];
				final int size = sizes[idx];

				Rect rect = outRects[idx];
				rect.x = penX;
				rect.y = penY;
				rect.size = size;

				// Shift pen right.
				penX += size;

				// Update ladder.
				if (ladderTop >= 0 && ladderY[ladderTop] == penY + size) {
					ladderX[ladderTop] = penX;
				} else {
					++ladderTop;
					ladderX[ladderTop] = penX;
					ladderY[ladderTop] = penY + size;
				}

				// Hit the right edge.
				if (penX == atlasSize) {
					--ladderTop;

					penY += size;
					penX = ladderTop >= 0 ? ladderX[ladderTop] : 0;
				}
			}

			return true;
		} finally {
			PooledArrayType.INT.release(order);
			PooledArrayType.INT.release(ladderX);
			PooledArrayType.INT.release(ladderY);
		}
	}

	public static float computeFillScale(
		float[] sizes, int count,
		float min, float max,
		double targetArea,
		float maxScale, int iterations
	) {
		float lo = 0f, hi = maxScale;
		for (int iter = 0; iter < iterations; iter++) {
			final float mid = (lo + hi) * 0.5f;
			double area = 0;
			for (int i = 0; i < count; i++) {
				final float s = clamp(sizes[i] * mid, min, max);
				area += (double) s * s;
			}
			if (area < targetArea)
				lo = mid;
			else
				hi = mid;
		}
		return lo;
	}

	public static long totalArea(int[] sizes, int count) {
		long area = 0;
		for (int i = 0; i < count; i++) {
			final int size = sizes[i];
			area += (long) size * size;
		}
		return area;
	}
}