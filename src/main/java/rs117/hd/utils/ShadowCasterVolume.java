package rs117.hd.utils;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

public class ShadowCasterVolume {
	private static final float EPS = 1e-5f;
	private static final int[] FRUSTUM_FACES = { 0, 2, 6, 1, 5, 7, 0, 4, 5, 2, 3, 7, 0, 1, 3, 4, 6, 7 };
	// Each edge's endpoints followed by its two adjacent faces.
	private static final int[] FRUSTUM_EDGES = {
		0, 1, 2, 4, 2, 3, 3, 4, 0, 2, 0, 4, 1, 3, 1, 4,
		4, 5, 2, 5, 6, 7, 3, 5, 4, 6, 0, 5, 5, 7, 1, 5,
		0, 4, 0, 2, 1, 5, 1, 2, 2, 6, 0, 3, 3, 7, 1, 3
	};
	private final Camera shadowCamera;
	private final float[] lightDir = new float[3];
	private final float[][] volumeCorners = new float[8][3];
	private final float[][] casterPlanes = new float[18][4];
	private final float[] faceLightCosines = new float[6];
	private int casterPlaneCount;
	private final float[][] clipA = new float[12][3];
	private final float[][] clipB = new float[12][3];
	public boolean isConservative;

	public ShadowCasterVolume(Camera shadowCamera) {
		this.shadowCamera = shadowCamera;
	}

	public float[][] build(Camera sceneCamera, float receiverDistance, int shadowDrawDistance, boolean conservative) {
		this.isConservative = conservative;
		// Build a finite receiver frustum directly in the scene camera's positive-Z view
		// space, independent of the projection's reverse-Z/infinite-far conventions.
		float far = max(sceneCamera.getNearPlane() + 1, receiverDistance);
		for (int i = 0; i < volumeCorners.length; i++) {
			float[] corner = volumeCorners[i];
			corner[2] = i < 4 ? sceneCamera.getNearPlane() : far;
			corner[0] = ((i & 1) == 0 ? -1 : 1) * corner[2] * sceneCamera.getViewportWidth() / (2 * sceneCamera.getZoom());
			corner[1] = ((i & 2) == 0 ? -1 : 1) * corner[2] * sceneCamera.getViewportHeight() / (2 * sceneCamera.getZoom());
			sceneCamera.inverseTransformPoint(corner, corner);
		}
		shadowCamera.getForwardDirection(lightDir); // Toward the light (negative view Z).
		casterPlaneCount = 6;
		for (int i = 0; i < 6; i++) {
			float[] plane = casterPlanes[i];
			float[] a = volumeCorners[FRUSTUM_FACES[i * 3]];
			float[] b = volumeCorners[FRUSTUM_FACES[i * 3 + 1]];
			float[] c = volumeCorners[FRUSTUM_FACES[i * 3 + 2]];
			float bx = b[0] - a[0], by = b[1] - a[1], bz = b[2] - a[2];
			float cx = c[0] - a[0], cy = c[1] - a[1], cz = c[2] - a[2];
			buildPlane(plane, a, bx, by, bz, cx, cy, cz);
			float cosine = plane[0] * lightDir[0] + plane[1] * lightDir[1] + plane[2] * lightDir[2];
			faceLightCosines[i] = cosine;
			plane[3] += conservative ? max(0, -cosine * shadowDrawDistance) : 4 * LOCAL_TILE_SIZE;
		}
		// The inexpensive mode only uses the scene frustum with a four-tile margin.
		if (!conservative)
			return volumeCorners;

		// Close the wedges between translated faces with planes along silhouette edges.
		// Together these bound the convex hull of the frustum and its translated copy.
		for (int i = 0; i < FRUSTUM_EDGES.length; i += 4) {
			if ((faceLightCosines[FRUSTUM_EDGES[i + 2]] >= 0) == (faceLightCosines[FRUSTUM_EDGES[i + 3]] >= 0))
				continue;
			float[] a = volumeCorners[FRUSTUM_EDGES[i]];
			float[] b = volumeCorners[FRUSTUM_EDGES[i + 1]];
			if (buildPlane(
				casterPlanes[casterPlaneCount],
				a,
				b[0] - a[0], b[1] - a[1], b[2] - a[2],
				lightDir[0], lightDir[1], lightDir[2]
			)) {
				casterPlaneCount++;
			}
		}
		return volumeCorners;
	}

