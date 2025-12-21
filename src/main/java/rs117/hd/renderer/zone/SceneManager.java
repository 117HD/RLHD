package rs117.hd.renderer.zone;

import com.google.common.base.Stopwatch;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.jobs.GenericJob;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.SCENE_SIZE;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class SceneManager {
	public static final int MAX_WORLDVIEWS = 4096;
	public static final int NUM_ZONES = EXTENDED_SCENE_SIZE >> 3;

	private static final int ZONE_DEFER_DIST_START = 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private AreaManager areaManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private FrameTimer frameTimer;

	private UBOWorldViews uboWorldViews;

	private final Map<Integer, Integer> nextRoofChanges = new HashMap<>();
	@Getter
	private final WorldViewContext root = new WorldViewContext(this, null, null, null);
	private final WorldViewContext[] subs = new WorldViewContext[MAX_WORLDVIEWS];
	private final List<SortedZone> sortedZones = new ArrayList<>();
	private ZoneSceneContext nextSceneContext;
	private Zone[][] nextZones;
	private boolean reloadRequested;

	public boolean isZoneStreamingEnabled() {
		return plugin.configZoneStreaming;
	}

	public long getFrameNumber() { return plugin.frameNumber; }

	@Getter
	public final ReentrantLock loadingLock = new ReentrantLock();

	public boolean isTopLevelValid() {
		return root.sceneContext != null;
	}

	@Nullable
	public ZoneSceneContext getSceneContext() {
		return root.sceneContext;
	}

	public boolean isRoot(WorldViewContext context) { return root == context; }

	public WorldViewContext getContext(Scene scene) {
		return getContext(scene.getWorldViewId());
	}

	public WorldViewContext getContext(WorldView wv) {
		return getContext(wv.getId());
	}

	public WorldViewContext getContext(int worldViewId) {
		if (worldViewId != -1)
			return subs[worldViewId];
		if (root.sceneContext == null)
			return null;
		return root;
	}

	public void initialize(UBOWorldViews uboWorldViews) {
		this.uboWorldViews = uboWorldViews;
	}

	public void destroy() {
		root.free();

		for (int i = 0; i < subs.length; i++) {
			if (subs[i] != null)
				subs[i].free();
			subs[i] = null;
		}

		Zone.freeZones(nextZones);
		nextZones = null;
		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;

		uboWorldViews = null;
	}

	public void update() {
		frameTimer.begin(Timer.UPDATE_AREA_HIDING);
		updateAreaHiding();
		frameTimer.end(Timer.UPDATE_AREA_HIDING);

		Zone.processPendingDeletions();

		if (reloadRequested && loadingLock.getHoldCount() == 0) {
			reloadRequested = false;
			try {
				loadingLock.lock();

				completeAllStreaming();

				if (!generateSceneDataTask.isDone())
					generateSceneDataTask.waitForCompletion();
				generateSceneDataTask.queue();

				root.invalidate();
				for (var sub : subs)
					if (sub != null)
						sub.invalidate();

				root.sceneLoadGroup.complete();
				root.streamingGroup.complete();
				root.blockingInvalidationGroup.complete();
			} finally {
				loadingLock.unlock();
				log.trace("loadingLock unlocked - holdCount: {}", loadingLock.getHoldCount());
			}
		}

		root.update(plugin.deltaTime);

		WorldView wv = client.getTopLevelWorldView();
		if (wv != null) {
			for (WorldEntity we : wv.worldEntities()) {
				WorldViewContext ctx = getContext(we.getWorldView());
				if (ctx != null)
					ctx.update(plugin.deltaTime);
			}
		}

		// Ensure any queued zone invalidations are now completed
		root.completeInvalidation();

		if (wv != null) {
			for (WorldEntity we : wv.worldEntities()) {
				WorldViewContext ctx = getContext(we.getWorldView());
				if (ctx != null)
					ctx.completeInvalidation();
			}
		}
	}

	private void updateAreaHiding() {
		Player localPlayer = client.getLocalPlayer();
		if (!isTopLevelValid() || localPlayer == null || root.isLoading)
			return;

		var lp = localPlayer.getLocalLocation();
		if (root.sceneContext.enableAreaHiding) {
			var base = root.sceneContext.sceneBase;
			assert base != null;
			int[] worldPos = {
				base[0] + lp.getSceneX(),
				base[1] + lp.getSceneY(),
				base[2] + client.getTopLevelWorldView().getPlane()
			};

			// We need to check all areas contained in the scene in the order they appear in the list,
			// in order to ensure lower floors can take precedence over higher floors which include tiny
			// portions of the floor beneath around stairs and ladders
			Area newArea = null;
			for (var area : root.sceneContext.possibleAreas) {
				if (area.containsPoint(false, worldPos)) {
					newArea = area;
					break;
				}
			}

			// Force a scene reload if the player is no longer in the same area
			if (newArea != root.sceneContext.currentArea) {
				if (plugin.justChangedArea) {
					// Disable area hiding if it somehow gets stuck in a loop switching areas
					root.sceneContext.enableAreaHiding = false;
					log.error(
						"Disabling area hiding after moving from {} to {} at {}",
						root.sceneContext.currentArea,
						newArea,
						worldPos
					);
					newArea = null;
				} else {
					plugin.justChangedArea = true;
					// This should happen very rarely, so we invalidate all zones for simplicity
					root.invalidate();
				}
				root.sceneContext.currentArea = newArea;
			} else {
				plugin.justChangedArea = false;
			}
		} else {
			plugin.justChangedArea = false;
		}
	}

	public void despawnWorldView(WorldView worldView) {
		int worldViewId = worldView.getId();
		if (worldViewId > -1) {
			log.debug("WorldView despawn: {}", worldViewId);
			if (subs[worldViewId] == null) {
				log.debug("Attempted to despawn unloaded worldview: {}", worldView);
			} else {
				subs[worldViewId].free();
				subs[worldViewId] = null;
			}
		}
	}

	public void reloadScene() {
		if (!plugin.isActive() || reloadRequested)
			return;

		reloadRequested = true;
		log.debug("Scene reload requested");
	}

	public boolean isLoadingScene() { return nextSceneContext != null; }

	public void completeAllStreaming() {
		root.sceneLoadGroup.complete();
		root.streamingGroup.complete();

		root.completeInvalidation();

		WorldView wv = client.getTopLevelWorldView();
		if (wv != null) {
			for (WorldEntity we : wv.worldEntities()) {
				WorldViewContext ctx = getContext(we.getWorldView());
				if (ctx != null) {
					ctx.sceneLoadGroup.complete();
					ctx.streamingGroup.complete();

					ctx.completeInvalidation();
				}
			}
		}
	}

	public void invalidateZone(Scene scene, int zx, int zz) {
		WorldViewContext ctx = getContext(scene);
		if (ctx == null)
			return;

		Zone zone = ctx.zones[zx][zz];
		if (zone.rebuild)
			return;

		log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		boolean shouldBlock = ctx.doesZoneContainPreviouslyDynamicGameObject(zx, zz);
		if(shouldBlock) {
			// Start the invalidation ASAP since we'll be blocking before drawing the next frame
			ctx.invalidateZone(true, zx, zz);
		} else {
			// Since we're not blocking, use rebuild method since invaldiation will occur async
			zone.rebuild = true;
		}
	}

	private static boolean isEdgeTile(Zone[][] zones, int zx, int zz) {
		for (int x = zx - 2; x <= zx + 2; ++x) {
			if (x < 0 || x >= NUM_ZONES)
				return true;
			for (int z = zz - 2; z <= zz + 2; ++z) {
				if (z < 0 || z >= NUM_ZONES)
					return true;
				Zone zone = zones[x][z];
				if (!zone.initialized)
					return true;
				if (zone.sizeO == 0 && zone.sizeA == 0)
					return true;
			}
		}
		return false;
	}

	@Getter
	private final GenericJob generateSceneDataTask = GenericJob.build(
		"ProceduralGenerator::generateSceneData",
		(task) -> proceduralGenerator.generateSceneData(nextSceneContext != null ? nextSceneContext : root.sceneContext)
	);

	@Getter
	private final GenericJob loadSceneLightsTask = GenericJob.build(
		"LightManager::loadSceneLights",
		(task) -> lightManager.loadSceneLights(nextSceneContext, root.sceneContext)
	);

	private final GenericJob calculateRoofChangesTask = GenericJob.build(
		"calculateRoofChanges",
		(task) -> {
			Scene prev = client.getTopLevelWorldView().getScene();
			Scene scene = nextSceneContext.scene;

			// Calculate roof ids for the zone
			final int[][][] prids = prev.getRoofs();
			final int[][][] nrids = scene.getRoofs();

			final int dx = scene.getBaseX() - prev.getBaseX() >> 3;
			final int dy = scene.getBaseY() - prev.getBaseY() >> 3;

			nextRoofChanges.clear();
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int z = 0; z < EXTENDED_SCENE_SIZE; ++z) {
					int ox = x + (dx << 3);
					int oz = z + (dy << 3);

					for (int level = 0; level < 4; ++level) {
						task.workerHandleCancel();
						// old zone still in scene?
						if (ox >= 0 && oz >= 0 && ox < EXTENDED_SCENE_SIZE && oz < EXTENDED_SCENE_SIZE) {
							int prid = prids[level][ox][oz];
							int nrid = nrids[level][x][z];
							if (prid > 0 && nrid > 0 && prid != nrid) {
								Integer old = nextRoofChanges.putIfAbsent(prid, nrid);
								if (old == null) {
									log.trace("Roof change: {} -> {}", prid, nrid);
								} else if (old != nrid) {
									log.debug("Roof change mismatch: {} -> {} vs {}", prid, nrid, old);
								}
							}
						}
					}
				}
			}
		}
	);

	public synchronized void loadScene(WorldView worldView, Scene scene) {
		try {
			loadingLock.lock();
			if (scene.getWorldViewId() > -1) {
				loadSubScene(worldView, scene);
				return;
			}

			assert worldView.getId() == -1;
			if (nextZones != null)
				throw new RuntimeException("Double zone load!"); // does this happen?

			Stopwatch sw = Stopwatch.createStarted();
			root.isLoading = true;
			root.loadTime = root.uploadTime = root.sceneSwapTime = 0;

			root.sceneLoadGroup.complete();
			root.streamingGroup.complete();
			root.blockingInvalidationGroup.complete();

			if (nextSceneContext != null)
				nextSceneContext.destroy();
			nextSceneContext = null;

			nextZones = new Zone[NUM_ZONES][NUM_ZONES];
			nextSceneContext = new ZoneSceneContext(
				client,
				worldView,
				scene,
				plugin.getExpandedMapLoadingChunks(),
				root.sceneContext
			);

			WorldViewContext ctx = root;
			Scene prev = client.getTopLevelWorldView().getScene();

			nextSceneContext.enableAreaHiding = nextSceneContext.sceneBase != null && config.hideUnrelatedAreas();

			if (nextSceneContext.intersects(areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
				nextSceneContext.isInHouse = true;
				nextSceneContext.isInChambersOfXeric = false;
			} else {
				nextSceneContext.isInHouse = false;
				nextSceneContext.isInChambersOfXeric = nextSceneContext.intersects(areaManager.getArea("CHAMBERS_OF_XERIC"));
			}

			environmentManager.loadSceneEnvironments(nextSceneContext);

			loadSceneLightsTask.cancel();
			calculateRoofChangesTask.cancel();

			generateSceneDataTask.queue();
			loadSceneLightsTask.queue();

			if (nextSceneContext.enableAreaHiding) {
				assert nextSceneContext.sceneBase != null;
				int centerOffset = SCENE_SIZE / 2 & ~7;
				int centerX = nextSceneContext.sceneBase[0] + centerOffset;
				int centerY = nextSceneContext.sceneBase[1] + centerOffset;

				nextSceneContext.possibleAreas = Arrays
					.stream(areaManager.areasWithAreaHiding)
					.filter(area -> nextSceneContext.sceneBounds.intersects(area.aabbs))
					.toArray(Area[]::new);

				if (log.isDebugEnabled() && nextSceneContext.possibleAreas.length > 0) {
					log.debug(
						"Area hiding areas: {}",
						Arrays.stream(nextSceneContext.possibleAreas)
							.distinct()
							.map(Area::toString)
							.collect(Collectors.joining(", "))
					);
				}

				// If area hiding can be decided based on the central chunk, apply it early
				AABB centerChunk = new AABB(centerX, centerY, centerX + 7, centerY + 7);
				for (Area possibleArea : nextSceneContext.possibleAreas) {
					if (!possibleArea.intersects(centerChunk))
						continue;

					if (nextSceneContext.currentArea != null) {
						// Multiple possible areas, so let's defer this until swapScene
						nextSceneContext.currentArea = null;
						break;
					}
					nextSceneContext.currentArea = possibleArea;
				}
			}

			for (int x = 0; x < NUM_ZONES; ++x) {
				for (int z = 0; z < NUM_ZONES; ++z) {
					Zone curZone = ctx.zones[x][z];
					curZone.cull = true;

					// Last minute chance for a streamed in zone to be reused
					ctx.handleZoneSwap(-1.0f, x, z);
					// Mark all zones to be culled, unless they get reused later
					ctx.zones[x][z].cull = true;
				}
			}

			// Queue after ensuring previous scene has been cancelled
			calculateRoofChangesTask.queue();

			final int dx = scene.getBaseX() - prev.getBaseX() >> 3;
			final int dy = scene.getBaseY() - prev.getBaseY() >> 3;

			if (ctx.sceneContext != null &&
				prev.isInstance() == scene.isInstance() &&
				client.getGameState() == GameState.LOGGED_IN && // only reuse for async loads to respect roof removal state changes
				ctx.sceneContext.expandedMapLoadingChunks == nextSceneContext.expandedMapLoadingChunks &&
				ctx.sceneContext.currentArea == nextSceneContext.currentArea) {
				for (int x = 0; x < NUM_ZONES; ++x) {
					for (int z = 0; z < NUM_ZONES; ++z) {
						int ox = x + dx;
						int oz = z + dy;
						if (ox < 0 || ox >= NUM_ZONES || oz < 0 || oz >= NUM_ZONES)
							continue;

						final Zone old = ctx.zones[ox][oz];
						if (!old.initialized || (old.sizeO == 0 && old.sizeA == 0))
							continue;

						old.needsRoofUpdate = true;

						if (old.hasWater || old.dirty || isEdgeTile(ctx.zones, ox, oz)) {
							float dist = distance(vec(x, z), vec(NUM_ZONES / 2, NUM_ZONES / 2));
							sortedZones.add(SortedZone.getZone(old, x, z, dist));
							nextSceneContext.totalDeferred++;
						} else {
							// The zone can be reused without modifications
							old.cull = false;
							nextSceneContext.totalReused++;
						}

						nextZones[x][z] = old;
					}
				}
			}

			boolean staggerLoad =
				isZoneStreamingEnabled() &&
				!nextSceneContext.isInHouse &&
				root.sceneContext != null &&
				nextSceneContext.totalReused + nextSceneContext.totalDeferred > 0;
			for (int x = 0; x < NUM_ZONES; ++x) {
				for (int z = 0; z < NUM_ZONES; ++z) {
					Zone zone = nextZones[x][z];
					if (zone == null)
						zone = nextZones[x][z] = new Zone();

					if (!zone.initialized) {
						float dist = distance(vec(x, z), vec(NUM_ZONES / 2, NUM_ZONES / 2));
						if (!staggerLoad || dist < ZONE_DEFER_DIST_START) {
							ZoneUploadJob
								.build(ctx, nextSceneContext, zone, true, x, z)
								.queue(ctx.sceneLoadGroup, generateSceneDataTask);
							nextSceneContext.totalMapZones++;
						} else {
							sortedZones.add(SortedZone.getZone(zone, x, z, dist));
							nextSceneContext.totalDeferred++;
						}
					}
				}
			}

			for (SortedZone sorted : sortedZones) {
				Zone newZone = new Zone();
				newZone.dirty = sorted.zone.dirty;
				if (staggerLoad) {
					// Reuse the old zone while uploading a correct one
					sorted.zone.cull = false;
					sorted.zone.uploadJob = ZoneUploadJob
						.build(ctx, nextSceneContext, newZone, false, sorted.x, sorted.z);
					sorted.zone.uploadJob.delay = 0.5f + clamp(sorted.dist / 15.0f, 0.0f, 1.0f) * 1.5f;
				} else {
					nextZones[sorted.x][sorted.z] = newZone;
					ZoneUploadJob
						.build(ctx, nextSceneContext, newZone, true, sorted.x, sorted.z)
						.queue(ctx.sceneLoadGroup, generateSceneDataTask);
				}
				sorted.free();
			}
			sortedZones.clear();

			root.loadTime = sw.elapsed(TimeUnit.NANOSECONDS);
			log.debug("loadScene time: {}", sw);
		} finally {
			loadingLock.unlock();
			log.trace("loadingLock unlocked - holdCount: {}", loadingLock.getHoldCount());
		}
	}

	public void swapScene(Scene scene) {
		if (!plugin.isActive() || plugin.skipScene == scene) {
			plugin.redrawPreviousFrame = true;
			return;
		}

		if (scene.getWorldViewId() > -1) {
			swapSubScene(scene);
			return;
		}

		if (nextSceneContext == null)
			return; // Return early if scene loading failed

		Stopwatch sw = Stopwatch.createStarted();

		fishingSpotReplacer.despawnRuneLiteObjects();
		npcDisplacementCache.clear();

		boolean isFirst = root.sceneContext == null;
		if (!isFirst)
			root.sceneContext.destroy(); // Destroy the old context before replacing it

		// Wait for roof change calculation to complete
		calculateRoofChangesTask.waitForCompletion();

		WorldViewContext ctx = root;
		if (!nextRoofChanges.isEmpty()) {
			for (int x = 0; x < ctx.sizeX; ++x) {
				for (int z = 0; z < ctx.sizeZ; ++z) {
					Zone zone = nextZones[x][z];
					if (zone.needsRoofUpdate) {
						zone.needsRoofUpdate = false;
						zone.updateRoofs(nextRoofChanges);
					}
				}
			}
		}
		long roofsTime = sw.elapsed(TimeUnit.MILLISECONDS);
		log.debug("swapScene - Roofs: {} ms", roofsTime);

		// Handle object spawns that must be processed on the client thread
		loadSceneLightsTask.waitForCompletion();

		for (var tileObject : nextSceneContext.lightSpawnsToHandleOnClientThread)
			lightManager.handleObjectSpawn(nextSceneContext, tileObject);
		nextSceneContext.lightSpawnsToHandleOnClientThread.clear();

		long lightsTime = sw.elapsed(TimeUnit.MILLISECONDS);
		log.debug("swapScene - Lights: {} ms", lightsTime - roofsTime);

		long sceneUploadTimeStart = sw.elapsed(TimeUnit.NANOSECONDS);
		int blockingCount = root.sceneLoadGroup.getPendingCount();
		root.sceneLoadGroup.complete();

		int totalOpaque = 0;
		int totalAlpha = 0;
		for (int x = 0; x < NUM_ZONES; ++x) {
			for (int z = 0; z < NUM_ZONES; ++z) {
				totalOpaque += nextZones[x][z].bufLen;
				totalAlpha += nextZones[x][z].bufLenA;
			}
		}

		root.uploadTime = sw.elapsed(TimeUnit.NANOSECONDS) - sceneUploadTimeStart;
		log.debug(
			"upload time {} reused {} deferred {} map {} sceneLoad {} len opaque {} size opaque {} KiB len alpha {} size alpha {} KiB",
			TimeUnit.MILLISECONDS.convert(root.uploadTime, TimeUnit.NANOSECONDS),
			nextSceneContext.totalReused,
			nextSceneContext.totalDeferred,
			nextSceneContext.totalMapZones,
			blockingCount,
			totalOpaque,
			(totalOpaque * Zone.VERT_SIZE * 3L) / KiB,
			totalAlpha,
			(totalAlpha * Zone.VERT_SIZE * 3L) / KiB
		);

		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone preZone = ctx.zones[x][z];
				Zone nextZone = nextZones[x][z];

				assert !preZone.cull || preZone != nextZone : "Zone which is marked for culling was reused!";
				if (preZone.cull)
					root.pendingCull.add(preZone);

				nextZone.setMetadata(ctx, nextSceneContext, x, z);
			}
		}

		ctx.zones = nextZones;
		root.sceneContext = nextSceneContext;
		root.isLoading = false;

		nextZones = null;
		nextSceneContext = null;

		if (isFirst) {
			// Load all pre-existing sub scenes on the first scene load
			for (WorldEntity subEntity : client.getTopLevelWorldView().worldEntities()) {
				WorldView sub = subEntity.getWorldView();
				Scene subScene = sub.getScene();
				log.debug(
					"Loading worldview: id={}, sizeX={}, sizeZ={}",
					sub.getId(),
					sub.getSizeX(),
					sub.getSizeY()
				);
				loadSubScene(sub, subScene);
				swapSubScene(subScene);
			}
		}

		checkGLErrors();
		root.sceneSwapTime = sw.elapsed(TimeUnit.NANOSECONDS);
		log.debug("swapScene time: {}", sw);
	}

	private void loadSubScene(WorldView worldView, Scene scene) {
		int worldViewId = worldView.getId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);
		Stopwatch sw = Stopwatch.createStarted();
		final WorldViewContext prevCtx = subs[worldViewId];
		if (prevCtx != null) {
			log.error("Reload of an already loaded sub scene?");
			prevCtx.sceneLoadGroup.cancel();
			prevCtx.streamingGroup.cancel();
			clientThread.invoke(prevCtx::free);
		}

		var sceneContext = new ZoneSceneContext(client, worldView, scene, plugin.getExpandedMapLoadingChunks(), null);
		proceduralGenerator.generateSceneData(sceneContext);

		final WorldViewContext ctx = new WorldViewContext(this, worldView, sceneContext, uboWorldViews);
		subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
			for (int z = 0; z < ctx.sizeZ; ++z)
				ZoneUploadJob
					.build(ctx, sceneContext, ctx.zones[x][z], true, x, z)
					.queue(ctx.sceneLoadGroup);

		ctx.loadTime = sw.elapsed(TimeUnit.NANOSECONDS);
	}

	private void swapSubScene(Scene scene) {
		WorldViewContext ctx = getContext(scene);
		if (ctx == null)
			return;

		Stopwatch sw = Stopwatch.createStarted();
		ctx.sceneLoadGroup.complete();
		ctx.uploadTime = sw.elapsed(TimeUnit.NANOSECONDS);
		ctx.initMetadata();
		ctx.isLoading = false;
		ctx.sceneSwapTime = sw.elapsed(TimeUnit.NANOSECONDS);
		log.debug("swapSubScene time {} WorldView ready: {}", ctx.sceneSwapTime, scene.getWorldViewId());
	}

	static class SortedZone implements Comparable<SortedZone> {
		private static final ArrayDeque<SortedZone> POOL = new ArrayDeque<>();

		public Zone zone;
		public int x, z;
		public float dist;

		public static SortedZone getZone(Zone zone, int x, int z, float dist) {
			SortedZone sorted = POOL.poll();
			if (sorted == null)
				sorted = new SortedZone();
			sorted.zone = zone;
			sorted.x = x;
			sorted.z = z;
			sorted.dist = dist;
			return sorted;
		}

		public void free() { POOL.add(this); }

		@Override
		public int compareTo(SortedZone o) {
			return Float.compare(dist, o.dist);
		}
	}
}
