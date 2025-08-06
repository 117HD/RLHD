package rs117.hd.scene;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Job;
import rs117.hd.utils.SceneView;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class SceneCullingManager {
	public static final int VISIBILITY_UNKNOWN = -1;
	public static final int VISIBILITY_HIDDEN = 0;
	public static final int VISIBILITY_TILE_VISIBLE = 1;
	public static final int VISIBILITY_UNDER_WATER_TILE_VISIBLE = 1 << 1;
	public static final int VISIBILITY_RENDERABLE_VISIBLE = 1 << 2;

	@Inject
	private FrameTimer frameTimer;

	public TileVisibility combinedTileVisibility = new TileVisibility();

	// Object Pools
	private final ArrayDeque<TileVisibility> tileVisibilityBin = new ArrayDeque<>();
	private final ArrayDeque<SceneViewContext> sceneViewContextBin = new ArrayDeque<>();

	private final List<SceneView> cullingViews = new ArrayList<>();
	private final List<SceneViewContext> cullingViewContexts = new ArrayList<>();

	private final ResetVisibilityArrayJob clearJob = new ResetVisibilityArrayJob(this);
	private int frustumCullingJobCount;
	private FrustumCullingJob[][] frustumCullingJobs;

	public void startUp() {
		int sqJobCount = max(1, (int)sqrt(HdPlugin.PROCESSOR_COUNT - 1));
		frustumCullingJobCount = sqJobCount * sqJobCount;
		frustumCullingJobs = new FrustumCullingJob[sqJobCount][sqJobCount];
		for(int x = 0; x < sqJobCount; x++) {
			for(int y = 0; y < sqJobCount; y++) {
				frustumCullingJobs[x][y] = new FrustumCullingJob(this);
			}
		}
	}

	public void complete() {
		clearJob.complete();

		for (FrustumCullingJob[] frustumCullingJob : frustumCullingJobs) {
			for (FrustumCullingJob cullingJob : frustumCullingJob) {
				cullingJob.complete();
			}
		}

		sceneViewContextBin.addAll(cullingViewContexts);
		cullingViewContexts.clear();
	}

	public void shutDown() {
		complete();
	}

	public void addView(SceneView view) {
		if(!cullingViews.contains(view)) {
			cullingViews.add(view);
		}
	}

	private SceneViewContext getAvailableSceneViewContext() {
		return sceneViewContextBin.isEmpty() ? new SceneViewContext() : sceneViewContextBin.pop();
	}

	private TileVisibility getAvailableTileVisibility() {
		return tileVisibilityBin.isEmpty() ? new TileVisibility() : tileVisibilityBin.pop();
	}

	public void onDraw(SceneContext sceneContext) {
		if(cullingViews.isEmpty()) {
			return; // No work to be done
		}

		// Make sure all work has been completed
		complete();

		// Ensure cullingViews is sorted such that parent SceneViews are processed first
		for(int i = 0; i < cullingViews.size(); i++) {
			SceneView currentView = cullingViews.get(i);
			SceneView parent = currentView.getCullingParent();
			if(parent != null) {
				int parentIdx = cullingViews.indexOf(parent);
				if(parentIdx > i) {
					// Move the parent to be before this view
					cullingViews.remove(parent);
					cullingViews.add(i, parent);
				}
			}
		}

		clearJob.clearTargets.add(combinedTileVisibility);
		combinedTileVisibility = getAvailableTileVisibility();
		combinedTileVisibility.inFlightCount = frustumCullingJobCount;

		for(int i = 0; i < cullingViews.size(); i++) {
			final SceneView view = cullingViews.get(i);
			final SceneViewContext ctx = getAvailableSceneViewContext();
			ctx.frustumPlanes = view.getFrustumPlanes();
			ctx.cullingFlags = view.getCullingFlags();
			ctx.parentIdx = -1;

			if((ctx.cullingFlags & SceneView.CULLING_FLAG_FREEZE) == 0) {
				if (view.getTileVisibility() != null) {
					clearJob.clearTargets.add(view.getTileVisibility());
				}
				view.setTileVisibility(getAvailableTileVisibility());
			}
			ctx.tileVisibility = view.getTileVisibility();
			ctx.tileVisibility.inFlightCount = frustumCullingJobCount;

			final SceneView parent = view.getCullingParent();
			if(parent != null) {
				ctx.parentIdx = cullingViews.indexOf(parent);
			}

			cullingViewContexts.add(ctx);
		}

		if(!cullingViewContexts.isEmpty()) {
			int SceneTileCountPerJob = EXTENDED_SCENE_SIZE / frustumCullingJobs.length;
			for (int x = 0; x < frustumCullingJobs.length; x++) {
				for (int y = 0; y < frustumCullingJobs[x].length; y++) {
					var cullingJob = frustumCullingJobs[x][y];
					cullingJob.startX = SceneTileCountPerJob * x;
					cullingJob.startY = SceneTileCountPerJob * y;

					cullingJob.endX = cullingJob.startX + SceneTileCountPerJob;
					cullingJob.endY = cullingJob.startY + SceneTileCountPerJob;

					cullingJob.sceneContext = sceneContext;

					cullingJob.submit();
				}
			}
		}

		clearJob.submit();
		cullingViews.clear();
	}

	private static class SceneViewContext {
		public float[][] frustumPlanes;
		public TileVisibility tileVisibility;
		public int parentIdx;
		public int cullingFlags;
	}

	public static final class TileVisibility {
		private final int[][][] results = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE];
		private int inFlightCount = 0;

		public TileVisibility() {
			reset();
		}

		public boolean isTileVisibleBlocking(int plane, int tileExX, int tileExY) {
			int result = results[plane][tileExX][tileExY];

			// Check if the result is usable & known
			if (result >= VISIBILITY_HIDDEN) {
				return result > 0;
			}

			// If the Tile is still in-progress then wait for the job to complete
			long waitStart = System.currentTimeMillis();
			while (inFlightCount > 0 && result == VISIBILITY_UNKNOWN) {
				Thread.yield();
				result = results[plane][tileExX][tileExY];

				if(result == VISIBILITY_UNKNOWN && System.currentTimeMillis() - waitStart > 100) {
					break; // Dead-Lock Prevention
				}
			}

			return result > 0;
		}

		public boolean isTileSurfaceVisible(int plane, int tileExX, int tileExY) {
			return (results[plane][tileExX][tileExY] & VISIBILITY_TILE_VISIBLE) != 0;
		}

		public boolean isTileUnderwaterVisible(int plane, int tileExX, int tileExY) {
			return (results[plane][tileExX][tileExY] & VISIBILITY_UNDER_WATER_TILE_VISIBLE) != 0;
		}

		public boolean isTileRenderablesVisible(int plane, int tileExX, int tileExY) {
			return (results[plane][tileExX][tileExY] & VISIBILITY_RENDERABLE_VISIBLE) != 0;
		}

		public void reset() {
			for(int plane = 0; plane < MAX_Z; plane++) {
				for(int tileExX = 0; tileExX < EXTENDED_SCENE_SIZE; tileExX++) {
					for(int tileExY = 0; tileExY < EXTENDED_SCENE_SIZE; tileExY++) {
						results[plane][tileExX][tileExY] = VISIBILITY_UNKNOWN;
					}
				}
			}
		}
	}

	@RequiredArgsConstructor
	public static final class ResetVisibilityArrayJob extends Job {
		private final SceneCullingManager cullManager;

		public List<TileVisibility> clearTargets = new ArrayList<>();

		@Override
		protected void doWork() {
			for(TileVisibility target : clearTargets) {
				target.reset();
			}
		}

		@Override
		protected void onComplete() {
			super.onComplete();
			cullManager.tileVisibilityBin.addAll(clearTargets);
			clearTargets.clear();
		}
	}

	@RequiredArgsConstructor
	public static final class FrustumCullingJob extends Job {
		private final SceneCullingManager cullManager;

		public SceneContext sceneContext;
		public int startX, endX;
		public int startY, endY;

		@Override
		protected void doWork() {
			int[][][] tileHeights = sceneContext.scene.getTileHeights();
			for(int plane = 0; plane < MAX_Z; plane++) {
				for (int tileExX = startX; tileExX < endX; tileExX++) {
					for (int tileExY = startY; tileExY < endY; tileExY++) {
						final int x = (tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
						final int z = (tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;

						// Surface Plane Heights
						final int h0 = tileHeights[plane][tileExX][tileExY];
						final int h1 = tileHeights[plane][tileExX + 1][tileExY];
						final int h2 = tileHeights[plane][tileExX][tileExY + 1];
						final int h3 = tileHeights[plane][tileExX + 1][tileExY + 1];
						final int ch = (int) ((h0 + h1 + h2 + h3) / 4.0f);

						// Underwater Plane Heights
						boolean hasUnderwaterTile = false;
						int uh0 = h0;
						int uh1 = h1;
						int uh2 = h2;
						int uh3 = h3;

						if(sceneContext.tileIsWater[plane][tileExX][tileExY]) {
							final int dl0 = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY];
							final int dl1 = sceneContext.underwaterDepthLevels[plane][tileExX + 1][tileExY];
							final int dl2 = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY + 1];
							final int dl3 = sceneContext.underwaterDepthLevels[plane][tileExX + 1][tileExY + 1];

							hasUnderwaterTile = dl0 > 0 || dl1 > 0 || dl2 > 0 || dl3 > 0;
							if(dl0 > 0) uh0 += ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl0 - 1];
							if(dl1 > 0) uh1 += ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl1 - 1];
							if(dl2 > 0) uh2 += ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl2 - 1];
							if(dl3 > 0) uh3 += ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl3 - 1];
						}

						int combinedResult = VISIBILITY_HIDDEN;
						for(SceneViewContext viewCtx : cullManager.cullingViewContexts) {
							if((viewCtx.cullingFlags & SceneView.CULLING_FLAG_FREEZE) != 0) {
								combinedResult |= viewCtx.tileVisibility.results[plane][tileExX][tileExY];
								continue;
							}

							// Check if Parent view has already determined that it's visible if so we can use that result and early out
							if(viewCtx.parentIdx != -1) {
								SceneViewContext parentViewCtx = cullManager.cullingViewContexts.get(viewCtx.parentIdx);
								int parentViewResult = parentViewCtx.tileVisibility.results[plane][tileExX][tileExY];
								if(parentViewResult != VISIBILITY_HIDDEN) {
									if(plane == 0 && (viewCtx.cullingFlags & SceneView.CULLING_FLAG_GROUND_PLANES) == 0){
										parentViewResult &= ~VISIBILITY_TILE_VISIBLE;
									}
									viewCtx.tileVisibility.results[plane][tileExX][tileExY] = parentViewResult;
									continue; // Early out, no need to perform any culling
								}
							}

							int viewResult = VISIBILITY_HIDDEN;
							if(plane != 0 || (viewCtx.cullingFlags & SceneView.CULLING_FLAG_GROUND_PLANES) != 0){
								viewResult |= HDUtils.IsTileVisible(x, z, h0, h1, h2, h3, viewCtx.frustumPlanes, -LOCAL_HALF_TILE_SIZE) ? VISIBILITY_TILE_VISIBLE : 0;// SceneView doesn't want to cull GroundPlanes, Consider them all hidden
							}

							if(hasUnderwaterTile && (viewCtx.cullingFlags & SceneView.CULLING_FLAG_UNDERWATER_PLANES) != 0) {
								viewResult |= HDUtils.IsTileVisible(x, z, uh0, uh1, uh2, uh3, viewCtx.frustumPlanes, -(LOCAL_TILE_SIZE * 4)) ?
									VISIBILITY_UNDER_WATER_TILE_VISIBLE :
									0;
							}

							if((viewCtx.cullingFlags & SceneView.CULLING_FLAG_RENDERABLES) != 0) {
								SceneContext.RenderableCullingData[] renderableCullingData = sceneContext.tileRenderableCullingData[plane][tileExX][tileExY];
								if(renderableCullingData != null && renderableCullingData.length > 0) {
									for (SceneContext.RenderableCullingData renderable : renderableCullingData) {
										final int bottom = ch - renderable.bottomY;
										final int radius = renderable.radius;
										if (HDUtils.isAABBVisible(
											x - radius,
											bottom,
											z - radius,
											x + radius,
											bottom + renderable.height,
											z + radius,
											viewCtx.frustumPlanes,
											-LOCAL_HALF_TILE_SIZE
										)) {
											viewResult |= VISIBILITY_RENDERABLE_VISIBLE;
											break;
										}
									}
								} else {
									// No Static Culling data was present, allow missed static renderables to be visible here
									viewResult |= VISIBILITY_RENDERABLE_VISIBLE;
								}
							}

							combinedResult |= viewResult;
							viewCtx.tileVisibility.results[plane][tileExX][tileExY] = viewResult;
						}
						cullManager.combinedTileVisibility.results[plane][tileExX][tileExY] = combinedResult;
					}
				}
			}

			for(SceneViewContext viewCtx : cullManager.cullingViewContexts) {
				viewCtx.tileVisibility.inFlightCount--;
			}
			cullManager.combinedTileVisibility.inFlightCount--;
		}
	}
}
