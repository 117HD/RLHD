package rs117.hd.scene;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Job;
import rs117.hd.utils.SceneView;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

@Slf4j
@Singleton
public class SceneCullingManager {
	public static final byte VISIBILITY_UNKNOWN = -1;
	public static final byte VISIBILITY_HIDDEN = 0;
	public static final byte VISIBILITY_TILE_VISIBLE = 1;
	public static final byte VISIBILITY_UNDER_WATER_TILE_VISIBLE = 1 << 1;
	public static final byte VISIBILITY_RENDERABLE_VISIBLE = 1 << 2;

	@Inject
	private Client client;

	@Inject
	private FrameTimer frameTimer;

	public CullingResults combinedTileVisibility = new CullingResults();

	// Object Pools
	private final ArrayDeque<CullingResults> tileVisibilityBin = new ArrayDeque<>();
	private final ArrayDeque<SceneViewContext> sceneViewContextBin = new ArrayDeque<>();

	private final List<SceneView> cullingViews = new ArrayList<>();
	private final List<SceneViewContext> cullingViewContexts = new ArrayList<>();

	private final int frustumCullingJobCount;
	private final FrustumTileCullingJob[][] frustumCullingJobs;
	private final ResetVisibilityArrayJob clearJob = new ResetVisibilityArrayJob(this);
	private final FrustumSphereCullingJob playerCullingJob = new FrustumSphereCullingJob(this, FrustumSphereCullingJob.JobType.PLAYER);
	private final FrustumSphereCullingJob npcCullingJob = new FrustumSphereCullingJob(this, FrustumSphereCullingJob.JobType.NPC);
	private final FrustumSphereCullingJob projectileCullingJob = new FrustumSphereCullingJob(this, FrustumSphereCullingJob.JobType.PROJECTILES);

	public SceneCullingManager() {
		frustumCullingJobs = new FrustumTileCullingJob[EXTENDED_SCENE_SIZE / CHUNK_SIZE][EXTENDED_SCENE_SIZE / CHUNK_SIZE];
		frustumCullingJobCount = frustumCullingJobs.length * frustumCullingJobs.length;

		for(int x = 0; x < frustumCullingJobs.length; x++) {
			for(int y = 0; y < frustumCullingJobs.length; y++) {
				frustumCullingJobs[x][y] = new FrustumTileCullingJob(this);
			}
		}
	}

	public void startUp() { }

