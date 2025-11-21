package rs117.hd.renderer.zone;

import com.google.common.base.Stopwatch;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.renderer.zone.ZoneStreamingManager.WorkHandle;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.NpcDisplacementCache;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static net.runelite.api.Perspective.SCENE_SIZE;
import static rs117.hd.HdPlugin.THREAD_POOL;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class SceneManager {
	private static final int ZONE_DEFER_DIST_START = 50 * LOCAL_TILE_SIZE;

	private static final int REUSE_STATE_NONE = -1;
	private static final int REUSE_STATE_PARTIAL = 0;
	private static final int REUSE_STATE_FULLY = 1;

	public static final int MAX_WORLDVIEWS = 4096;

	public static final int NUM_ZONES = EXTENDED_SCENE_SIZE >> 3;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private AreaManager areaManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private ZoneStreamingManager zoneStreamingManager;

	private ZoneRenderer zoneRenderer;
	private UBOWorldViews uboWorldViews;

	private final WorldViewContext root = new WorldViewContext(null, null, null);
	private final WorldViewContext[] subs = new WorldViewContext[MAX_WORLDVIEWS];
	private ZoneSceneContext nextSceneContext;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	public boolean isTopLevelValid() {
		return root != null && root.sceneContext != null;
	}

	@Nullable
	public ZoneSceneContext getSceneContext() {
		return root.sceneContext;
	}

	@Nonnull
	public WorldViewContext getRoot() { return root; }

	public boolean isRoot(WorldViewContext context) { return root == context; }

	public WorldViewContext context(Scene scene) {
		return context(scene.getWorldViewId());
	}

	public WorldViewContext context(WorldView wv) {
		return context(wv.getId());
	}

	public WorldViewContext context(int worldViewId) {
		if (worldViewId != -1)
			return subs[worldViewId];
		if (root.sceneContext == null)
			return null;
		return root;
	}

	public void initialize(ZoneRenderer renderer, UBOWorldViews uboWorldViews) {
		this.zoneRenderer = renderer;
		this.uboWorldViews = uboWorldViews;
	}

	public void shutdown() {
		root.free();

		for (int i = 0; i < subs.length; i++) {
			if (subs[i] != null)
				subs[i].free();
			subs[i] = null;
		}

		Zone.freeZones(nextZones);
		nextZones = null;
		nextRoofChanges = null;
		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;

		zoneRenderer = null;
		uboWorldViews = null;
	}

	public void update(WorldView wv) {
		updateAreaHiding();

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
			rebuild(we.getWorldView());
	}

	private void updateAreaHiding() {
		Player localPlayer = client.getLocalPlayer();
		if(!isTopLevelValid() || localPlayer == null)
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

	private void rebuild(WorldView wv) {
		assert client.isClientThread();
		WorldViewContext ctx = context(wv);
		if (ctx == null || ctx.isLoading)
			return;

		ctx.clearCompleteStreaming();
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];
				if(!zone.needsRebuild())
					continue;

				zone.isRebuild = true;
				ctx.streamingZones.add(zoneStreamingManager.queueZone(ctx, ctx.sceneContext, zone, x, z, false));
			}
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
		root.invalidate();
		for (var sub : subs)
			if (sub != null)
				sub.invalidate();
	}

	public boolean isLoadingScene() { return nextSceneContext != null; }

	private static int canReuse(Zone[][] zones, int zx, int zz) {
		// For tile blending, sharelight, and shadows to work correctly, the zones surrounding
		// the zone must be valid.
		for (int x = zx - 1; x <= zx + 1; ++x) {
			if (x < 0 || x >= NUM_ZONES)
				return REUSE_STATE_PARTIAL;
			for (int z = zz - 1; z <= zz + 1; ++z) {
				if (z < 0 || z >= NUM_ZONES)
					return REUSE_STATE_PARTIAL;
				Zone zone = zones[x][z];
				if (!zone.initialized)
					return REUSE_STATE_NONE;
				if (zone.sizeO == 0 && zone.sizeA == 0)
					return REUSE_STATE_NONE;
				if (zone.hasWater)
					return REUSE_STATE_PARTIAL;
				if (zone.dirty)
					return REUSE_STATE_PARTIAL;
			}
		}
		return REUSE_STATE_FULLY;
	}

	public void invalidateZone(Scene scene, int zx, int zz) {
		WorldViewContext ctx = context(scene);
		if(ctx == null) return;
		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate) {
			z.invalidate = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	public void loadScene(WorldView worldView, Scene scene) {
		if (scene.getWorldViewId() > -1) {
			loadSubScene(worldView, scene);
			return;
		}

		assert scene.getWorldViewId() == -1;
		if (nextZones != null)
			throw new RuntimeException("Double zone load!"); // does this happen?

		Stopwatch sw = Stopwatch.createStarted();
		if (nextSceneContext != null)
			nextSceneContext.destroy();

		nextSceneContext = null;

		root.isLoading = true;
		root.cancelStreaming();

		nextZones = new Zone[NUM_ZONES][NUM_ZONES];
		nextRoofChanges = new HashMap<>();
		nextSceneContext = new ZoneSceneContext(
			client,
			worldView,
			scene,
			plugin.getExpandedMapLoadingChunks(),
			root.sceneContext
		);
		nextSceneContext.enableAreaHiding = nextSceneContext.sceneBase != null && config.hideUnrelatedAreas();

		environmentManager.loadSceneEnvironments(nextSceneContext);

		LocalPoint lp = client.getLocalPlayer().getLocalLocation();
		final int[] nextPlayerPos;
		if(nextSceneContext.sceneBase != null) {
			nextPlayerPos = new int[] {
				((lp.getX()) + nextSceneContext.sceneBase[0]) + (NUM_ZONES * LOCAL_TILE_SIZE) ,
				((lp.getY()) + nextSceneContext.sceneBase[1]) + (NUM_ZONES * LOCAL_TILE_SIZE)
			};
		} else {
			nextPlayerPos = null;
		}

		proceduralGenerator.asyncProcGenTask = THREAD_POOL.submit(() -> proceduralGenerator.generateSceneData(nextSceneContext));
		lightManager.asyncLoadTask = THREAD_POOL.submit(() ->  lightManager.loadSceneLights(nextSceneContext, root.sceneContext));

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
			rs117.hd.scene.areas.AABB centerChunk = new AABB(centerX, centerY, centerX + 7, centerY + 7);
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

		WorldViewContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		// Initially mark every zone as being no longer in use
		for (int x = 0; x < NUM_ZONES; ++x)
			for (int z = 0; z < NUM_ZONES; ++z)
				ctx.zones[x][z].cull = true;


		if (ctx.sceneContext != null && ctx.sceneContext.currentArea == nextSceneContext.currentArea) {
			// Find zones which overlap, and reuse them
			if (prev.isInstance() == scene.isInstance() && prev.getRoofRemovalMode() == scene.getRoofRemovalMode()) {
				int[][][] prevTemplates = prev.getInstanceTemplateChunks();
				int[][][] curTemplates = scene.getInstanceTemplateChunks();

				for (int x = 0; x < NUM_ZONES; ++x) {
					next:
					for (int z = 0; z < NUM_ZONES; ++z) {
						int ox = x + dx;
						int oz = z + dy;
						if(ox < 0 || ox >= NUM_ZONES || oz < 0 || oz >= NUM_ZONES)
							continue;

						int reuseState = canReuse(ctx.zones, ox, oz);
						if (reuseState == REUSE_STATE_NONE)
							continue;

						if (scene.isInstance()) {
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - nextSceneContext.sceneOffset / 8;
							int jz = z - nextSceneContext.sceneOffset / 8;
							int jox = ox - nextSceneContext.sceneOffset / 8;
							int joz = oz - nextSceneContext.sceneOffset / 8;
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < SCENE_SIZE / 8 && jz >= 0 && jz < SCENE_SIZE / 8) {
								if (jox >= 0 && jox < SCENE_SIZE / 8 && joz >= 0 && joz < SCENE_SIZE / 8) {
									for (int level = 0; level < 4; ++level) {
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate) {
											// Does this ever happen?
											log.warn("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;
						assert old.sizeO > 0 || old.sizeA > 0;
						assert old.cull;

						if (reuseState == REUSE_STATE_PARTIAL) {
							if(nextPlayerPos == null)
								continue;
							old.invalidate = true;
						}

						old.cull = false;
						old.metadataDirty = true;

						nextZones[x][z] = old;
					}
				}
			} else {
				log.debug("Couldn't reuse anything! \nprev.isInstance()={} cur.isInstance={}\nprev.roofRemovalMode={} cur.roofRemovalMode={}",
					prev.isInstance(), scene.isInstance(),
					prev.getRoofRemovalMode(), scene.getRoofRemovalMode());
			}
		}

		zoneStreamingManager.resumeStreaming();
		for (int x = 0; x < NUM_ZONES; ++x) {
			for (int z = 0; z < NUM_ZONES; ++z) {
				Zone zone = nextZones[x][z];
				if(zone == null)
					zone = nextZones[x][z] = new Zone();

				// TODO: Partial zone reuse still need terrain gen performed, this is why the issues occur along borders
				zone.needsTerrainGen = true;

				if (!zone.initialized) {
					boolean isZoneRequired = root.sceneContext == null || nextSceneContext.sceneBase == null || nextPlayerPos == null;

					if(!isZoneRequired) {
						int baseX = (x - (nextSceneContext.sceneOffset >> 3)) << 10;
						int baseZ = (z - (nextSceneContext.sceneOffset >> 3)) << 10;
						isZoneRequired = distance(vec(baseX, baseZ), vec(nextPlayerPos[0], nextPlayerPos[1])) > ZONE_DEFER_DIST_START;
					}

					WorkHandle handle = zoneStreamingManager.queueZone(ctx, nextSceneContext, zone, x, z, isZoneRequired);
					if(isZoneRequired) {
						ctx.streamingZones.add(handle);
					} else {
						nextSceneContext.totalDeferred++;
					}
					nextSceneContext.totalNewZones++;
				} else {
					nextSceneContext.totalReused++;
				}
			}
		}

		final int[][][] prids = prev.getRoofs();
		final int[][][] nrids = scene.getRoofs();
		for (int x = 0; x < NUM_ZONES; ++x) {
			for (int z = 0; z < NUM_ZONES; ++z) {
				// Calculate roof ids for the zone
				for (int level = 0; level < 4; ++level) {
					int ox = x + (dx << 3);
					int oz = z + (dy << 3);

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

		log.debug("loadScene time: {}", sw);
	}

	@SneakyThrows
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

		lightManager.asyncLoadTask.get();
		lightManager.setupImposterTracking(nextSceneContext);
		fishingSpotReplacer.despawnRuneLiteObjects();

		npcDisplacementCache.clear();

		boolean isFirst = root.sceneContext == null;
		if (!isFirst)
			root.sceneContext.destroy(); // Destroy the old context before replacing it

		root.completeStreaming();

		int totalOpaque = 0;
		int totalAlpha = 0;
		for(int x = 0; x < NUM_ZONES; ++x) {
			for(int z = 0; z < NUM_ZONES; ++z) {
				totalOpaque += nextZones[x][z].bufLen;
				totalAlpha += nextZones[x][z].bufLenA;
			}
		}

		log.debug(
			"upload time {} reused {} deferred {} new {} len opaque {} size opaque {} KiB len alpha {} size alpha {} KiB",
			sw, nextSceneContext.totalReused, nextSceneContext.totalDeferred, nextSceneContext.totalNewZones,
			totalOpaque, ((long) totalOpaque * Zone.VERT_SIZE * 3) / KiB,
			totalAlpha, ((long) totalAlpha * Zone.VERT_SIZE * 3) / KiB
		);


		if (nextSceneContext.intersects(areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
			plugin.isInHouse = true;
			plugin.isInChambersOfXeric = false;
		} else {
			plugin.isInHouse = false;
			plugin.isInChambersOfXeric = nextSceneContext.intersects(areaManager.getArea("CHAMBERS_OF_XERIC"));
		}

		WorldViewContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];

				if (zone.cull) {
					zone.free();
				} else {
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}

				nextZones[x][z].setMetadata(ctx, nextSceneContext, x, z);
			}
		}

		ctx.zones = nextZones;
		root.sceneContext = nextSceneContext;

		nextZones = null;
		nextRoofChanges = null;
		nextSceneContext = null;
		root.isLoading = false;

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
		log.debug("swapScene time: {}", sw);
	}

	private void loadSubScene(WorldView worldView, Scene scene) {
		int worldViewId = worldView.getId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		WorldViewContext prevCtx = subs[worldViewId];
		if (prevCtx != null) {
			log.error("Reload of an already loaded sub scene?");
			prevCtx.free();
		}
		assert prevCtx == null;

		if(proceduralGenerator.asyncProcGenTask != null) {
			try {
				proceduralGenerator.asyncProcGenTask.get();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			} catch (ExecutionException e) {
				throw new RuntimeException(e);
			}
		}

		var sceneContext = new ZoneSceneContext(client, worldView, scene, plugin.getExpandedMapLoadingChunks(), null);
		proceduralGenerator.generateSceneData(sceneContext);

		final WorldViewContext ctx = new WorldViewContext(worldView, sceneContext, uboWorldViews);
		subs[worldViewId] = ctx;

		for(int x = 0; x <  ctx.sizeX; ++x) {
			for(int z = 0; z < ctx.sizeZ; ++z) {
				zoneStreamingManager.queueZone(ctx, sceneContext, ctx.zones[x][z], x, z, false);
			}
		}
	}

	private void swapSubScene(Scene scene) {
		WorldViewContext ctx = context(scene);
		if (ctx == null)
			return;

		Stopwatch sw = Stopwatch.createStarted();
		ctx.completeStreaming();
		ctx.isLoading = false;
		ctx.initMetadata();

		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				ctx.zones[x][z].setMetadata(ctx, ctx.sceneContext, x, z);
			}
		}

		log.debug("swapSubScene time {} WorldView ready: {}", sw, scene.getWorldViewId());
	}
}