	private boolean buildPlane(float[] plane, float[] origin, float bx, float by, float bz, float cx, float cy, float cz) {
		float nx = by * cz - bz * cy;
		float ny = bz * cx - bx * cz;
		float nz = bx * cy - by * cx;
		float length = sqrt(nx * nx + ny * ny + nz * nz);
		if (length < EPS)
			return false;
		float d = -(nx * origin[0] + ny * origin[1] + nz * origin[2]);
		// Orient inward using an interior point of the receiver frustum.
		float side = d + .5f * (
			nx * (volumeCorners[0][0] + volumeCorners[7][0]) +
			ny * (volumeCorners[0][1] + volumeCorners[7][1]) +
			nz * (volumeCorners[0][2] + volumeCorners[7][2])
		);
		float scale = (side < 0 ? -1 : 1) / length;
		plane[0] = nx * scale;
		plane[1] = ny * scale;
		plane[2] = nz * scale;
		plane[3] = d * scale;
		return true;
	}

	/**
	 * Fits the visible receiver frustum intersected with the draw-distance square in world XZ.
	 */
	public void getReceiverBounds(float[] out, float minX, float minZ, float maxX, float maxZ) {
		for (int i = 0; i < 3; i++) {
			out[i] = Float.POSITIVE_INFINITY;
			out[i + 3] = Float.NEGATIVE_INFINITY;
		}
		for (int face = 0; face < 6; face++) {
			int a = FRUSTUM_FACES[face * 3];
			int b = FRUSTUM_FACES[face * 3 + 1];
			int c = FRUSTUM_FACES[face * 3 + 2];
			copyTo(clipA[0], volumeCorners[a]);
			copyTo(clipA[1], volumeCorners[b]);
			copyTo(clipA[2], volumeCorners[c]);
			copyTo(clipA[3], volumeCorners[a ^ b ^ c]);
			float[][] input = clipA, output = clipB;
			int count = 4;
			// Clip each face against the four vertical planes. Clamping corners alone
			// would miss intersections along edges and could crop visible shadows.
			for (int plane = 0; plane < 4 && count > 0; plane++) {
				int axis = plane < 2 ? 0 : 2;
				float sign = (plane & 1) == 0 ? 1 : -1;
				float limit = plane == 0 ? minX : plane == 1 ? maxX : plane == 2 ? minZ : maxZ;
				int nextCount = 0;
				float[] previous = input[count - 1];
				float previousDistance = sign * (previous[axis] - limit);
				for (int i = 0; i < count; i++) {
					float[] current = input[i];
					float currentDistance = sign * (current[axis] - limit);
					if ((previousDistance < 0) != (currentDistance < 0)) {
						float t = previousDistance / (previousDistance - currentDistance);
						for (int j = 0; j < 3; j++)
							output[nextCount][j] = mix(previous[j], current[j], t);
						nextCount++;
					}
					if (currentDistance >= 0)
						copyTo(output[nextCount++], current);
					previous = current;
					previousDistance = currentDistance;
				}
				float[][] swap = input;
				input = output;
				output = swap;
				count = nextCount;
			}
			for (int i = 0; i < count; i++) {
				shadowCamera.transformPoint(input[i], input[i]);
				for (int j = 0; j < 3; j++) {
					out[j] = min(out[j], input[i][j]);
					out[j + 3] = max(out[j + 3], input[i][j]);
				}
			}
		}
		// Looking entirely away from the drawn scene: keep a small, valid projection.
		if (out[0] == Float.POSITIVE_INFINITY) {
			for (int i = 0; i < 3; i++) {
				out[i] = 0;
				out[i + 3] = 1;
			}
		}
	}

	public boolean intersectsPoint(int x, int y, int z) {
		return intersectsSphere(x, y, z, 0);
	}

	public boolean intersectsAABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		for (int i = 0; i < casterPlaneCount; i++) {
			float[] plane = casterPlanes[i];
			// Only the corner furthest inside each plane is needed: three multiplies,
			// with no allocations or per-corner tests.
			float distance =
				plane[3] +
				plane[0] * (plane[0] >= 0 ? maxX : minX) +
				plane[1] * (plane[1] >= 0 ? maxY : minY) +
				plane[2] * (plane[2] >= 0 ? maxZ : minZ);
			if (distance < 0)
				return false;
		}
		return true;
	}

	public boolean intersectsSphere(float x, float y, float z, float radius) {
		for (int i = 0; i < casterPlaneCount; i++) {
			float[] plane = casterPlanes[i];
			if (plane[0] * x + plane[1] * y + plane[2] * z + plane[3] < -radius)
				return false;
		}
		return true;
	}
}