	public void complete() {
		clearJob.complete();
		playerCullingJob.complete();
		npcCullingJob.complete();
		projectileCullingJob.complete();

		for (FrustumTileCullingJob[] frustumCullingJob : frustumCullingJobs) {
			for (FrustumTileCullingJob cullingJob : frustumCullingJob) {
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

	public boolean isTileVisibleBlocking(int plane, int tileExX, int tileExY) {
		final int tileIdx = HDUtils.tileCoordinateToIndex(plane, tileExX, tileExY);
		int result = combinedTileVisibility.tiles[tileIdx];

		// Check if the result is usable & known
		if (result >= VISIBILITY_HIDDEN) {
			return result > 0;
		}

		frameTimer.begin(Timer.VISIBILITY_CHECK);
		// If the Tile is still in-progress then wait for the job to complete
		long waitStart = System.currentTimeMillis();
		while (combinedTileVisibility.inFlightTileJobCount > 0 && result == VISIBILITY_UNKNOWN) {
			Thread.yield();
			result = combinedTileVisibility.tiles[tileIdx];

			if (result == VISIBILITY_UNKNOWN && System.currentTimeMillis() - waitStart > 100) {
				break; // Dead-Lock Prevention
			}
		}
		frameTimer.end(Timer.VISIBILITY_CHECK);

		return result > 0;
	}

	private SceneViewContext getAvailableSceneViewContext() {
		return sceneViewContextBin.isEmpty() ? new SceneViewContext() : sceneViewContextBin.pop();
	}

	private CullingResults getAvailableCullingResults() {
		return tileVisibilityBin.isEmpty() ? new CullingResults() : tileVisibilityBin.pop();
	}

	public void onDraw(SceneContext sceneContext) {
		if(cullingViews.isEmpty()) {
			return; // No work to be done
		}

		// Make sure all work has been completed
		complete();

		// Ensure cullingViews is sorted such that parent SceneViews are processed first
		boolean needTileCulling = false;
		for(int i = 0; i < cullingViews.size(); i++) {
			SceneView currentView = cullingViews.get(i);
			needTileCulling = needTileCulling || currentView.isTileVisibilityDirty();

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

		if (needTileCulling) {
			clearJob.clearTargets.add(combinedTileVisibility);
			combinedTileVisibility = getAvailableCullingResults();
			combinedTileVisibility.inFlightTileJobCount = frustumCullingJobCount;
		}

		for(int i = 0; i < cullingViews.size(); i++) {
			final SceneView view = cullingViews.get(i);
			final SceneViewContext ctx = getAvailableSceneViewContext();
			ctx.frustumPlanes = view.getFrustumPlanes();
			ctx.viewProj = view.getViewProjMatrix();
			ctx.cullingFlags = view.getCullingFlags();
			ctx.parentIdx = -1;

			if (needTileCulling) {
				if (!view.isTileVisibilityDirty()) {
					ctx.cullingFlags |= SceneView.CULLING_FLAG_FREEZE;
				}

				if ((ctx.cullingFlags & SceneView.CULLING_FLAG_FREEZE) == 0) {
					if (view.getCullingResults() != null) {
						clearJob.clearTargets.add(view.getCullingResults());
					}
					view.setCullingResults(getAvailableCullingResults());
				}
				ctx.results = view.getCullingResults();
				ctx.results.inFlightTileJobCount = frustumCullingJobCount;

				final SceneView parent = view.getCullingParent();
				if (parent != null) {
					ctx.parentIdx = cullingViews.indexOf(parent);
				}
			}

			ctx.results.npcs.clear();
			ctx.results.players.clear();
			ctx.results.projectiles.clear();

			cullingViewContexts.add(ctx);
		}

		if (!cullingViewContexts.isEmpty()) {
			if (needTileCulling) {
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

			// Build Actor Culling Job
			final int[][][] tileHeights = sceneContext.scene.getTileHeights();
			final WorldView wv = client.getTopLevelWorldView();
			final int plane = wv.getPlane();
			for (Player player : wv.players()) {
				FrustumSphereCullingJob.BoundingSphere sphere = FrustumSphereCullingJob.getOrCreateBoundingSphere();
				var lp = player.getLocalLocation();
				sphere.x = lp.getX();
				sphere.y = tileHeights[plane][lp.getSceneX()][lp.getSceneY()];
				sphere.z = lp.getY();
				sphere.height = player.getModelHeight();
				sphere.id = player.getId();
				playerCullingJob.spheres.add(sphere);
			}

			if (!playerCullingJob.spheres.isEmpty()) {
				playerCullingJob.submit();
			}

			for (NPC npc : wv.npcs()) {
				FrustumSphereCullingJob.BoundingSphere sphere = FrustumSphereCullingJob.getOrCreateBoundingSphere();
				var lp = npc.getLocalLocation();
				sphere.x = lp.getX();
				sphere.y = tileHeights[plane][lp.getSceneX()][lp.getSceneY()];
				sphere.z = lp.getY();
				sphere.height = npc.getModelHeight();
				sphere.id = npc.getIndex();
				npcCullingJob.spheres.add(sphere);
			}

			if (!npcCullingJob.spheres.isEmpty()) {
				npcCullingJob.submit();
			}

			for (Projectile projectile : sceneContext.knownProjectiles) {
				FrustumSphereCullingJob.BoundingSphere sphere = FrustumSphereCullingJob.getOrCreateBoundingSphere();
				sphere.x = (float) projectile.getX();
				sphere.y = (float) projectile.getZ();
				sphere.z = (float) projectile.getY();
				sphere.height = projectile.getModelHeight();
				sphere.id = projectile.getId();
				projectileCullingJob.spheres.add(sphere);
			}

			if (!projectileCullingJob.spheres.isEmpty()) {
				projectileCullingJob.submit();
			}
		}

		if (!clearJob.clearTargets.isEmpty()) {
			clearJob.submit();
		}
		cullingViews.clear();
	}

	private static class SceneViewContext {
		public float[][] frustumPlanes;
		public float[] viewProj;
		public CullingResults results;
		public int parentIdx;
		public int cullingFlags;
	}

	public static final class CullingResults {
		private final byte[] tiles = new byte[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
		private final HashMap<Integer, Boolean> players = new HashMap<>();
		private final HashMap<Integer, Boolean> npcs = new HashMap<>();
		private final HashMap<Integer, Boolean> projectiles = new HashMap<>();

		private int inFlightTileJobCount = 0;

		public CullingResults() {
			reset();
		}

		public boolean isPlayerVisible(int id) { return players.getOrDefault(id, true); }

		public boolean isNPCVisible(int id) { return npcs.getOrDefault(id, true); }

		public boolean isProjectileVisible(int id) { return projectiles.getOrDefault(id, true); }

		public byte getTileResult(int tileIdx) {
			return tiles[tileIdx];
		}

		public byte getTileResult(int plane, int tileExX, int tileExY) {
			return tiles[HDUtils.tileCoordinateToIndex(
				plane,
				tileExX,
				tileExY
			)];
		}

		public boolean isTileSurfaceVisible(int tileIdx) {
			return isTileSurfaceVisible(getTileResult(tileIdx));
		}

		public boolean isTileSurfaceVisible(int plane, int tileExX, int tileExY) {
			return isTileSurfaceVisible(getTileResult(plane, tileExX, tileExY));
		}

		public boolean isTileUnderwaterVisible(int tileIdx) {
			return isTileUnderwaterVisible(getTileResult(tileIdx));
		}

		public boolean isTileUnderwaterVisible(int plane, int tileExX, int tileExY) {
			return isTileUnderwaterVisible(getTileResult(plane, tileExX, tileExY));
		}

		public boolean isTileRenderablesVisible(int tileIdx) {
			return isTileRenderablesVisible(getTileResult(tileIdx));
		}

		public boolean isTileRenderablesVisible(int plane, int tileExX, int tileExY) {
			return isTileRenderablesVisible(getTileResult(plane, tileExX, tileExY));
		}

		public static boolean isTileSurfaceVisible(byte tileCullingResult) {
			return (tileCullingResult & VISIBILITY_TILE_VISIBLE) == VISIBILITY_TILE_VISIBLE;
		}

		public static boolean isTileUnderwaterVisible(byte tileCullingResult) {
			return (tileCullingResult & VISIBILITY_UNDER_WATER_TILE_VISIBLE) == VISIBILITY_UNDER_WATER_TILE_VISIBLE;
		}

		public static boolean isTileRenderablesVisible(byte tileCullingResult) {
			return (tileCullingResult & VISIBILITY_RENDERABLE_VISIBLE) == VISIBILITY_RENDERABLE_VISIBLE;
		}

		public void reset() {
			Arrays.fill(tiles, VISIBILITY_UNKNOWN);
		}
	}

	@RequiredArgsConstructor
	public static final class ResetVisibilityArrayJob extends Job {
		private final SceneCullingManager cullManager;

		public List<CullingResults> clearTargets = new ArrayList<>();

		@Override
		protected void doWork() {
			for (CullingResults target : clearTargets) {
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
	public static final class FrustumTileCullingJob extends Job {
		private final SceneCullingManager cullManager;

		public SceneContext sceneContext;
		public int startX, endX;
		public int startY, endY;
		public int sceneID;

		// Job AABB
		private int aabb_MinX, aabb_MinY, aabb_MinZ;
		private int aabb_MaxX, aabb_MaxY, aabb_MaxZ;

		@Override
		protected void onComplete() {
			if(sceneID == sceneContext.id) {
				return;
			}
			sceneID = sceneContext.id;

			aabb_MinX = Integer.MAX_VALUE;
			aabb_MinY = Integer.MAX_VALUE;
			aabb_MinZ = Integer.MAX_VALUE;

			aabb_MaxX = Integer.MIN_VALUE;
			aabb_MaxY = Integer.MIN_VALUE;
			aabb_MaxZ = Integer.MIN_VALUE;

			// Build Job AABB, used for early out to avoid performing expensive frustum culling for all tiles
			final int[][][] tileHeights = sceneContext.scene.getTileHeights();
			for(int plane = 0; plane < MAX_Z; plane++) {
				for (int tileExX = startX; tileExX < endX; tileExX++) {
					for (int tileExY = startY; tileExY < endY; tileExY++) {
						int h0 = tileHeights[plane][tileExX][tileExY];
						int h1 = tileHeights[plane][tileExX + 1][tileExY];
						int h2 = tileHeights[plane][tileExX][tileExY + 1];
						int h3 = tileHeights[plane][tileExX + 1][tileExY + 1];

						int tileX = (tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
						int tileZ = (tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;

						int localMinY = Math.min(Math.min(h0, h1), Math.min(h2, h3));
						int localMaxY = Math.max(Math.max(h0, h1), Math.max(h2, h3));

						aabb_MinX = Math.min(aabb_MinX, tileX);
						aabb_MinZ = Math.min(aabb_MinZ, tileZ);
						aabb_MinY = Math.min(aabb_MinY, localMinY);

						aabb_MaxX = Math.max(aabb_MaxX, tileX + LOCAL_TILE_SIZE);
						aabb_MaxZ = Math.max(aabb_MaxZ, tileZ + LOCAL_TILE_SIZE);
						aabb_MaxY = Math.max(aabb_MaxY, localMaxY);

						if(sceneContext.tileIsWater[plane][tileExX][tileExY]) {
							final int dl0 = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY];
							final int dl1 = sceneContext.underwaterDepthLevels[plane][tileExX + 1][tileExY];
							final int dl2 = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY + 1];
							final int dl3 = sceneContext.underwaterDepthLevels[plane][tileExX + 1][tileExY + 1];

							if(dl0 > 0) h0 += ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl0 - 1];
							if(dl1 > 0) h1 += ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl1 - 1];
							if(dl2 > 0) h2 += ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl2 - 1];
							if(dl3 > 0) h3 += ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl3 - 1];

							localMinY = Math.min(Math.min(h0, h1), Math.min(h2, h3));
							localMaxY = Math.max(Math.max(h0, h1), Math.max(h2, h3));

							aabb_MinX = Math.min(aabb_MinX, tileX);
							aabb_MinZ = Math.min(aabb_MinZ, tileZ);
							aabb_MinY = Math.min(aabb_MinY, localMinY);

							aabb_MaxX = Math.max(aabb_MaxX, tileX + LOCAL_TILE_SIZE);
							aabb_MaxZ = Math.max(aabb_MaxZ, tileZ + LOCAL_TILE_SIZE);
							aabb_MaxY = Math.max(aabb_MaxY, localMaxY);
						}
					}
				}
			}
		}

		@Override
		protected void doWork() {
			{
				boolean isJobAreaVisible = false;
				for (SceneViewContext viewCtx : cullManager.cullingViewContexts) {
					if ((viewCtx.cullingFlags & SceneView.CULLING_FLAG_FREEZE) != 0)
						continue;

					if (HDUtils.isAABBVisible(aabb_MinX, aabb_MinY, aabb_MinZ, aabb_MaxX, aabb_MaxY, aabb_MaxZ, viewCtx.frustumPlanes, 0)) {
						isJobAreaVisible = true;
						break;
					}
				}

				if (!isJobAreaVisible) {
					for(int plane = 0; plane < MAX_Z; plane++) {
						for (int tileExX = startX; tileExX < endX; tileExX++) {
							for (int tileExY = startY; tileExY < endY; tileExY++) {
								final int tileIdx = HDUtils.tileCoordinateToIndex(plane, tileExX, tileExY);
								byte combinedResult = VISIBILITY_HIDDEN;
								for (SceneViewContext viewCtx : cullManager.cullingViewContexts) {
									if ((viewCtx.cullingFlags & SceneView.CULLING_FLAG_FREEZE) != 0) {
										combinedResult |= viewCtx.results.tiles[tileIdx];
										continue;
									}

									viewCtx.results.tiles[tileIdx] = VISIBILITY_HIDDEN;
								}
								cullManager.combinedTileVisibility.tiles[tileIdx] = combinedResult;
							}
						}
					}

					// Skip processing entirely
					for(SceneViewContext viewCtx : cullManager.cullingViewContexts) {
						viewCtx.results.inFlightTileJobCount--;
					}
					cullManager.combinedTileVisibility.inFlightTileJobCount--;
					return;
				}
			}

			final int[][][] tileHeights = sceneContext.scene.getTileHeights();
			for(int plane = 0; plane < MAX_Z; plane++) {
				for (int tileExX = startX; tileExX < endX; tileExX++) {
					for (int tileExY = startY; tileExY < endY; tileExY++) {
						final int tileIdx = HDUtils.tileCoordinateToIndex(plane, tileExX, tileExY);

						// Surface Plane Heights
						final int h0 = tileHeights[plane][tileExX][tileExY];
						final int h1 = tileHeights[plane][tileExX + 1][tileExY];
						final int h2 = tileHeights[plane][tileExX][tileExY + 1];
						final int h3 = tileHeights[plane][tileExX + 1][tileExY + 1];

						// Positions
						final int x = (tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
						final int z = (tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;
						final int cX = x + LOCAL_HALF_TILE_SIZE;
						final int cZ = z + LOCAL_HALF_TILE_SIZE;
						final int cH = (int) ((h0 + h1 + h2 + h3) / 4.0f);

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

						byte combinedResult = VISIBILITY_HIDDEN;
						for(SceneViewContext viewCtx : cullManager.cullingViewContexts) {
							if((viewCtx.cullingFlags & SceneView.CULLING_FLAG_FREEZE) != 0) {
								combinedResult |= viewCtx.results.tiles[tileIdx];
								continue;
							}

							// Check if Parent view has already determined that it's visible if so we can use that result and early out
							if(viewCtx.parentIdx != -1) {
								SceneViewContext parentViewCtx = cullManager.cullingViewContexts.get(viewCtx.parentIdx);
								byte parentViewResult = parentViewCtx.results.tiles[tileIdx];
								if ((parentViewResult & (VISIBILITY_TILE_VISIBLE | VISIBILITY_RENDERABLE_VISIBLE)) == (
									VISIBILITY_TILE_VISIBLE | VISIBILITY_RENDERABLE_VISIBLE
								)) {
									if(plane == 0 && (viewCtx.cullingFlags & SceneView.CULLING_FLAG_GROUND_PLANES) == 0){
										parentViewResult &= ~VISIBILITY_TILE_VISIBLE;
									}
									viewCtx.results.tiles[tileIdx] = parentViewResult;
									continue; // Early out, no need to perform any culling
								}
							}

							byte viewResult = VISIBILITY_HIDDEN;
							if(plane != 0 || (viewCtx.cullingFlags & SceneView.CULLING_FLAG_GROUND_PLANES) != 0){
								if (!HDUtils.isTileBackFacing(x, z, h0, h1, h2, h3, viewCtx.viewProj)) {
									viewResult |= HDUtils.IsTileVisible(
										x,
										z,
										h0,
										h1,
										h2,
										h3,
										viewCtx.frustumPlanes,
										-LOCAL_HALF_TILE_SIZE
									) ? VISIBILITY_TILE_VISIBLE : 0;// SceneView doesn't want to cull GroundPlanes, Consider them all hidden
								}
							}

							if(hasUnderwaterTile && (viewCtx.cullingFlags & SceneView.CULLING_FLAG_UNDERWATER_PLANES) != 0) {
								viewResult |= HDUtils.IsTileVisible(
									x,
									z,
									uh0,
									uh1,
									uh2,
									uh3,
									viewCtx.frustumPlanes,
									-(LOCAL_TILE_SIZE * 4)
								) ?
									VISIBILITY_UNDER_WATER_TILE_VISIBLE :
									0;
							}

							if((viewCtx.cullingFlags & SceneView.CULLING_FLAG_RENDERABLES) != 0) {
								SceneContext.RenderableCullingData[] renderableCullingData = sceneContext.tileRenderableCullingData[plane][tileExX][tileExY];
								if(renderableCullingData != null && renderableCullingData.length > 0) {
									for (SceneContext.RenderableCullingData renderable : renderableCullingData) {
										final int radius = renderable.radius;
										if (Math.abs(renderable.bottomY) < LOCAL_TILE_SIZE && renderable.height < LOCAL_TILE_SIZE) {
											// Renderable is probably laying along surface of tile, if surface isn't visible then its safe to cull this too
											if ((viewResult & VISIBILITY_TILE_VISIBLE) == 0) {
												continue; // Surface isn't visible, skip this renderable
											}
										}
										if (HDUtils.isAABBVisible(
											cX - radius,
											cH - renderable.bottomY,
											cZ - radius,
											cX + radius,
											cH - renderable.bottomY + renderable.height,
											cZ + radius,
											viewCtx.frustumPlanes,
											-LOCAL_TILE_SIZE
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
							viewCtx.results.tiles[tileIdx] = viewResult;
						}
						cullManager.combinedTileVisibility.tiles[tileIdx] = combinedResult;
					}
				}
			}

			for(SceneViewContext viewCtx : cullManager.cullingViewContexts) {
				viewCtx.results.inFlightTileJobCount--;
			}
			cullManager.combinedTileVisibility.inFlightTileJobCount--;
		}
	}

	@RequiredArgsConstructor
	public static final class FrustumSphereCullingJob extends Job {
		private static final ArrayDeque<BoundingSphere> BOUNDING_SPHERE_BIN = new ArrayDeque<>();

		public static BoundingSphere getOrCreateBoundingSphere() {
			return BOUNDING_SPHERE_BIN.isEmpty() ? new BoundingSphere() : BOUNDING_SPHERE_BIN.pop();
		}

		public enum JobType { PLAYER, NPC, PROJECTILES }

		public static class BoundingSphere {
			public float x, y, z;
			public int height;
			public int id;
		}

		private final SceneCullingManager cullManager;
		private final JobType type;
		public List<BoundingSphere> spheres = new ArrayList<>();

		@Override
		protected void doWork() {
			for (BoundingSphere sphere : spheres) {
				for (SceneViewContext viewCtx : cullManager.cullingViewContexts) {
					final boolean visible = HDUtils.isSphereInsideFrustum(
						sphere.x,
						sphere.y,
						sphere.z,
						sphere.height,
						viewCtx.frustumPlanes
					);

					switch (type) {
						case PLAYER:
							viewCtx.results.players.put(sphere.id, visible);
							break;
						case NPC:
							viewCtx.results.npcs.put(sphere.id, visible);
							break;
						case PROJECTILES:
							viewCtx.results.projectiles.put(sphere.id, visible);
							break;
					}
				}
			}
		}

		@Override
		protected void onComplete() {
			BOUNDING_SPHERE_BIN.addAll(spheres);
			spheres.clear();
		}
	}
}
