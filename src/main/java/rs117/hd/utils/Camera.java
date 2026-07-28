package rs117.hd.utils;

import java.util.Arrays;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class Camera implements Projection {
	private static final int PROJECTION_MATRIX_DIRTY = 1;
	private static final int VIEW_MATRIX_DIRTY = 1 << 1;
	private static final int VIEW_PROJ_MATRIX_DIRTY = 1 << 2;
	private static final int INV_VIEW_PROJ_MATRIX_DIRTY = 1 << 3;
	private static final int FRUSTUM_PLANES_DIRTY = 1 << 4;
	private static final int FRUSTUM_CORNERS_DIRTY = 1 << 5;
	private static final int BASIS_DIRTY = 1 << 6;

	private static final int VIEW_PROJ_CHANGED =
		VIEW_PROJ_MATRIX_DIRTY | INV_VIEW_PROJ_MATRIX_DIRTY | FRUSTUM_PLANES_DIRTY | FRUSTUM_CORNERS_DIRTY;
	private static final int PROJ_CHANGED = PROJECTION_MATRIX_DIRTY | VIEW_PROJ_CHANGED;
	private static final int VIEW_CHANGED = VIEW_MATRIX_DIRTY | VIEW_PROJ_CHANGED;

	private float[] viewMatrix;
	private float[] invViewMatrix;
	private float[] projectionMatrix;
	private float[] invProjectionMatrix;
	private float[] viewProjMatrix;
	private float[] invViewProjMatrix;

	private final float[][] frustumCorners = new float[8][3];
	private final float[][] frustumPlanes = new float[6][4];
	private final float[] position = new float[3];
	private final float[] orientation = new float[2];
	private final int[] fixedOrientation = new int[2]; // TODO: Is there a reliable way to go from orientation -> Fixed?

	// Explicit look-direction state (alternative to yaw/pitch orientation).
	// When useExplicitBasis is true, the view rotation is derived from
	// lookDir/lookUp via Mat4.lookAtRotation() instead of orientation[].
	private boolean useExplicitBasis = false;
	private final float[] lookDir = new float[3];
	private final float[] lookUp = new float[3];
	private float[] explicitBasis;

	private volatile int dirtyFlags = PROJ_CHANGED | VIEW_CHANGED;

	@Getter
	private int viewportWidth = 10;
	@Getter
	private int viewportHeight = 10;

	@Getter
	private float zoom = 1.0f;
	@Getter
	private float nearPlane = 0.5f;
	@Getter
	private float farPlane = 0.0f;
	@Getter
	private boolean orthographic = false;
	@Getter
	private boolean reverseZ = false;

	@Override
	public float[] project(float x, float y, float z) {
		return project(x, y, z, new float[3]);
	}

	@Override
	public float[] project(float x, float y, float z, float[] out) {
		out[0] = x;
		out[1] = y;
		out[2] = z;
		// This is only the view transform, not projection, but we only use it for CPU-side back-face culling
		return transformPoint(out, out);
	}

	public boolean isDirty() {
		return dirtyFlags != 0;
	}

	public boolean isProjDirty() { return (dirtyFlags & PROJECTION_MATRIX_DIRTY) != 0; }

	public boolean isViewDirty() { return (dirtyFlags & VIEW_MATRIX_DIRTY) != 0; }

	public synchronized void setDirty() {
		dirtyFlags |= PROJ_CHANGED | VIEW_CHANGED;
	}

	public Camera setOrthographic(boolean newOrthographic) {
		if (orthographic != newOrthographic) {
			synchronized (this) {
				orthographic = newOrthographic;
				dirtyFlags |= PROJ_CHANGED;
			}
		}
		return this;
	}

	public Camera setReverseZ(boolean newReverseZ) {
		if (reverseZ != newReverseZ) {
			synchronized (this) {
				reverseZ = newReverseZ;
				dirtyFlags |= PROJ_CHANGED;
			}
		}
		return this;
	}

	public Camera setViewportWidth(int newViewportWidth) {
		if (viewportWidth != newViewportWidth) {
			synchronized (this) {
				viewportWidth = newViewportWidth;
				dirtyFlags |= PROJ_CHANGED;
			}
		}
		return this;
	}

	public Camera setViewportHeight(int newViewportHeight) {
		if (viewportHeight != newViewportHeight) {
			synchronized (this) {
				viewportHeight = newViewportHeight;
				dirtyFlags |= PROJ_CHANGED;
			}
		}
		return this;
	}

	public float getAspectRatio() {
		return viewportHeight == 0 ? 1 : (float) viewportWidth / viewportHeight;
	}

	public Camera setNearPlane(float newNearPlane) {
		if (nearPlane != newNearPlane) {
			synchronized (this) {
				nearPlane = newNearPlane;
				dirtyFlags |= PROJ_CHANGED;
			}
		}
		return this;
	}

	public Camera setFarPlane(float newFarPlane) {
		if (farPlane != newFarPlane) {
			synchronized (this) {
				farPlane = newFarPlane;
				dirtyFlags |= PROJ_CHANGED;
			}
		}
		return this;
	}

	public Camera setZoom(float newZoom) {
		if (zoom != newZoom) {
			synchronized (this) {
				zoom = newZoom;
				dirtyFlags |= PROJ_CHANGED;
			}
		}
		return this;
	}

	public float getPositionX() {
		return position[0];
	}

	public void translateX(float xOffset) {
		setPositionX(getPositionX() + xOffset);
	}

	public Camera setPositionX(float x) {
		if (position[0] != x) {
			synchronized (this) {
				position[0] = x;
				dirtyFlags |= VIEW_CHANGED;
			}
		}
		return this;
	}

	public float getPositionY() {
		return position[1];
	}

	public void translateY(float yOffset) {
		setPositionY(getPositionY() + yOffset);
	}

	public Camera setPositionY(float y) {
		if (position[1] != y) {
			synchronized (this) {
				position[1] = y;
				dirtyFlags |= VIEW_CHANGED;
			}
		}
		return this;
	}

	public float getPositionZ() {
		return position[2];
	}

	public void translateZ(float zOffset) {
		setPositionZ(getPositionZ() + zOffset);
	}

	public Camera setPositionZ(float z) {
		if (position[2] != z) {
			synchronized (this) {
				position[2] = z;
				dirtyFlags |= VIEW_CHANGED;
			}
		}
		return this;
	}

	public Camera setPosition(float... newPosition) {
		assert newPosition.length >= 3;
		if (position[0] != newPosition[0] || position[1] != newPosition[1] || position[2] != newPosition[2]) {
			synchronized (this) {
				position[0] = newPosition[0];
				position[1] = newPosition[1];
				position[2] = newPosition[2];
				dirtyFlags |= VIEW_CHANGED;
			}
		}
		return this;
	}

	public float[] getPosition() {
		return copy(position);
	}

	public float distanceTo(float[] point) {
		return distanceTo(point[0], point[1], point[2]);
	}

	public float distanceTo(float x, float y, float z) {
		return sqrt(squaredDistanceTo(x, y, z));
	}

	public float squaredDistanceTo(float x, float y, float z) {
		float dx = position[0] - x;
		float dy = position[1] - y;
		float dz = position[2] - z;
		return dx * dx + dy * dy + dz * dz;
	}

	public Camera translate(float[] translation) {
		if (translation[0] != 0 || translation[1] != 0 || translation[2] != 0) {
			synchronized (this) {
				add(position, position, translation);
				dirtyFlags |= VIEW_CHANGED;
			}
		}
		return this;
	}

	public Camera setYaw(float yaw) {
		synchronized (this) {
			boolean changed = orientation[0] != yaw || useExplicitBasis;
			orientation[0] = yaw;
			useExplicitBasis = false;
			if (changed)
				dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public Camera setFixedYaw(int yaw) {
		fixedOrientation[0] = yaw;
		return this;
	}

	public float getYaw() {
		return orientation[0];
	}

	public int getFixedYaw() { return fixedOrientation[0]; }

	public Camera setPitch(float pitch) {
		synchronized (this) {
			boolean changed = orientation[1] != pitch || useExplicitBasis;
			orientation[1] = pitch;
			useExplicitBasis = false;
			if (changed)
				dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public Camera setFixedPitch(int pitch) {
		fixedOrientation[1] = pitch;
		return this;
	}

	public float getPitch() {
		return orientation[1];
	}

	public int getFixedPitch() { return fixedOrientation[1]; }

	public float[] getOrientation() {
		return Arrays.copyOf(orientation, 2);
	}

	public int[] getFixedOrientation() {
		return Arrays.copyOf(fixedOrientation, 2);
	}

	public Camera setOrientation(float[] newOrientation) {
		synchronized (this) {
			boolean changed = orientation[0] != newOrientation[0]
			                  || orientation[1] != newOrientation[1]
			                  || useExplicitBasis;
			orientation[0] = newOrientation[0];
			orientation[1] = newOrientation[1];
			useExplicitBasis = false;
			if (changed)
				dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public Camera setLookDirection(float[] dir, float[] up) {
		synchronized (this) {
			useExplicitBasis = true;
			copyTo(lookDir, dir);
			copyTo(lookUp, up);
			dirtyFlags |= BASIS_DIRTY | VIEW_CHANGED;
		}
		return this;
	}

	public Camera setLookDirection(float dirX, float dirY, float dirZ, float upX, float upY, float upZ) {
		synchronized (this) {
			useExplicitBasis = true;
			lookDir[0] = dirX;
			lookDir[1] = dirY;
			lookDir[2] = dirZ;
			lookUp[0] = upX;
			lookUp[1] = upY;
			lookUp[2] = upZ;
			dirtyFlags |= BASIS_DIRTY | VIEW_CHANGED;
		}
		return this;
	}

	public Camera lookAt(float dirX, float dirY, float dirZ, float upX, float upY, float upZ) {
		return setLookDirection(dirX, dirY, dirZ, upX, upY, upZ);
	}

	public Camera lookAtPoint(float targetX, float targetY, float targetZ, float upX, float upY, float upZ) {
		return setLookDirection(
			targetX - position[0],
			targetY - position[1],
			targetZ - position[2],
			upX, upY, upZ
		);
	}

	public Camera clearLookDirection() {
		synchronized (this) {
			if (useExplicitBasis) {
				useExplicitBasis = false;
				dirtyFlags |= VIEW_CHANGED;
			}
		}
		return this;
	}

	public boolean isUsingExplicitBasis() {
		return useExplicitBasis;
	}

	public float[] getForwardDirection(float[] out) {
		calculateViewMatrix();
		out[0] = -viewMatrix[2];
		out[1] = -viewMatrix[6];
		out[2] = -viewMatrix[10];
		return out;
	}

	public float[] getForwardDirection() { return getForwardDirection(new float[3]); }

	private void calculateBasis() {
		if (!useExplicitBasis || (dirtyFlags & BASIS_DIRTY) == 0)
			return;

		synchronized (this) {
			if (!useExplicitBasis || (dirtyFlags & BASIS_DIRTY) == 0)
				return;

			explicitBasis = Mat4.lookAtRotation(
				lookDir[0], lookDir[1], lookDir[2],
				lookUp[0], lookUp[1], lookUp[2]
			);
			dirtyFlags &= ~BASIS_DIRTY;
		}
	}

	private void calculateViewMatrix() {
		if ((dirtyFlags & VIEW_MATRIX_DIRTY) == 0)
			return;

		synchronized (this) {
			if ((dirtyFlags & VIEW_MATRIX_DIRTY) == 0)
				return;

			float[] view;
			if (useExplicitBasis) {
				calculateBasis();
				view = copy(explicitBasis);
			} else {
				view = Mat4.rotateX(orientation[1]);
				Mat4.mul(view, Mat4.rotateY(orientation[0]));
			}

			if (position[0] != 0 || position[1] != 0 || position[2] != 0) {
				Mat4.mul(
					view,
					Mat4.translate(
						-position[0],
						-position[1],
						-position[2]
					)
				);
			}
			viewMatrix = view;
			try {
				invViewMatrix = Mat4.inverse(viewMatrix);
			} catch (Exception ex) {
				log.warn("Encountered an exception whilst solving inverse of camera view:", ex);
			}
			dirtyFlags &= ~VIEW_MATRIX_DIRTY;
		}
	}

	public float[] getViewMatrix(float[] out) {
		calculateViewMatrix();
		copyTo(out, viewMatrix);
		return out;
	}

	public float[] getViewMatrix() {
		return getViewMatrix(Mat4.zero());
	}

	public float[] inverseTransformPoint(float[] out, float[] point) {
		calculateViewMatrix();
		Mat4.transformVecAffine(out, invViewMatrix, point);
		return out;
	}

	public float[] inverseTransformPoint(float[] point) {
		return inverseTransformPoint(new float[3], point);
	}

	public float[] transformPoint(float[] out, float[] point) {
		calculateViewMatrix();
		Mat4.transformVecAffine(out, viewMatrix, point);
		return out;
	}

	public float[] transformPoint(float[] point) {
		return transformPoint(new float[3], point);
	}

	private void calculateProjectionMatrix() {
		if ((dirtyFlags & PROJECTION_MATRIX_DIRTY) == 0)
			return;

		synchronized (this) {
			if ((dirtyFlags & PROJECTION_MATRIX_DIRTY) == 0)
				return;

			final float zoomedViewportWidth = (viewportWidth / zoom);
			final float zoomedViewportHeight = (viewportHeight / zoom);
			if (orthographic) {
				if (reverseZ) {
					projectionMatrix = Mat4.orthographicReverseZ(zoomedViewportWidth, zoomedViewportHeight, nearPlane, farPlane);
				} else {
					if (farPlane > 0.0f) {
						projectionMatrix = Mat4.orthographic(zoomedViewportWidth, zoomedViewportHeight, nearPlane, farPlane);
					} else {
						projectionMatrix = Mat4.orthographic(zoomedViewportWidth, zoomedViewportHeight, nearPlane);
					}
				}
			} else {
				if (reverseZ) {
					if (farPlane > 0.0f) {
						projectionMatrix = Mat4.perspectiveReverseZ(zoomedViewportWidth, zoomedViewportHeight, nearPlane, farPlane);
					} else {
						projectionMatrix = Mat4.perspectiveInfiniteReverseZ(zoomedViewportWidth, zoomedViewportHeight, nearPlane);
					}
				} else {
					if (farPlane > 0.0f) {
						projectionMatrix = Mat4.perspective(zoomedViewportWidth, zoomedViewportHeight, nearPlane, farPlane);
					} else {
						projectionMatrix = Mat4.perspectiveInfinite(zoomedViewportWidth, zoomedViewportHeight, nearPlane);
					}
				}
			}
			try {
				invProjectionMatrix = Mat4.inverse(projectionMatrix);
			} catch (Exception ex) {
				log.warn("Encountered an exception whilst solving inverse of camera projection:", ex);
			}
			dirtyFlags &= ~PROJECTION_MATRIX_DIRTY;
		}
	}

	public float[] getProjectionMatrix(float[] out) {
		calculateProjectionMatrix();
		copyTo(out, projectionMatrix);
		return out;
	}

	public float[] getProjectionMatrix() {
		return getProjectionMatrix(Mat4.zero());
	}

	private void calculateViewProjMatrix() {
		if ((dirtyFlags & VIEW_PROJ_MATRIX_DIRTY) == 0)
			return;

		synchronized (this) {
			if ((dirtyFlags & VIEW_PROJ_MATRIX_DIRTY) == 0)
				return;

			calculateViewMatrix();
			calculateProjectionMatrix();

			viewProjMatrix = Mat4.identity();
			Mat4.mul(viewProjMatrix, projectionMatrix);
			Mat4.mul(viewProjMatrix, viewMatrix);

			dirtyFlags &= ~VIEW_PROJ_MATRIX_DIRTY;
		}
	}

	public float[] getViewProjMatrix(float[] out) {
		calculateViewProjMatrix();
		copyTo(out, viewProjMatrix);
		return out;
	}

	public float[] getViewProjMatrix() {
		return getViewProjMatrix(Mat4.zero());
	}

	private void calculateInvViewProjMatrix() {
		if ((dirtyFlags & INV_VIEW_PROJ_MATRIX_DIRTY) == 0)
			return;

		synchronized (this) {
			if ((dirtyFlags & INV_VIEW_PROJ_MATRIX_DIRTY) == 0)
				return;

			calculateViewProjMatrix();
			try {
				float[] invViewProj = copy(invViewMatrix);
				Mat4.mul(invViewProj, invProjectionMatrix);
				invViewProjMatrix = invViewProj;
			} catch (Exception ex) {
				log.warn("Encountered an exception whilst solving inverse of camera ViewProj: ", ex);
			}
			dirtyFlags &= ~INV_VIEW_PROJ_MATRIX_DIRTY;
		}
	}

	public float[] getInvViewProjMatrix(float[] out) {
		calculateInvViewProjMatrix();
		copyTo(out, invViewProjMatrix);
		return out;
	}

	public float[] getInvViewProjMatrix() {
		calculateInvViewProjMatrix();
		return Arrays.copyOf(invViewProjMatrix, invViewProjMatrix.length);
	}

	private void calculateFrustumPlanes() {
		if ((dirtyFlags & FRUSTUM_PLANES_DIRTY) == 0)
			return;

		synchronized (this) {
			if ((dirtyFlags & FRUSTUM_PLANES_DIRTY) == 0)
				return;

			calculateViewProjMatrix();
			Mat4.extractPlanes(viewProjMatrix, frustumPlanes);
			dirtyFlags &= ~FRUSTUM_PLANES_DIRTY;
		}
	}

	public float[][] getFrustumPlanes(float[][] out) {
		calculateFrustumPlanes();
		for (int i = 0; i < out.length; i++)
			copyTo(out[i], frustumPlanes[i]);
		return out;
	}

	public float[][] getFrustumPlanes() {
		return getFrustumPlanes(new float[6][4]);
	}

	private void calculateFrustumCorners() {
		if ((dirtyFlags & FRUSTUM_CORNERS_DIRTY) == 0)
			return;

		synchronized (this) {
			if ((dirtyFlags & FRUSTUM_CORNERS_DIRTY) == 0)
				return;

			calculateInvViewProjMatrix();
			Mat4.extractFrustumCorners(invViewProjMatrix, frustumCorners);
			dirtyFlags &= ~FRUSTUM_CORNERS_DIRTY;
		}
	}

	public float[][] getFrustumCorners(float[][] out) {
		calculateFrustumCorners();
		for (int i = 0; i < out.length; i++)
			copyTo(out[i], frustumCorners[i]);
		return frustumCorners;
	}

	public float[][] getFrustumCorners() {
		return getFrustumCorners(new float[8][3]);
	}

	public boolean intersectsAABB(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		calculateFrustumPlanes();
		return HDUtils.isAABBIntersectingFrustum(minX, minY, minZ, maxX, maxY, maxZ, frustumPlanes);
	}

	public boolean intersectsSphere(float x, float y, float z, float radius) {
		calculateFrustumPlanes();
		return HDUtils.isSphereIntersectingFrustum(x, y, z, radius, frustumPlanes, frustumPlanes.length);
	}

	public int classifySphere(float x, float y, float z, float radius) {
		calculateFrustumPlanes();
		return HDUtils.classifySphereFrustum(x, y, z, radius, frustumPlanes, frustumPlanes.length);
	}

	public void copyFrom(Camera other) {
		viewportWidth = other.viewportWidth;
		viewportHeight = other.viewportHeight;
		zoom = other.zoom;
		nearPlane = other.nearPlane;
		farPlane = other.farPlane;
		orthographic = other.orthographic;
		reverseZ = other.reverseZ;

		copyTo(position, other.position);
		copyTo(orientation, other.orientation);
		copyTo(fixedOrientation, other.fixedOrientation);

		useExplicitBasis = other.useExplicitBasis;
		copyTo(lookDir, other.lookDir);
		copyTo(lookUp, other.lookUp);

		dirtyFlags = PROJ_CHANGED | VIEW_CHANGED | BASIS_DIRTY;
	}
}