package rs117.hd.utils;

import static rs117.hd.utils.MathUtils.*;

public class ShadowCasterVolume {
	private static final float EPS = 1e-5f;
	private static final int VOLUME_TRIANGLE_COUNT = 12;
	private static final int[] VOLUME_TRIANGLE_INDICES = {
		// Left Side
		0, 1, 5,
		0, 5, 4,

		// Right Side
		2, 3, 7,
		2, 7, 6,

		// Top Side
		1, 2, 6,
		1, 6, 5,

		// Bottom Side
		0, 3, 7,
		0, 7, 4,

		// Near Plane
		0, 1, 2,
		2, 3, 0,

		// Far Plane
		4, 5, 6,
		6, 7, 4
	};

	private final Camera shadowCamera;
	private final float[] lightDir = new float[3];
	private final VolumeTriangle[] volumeTriangles = new VolumeTriangle[VOLUME_TRIANGLE_COUNT];

	public ShadowCasterVolume(Camera shadowCamera) {
		this.shadowCamera = shadowCamera;
		for (int t = 0; t < VOLUME_TRIANGLE_COUNT; t++)
			volumeTriangles[t] = new VolumeTriangle();
	}

	public void build(float[][] volumeCorners) {
		shadowCamera.getForwardDirection(lightDir);

		// Invert Light Direction
		normalize(lightDir, lightDir);
		lightDir[0] = -lightDir[0];
		lightDir[1] = -lightDir[1];
		lightDir[2] = -lightDir[2];

		for (int t = 0; t < VOLUME_TRIANGLE_COUNT; t++) {
			final float[] v0 = volumeCorners[VOLUME_TRIANGLE_INDICES[t * 3]];
			final float[] v1 = volumeCorners[VOLUME_TRIANGLE_INDICES[t * 3 + 1]];
			final float[] v2 = volumeCorners[VOLUME_TRIANGLE_INDICES[t * 3 + 2]];

			volumeTriangles[t].build(v0, v1, v2);
		}
	}

	public boolean intersectsPoint(int x, int y, int z) {
		for (int t = 0; t < VOLUME_TRIANGLE_COUNT; t++) {
			if (volumeTriangles[t].side(lightDir, x, y, z))
				return true;
		}

		return false;
	}

	public boolean intersectsAABB(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		for (int t = 0; t < VOLUME_TRIANGLE_COUNT; t++) {
			if (volumeTriangles[t].side(lightDir, minX, minY, minZ)) return true;
			if (volumeTriangles[t].side(lightDir, maxX, minY, minZ)) return true;
			if (volumeTriangles[t].side(lightDir, maxX, minY, maxZ)) return true;
			if (volumeTriangles[t].side(lightDir, minX, minY, maxZ)) return true;

			if (volumeTriangles[t].side(lightDir, minX, maxY, minZ)) return true;
			if (volumeTriangles[t].side(lightDir, maxX, maxY, minZ)) return true;
			if (volumeTriangles[t].side(lightDir, maxX, maxY, maxZ)) return true;
			if (volumeTriangles[t].side(lightDir, minX, maxY, maxZ)) return true;
		}

		return false;
	}

	static class VolumeTriangle {
		float x;
		float y;
		float z;

		float e1x;
		float e1y;
		float e1z;

		float e2x;
		float e2y;
		float e2z;

		void build(float[] v0, float[] v1, float[] v2) {
			x = v0[0];
			y = v0[1];
			z = v0[2];

			e1x = v1[0] - v0[0];
			e1y = v1[1] - v0[1];
			e1z = v1[2] - v0[2];

			e2x = v2[0] - v0[0];
			e2y = v2[1] - v0[1];
			e2z = v2[2] - v0[2];
		}

		boolean side(float[] lightDir, float x, float y, float z) {
			float px = lightDir[1] * e2z - lightDir[2] * e2y;
			float py = lightDir[2] * e2x - lightDir[0] * e2z;
			float pz = lightDir[0] * e2y - lightDir[1] * e2x;

			float det = e1x * px + e1y * py + e1z * pz;
			if (det > -EPS && det < EPS) return false;

			float invDet = 1.0f / det;

			float tx = x - this.x;
			float ty = y - this.y;
			float tz = z - this.z;

			float u = (tx * px + ty * py + tz * pz) * invDet;
			if (u < 0.0f || u > 1.0f) return false;

			float qx = ty * e1z - tz * e1y;
			float qy = tz * e1x - tx * e1z;
			float qz = tx * e1y - ty * e1x;

			float v = (lightDir[0] * qx + lightDir[1] * qy + lightDir[2] * qz) * invDet;
			if (v < 0.0f || u + v > 1.0f) return false;

			float t = (e2x * qx + e2y * qy + e2z * qz) * invDet;
			return t > EPS;
		}
	}
}
