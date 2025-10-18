package rs117.hd.utils;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class Camera {
	private static final int PROJECTION_MATRIX_DIRTY = 1;
	private static final int VIEW_MATRIX_DIRTY = 1 << 1;
	private static final int VIEW_PROJ_MATRIX_DIRTY = 1 << 2;
	private static final int INV_VIEW_PROJ_MATRIX_DIRTY = 1 << 3;
	private static final int FRUSTUM_PLANES_DIRTY = 1 << 4;

	private static final int VIEW_PROJ_CHANGED =
		VIEW_PROJ_MATRIX_DIRTY | INV_VIEW_PROJ_MATRIX_DIRTY | FRUSTUM_PLANES_DIRTY;
	private static final int PROJ_CHANGED = PROJECTION_MATRIX_DIRTY | VIEW_PROJ_CHANGED;
	private static final int VIEW_CHANGED = VIEW_MATRIX_DIRTY | VIEW_PROJ_CHANGED;

	private float[] viewMatrix;
	private float[] projectionMatrix;
	private float[] viewProjMatrix;
	private float[] invViewProjMatrix;

	private final float[][] frustumPlanes = new float[6][4];
	private final float[] position = new float[3];
	private final float[] orientation = new float[2];

	private int dirtyFlags = PROJ_CHANGED | VIEW_CHANGED;

	private int viewportWidth = 10;
	private int viewportHeight = 10;

	private float zoom = 1.0f;
	private float nearPlane = 0.5f;
	private float farPlane = 0.0f;
	private boolean isOrthographic = false;

	public boolean isDirty() {
		return dirtyFlags != 0;
	}

	public boolean isProjDirty() { return (dirtyFlags & PROJECTION_MATRIX_DIRTY) != 0; }

	public boolean isViewDirty() { return (dirtyFlags & VIEW_MATRIX_DIRTY) != 0; }

	public boolean getIsOrthographic() {return isOrthographic; }

	public Camera setOrthographic(boolean newOrthographic) {
		if(isOrthographic != newOrthographic) {
			isOrthographic = newOrthographic;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

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

	public Camera setNearPlane(float newNearPlane) {
		if (nearPlane != newNearPlane) {
			nearPlane = newNearPlane;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float getNearPlane() {
		return nearPlane;
	}

	public Camera setFarPlane(float newFarPlane) {
		if (farPlane != newFarPlane) {
			farPlane = newFarPlane;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float getFarPlane() {
		return farPlane;
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
		return Arrays.copyOf(position, 3);
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

	public float getYaw() {
		return orientation[0];
	}

	public int getYawCos() {
		return Perspective.COSINE[(int)getYaw()];
	}

	public int getYawSin() {
		return Perspective.SINE[(int)getYaw()];
	}

	public Camera setPitch(float pitch) {
		if (orientation[1] != pitch) {
			orientation[1] = pitch;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float getPitch() {
		return orientation[1];
	}

	public int getPitchCos() {
		return Perspective.COSINE[(int)getPitch()];
	}

	public int getPitchSin() {
		return Perspective.SINE[(int)getPitch()];
	}

	public float[] getOrientation() {
		return Arrays.copyOf(orientation, 2);
	}

	public Camera setOrientation(float[] newOrientation) {
		if (orientation[0] != newOrientation[0] || orientation[1] != newOrientation[1]) {
			orientation[0] = newOrientation[0];
			orientation[1] = newOrientation[1];
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float[] getForwardDirection() {
		calculateViewMatrix();
		return new float[] { -viewMatrix[2], -viewMatrix[6], -viewMatrix[10] };
	}

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

	private void calculateProjectionMatrix() {
		if ((dirtyFlags & PROJECTION_MATRIX_DIRTY) != 0) {
			final float zoomedViewportWidth = (viewportWidth / zoom);
			final float zoomedViewportHeight = (viewportHeight / zoom);
			if (isOrthographic) {
				projectionMatrix = Mat4.orthographic(zoomedViewportWidth, zoomedViewportHeight, nearPlane);
			} else {
				if (farPlane > 0.0f) {
					projectionMatrix = Mat4.perspective(zoomedViewportWidth, zoomedViewportHeight, nearPlane, farPlane);
				} else {
					projectionMatrix = Mat4.perspective(zoomedViewportWidth, zoomedViewportHeight, nearPlane);
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
			invViewProjMatrix = Mat4.inverse(viewProjMatrix);
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
		if ((dirtyFlags & FRUSTUM_PLANES_DIRTY) != 0) {
			calculateViewProjMatrix();
			Mat4.extractPlanes(
				viewProjMatrix,
				frustumPlanes[0], frustumPlanes[1],
				frustumPlanes[2], frustumPlanes[3],
				frustumPlanes[4], frustumPlanes[5]
			);
			dirtyFlags &= ~FRUSTUM_PLANES_DIRTY;
		}
	}

	public float[][] getFrustumPlanes(float[][] out) {
		calculateFrustumPlanes();
		for(int i = 0; i < out.length; i++) {
			copyTo(out[i], frustumPlanes[i]);
		}
		return out;
	}

	public float[][] getFrustumPlanes() {
		return getFrustumPlanes(new float[6][4]);
	}

	public float[][] getFrustumCorners() {
		calculateInvViewProjMatrix();
		return Mat4.extractFrustumCorners(invViewProjMatrix);
	}
}
