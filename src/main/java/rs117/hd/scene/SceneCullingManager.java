package rs117.hd.scene;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.data.StaticRenderable;
import rs117.hd.data.StaticRenderableInstance;
import rs117.hd.data.StaticTileData;
import rs117.hd.model.ModelPusher;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Job;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.SceneView;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class SceneCullingManager {
	private static final int JOB_BATCH_SIZE = CHUNK_SIZE;

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

	private final FrustumTileCullingJob[][] frustumCullingJobs;
	private final ResetVisibilityArrayJob clearJob = new ResetVisibilityArrayJob(this);
	private final FrustumSphereCullingJob playerCullingJob = new FrustumSphereCullingJob(this, FrustumSphereCullingJob.JobType.PLAYER);
	private final FrustumSphereCullingJob npcCullingJob = new FrustumSphereCullingJob(this, FrustumSphereCullingJob.JobType.NPC);
	private final FrustumSphereCullingJob projectileCullingJob = new FrustumSphereCullingJob(this, FrustumSphereCullingJob.JobType.PROJECTILES);
	private final FrustumSphereCullingJob graphicObjectCullingJob = new FrustumSphereCullingJob(this, FrustumSphereCullingJob.JobType.GRAPHICS_OBJECTS);

	private boolean cullingInFlight = false;

	public SceneCullingManager() {
		frustumCullingJobs = new FrustumTileCullingJob[EXTENDED_SCENE_SIZE / JOB_BATCH_SIZE][EXTENDED_SCENE_SIZE / JOB_BATCH_SIZE];
		for(int x = 0; x < frustumCullingJobs.length; x++) {
			for(int y = 0; y < frustumCullingJobs.length; y++) {
				frustumCullingJobs[x][y] = new FrustumTileCullingJob(this);
			}
		}
	}

	public void startUp() { }

	public boolean ensureCullingComplete() {
		if(!cullingInFlight) {
			return false;
		}
		cullingInFlight = false;

		frameTimer.begin(Timer.VISIBILITY_CHECK);
		playerCullingJob.complete();
		npcCullingJob.complete();
		projectileCullingJob.complete();
		graphicObjectCullingJob.complete();

		for (FrustumTileCullingJob[] frustumCullingJob : frustumCullingJobs) {
			for (FrustumTileCullingJob cullingJob : frustumCullingJob) {
				cullingJob.complete();
			}
		}
		frameTimer.end(Timer.VISIBILITY_CHECK);

		return true;
	}

	public void complete() {
		ensureCullingComplete();

		clearJob.complete();

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
		int jobX = tileExX / JOB_BATCH_SIZE;
		int jobY = tileExY / JOB_BATCH_SIZE;
		if (jobX > 0 && jobX < frustumCullingJobs.length &&
			jobY > 0 && jobY < frustumCullingJobs[jobX].length) {
			while (frustumCullingJobs[jobX][jobY].isInFlight() && result == VISIBILITY_UNKNOWN) {
				frustumCullingJobs[jobX][jobY].awaitCompletion(false, 100);
				result = combinedTileVisibility.tiles[tileIdx];
			}
		}
		frameTimer.end(Timer.VISIBILITY_CHECK);

		return combinedTileVisibility.tiles[tileIdx] > 0;
	}

	private SceneViewContext getAvailableSceneViewContext() {
		return sceneViewContextBin.isEmpty() ? new SceneViewContext() : sceneViewContextBin.pop();
	}

	private CullingResults getAvailableCullingResults() {
		return tileVisibilityBin.isEmpty() ? new CullingResults() : tileVisibilityBin.pop();
	}

	public void appendTileCullingJobDependencies(Job inJob) {
		for (FrustumTileCullingJob[] frustumCullingJob : frustumCullingJobs) {
			for (FrustumTileCullingJob cullingJob : frustumCullingJob) {
				inJob.addDependency(cullingJob);
			}
		}
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
		}

		if (needTileCulling) {
			clearJob.clearTargets.add(combinedTileVisibility);
			combinedTileVisibility = getAvailableCullingResults();
		}

		for(int i = 0; i < cullingViews.size(); i++) {
			final SceneView view = cullingViews.get(i);
			final SceneViewContext ctx = getAvailableSceneViewContext();
			ctx.frustumPlanes = view.getFrustumPlanes();
			ctx.viewProj = view.getViewProjMatrix();
			ctx.cullingFlags = view.getCullingFlags();

			if ((ctx.cullingFlags & SceneView.CULLING_FLAG_CALLBACK) != 0) {
				ctx.callbacks = view.getCullingCallbacks();
			}

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
			}

			ctx.results.npcs.clear();
			ctx.results.players.clear();
			ctx.results.projectiles.clear();

			cullingViewContexts.add(ctx);
		}

		cullingInFlight = true;

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

			// Build Graphics Object Culling
			for(GraphicsObject graphicsObject : client.getTopLevelWorldView().getGraphicsObjects()) {
				FrustumSphereCullingJob.BoundingSphere sphere = FrustumSphereCullingJob.getOrCreateBoundingSphere();
				sphere.x = (float) graphicsObject.getLocation().getX();
				sphere.y = (float) graphicsObject.getZ();
				sphere.z = (float) graphicsObject.getLocation().getY();
				sphere.height = graphicsObject.getModelHeight();
				sphere.id = graphicsObject.getId();
				graphicObjectCullingJob.spheres.add(sphere);
			}

			if (!graphicObjectCullingJob.spheres.isEmpty()) {
				graphicObjectCullingJob.submit();
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
		public ICullingCallback callbacks;
		public CullingResults results;
		public int cullingFlags;
	}

	public interface ICullingCallback {
		boolean isTileVisible(int x, int z, int h0, int h1, int h2, int h3, boolean isVisible);
		boolean isStaticRenderableVisible(int x, int y, int z, int radius, int height, boolean isVisible);
		boolean isBoundingSphereVisible(float x, float y, float z, float radius, boolean isVisible);
	}

	public static final class CullingResults {
		private final int[] visibleTiles = new int[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
		private final byte[] tiles = new byte[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
		private final HashSet<Integer> players = new HashSet<>();
		private final HashSet<Integer> npcs = new HashSet<>();
		private final HashSet<Integer> projectiles = new HashSet<>();
		private final HashSet<Integer> graphicsObjects = new HashSet<>();
		private final AtomicInteger numVisibleTiles = new AtomicInteger(0);

		public CullingResults() {
			reset();
		}

		public int getNumVisibleTiles() {return numVisibleTiles.get();}

		public int getVisibleTile(int idx) { return visibleTiles[idx]; }

		public boolean isPlayerVisible(int id) { return players.contains(id); }

		public boolean isNPCVisible(int id) { return npcs.contains(id); }

		public boolean isProjectileVisible(int id) { return projectiles.contains(id); }

		public boolean isGraphicsObjectVisible(int id) { return graphicsObjects.contains(id); }

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

		public static boolean isTileVisible(byte tileCullingResult) {
			return (tileCullingResult & (VISIBILITY_TILE_VISIBLE | VISIBILITY_UNDER_WATER_TILE_VISIBLE)) != 0;
		}

		public static boolean isTileSurfaceVisible(byte tileCullingResult) {
			return (tileCullingResult & VISIBILITY_TILE_VISIBLE) != 0;
		}

		public static boolean isTileUnderwaterVisible(byte tileCullingResult) {
			return (tileCullingResult & VISIBILITY_UNDER_WATER_TILE_VISIBLE) != 0;
		}

		public static boolean isTileRenderablesVisible(byte tileCullingResult) {
			return (tileCullingResult & VISIBILITY_RENDERABLE_VISIBLE) != 0;
		}

		public void reset() {
			Arrays.fill(tiles, VISIBILITY_UNKNOWN);
			players.clear();
			npcs.clear();
			projectiles.clear();
			graphicsObjects.clear();
			numVisibleTiles.set(0);
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
		public int worldPlane;
		public boolean[] sceneViewCtxVisible = new boolean[4];

		// Job AABB
		private int aabb_MinX, aabb_MinY, aabb_MinZ;
		private int aabb_MaxX, aabb_MaxY, aabb_MaxZ;
		private int[][][] tileHeights;
		private final float[][] vertices = new float[4][4];

		@Override
		protected void onPrepare() {
			if(sceneID == sceneContext.id) {
				return;
			}
			sceneID = sceneContext.id;
			worldPlane = cullManager.client.getPlane();

			aabb_MinX = Integer.MAX_VALUE;
			aabb_MinY = Integer.MAX_VALUE;
			aabb_MinZ = Integer.MAX_VALUE;

			aabb_MaxX = Integer.MIN_VALUE;
			aabb_MaxY = Integer.MIN_VALUE;
			aabb_MaxZ = Integer.MIN_VALUE;

			// Build Job AABB, used for early out to avoid performing expensive frustum culling for all tiles
			tileHeights = sceneContext.scene.getTileHeights();

			for (int plane = 0; plane < MAX_Z; plane++) {
				for (int tileExX = startX; tileExX < endX; tileExX++) {
					for (int tileExY = startY; tileExY < endY; tileExY++) {
						final int tileIdx = HDUtils.tileCoordinateToIndex(plane, tileExX, tileExY);
						final StaticTileData staticTileData = sceneContext.staticTileData[tileIdx];
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

						if (staticTileData.isWater) {
							final int dl0 = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY];
							final int dl1 = sceneContext.underwaterDepthLevels[plane][tileExX + 1][tileExY];
							final int dl2 = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY + 1];
							final int dl3 = sceneContext.underwaterDepthLevels[plane][tileExX + 1][tileExY + 1];

							if (dl0 > 0) h0 += (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl0 - 1] * 0.55f);
							if (dl1 > 0) h1 += (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl1 - 1] * 0.55f);
							if (dl2 > 0) h2 += (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl2 - 1] * 0.55f);
							if (dl3 > 0) h3 += (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl3 - 1] * 0.55f);

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
			if (sceneViewCtxVisible.length < cullManager.cullingViewContexts.size()) {
				sceneViewCtxVisible = new boolean[cullManager.cullingViewContexts.size()];
			}

			boolean isVisibleInAny = false;
			for (int i = 0; i < cullManager.cullingViewContexts.size(); i++) {
				SceneViewContext viewCtx = cullManager.cullingViewContexts.get(i);
				sceneViewCtxVisible[i] = HDUtils.isAABBIntersectingFrustum(
					aabb_MinX,
					aabb_MinY,
					aabb_MinZ,
					aabb_MaxX,
					aabb_MaxY,
					aabb_MaxZ,
					viewCtx.frustumPlanes,
					0
				);
				isVisibleInAny = isVisibleInAny || sceneViewCtxVisible[i];
			}

			// Check if we're visible in any SceneView
			if (!isVisibleInAny) {
				for (int plane = 0; plane < MAX_Z; plane++) {
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
				return;
			}

			int baseExX = sceneContext.sceneBase[0] - SCENE_OFFSET;
			int baseExY = sceneContext.sceneBase[1] - SCENE_OFFSET;
			int basePlane = sceneContext.sceneBase[2];
			for(int plane = 0; plane < MAX_Z; plane++) {
				for (int tileExX = startX; tileExX < endX; tileExX++) {
					for (int tileExY = startY; tileExY < endY; tileExY++) {
						final int tileIdx = HDUtils.tileCoordinateToIndex(plane, tileExX, tileExY);
						final StaticTileData staticTileData = sceneContext.staticTileData[tileIdx];

						// Check if tile is Empty so we can skip expensive culling
						boolean shouldProcessTile = !staticTileData.isEmpty();

						if(shouldProcessTile && sceneContext.currentArea != null) {
							// Check area hiding if this tile is hidden
							shouldProcessTile = sceneContext.currentArea.containsPoint(
								baseExX + tileExX,
								baseExY + tileExY,
								basePlane + plane
							);
						}

						if (!shouldProcessTile) {
							for (int i = 0; i < cullManager.cullingViewContexts.size(); i++) {
								SceneViewContext viewCtx = cullManager.cullingViewContexts.get(i);
								if (!sceneViewCtxVisible[i]) {
									viewCtx.results.tiles[tileIdx] = VISIBILITY_RENDERABLE_VISIBLE;
								}
								cullManager.combinedTileVisibility.tiles[tileIdx] = VISIBILITY_RENDERABLE_VISIBLE;
							}
							continue;
						}

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

						if (staticTileData.isWater) {
							final int dl0 = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY];
							final int dl1 = sceneContext.underwaterDepthLevels[plane][tileExX + 1][tileExY];
							final int dl2 = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY + 1];
							final int dl3 = sceneContext.underwaterDepthLevels[plane][tileExX + 1][tileExY + 1];

							hasUnderwaterTile = dl0 > 0 || dl1 > 0 || dl2 > 0 || dl3 > 0;
							if (dl0 > 0) uh0 = (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl0 - 1] * 0.55f);
							if (dl1 > 0) uh1 = (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl1 - 1] * 0.55f);
							if (dl2 > 0) uh2 = (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl2 - 1] * 0.55f);
							if (dl3 > 0) uh3 = (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl3 - 1] * 0.55f);
						}

						byte combinedResult = VISIBILITY_HIDDEN;
						for (int i = 0; i < cullManager.cullingViewContexts.size(); i++) {
							SceneViewContext viewCtx = cullManager.cullingViewContexts.get(i);
							if (!sceneViewCtxVisible[i]) {
								viewCtx.results.tiles[tileIdx] = VISIBILITY_HIDDEN;
								continue;
							}

							if ((viewCtx.cullingFlags & SceneView.CULLING_FLAG_FREEZE) != 0) {
								combinedResult |= viewCtx.results.tiles[tileIdx];
								continue;
							}

							byte viewResult = VISIBILITY_HIDDEN;
							if (plane != 0 || (viewCtx.cullingFlags & SceneView.CULLING_FLAG_GROUND_PLANES) != 0) {
								boolean visible = HDUtils.IsTileVisible(
									x,
									z,
									h0,
									h1,
									h2,
									h3,
									viewCtx.frustumPlanes
								);

								if (visible && (viewCtx.cullingFlags & SceneView.CULLING_FLAG_BACK_FACE_CULL) != 0) {
									visible = getTileTriangleArea(x, z, h0, h1, h2, h3, viewCtx.viewProj) > 1e-6f;
								}

								if ((viewCtx.cullingFlags & SceneView.CULLING_FLAG_CALLBACK) != 0) {
									visible = viewCtx.callbacks.isTileVisible(x, z, h0, h1, h2, h3, visible);
								}

								if (visible) {
									viewResult |= VISIBILITY_TILE_VISIBLE;
								}
							}

							if (hasUnderwaterTile && (viewCtx.cullingFlags & SceneView.CULLING_FLAG_UNDERWATER_PLANES) != 0) {
								boolean visible = HDUtils.IsTileVisible(
									x,
									z,
									uh0,
									uh1,
									uh2,
									uh3,
									viewCtx.frustumPlanes,
									-LOCAL_TILE_SIZE
								);

								if (visible && (viewCtx.cullingFlags & SceneView.CULLING_FLAG_BACK_FACE_CULL) != 0) {
									visible = getTileTriangleArea(x, z, uh0, uh1, uh2, uh3, viewCtx.viewProj) > 1e-6f;
								}

								if ((viewCtx.cullingFlags & SceneView.CULLING_FLAG_CALLBACK) != 0) {
									visible = viewCtx.callbacks.isTileVisible(x, z, uh0, uh1, uh2, uh3, visible);
								}

								if (visible) {
									viewResult |= VISIBILITY_UNDER_WATER_TILE_VISIBLE;
								}
							}

							if ((viewCtx.cullingFlags & SceneView.CULLING_FLAG_RENDERABLES) != 0) {
								if (!staticTileData.renderables.isEmpty()) {
									for (StaticRenderableInstance instance : staticTileData.renderables) {
										final StaticRenderable renderable = instance.renderable;
										if (renderable.height < LOCAL_HALF_TILE_SIZE) {
											// Renderable is probably laying along surface of tile, if surface isn't visible then its safe to cull this too
											if ((viewResult & VISIBILITY_TILE_VISIBLE) == 0) {
												continue; // Surface isn't visible, skip this renderable
											}
										}

										boolean visible = HDUtils.isAABBIntersectingFrustum(
											instance.x - renderable.radius,
											instance.y - renderable.bottomY,
											instance.z - renderable.radius,
											instance.x + renderable.radius,
											instance.y - renderable.bottomY + renderable.height,
											instance.z + renderable.radius,
											viewCtx.frustumPlanes, -LOCAL_TILE_SIZE
										);

										if ((viewCtx.cullingFlags & SceneView.CULLING_FLAG_CALLBACK) != 0) {
											visible = viewCtx.callbacks.isStaticRenderableVisible(
												cX,
												cH - renderable.bottomY,
												cZ,
												renderable.radius,
												renderable.height,
												visible
											);
										}

										/*
										if (visible && (viewCtx.cullingFlags & SceneView.CULLING_FLAG_BACK_FACE_CULL) != 0) {
											float rad = (float)(instance.orientation * UNIT);
											float sOrientation = sin(rad);
											float cOrientation = cos(rad);

											boolean isModelBackFacing = true;
											for(int f = 0; f < renderable.faceCount; f++) {
												if(getModelTriangleArea(instance, f, sOrientation, cOrientation, viewCtx.viewProj) > 0.1f) {
													isModelBackFacing = false;
													break;
												}
											}
											visible = !isModelBackFacing;
										} */

										if (visible) {
											viewResult |= VISIBILITY_RENDERABLE_VISIBLE;
											break;
										}
									}
								} else {
									// No Static Culling data was present, allow missed static renderables to be visible here
									viewResult |= VISIBILITY_RENDERABLE_VISIBLE;
								}
							}

							if(viewResult != VISIBILITY_HIDDEN) {
								final int writeIdx = viewCtx.results.numVisibleTiles.getAndIncrement();
								viewCtx.results.visibleTiles[writeIdx] = tileIdx;
							}

							combinedResult |= viewResult;
							viewCtx.results.tiles[tileIdx] = viewResult;
						}

						if(combinedResult != VISIBILITY_HIDDEN) {
							final int writeIdx = cullManager.combinedTileVisibility.numVisibleTiles.getAndIncrement();
							cullManager.combinedTileVisibility.visibleTiles[writeIdx] = tileIdx;
						}

						cullManager.combinedTileVisibility.tiles[tileIdx] = combinedResult;
					}
				}
			}
		}

		private float getModelTriangleArea(StaticRenderableInstance instance, int face, float sOrientation, float cOrientation, float[] viewProj) {
			var vertexBuffer = sceneContext.stagingBufferVertices.getBuffer();
			int vertexOffset = instance.renderable.vertexOffset + (face * ModelPusher.DATUM_PER_FACE);

			vertices[0][0] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset));
			vertices[0][1] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset + 1));
			vertices[0][2] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset + 2));
			vertices[0][3] = 1.0f;

			vertices[0][0] = vertices[0][2] * sOrientation + vertices[0][0] * cOrientation;
			vertices[0][2] = vertices[0][2] * cOrientation - vertices[0][0] * sOrientation;

			vertices[1][0] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset + 4));
			vertices[1][1] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset + 5));
			vertices[1][2] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset + 6));
			vertices[1][3] = 1.0f;

			vertices[1][0] = vertices[1][2] * sOrientation + vertices[1][0] * cOrientation;
			vertices[1][2] = vertices[1][2] * cOrientation - vertices[1][0] * sOrientation;

			vertices[2][0] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset + 8));
			vertices[2][1] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset + 9));
			vertices[2][2] = Float.intBitsToFloat(vertexBuffer.get(vertexOffset + 10));
			vertices[2][3] = 1.0f;

			vertices[2][0] = vertices[2][2] * sOrientation + vertices[2][0] * cOrientation;
			vertices[2][2] = vertices[2][2] * cOrientation - vertices[2][0] * sOrientation;

			Mat4.projectVec(vertices[0], viewProj, vertices[0]);
			Mat4.projectVec(vertices[1], viewProj, vertices[1]);
			Mat4.projectVec(vertices[2], viewProj, vertices[2]);

			return HDUtils.signedTriangleArea(vertices[0], vertices[1], vertices[2]);
		}

		private float getTileTriangleArea(int x, int z, int h0, int h1, int h2, int h3, float[] viewProj) {
			vertices[0][0] = x;
			vertices[0][1] = h0;
			vertices[0][2] = z;
			vertices[0][3] = 1.0f;

			vertices[1][0] = x + LOCAL_TILE_SIZE;
			vertices[1][1] = h1;
			vertices[1][2] = z;
			vertices[1][3] = 1.0f;

			vertices[2][0] = x;
			vertices[2][1] = h2;
			vertices[2][2] = z + LOCAL_TILE_SIZE;
			vertices[2][3] = 1.0f;

			vertices[3][0] = x + LOCAL_TILE_SIZE;
			vertices[3][1] = h3;
			vertices[3][2] = z + LOCAL_TILE_SIZE;
			vertices[3][3] = 1.0f;

			Mat4.projectVec(vertices[0], viewProj, vertices[0]);
			Mat4.projectVec(vertices[1], viewProj, vertices[1]);
			Mat4.projectVec(vertices[2], viewProj, vertices[2]);
			Mat4.projectVec(vertices[3], viewProj, vertices[3]);

			return max(
				HDUtils.signedTriangleArea(vertices[0], vertices[1], vertices[2]),
				HDUtils.signedTriangleArea(vertices[2], vertices[1], vertices[3])
			);
		}
	}

	@RequiredArgsConstructor
	public static final class FrustumSphereCullingJob extends Job {
		private static final ArrayDeque<BoundingSphere> BOUNDING_SPHERE_BIN = new ArrayDeque<>();

		public static BoundingSphere getOrCreateBoundingSphere() {
			return BOUNDING_SPHERE_BIN.isEmpty() ? new BoundingSphere() : BOUNDING_SPHERE_BIN.pop();
		}

		public enum JobType { PLAYER, NPC, PROJECTILES, GRAPHICS_OBJECTS }

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
					boolean visible = HDUtils.isSphereIntersectingFrustum(
						sphere.x,
						sphere.y,
						sphere.z,
						sphere.height,
						viewCtx.frustumPlanes
					);

					if ((viewCtx.cullingFlags & SceneView.CULLING_FLAG_CALLBACK) != 0) {
						visible = viewCtx.callbacks.isBoundingSphereVisible(sphere.x, sphere.y, sphere.z, sphere.height, visible);
					}

					if(visible) {
						switch (type) {
							case PLAYER:
								cullManager.combinedTileVisibility.players.add(sphere.id);
								viewCtx.results.players.add(sphere.id);
								break;
							case NPC:
								cullManager.combinedTileVisibility.npcs.add(sphere.id);
								viewCtx.results.npcs.add(sphere.id);
								break;
							case PROJECTILES:
								cullManager.combinedTileVisibility.projectiles.add(sphere.id);
								viewCtx.results.projectiles.add(sphere.id);
								break;
							case GRAPHICS_OBJECTS:
								cullManager.combinedTileVisibility.graphicsObjects.add(sphere.id);
								viewCtx.results.graphicsObjects.add(sphere.id);
								break;
						}
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
