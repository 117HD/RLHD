package rs117.hd.utils;

import rs117.hd.scene.SceneCullingManager;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

public class DirectionalShadowCulling implements SceneCullingManager.ICullingCallback {

	private static final float EPS = 1e-5f;

	private static final int[] FRUSTUM_TRIANGLE_INDICES = {
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

	private final float[] lightDir = new float[3];
	private final float[][] sceneFrustumCorners = new float[8][3];

	public void setup(float[][] newSceneFrustumCorners, float[] newLightDir) {
		System.arraycopy(newLightDir, 0, lightDir, 0, 3);

		normalize(lightDir, lightDir);

		lightDir[0] = -lightDir[0];
		lightDir[1] = -lightDir[1];
		lightDir[2] = -lightDir[2];

		for (int c = 0; c < newSceneFrustumCorners.length; c++) {
			System.arraycopy(newSceneFrustumCorners[c], 0, sceneFrustumCorners[c], 0, 3);
		}
	}

	@Override
	public boolean isTileVisible(int x, int z, int h0, int h1, int h2, int h3, boolean isVisible) {
		if (!isVisible) return false;
		int ch = round(h0 + h1 + h2 + h3 / 4.0f);
		for (int t = 0; t < 12; t++) {
			if (intersectsTriangle(x + LOCAL_HALF_TILE_SIZE, ch, z + LOCAL_HALF_TILE_SIZE, t)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean isStaticRenderableVisible(int x, int y, int z, int radius, int height, boolean isVisible) {
		if (!isVisible) return false;
		int ch = y + height / 2;
		for (int t = 0; t < 12; t++) {
			if (intersectsTriangle(x, ch, z, t)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean isBoundingSphereVisible(float x, float y, float z, float radius, boolean isVisible) {
		if(!isVisible) return false;
		for (int t = 0; t < 12; t++) {
			if (intersectsTriangle(x, y, z, t)) {
				return true;
			}
		}
		return false;
	}

	private boolean intersectsTriangle(float x, float y, float z, int triangle) {
		final float[] v0 = sceneFrustumCorners[FRUSTUM_TRIANGLE_INDICES[triangle * 3]];
		final float[] v1 = sceneFrustumCorners[FRUSTUM_TRIANGLE_INDICES[triangle * 3 + 1]];
		final float[] v2 = sceneFrustumCorners[FRUSTUM_TRIANGLE_INDICES[triangle * 3 + 2]];

		float e1x = v1[0] - v0[0];
		float e1y = v1[1] - v0[1];
		float e1z = v1[2] - v0[2];
		float e2x = v2[0] - v0[0];
		float e2y = v2[1] - v0[1];
		float e2z = v2[2] - v0[2];

		float px = lightDir[1] * e2z - lightDir[2] * e2y;
		float py = lightDir[2] * e2x - lightDir[0] * e2z;
		float pz = lightDir[0] * e2y - lightDir[1] * e2x;

		float det = e1x * px + e1y * py + e1z * pz;
		if (det > -EPS && det < EPS) return false;

		float invDet = 1.0f / det;

		float tx = x - v0[0];
		float ty = y - v0[1];
		float tz = z - v0[2];

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