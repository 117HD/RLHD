package rs117.hd.utils;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

@Slf4j
public class SceneView {
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

	private final FrameTimer frameTimer;

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

	public boolean freezeCulling;

	public enum VisibilityResult {
		UNKNOWN, IN_PROGRESS, HIDDEN, VISIBLE;

		public boolean isKnown() { return this == HIDDEN || this == VISIBLE; }
	}

	private VisibilityResult[][][] tileVisibility = new VisibilityResult[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE];

	private final AsyncCullingJob[] cullingJobs = new AsyncCullingJob[MAX_Z];
	private final AsyncTileVisibilityClear clearJob = new AsyncTileVisibilityClear(this);

	public SceneView(FrameTimer frameTimer, boolean isOrthographic) {
		this.frameTimer = frameTimer;
		this.isOrthographic = isOrthographic;

		for (int plane = 0; plane < MAX_Z; plane++) {
			cullingJobs[plane] = new AsyncCullingJob(this, plane);
		}
		clearJob.submit(true);
	}

	public boolean isDirty() {
		return dirtyFlags != 0;
	}

	public boolean isProjDirty() { return (dirtyFlags & PROJECTION_MATRIX_DIRTY) != 0; }

	public boolean isViewDirty() { return (dirtyFlags & VIEW_MATRIX_DIRTY) != 0; }

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

	@SneakyThrows
	public void performAsyncTileCulling(SceneContext ctx, boolean checkUnderwater) {
		if (ctx == null) {
			return;
		}

		if(freezeCulling) {
			dirtyFlags &= ~TILE_VISIBILITY_DIRTY;
			return;
		}

		clearJob.complete(true);
		for (AsyncCullingJob planeJob : cullingJobs) {
			planeJob.complete(true);
		}

		calculateFrustumPlanes();
		clearJob.submit();

		dirtyFlags &= ~TILE_VISIBILITY_DIRTY;
		for (AsyncCullingJob planeJob : cullingJobs) {
			planeJob.tileHeights = ctx.scene.getTileHeights()[planeJob.plane];
			planeJob.tileIsWater = checkUnderwater ? ctx.tileIsWater[planeJob.plane] : null;
			planeJob.underwaterDepthLevels =
				checkUnderwater && ctx.underwaterDepthLevels != null ? ctx.underwaterDepthLevels[planeJob.plane] : null;
			planeJob.submit();
		}
	}

	public boolean isTileVisibleFast(int plane, int tileExX, int tileExY) {
		return tileVisibility[plane][tileExX][tileExY] == VisibilityResult.VISIBLE;
	}

	@SneakyThrows
	public boolean isTileVisible(int plane, int tileExX, int tileExY) {
		frameTimer.begin(Timer.VISIBILITY_CHECK);
		VisibilityResult result = tileVisibility[plane][tileExX][tileExY];

		// Check if the result is usable & known
		if ((dirtyFlags & TILE_VISIBILITY_DIRTY) == 0 && result.ordinal() > VisibilityResult.IN_PROGRESS.ordinal()) {
			frameTimer.end(Timer.VISIBILITY_CHECK);
			return result == VisibilityResult.VISIBLE;
		}

		if (result == VisibilityResult.UNKNOWN) {
			// Process on client thread, rather than waiting for result
			result = cullingJobs[plane].performTileCulling(tileExX, tileExY);
		}

		// If the Tile is still in-progress then wait for the job to complete
		while (result == VisibilityResult.IN_PROGRESS) {
			Thread.yield();
			result = tileVisibility[plane][tileExX][tileExY];
		}

		frameTimer.end(Timer.VISIBILITY_CHECK);
		return result == VisibilityResult.VISIBLE;
	}

	public boolean isModelVisible(Model model, int x, int y, int z) {
		calculateFrustumPlanes();
		return HDUtils.isModelVisible(x, y, z, model, frustumPlanes);
	}

