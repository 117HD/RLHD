package rs117.hd.utils;

import java.util.Arrays;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UniformBuffer;
import rs117.hd.opengl.uniforms.UniformBuffer.PropertyType;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class Camera {
	private static final int PROJECTION_MATRIX_DIRTY = 1;
	private static final int VIEW_MATRIX_DIRTY = 1 << 1;
	private static final int VIEW_PROJ_MATRIX_DIRTY = 1 << 2;
	private static final int INV_VIEW_PROJ_MATRIX_DIRTY = 1 << 3;
	private static final int FRUSTUM_PLANES_DIRTY = 1 << 4;
	private static final int FRUSTUM_CORNERS_DIRTY = 1 << 5;

	private static final int VIEW_PROJ_CHANGED =
		VIEW_PROJ_MATRIX_DIRTY | INV_VIEW_PROJ_MATRIX_DIRTY | FRUSTUM_PLANES_DIRTY | FRUSTUM_CORNERS_DIRTY;
	private static final int PROJ_CHANGED = PROJECTION_MATRIX_DIRTY | VIEW_PROJ_CHANGED;
	private static final int VIEW_CHANGED = VIEW_MATRIX_DIRTY | VIEW_PROJ_CHANGED;

	private float[] viewMatrix;
	private float[] invViewMatrix;
	private float[] projectionMatrix;
	private float[] viewProjMatrix;
	private float[] invViewProjMatrix;

	private final float[][] frustumCorners = new float[8][3];
	private final float[][] frustumPlanes = new float[6][4];
	private final float[] position = new float[3];
	private final float[] orientation = new float[2];
	private final int[] fixedOrientation = new int[2]; // TODO: Is there a reliable way to go from orientation -> Fixed?

	private int dirtyFlags = PROJ_CHANGED | VIEW_CHANGED;

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
	private boolean isOrthographic = false;
	private boolean reverseZ = false;

	public boolean isDirty() {
		return dirtyFlags != 0;
	}

	public boolean isProjDirty() { return (dirtyFlags & PROJECTION_MATRIX_DIRTY) != 0; }

	public boolean isViewDirty() { return (dirtyFlags & VIEW_MATRIX_DIRTY) != 0; }

	public boolean getIsOrthographic() {return isOrthographic; }

	public Camera setOrthographic(boolean newOrthographic) {
		if (isOrthographic != newOrthographic) {
			isOrthographic = newOrthographic;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public Camera setReverseZ(boolean newReverseZ) {
		if (reverseZ != newReverseZ) {
			reverseZ = newReverseZ;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public boolean getIsReverseZ() {return reverseZ; }

	public Camera setViewportWidth(int newViewportWidth) {
		if (viewportWidth != newViewportWidth) {
			viewportWidth = newViewportWidth;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public Camera setViewportHeight(int newViewportHeight) {
		if (viewportHeight != newViewportHeight) {
			viewportHeight = newViewportHeight;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float getAspectRatio() {
		return viewportHeight == 0 ? 1 : (float) viewportWidth / viewportHeight;
	}

	public Camera setNearPlane(float newNearPlane) {
		if (nearPlane != newNearPlane) {
			nearPlane = newNearPlane;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public Camera setFarPlane(float newFarPlane) {
		if (farPlane != newFarPlane) {
			farPlane = newFarPlane;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public Camera setZoom(float newZoom) {
		if (zoom != newZoom) {
			zoom = newZoom;
			dirtyFlags |= PROJ_CHANGED;
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
			position[0] = x;
			dirtyFlags |= VIEW_CHANGED;
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
			position[1] = y;
			dirtyFlags |= VIEW_CHANGED;
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
			position[2] = z;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public Camera setPosition(float[] newPosition) {
		if (position[0] != newPosition[0] || position[1] != newPosition[1] || position[2] != newPosition[2]) {
			position[0] = newPosition[0];
			position[1] = newPosition[1];
			position[2] = newPosition[2];
			dirtyFlags |= VIEW_CHANGED;
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
		if (translation[0] != 0.0f || translation[1] != 0.0f || translation[2] != 0.0f) {
			position[0] += translation[0];
			position[1] += translation[1];
			position[2] += translation[2];
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public Camera setYaw(float yaw) {
		if (orientation[0] != yaw) {
			orientation[0] = yaw;
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
		if (orientation[1] != pitch) {
			orientation[1] = pitch;
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
		if (orientation[0] != newOrientation[0] || orientation[1] != newOrientation[1]) {
			orientation[0] = newOrientation[0];
			orientation[1] = newOrientation[1];
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float[] getForwardDirection(float[] out) {
		calculateViewMatrix();
		out[0] = -viewMatrix[2];
		out[1] = -viewMatrix[6];
		out[2] = -viewMatrix[10];
		return out;
	}

	public float[] getForwardDirection() { return getForwardDirection(new float[3]); }

	private void calculateViewMatrix() {
		if ((dirtyFlags & VIEW_MATRIX_DIRTY) != 0) {
			viewMatrix = Mat4.identity();
			Mat4.mul(viewMatrix, Mat4.rotateX(orientation[1]));
			Mat4.mul(viewMatrix, Mat4.rotateY(orientation[0]));
			if (position[0] != 0 || position[1] != 0 || position[2] != 0) {
				Mat4.mul(
					viewMatrix,
					Mat4.translate(
						-position[0],
						-position[1],
						-position[2]
					)
				);
			}

			try {
				invViewMatrix = Mat4.inverse(viewMatrix);
			} catch (Exception ex) {
				log.warn("Encountered an exception whilst solving inverse of camera view: ", ex);
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
		return getViewMatrix(new float[16]);
	}

	public float[] transformPoint(float[] out, float[] point) {
		calculateViewMatrix();
		Mat4.transformVecAffine(out, viewMatrix, point);
		return out;
	}

	public float[] transformPoint(float[] point) {
		return transformPoint(new float[3], point);
	}

	public float[] inverseTransformPoint(float[] out, float[] point) {
		calculateViewMatrix();
		Mat4.transformVecAffine(out, invViewMatrix, point);
		return out;
	}

	public float[] inverseTransformPoint(float[] point) {
		return inverseTransformPoint(new float[3], point);
	}

	private void calculateProjectionMatrix() {
		if ((dirtyFlags & PROJECTION_MATRIX_DIRTY) != 0) {
			final float zoomedViewportWidth = (viewportWidth / zoom);
			final float zoomedViewportHeight = (viewportHeight / zoom);
			if (isOrthographic) {
				if (reverseZ) {
					if (farPlane > 0.0f) {
						projectionMatrix = Mat4.orthographicReverseZ(zoomedViewportWidth, zoomedViewportHeight, nearPlane, farPlane);
					} else {
						projectionMatrix = Mat4.orthographic(zoomedViewportWidth, zoomedViewportHeight, nearPlane, farPlane);
					}
				} else {
					projectionMatrix = Mat4.orthographic(zoomedViewportWidth, zoomedViewportHeight, nearPlane);
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
			dirtyFlags &= ~PROJECTION_MATRIX_DIRTY;
		}
	}

	public float[] getProjectionMatrix(float[] out) {
		calculateProjectionMatrix();
		copyTo(out, projectionMatrix);
		return out;
	}

	public float[] getProjectionMatrix() {
		return getProjectionMatrix(new float[16]);
	}

	private void calculateViewProjMatrix() {
		if ((dirtyFlags & VIEW_PROJ_MATRIX_DIRTY) != 0) {
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
		return getViewProjMatrix(new float[16]);
	}

	private void calculateInvViewProjMatrix() {
		if ((dirtyFlags & INV_VIEW_PROJ_MATRIX_DIRTY) != 0) {
			calculateViewProjMatrix();
			try {
				invViewProjMatrix = Mat4.inverse(viewProjMatrix);
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
		calculateViewProjMatrix();
		Mat4.extractPlanes(viewProjMatrix, frustumPlanes);
		dirtyFlags &= ~FRUSTUM_PLANES_DIRTY;
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
		calculateInvViewProjMatrix();
		Mat4.extractFrustumCorners(invViewProjMatrix, frustumCorners);
		dirtyFlags &= ~FRUSTUM_CORNERS_DIRTY;
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

	public boolean intersectsSphere(int x, int y, int z, int radius) {
		calculateFrustumPlanes();
		return HDUtils.isSphereIntersectingFrustum(x, y, z, radius, frustumPlanes, frustumPlanes.length);
	}

	public static class CameraStruct extends UniformBuffer.StructProperty {
		public UniformBuffer.Property viewProj = addProperty(PropertyType.Mat4, "viewProj");
		public UniformBuffer.Property invViewProj = addProperty(PropertyType.Mat4, "invViewProj");
		public UniformBuffer.Property viewMatrix = addProperty(PropertyType.Mat4, "viewMatrix");
		public UniformBuffer.Property nearPlane = addProperty(PropertyType.Float, "nearPlane");
		public UniformBuffer.Property farPlane = addProperty(PropertyType.Float, "farPlane");
		public UniformBuffer.Property position = addProperty(PropertyType.FVec3, "position");

		public void set(Camera camera) {
			camera.calculateViewMatrix();
			camera.calculateViewProjMatrix();
			camera.calculateInvViewProjMatrix();

			viewProj.set(camera.viewProjMatrix);
			invViewProj.set(camera.invViewProjMatrix);
			viewMatrix.set(camera.viewMatrix);
			nearPlane.set(camera.nearPlane);
			farPlane.set(camera.farPlane);
			position.set(camera.position);
		}
	}
}
