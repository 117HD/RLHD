package rs117.hd.utils;

import java.util.Arrays;

import static rs117.hd.utils.Mat4.mulVec;
import static rs117.hd.utils.MathUtils.*;

public class TileOcclusionBuffer {
	public static final int WIDTH = 128;
	public static final int HEIGHT = 128;
	private static final float CLIP_W_EPSILON = 0.0001f;

	private final float[][] depthBuffer = new float[HEIGHT][WIDTH];
	private float[] viewProj;
	private boolean reverseZ;

	public void setViewProj(float[] viewProj, boolean reverseZ) {
		this.viewProj = viewProj;
		this.reverseZ = reverseZ;
		clear();
	}

	public void clear() {
		float defaultDepth = reverseZ ? 0.0f : Float.POSITIVE_INFINITY;
		for (int y = 0; y < HEIGHT; ++y)
			Arrays.fill(depthBuffer[y], defaultDepth);
	}

	public void rasterizeTile(float[][] worldCorners) {
		final float[][] screenPoints = new float[4][2];
		final float[] depths = new float[4];

		if (!projectCorners(worldCorners, screenPoints, depths)) return;

		int minX = clamp((int) minX(screenPoints), 0, WIDTH - 1);
		int maxX = clamp((int) maxX(screenPoints), 0, WIDTH - 1);
		int minY = clamp((int) minY(screenPoints), 0, HEIGHT - 1);
		int maxY = clamp((int) maxY(screenPoints), 0, HEIGHT - 1);

		final float[] point = new float[2];
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				point[0] = x + 0.5f;
				point[1] = y + 0.5f;

				if (!pointInQuad(point, screenPoints)) continue;

				float depth = bilinearDepth(point, screenPoints, depths);
				if (compareDepth(depth, depthBuffer[y][x])) {
					depthBuffer[y][x] = depth;
				}
			}
		}
	}

	public boolean isTileOccluded(float[][] worldCorners) {
		float[][] screenPoints = new float[4][2];
		float[] depths = new float[4];

		if (!projectCorners(worldCorners, screenPoints, depths)) return false;

		int minX = clamp((int) minX(screenPoints), 0, WIDTH - 1);
		int maxX = clamp((int) maxX(screenPoints), 0, WIDTH - 1);
		int minY = clamp((int) minY(screenPoints), 0, HEIGHT - 1);
		int maxY = clamp((int) maxY(screenPoints), 0, HEIGHT - 1);

		// Early out if tile is completely offscreen
		if (minX > WIDTH - 1 || maxX < 0 || minY > HEIGHT - 1 || maxY < 0)
			return false;

		final float[] point = new float[2];
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				point[0] = x + 0.5f;
				point[1] = y + 0.5f;

				if (!pointInQuad(point, screenPoints)) continue;

				float depth = bilinearDepth(point, screenPoints, depths);
				if (compareDepth(depth, depthBuffer[y][x])) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean projectCorners(float[][] worldCorners, float[][] screenPoints, float[] depths) {
		for (int i = 0; i < 4; i++) {
			float[] world = worldCorners[i];
			float[] clip = new float[4];
			mulVec(clip, viewProj, new float[]{ world[0], world[1], world[2], 1.0f });

			if (clip[3] <= CLIP_W_EPSILON) {
				return false;
			}

			float invW = 1.0f / clip[3];
			float[] ndc = { clip[0] * invW, clip[1] * invW, clip[2] * invW };

			screenPoints[i][0] = (ndc[0] * 0.5f + 0.5f) * WIDTH;
			screenPoints[i][1] = (1.0f - (ndc[1] * 0.5f + 0.5f)) * HEIGHT;
			depths[i] = getDepth(ndc[2]);
		}
		return true;
	}

	private boolean compareDepth(float testDepth, float storedDepth) {
		return reverseZ ? (testDepth > storedDepth) : (testDepth < storedDepth);
	}

	private float getDepth(float ndcZ) {
		return reverseZ ? (1.0f - (ndcZ * 0.5f + 0.5f)) : (ndcZ * 0.5f + 0.5f);
	}

	private boolean pointInQuad(float[] p, float[][] quad) {
		return HDUtils.pointInTriangle(p, quad[0], quad[1], quad[2]) ||
			   HDUtils.pointInTriangle(p, quad[2], quad[3], quad[0]);
	}

	private float bilinearDepth(float[] p, float[][] quad, float[] depths) {
		float area = HDUtils.triangleArea(quad[0], quad[1], quad[2]);
		if (area < 1e-6f) {
			// Fallback: use average depth
			float avg = 0f;
			for (float d : depths) avg += d;
			return avg / depths.length;
		}

		if (HDUtils.pointInTriangle(p, quad[0], quad[1], quad[2])) {
			return HDUtils.barycentricDepth(p, quad[0], quad[1], quad[2], depths[0], depths[1], depths[2]);
		} else {
			return HDUtils.barycentricDepth(p, quad[2], quad[3], quad[0], depths[2], depths[3], depths[0]);
		}
	}

	private float minX(float[][] points) {
		float m = points[0][0];
		for (float[] p : points) if (p[0] < m) m = p[0];
		return m;
	}

	private float maxX(float[][] points) {
		float m = points[0][0];
		for (float[] p : points) if (p[0] > m) m = p[0];
		return m;
	}

	private float minY(float[][] points) {
		float m = points[0][1];
		for (float[] p : points) if (p[1] < m) m = p[1];
		return m;
	}

	private float maxY(float[][] points) {
		float m = points[0][1];
		for (float[] p : points) if (p[1] > m) m = p[1];
		return m;
	}
}