	public boolean isSphereVisible(float x, float y, float z, int radius) {
		calculateFrustumPlanes();
		return HDUtils.isSphereInsideFrustum(x, y, z, radius, frustumPlanes);
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
			projectionMatrix = Mat4.scale(zoom, zoom, 1.0f);
			if (isOrthographic) {
				Mat4.mul(projectionMatrix, Mat4.orthographic(viewportWidth, viewportHeight, nearPlane));
			} else {
				if (farPlane > 0.0f) {
					Mat4.mul(projectionMatrix, Mat4.perspective(viewportWidth, viewportHeight, nearPlane, farPlane));
				} else {
					Mat4.mul(projectionMatrix, Mat4.perspective(viewportWidth, viewportHeight, nearPlane));
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

	@RequiredArgsConstructor
	public static final class AsyncTileVisibilityClear extends Job {
		private final SceneView view;
		private VisibilityResult[][][] clearTarget = new VisibilityResult[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE];

		protected void prepare() {
			// Swap the Visibility results with last frames, which should have been cleared by now
			VisibilityResult[][][] nextClearTarget = view.tileVisibility;
			view.tileVisibility = clearTarget;
			clearTarget = nextClearTarget;
		}

		protected void doWork() {
			for (int z = 0; z < MAX_Z; z++) {
				for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
					Arrays.fill(clearTarget[z][x], VisibilityResult.UNKNOWN);
				}
			}
		}
	}

	@RequiredArgsConstructor
	public static final class AsyncCullingJob extends Job {
		private final SceneView view;
		private final int plane;

		private int[][] tileHeights;
		private boolean[][] tileIsWater;
		private int[][] underwaterDepthLevels;

		@Override
		protected void doWork() {
			for (int tileExX = 0; tileExX < EXTENDED_SCENE_SIZE; tileExX++) {
				for (int tileExY = 0; tileExY < EXTENDED_SCENE_SIZE; tileExY++) {
					performTileCulling(tileExX, tileExY);
				}
			}
		}

		public VisibilityResult performTileCulling(int tileExX, int tileExY) {
			VisibilityResult result = view.tileVisibility[plane][tileExX][tileExY];
			if (result != VisibilityResult.UNKNOWN) { // Skip over tiles that are being processed or are known
				return result;
			}
			view.tileVisibility[plane][tileExX][tileExY] = VisibilityResult.IN_PROGRESS; // Signal that we are processing this tile (Could be Client or Job Thread doing so)

			final int h0 = tileHeights[tileExX][tileExY];
			final int h1 = tileHeights[tileExX + 1][tileExY];
			final int h2 = tileHeights[tileExX][tileExY + 1];
			final int h3 = tileHeights[tileExX + 1][tileExY + 1];

			int x = (tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
			int z = (tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;

			result = HDUtils.IsTileVisible(x, z, h0, h1, h2, h3, view.frustumPlanes) ? VisibilityResult.VISIBLE : VisibilityResult.HIDDEN;

			if (result == VisibilityResult.HIDDEN && underwaterDepthLevels != null && tileIsWater != null
				&& tileIsWater[tileExX][tileExY]) {
				final int dl0 = underwaterDepthLevels[tileExX][tileExY];
				final int dl1 = underwaterDepthLevels[tileExX + 1][tileExY];
				final int dl2 = underwaterDepthLevels[tileExX][tileExY + 1];
				final int dl3 = underwaterDepthLevels[tileExX + 1][tileExY + 1];

				if (dl0 > 0 || dl1 > 0 || dl2 > 0 || dl3 > 0) {
					final int uh0 = h0 + (dl0 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl0 - 1] : 0);
					final int uh1 = h1 + (dl1 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl1 - 1] : 0);
					final int uh2 = h2 + (dl2 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl2 - 1] : 0);
					final int uh3 = h3 + (dl3 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl3 - 1] : 0);

					// TODO: Had to pad the underwater tile check to get it to pass when its really close the nearPlane
					result = HDUtils.IsTileVisible(x, z, uh0, uh1, uh2, uh3, view.frustumPlanes, -(LOCAL_TILE_SIZE * 4)) ?
						VisibilityResult.VISIBLE :
						VisibilityResult.HIDDEN;
				}
			}

			return view.tileVisibility[plane][tileExX][tileExY] = result;
		}
	}
}
