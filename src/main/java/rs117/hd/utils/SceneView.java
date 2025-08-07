package rs117.hd.utils;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.scene.SceneCullingManager.CullingResults;

@Slf4j
public class SceneView {
	public static final int CULLING_FLAG_GROUND_PLANES = 1;
	public static final int CULLING_FLAG_UNDERWATER_PLANES = 1 << 1;
	public static final int CULLING_FLAG_RENDERABLES = 1 << 2;
	public static final int CULLING_FLAG_OCCLUSION_CULLING = 1 << 3;
	public static final int CULLING_FLAG_FREEZE = 1 << 4;

	private static final int PROJECTION_MATRIX_DIRTY = 1;
	private static final int VIEW_MATRIX_DIRTY = 1 << 1;
	private static final int VIEW_PROJ_MATRIX_DIRTY = 1 << 2;
	private static final int INV_VIEW_PROJ_MATRIX_DIRTY = 1 << 3;
	private static final int FRUSTUM_PLANES_DIRTY = 1 << 4;
	private static final int TILE_VISIBILITY_DIRTY = 1 << 5;

	private static final int VIEW_PROJ_CHANGED =
		VIEW_PROJ_MATRIX_DIRTY | INV_VIEW_PROJ_MATRIX_DIRTY | FRUSTUM_PLANES_DIRTY | TILE_VISIBILITY_DIRTY;
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

	private CullingResults cullingResults = new CullingResults();
	private SceneView cullingParent;
	private int cullingFlags;

	public boolean isDirty() {
		return dirtyFlags != 0;
	}

	public CullingResults getCullingResults() { return cullingResults; }

	public SceneView setCullingResults(CullingResults newVisibility) {
		if (newVisibility != cullingResults) {
			cullingResults = newVisibility;
			dirtyFlags &= ~TILE_VISIBILITY_DIRTY;
		}
		return this;
	}

	public boolean isProjDirty() { return (dirtyFlags & PROJECTION_MATRIX_DIRTY) != 0; }

	public boolean isViewDirty() { return (dirtyFlags & VIEW_MATRIX_DIRTY) != 0; }

	public boolean isTileVisibilityDirty() { return (dirtyFlags & TILE_VISIBILITY_DIRTY) != 0; }

	public int getCullingFlags() {return cullingFlags;}

	public boolean isCullingFlagSet(int flag) {return (cullingFlags & flag) == flag;}

	public SceneView toggleCullingFlag(int flag) {return setCullingFlag(flag, !isCullingFlagSet(flag));}

	public SceneView setCullingFlag(int flag) { return setCullingFlag(flag, true); }

	public SceneView setCullingFlag(int flag, boolean set) {
		if(set) {
			if((cullingFlags & flag) != flag) {
				cullingFlags |= flag;
				invalidateTileVisibility();
			}
		} else {
			if((cullingFlags & flag) == flag) {
				cullingFlags &= ~flag;
				invalidateTileVisibility();
			}
		}
		return this;
	}

	public SceneView setCullingParent(SceneView newCullingParent) {
		cullingParent = newCullingParent;
		return this;
	}

	public SceneView getCullingParent() { return cullingParent;}

	public boolean getIsOrthographic() {return isOrthographic; }

	public SceneView setOrthographic(boolean newOrthographic) {
		if(isOrthographic != newOrthographic) {
			isOrthographic = newOrthographic;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public SceneView setViewportWidth(int newViewportWidth) {
		if (viewportWidth != newViewportWidth) {
			viewportWidth = newViewportWidth;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public SceneView setViewportHeight(int newViewportHeight) {
		if (viewportHeight != newViewportHeight) {
			viewportHeight = newViewportHeight;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public SceneView setNearPlane(float newNearPlane) {
		if (nearPlane != newNearPlane) {
			nearPlane = newNearPlane;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float getNearPlane() {
		return nearPlane;
	}

	public SceneView setFarPlane(float newFarPlane) {
		if (farPlane != newFarPlane) {
			farPlane = newFarPlane;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float getFarPlane() {
		return farPlane;
	}

	public SceneView setZoom(float newZoom) {
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

	public SceneView setPositionX(float x) {
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

	public SceneView setPositionY(float y) {
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

	public SceneView setPositionZ(float z) {
		if (position[2] != z) {
			position[2] = z;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public SceneView setPosition(float[] newPosition) {
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

	public SceneView translate(float[] translation) {
		if (translation[0] != 0.0f || translation[1] != 0.0f || translation[2] != 0.0f) {
			position[0] += translation[0];
			position[1] += translation[1];
			position[2] += translation[2];
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public SceneView setYaw(float yaw) {
		if (orientation[0] != yaw) {
			orientation[0] = yaw;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float getYaw() {
		return orientation[0];
	}

	public SceneView setPitch(float pitch) {
		if (orientation[1] != pitch) {
			orientation[1] = pitch;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float getPitch() {
		return orientation[1];
	}

	public float[] getOrientation() {
		return Arrays.copyOf(orientation, 2);
	}

	public void invalidateTileVisibility() {
		dirtyFlags |= TILE_VISIBILITY_DIRTY;
	}

	public final boolean isRenderableVisible(
		Renderable renderable,
		boolean isStatic,
		int plane,
		int tileExX,
		int tileExY,
		int x,
		int y,
		int z,
		int modelRadius
	) {
		if (renderable instanceof Model || renderable instanceof DynamicObject || renderable instanceof TileItem) {
			if (isStatic) {
				return cullingResults.isTileRenderablesVisible(plane, tileExX, tileExY);
			} else {
				return cullingResults.isTileSurfaceVisible(plane, tileExX, tileExY);
			}
		} else {
			if (renderable instanceof NPC) {
				return cullingResults.isNPCVisible(((NPC) renderable).getId());
			} else if (renderable instanceof Player) {
				return cullingResults.isPlayerVisible(((Player) renderable).getId());
			} else if (renderable instanceof Projectile) {
				return cullingResults.isProjectileVisible(((Projectile) renderable).getId());
			}
		}
		return HDUtils.isSphereInsideFrustum(x, y, z, modelRadius, frustumPlanes);
	}

	public SceneView setOrientation(float[] newOrientation) {
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

	public float[] getViewMatrix() {
		calculateViewMatrix();
		return Arrays.copyOf(viewMatrix, viewMatrix.length);
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

	public float[] getProjectionMatrix() {
		calculateProjectionMatrix();
		return Arrays.copyOf(projectionMatrix, projectionMatrix.length);
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

	public float[] getViewProjMatrix() {
		calculateViewProjMatrix();
		return Arrays.copyOf(viewProjMatrix, viewProjMatrix.length);
	}

	private void calculateInvViewProjMatrix() {
		if ((dirtyFlags & INV_VIEW_PROJ_MATRIX_DIRTY) != 0) {
			calculateViewProjMatrix();
			invViewProjMatrix = Mat4.inverse(viewProjMatrix);
			dirtyFlags &= ~INV_VIEW_PROJ_MATRIX_DIRTY;
		}
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
				frustumPlanes[4], frustumPlanes[5],
				true
			);
			dirtyFlags &= ~FRUSTUM_PLANES_DIRTY;
		}
	}

	public float[][] getFrustumPlanes() {
		calculateFrustumPlanes();
		return frustumPlanes.clone();
	}

	public float[][] getFrustumCorners() {
		calculateInvViewProjMatrix();
		return Mat4.extractFrustumCorners(invViewProjMatrix);
	}
}
