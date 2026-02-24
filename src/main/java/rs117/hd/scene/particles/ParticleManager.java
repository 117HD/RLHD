/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import com.google.common.base.Stopwatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.emitter.EmitterDefinitionManager;
import rs117.hd.scene.particles.emitter.EmitterPlacement;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.scene.particles.emitter.ParticleEmitterDefinition;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ResourcePath;

import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Perspective.LOCAL_COORD_BITS;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.utils.MathUtils.min;

@Slf4j
public class ParticleManager {

	private static final int MAX_PARTICLES = 4096;
	private static final float UNITS_TO_RAD = (float) (2 * Math.PI / 2048);
	private static final float VEL_EPS = 1e-6f;
	private static final int FALLOFF_DIV_LINEAR = 1 << 18;
	private static final int FALLOFF_DIV_SQUARED = 1 << 28;

	private static long tileKey(WorldPoint wp) {
		return ((long) wp.getPlane() << 28) | ((wp.getX() & 0x3FFF) << 14) | (wp.getY() & 0x3FFF);
	}

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPlugin plugin;

	@Inject
	private EmitterDefinitionManager emitterDefinitionManager;

	@Inject
	private ParticleDefinitionLoader particleDefinitionLoader;

	@Inject
	private ParticleTextureLoader particleTextureLoader;

	@Inject
	private ClientThread clientThread;

	@Getter
	private final List<ParticleEmitter> sceneEmitters = new ArrayList<>();
	@Getter
	private final Map<TileObject, List<ParticleEmitter>> emittersByTileObject = new LinkedHashMap<>();
	@Getter
	private final ParticleBuffer particleBuffer = new ParticleBuffer();
	@Getter
	private final Set<ParticleEmitter> emittersCulledThisFrame = new HashSet<>();

	@Getter
	private int lastEmittersUpdating, lastEmittersCulled;

	public void addEmitter(ParticleEmitter emitter) {
		if (emitter != null && !sceneEmitters.contains(emitter))
			sceneEmitters.add(emitter);
	}

	public void removeEmitter(ParticleEmitter emitter) {
		sceneEmitters.remove(emitter);
	}

	public void clear() {
		removeAllObjectSpawnedEmitters();
		sceneEmitters.clear();
		emitterDefinitionManager.getDefinitionEmitters().clear();
		particleBuffer.clear();
	}

	public void startUp() {
		eventBus.register(this);
		loadConfig();
	}

	public void shutDown() {
		eventBus.unregister(this);
		removeAllObjectSpawnedEmitters();
	}

	private void removeAllObjectSpawnedEmitters() {
		for (List<ParticleEmitter> list : emittersByTileObject.values())
			sceneEmitters.removeAll(list);
		emittersByTileObject.clear();
	}

	public void loadSceneParticles(@Nullable SceneContext ctx) {
		removeAllObjectSpawnedEmitters();
		recreateEmittersFromPlacements(ctx);
		if (ctx == null || emitterDefinitionManager.getObjectEmittersByType().isEmpty()) return;
		Stopwatch sw = Stopwatch.createStarted();
		for (Tile[][] plane : ctx.scene.getExtendedTiles()) {
			for (Tile[] column : plane) {
				for (Tile tile : column) {
					if (tile == null) continue;
					DecorativeObject deco = tile.getDecorativeObject();
					if (deco != null) handleObjectSpawn(ctx, deco);
					WallObject wall = tile.getWallObject();
					if (wall != null) handleObjectSpawn(ctx, wall);
					GroundObject ground = tile.getGroundObject();
					if (ground != null && ground.getRenderable() != null) handleObjectSpawn(ctx, ground);
					for (GameObject go : tile.getGameObjects()) {
						if (go == null || go.getRenderable() instanceof Actor) continue;
						handleObjectSpawn(ctx, go);
					}
				}
			}
		}
		log.info("Finished loading scene particle emitters (count={}, took={}ms)", sceneEmitters.size(), sw.elapsed(TimeUnit.MILLISECONDS));
	}

	private void loadConfig() {
		Runnable onReload = () -> clientThread.invoke(this::applyConfig);
		emitterDefinitionManager.startup(onReload);
		particleDefinitionLoader.startup(onReload);
		applyConfig();
		log.info("[Particles] Loaded: Textures(size={}, time={}ms), Definitions(size={}, time={}ms), Emitters(placements={}, objects={}, time={}ms)",
			particleTextureLoader.getLastTextureCount(), particleTextureLoader.getLastLoadTimeMs(),
			particleDefinitionLoader.getLastDefinitionCount(), particleDefinitionLoader.getLastLoadTimeMs(),
			emitterDefinitionManager.getLastPlacements(), emitterDefinitionManager.getLastObjectBindings(), emitterDefinitionManager.getLastLoadTimeMs());
	}

	private void applyConfig() {
		emitterDefinitionManager.loadConfig();
		particleDefinitionLoader.loadConfig();
		particleTextureLoader.preload(particleDefinitionLoader.getAvailableTextureNames());
		loadSceneParticles(plugin.getSceneContext());
	}

	public void recreateEmittersFromPlacements(@Nullable SceneContext ctx) {
		final List<ParticleEmitter> definitionEmitters = emitterDefinitionManager.getDefinitionEmitters();
		if (!definitionEmitters.isEmpty()) {
			sceneEmitters.removeAll(definitionEmitters);
			definitionEmitters.clear();
		}

		particleTextureLoader.setActiveTextureName(particleDefinitionLoader.getDefaultTexturePath());

		final Map<String, ParticleEmitterDefinition> definitions = particleDefinitionLoader.getDefinitions();
		if (definitions.isEmpty()) {
			log.debug("[Particles] No particle definitions loaded, skipping emitter recreation.");
			return;
		}

		final List<EmitterPlacement> placements = emitterDefinitionManager.getPlacements();
		if (placements.isEmpty()) {
			return;
		}

		final boolean checkBounds = ctx != null;
		final var bounds = checkBounds ? ctx.sceneBounds : null;

		for (EmitterPlacement place : placements) {
			if (place.particleId == null) {
				continue;
			}

			final String pid = place.particleId.toUpperCase();
			final ParticleEmitterDefinition def = definitions.get(pid);
			if (def == null) {
				continue;
			}

			final WorldPoint wp = new WorldPoint(place.worldX, place.worldY, place.plane);

			if (checkBounds && !bounds.contains(wp)) {
				continue;
			}

			final ParticleEmitter emitter = createEmitterFromDefinition(def, wp);
			emitter.particleId(def.id);

			sceneEmitters.add(emitter);
			definitionEmitters.add(emitter);
		}
	}

	private ParticleEmitter createEmitterFromDefinition(ParticleEmitterDefinition def, WorldPoint wp) {
		def.postDecode();
		if (def.fallbackDefinition != null)
			def = def.fallbackDefinition;
		float syMin = (float) def.spreadYawMinDecoded * UNITS_TO_RAD;
		float syMax = (float) def.spreadYawMaxDecoded * UNITS_TO_RAD;
		float spMin = (float) def.spreadPitchMinDecoded * UNITS_TO_RAD;
		float spMax = (float) def.spreadPitchMaxDecoded * UNITS_TO_RAD;
		float sizeMin = def.minScaleDecoded / 16384f * 4f;
		float sizeMax = def.maxScaleDecoded / 16384f * 4f;
		float scaleTrans = def.scaleTransitionPercent > 0 ? def.scaleTransitionPercent / 100f * 2f : 1f;
		float[] colorMin = argbToFloat(def.minColourArgb);
		float[] colorMax = argbToFloat(def.maxColourArgb);
		float[] targetColor = def.targetColourArgb != 0 ? argbToFloat(def.targetColourArgb) : null;
		float lifeMin = def.minEmissionDelay / 64f;
		float lifeMax = Math.max(lifeMin, def.maxEmissionDelay / 64f);
		ParticleEmitter e = new ParticleEmitter()
			.at(wp)
			.heightOffset(def.heightOffset)
			.direction((float) def.directionYaw * UNITS_TO_RAD, (float) def.directionPitch * UNITS_TO_RAD)
			.spreadYaw(syMin, syMax)
			.spreadPitch(spMin, spMax)
			.speed(def.minSpeed, def.maxSpeed)
			.particleLifetime(lifeMin, lifeMax)
			.size(sizeMin, sizeMax)
			.color(colorMin[0], colorMin[1], colorMin[2], colorMin[3])
			.colorRange(colorMin, colorMax)
			.uniformColorVariation(def.uniformColourVariation)
			.emissionBurst(def.minSpawnCount, def.maxSpawnCount, def.initialSpawnCount);
		if (def.targetSpeed >= 0)
			e.targetSpeed(def.targetSpeed, def.speedTransitionPercent / 100f * 2f);
		if (def.targetScaleDecoded >= 0)
			e.targetScale(def.targetScaleDecoded / 16384f * 4f, scaleTrans);
		if (targetColor != null)
			e.targetColor(targetColor, def.colourTransitionPercent, def.alphaTransitionPercent);
		e.setDefinition(def);
		e.setEmissionTime(client.getGameCycle(), def.emissionCycleDuration, def.emissionTimeThreshold, def.emitOnlyBeforeTime, def.loopEmission);
		return e;
	}

	private static float[] argbToFloat(int argb) {
		return new float[] {
			((argb >> 16) & 0xff) / 255f,
			((argb >> 8) & 0xff) / 255f,
			(argb & 0xff) / 255f,
			((argb >> 24) & 0xff) / 255f
		};
	}

	public Map<String, ParticleEmitterDefinition> getDefinitions() {
		return particleDefinitionLoader.getDefinitions();
	}

	@Nullable
	public ParticleEmitterDefinition getDefinition(String id) {
		return particleDefinitionLoader.getDefinition(id);
	}

	public List<String> getDefinitionIdsOrdered() {
		return particleDefinitionLoader.getDefinitionIdsOrdered();
	}

	public void applyDefinitionToEmitter(ParticleEmitter emitter, ParticleEmitterDefinition def) {
		if (emitter == null || def == null) return;
		WorldPoint wp = emitter.getWorldPoint();
		if (wp == null) return;
		def.postDecode();
		if (def.fallbackDefinition != null)
			def = def.fallbackDefinition;
		float syMin = (float) def.spreadYawMinDecoded * UNITS_TO_RAD;
		float syMax = (float) def.spreadYawMaxDecoded * UNITS_TO_RAD;
		float spMin = (float) def.spreadPitchMinDecoded * UNITS_TO_RAD;
		float spMax = (float) def.spreadPitchMaxDecoded * UNITS_TO_RAD;
		float sizeMin = def.minScaleDecoded / 16384f * 4f;
		float sizeMax = def.maxScaleDecoded / 16384f * 4f;
		float scaleTrans = def.scaleTransitionPercent > 0 ? def.scaleTransitionPercent / 100f * 2f : 1f;
		float[] colorMin = argbToFloat(def.minColourArgb);
		float[] colorMax = argbToFloat(def.maxColourArgb);
		float[] targetColor = def.targetColourArgb != 0 ? argbToFloat(def.targetColourArgb) : null;
		float lifeMin = def.minEmissionDelay / 64f;
		float lifeMax = Math.max(lifeMin, def.maxEmissionDelay / 64f);
		emitter.at(wp);
		emitter.heightOffset(def.heightOffset);
		emitter.direction((float) def.directionYaw * UNITS_TO_RAD, (float) def.directionPitch * UNITS_TO_RAD);
		emitter.spreadYaw(syMin, syMax);
		emitter.spreadPitch(spMin, spMax);
		emitter.speed(def.minSpeed, def.maxSpeed);
		emitter.particleLifetime(lifeMin, lifeMax);
		emitter.size(sizeMin, sizeMax);
		emitter.color(colorMin[0], colorMin[1], colorMin[2], colorMin[3]);
		emitter.colorRange(colorMin, colorMax);
		emitter.uniformColorVariation(def.uniformColourVariation);
		emitter.emissionBurst(def.minSpawnCount, def.maxSpawnCount, def.initialSpawnCount);
		if (def.targetSpeed >= 0)
			emitter.targetSpeed(def.targetSpeed, def.speedTransitionPercent / 100f * 2f);
		else
			emitter.targetSpeed(-1f, 1f);
		if (def.targetScaleDecoded >= 0)
			emitter.targetScale(def.targetScaleDecoded / 16384f * 4f, scaleTrans);
		if (targetColor != null)
			emitter.targetColor(targetColor, def.colourTransitionPercent, def.alphaTransitionPercent);
		emitter.particleId(def.id);
		emitter.setDefinition(def);
		emitter.setEmissionTime(client.getGameCycle(), def.emissionCycleDuration, def.emissionTimeThreshold, def.emitOnlyBeforeTime, def.loopEmission);
		if (def.texture != null && !def.texture.isEmpty())
			particleTextureLoader.setActiveTextureName(def.texture);
	}

	public List<EmitterPlacement> getPlacements() {
		return emitterDefinitionManager.getPlacements();
	}

	@Nullable
	public String getActiveTextureName() {
		return particleTextureLoader.getActiveTextureName();
	}

	public List<String> getAvailableTextureNames() {
		return particleDefinitionLoader.getAvailableTextureNames();
	}

	public ParticleEmitter placeEmitter(WorldPoint worldPoint) {
		ParticleEmitter e = new ParticleEmitter().at(worldPoint);
		addEmitter(e);
		log.info("[Particles] Placed emitter at world ({}, {}, {}), total emitters={}", worldPoint.getX(), worldPoint.getY(), worldPoint.getPlane(), sceneEmitters.size());
		return e;
	}

	@Nullable
	public ParticleEmitter spawnEmitterFromDefinition(String definitionId, WorldPoint wp) {
		ParticleEmitterDefinition def = particleDefinitionLoader.getDefinition(definitionId);
		if (def == null) return null;
		ParticleEmitter e = createEmitterFromDefinition(def, wp);
		e.particleId(def.id);
		addEmitter(e);
		return e;
	}

	public int spawnPerformanceTestEmitters() {
		String[] ids = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" };
		int[][] offsets = {
			{ 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
			{ 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 }, { 2, 0 }
		};
		Player player = client.getLocalPlayer();
		if (player == null) return 0;
		WorldPoint base = player.getWorldLocation();
		int count = 0;
		for (int i = 0; i < ids.length; i++) {
			ParticleEmitter e = spawnEmitterFromDefinition(ids[i], base.dx(offsets[i][0]).dy(offsets[i][1]));
			if (e != null) count++;
		}
		log.info("[Particles] Performance test: spawned {} emitters around player", count);
		return count;
	}

	private int getImpostorId(TileObject tileObject) {
		ObjectComposition def = client.getObjectDefinition(tileObject.getId());
		if (def == null) return tileObject.getId();
		var impostorIds = def.getImpostorIds();
		if (impostorIds != null) {
			try {
				int impostorVarbit = def.getVarbitId();
				int impostorVarp = def.getVarPlayerId();
				int impostorIndex = -1;
				if (impostorVarbit != -1) {
					impostorIndex = client.getVarbitValue(impostorVarbit);
				} else if (impostorVarp != -1) {
					impostorIndex = client.getVarpValue(impostorVarp);
				}
				if (impostorIndex >= 0)
					return impostorIds[min(impostorIndex, impostorIds.length - 1)];
			} catch (Exception ignored) {}
		}
		return tileObject.getId();
	}

	private void handleObjectSpawn(TileObject tileObject) {
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext != null)
			handleObjectSpawn(sceneContext, tileObject);
	}

	private void handleObjectSpawn(@Nonnull SceneContext sceneContext, @Nonnull TileObject tileObject) {
		if (tileObject.getPlane() < 0 || emitterDefinitionManager.getObjectEmittersByType().isEmpty())
			return;
		int tileObjectId = tileObject.getId();
		if (tileObject instanceof GameObject &&
			((GameObject) tileObject).getRenderable() instanceof DynamicObject) {
			if (client.isClientThread()) {
				tileObjectId = getImpostorId(tileObject);
			} else {
				return;
			}
		}
		spawnEmittersForObject(tileObject, tileObjectId);
	}

	private void spawnEmittersForObject(TileObject tileObject, int objectId) {
		handleObjectDespawn(tileObject);
		List<String> particleIds = emitterDefinitionManager.getObjectEmittersByType().get(objectId);
		if (particleIds == null || particleIds.isEmpty()) return;
		WorldPoint wp = tileObject.getWorldLocation();
		List<ParticleEmitter> created = new ArrayList<>();
		for (String particleId : particleIds) {
			ParticleEmitterDefinition def = particleDefinitionLoader.getDefinition(particleId);
			if (def == null) continue;
			ParticleEmitter e = createEmitterFromDefinition(def, wp);
			e.particleId(def.id);
			sceneEmitters.add(e);
			created.add(e);
			if (def.texture != null && !def.texture.isEmpty())
				particleTextureLoader.setActiveTextureName(def.texture);
		}
		if (!created.isEmpty())
			emittersByTileObject.put(tileObject, created);
	}

	private void handleObjectDespawn(TileObject tileObject) {
		List<ParticleEmitter> list = emittersByTileObject.remove(tileObject);
		if (list != null)
			sceneEmitters.removeAll(list);
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e) {
		handleObjectSpawn(e.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e) {
		handleObjectDespawn(e.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned e) {
		handleObjectSpawn(e.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned e) {
		handleObjectDespawn(e.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned e) {
		handleObjectSpawn(e.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned e) {
		handleObjectDespawn(e.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned e) {
		handleObjectSpawn(e.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned e) {
		handleObjectDespawn(e.getGroundObject());
	}

	public void update(@Nullable SceneContext ctx, float dt) {
		emittersCulledThisFrame.clear();
		if (ctx != null && ctx.sceneBase != null) {
			final float cx = plugin.cameraPosition[0];
			final float cy = plugin.cameraPosition[1];
			final float cz = plugin.cameraPosition[2];
			float maxDistSq = (float) (plugin.getDrawDistance() * LOCAL_TILE_SIZE);
			maxDistSq *= maxDistSq;
			final float[][] frustum = plugin.cameraFrustum;
			final int frustumLen = frustum.length;
			final float halfTile = LOCAL_TILE_SIZE / 2f;
			final long gameCycle = client.getGameCycle();
			final int sceneOffset = ctx.sceneOffset;
			final int maxParticles = MAX_PARTICLES;
			ParticleBuffer buf = particleBuffer;

			// Group emitters by world tile for spatial culling
			List<ParticleEmitter> noTile = new ArrayList<>();
			Map<Long, List<ParticleEmitter>> byTile = new HashMap<>();
			for (ParticleEmitter emitter : sceneEmitters) {
				WorldPoint wp = emitter.getWorldPoint();
				if (wp == null) {
					noTile.add(emitter);
				} else {
					byTile.computeIfAbsent(tileKey(wp), k -> new ArrayList<>()).add(emitter);
				}
			}

			// Process emitters with no world position (cull or skip)
			for (ParticleEmitter emitter : noTile) {
				ParticleEmitterDefinition def = emitter.getDefinition();
				if (def == null || !def.displayWhenCulled)
					emittersCulledThisFrame.add(emitter);
			}

			// Process each tile: cull whole tile if first emitter is culled, else process each emitter
			for (Map.Entry<Long, List<ParticleEmitter>> entry : byTile.entrySet()) {
				List<ParticleEmitter> list = entry.getValue();
				ParticleEmitter first = list.get(0);
				ParticleEmitterDefinition def = first.getDefinition();
				boolean skipCulling = def != null && def.displayWhenCulled;
				WorldPoint wp = first.getWorldPoint();
				int[] local = wp != null ? ctx.worldToLocalFirst(wp) : null;
				boolean tileCulled;
				if (local == null) {
					tileCulled = !skipCulling;
				} else {
					int localX = local[0];
					int localY = local[1];
					int plane = local[2];
					int tileExX = localX / LOCAL_TILE_SIZE + sceneOffset;
					int tileExY = localY / LOCAL_TILE_SIZE + sceneOffset;
					float spawnY = 0f;
					if (tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE) {
						spawnY = ctx.scene.getTileHeights()[plane][tileExX][tileExY] - first.getHeightOffset();
					}
					float ex = localX + halfTile;
					float ez = localY + halfTile;
					float dx = ex - cx;
					float dy = spawnY - cy;
					float dz = ez - cz;
					tileCulled = dx * dx + dy * dy + dz * dz > maxDistSq
						|| !HDUtils.isSphereIntersectingFrustum(ex, spawnY, ez, halfTile, frustum, frustumLen);
					if (tileCulled && skipCulling) tileCulled = false;
				}
				if (tileCulled) {
					for (ParticleEmitter emitter : list)
						emittersCulledThisFrame.add(emitter);
					continue;
				}
				for (ParticleEmitter emitter : list) {
					def = emitter.getDefinition();
					skipCulling = def != null && def.displayWhenCulled;
					wp = emitter.getWorldPoint();
					if (wp == null) {
						if (!skipCulling) emittersCulledThisFrame.add(emitter);
						continue;
					}
					local = ctx.worldToLocalFirst(wp);
					if (local == null) {
						if (!skipCulling) emittersCulledThisFrame.add(emitter);
						continue;
					}
					int localX = local[0];
					int localY = local[1];
					int plane = local[2];
					int tileExX = localX / LOCAL_TILE_SIZE + sceneOffset;
					int tileExY = localY / LOCAL_TILE_SIZE + sceneOffset;
					float spawnY = 0f;
					if (tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE) {
						spawnY = ctx.scene.getTileHeights()[plane][tileExX][tileExY] - emitter.getHeightOffset();
					}
					float ex = localX + halfTile;
					float ez = localY + halfTile;
					float dx = ex - cx;
					float dy = spawnY - cy;
					float dz = ez - cz;
					if (dx * dx + dy * dy + dz * dz > maxDistSq) {
						if (!skipCulling) emittersCulledThisFrame.add(emitter);
						continue;
					}
					if (!HDUtils.isSphereIntersectingFrustum(ex, spawnY, ez, halfTile, frustum, frustumLen)) {
						if (!skipCulling) emittersCulledThisFrame.add(emitter);
						continue;
					}
					if (buf.count >= maxParticles) continue;
					if (def != null && def.hasLevelBounds) {
						int lower = def.lowerBoundLevel == -2 ? 0 : (def.lowerBoundLevel == -1 ? plane : def.lowerBoundLevel);
						int upper = def.upperBoundLevel == -2 ? 3 : (def.upperBoundLevel == -1 ? plane : def.upperBoundLevel);
						if (plane < lower || plane > upper) continue;
					}
					if (!emitter.isEmissionAllowedAtCycle(gameCycle)) continue;
					float ox = ex;
					float oz = ez;
					int toSpawn = emitter.advanceEmission(dt);
					for (int i = 0; i < toSpawn && buf.count < maxParticles; i++) {
						Particle p = emitter.spawn(ox, spawnY, oz, plane);
						if (p == null) continue;
						p.emitter = emitter;
						p.emitterOriginX = ox;
						p.emitterOriginY = spawnY;
						p.emitterOriginZ = oz;
						if (def != null) {
							if (def.colourIncrementPerSecond != null) {
								p.colourIncrementPerSecond = def.colourIncrementPerSecond;
								p.colourTransitionEndLife = p.maxLife - def.colourTransitionSecondsConstant;
							}
							if (def.targetScaleDecoded >= 0) {
								p.scaleIncrementPerSecond = def.scaleIncrementPerSecondCached;
								p.scaleTransitionEndLife = p.maxLife - def.scaleTransitionSecondsConstant;
							}
							if (def.targetSpeed >= 0) {
								p.speedIncrementPerSecond = def.speedIncrementPerSecondCached;
								p.speedTransitionEndLife = p.maxLife - def.speedTransitionSecondsConstant;
							}
							p.distanceFalloffType = def.distanceFalloffType;
							p.distanceFalloffStrength = def.distanceFalloffStrength;
							p.clipToTerrain = def.clipToTerrain;
							p.hasLevelBounds = def.hasLevelBounds;
							p.upperBoundLevel = def.upperBoundLevel;
							p.lowerBoundLevel = def.lowerBoundLevel;
						}
						buf.addFrom(p);
					}
				}
			}
			lastEmittersCulled = emittersCulledThisFrame.size();
			lastEmittersUpdating = sceneEmitters.size() - lastEmittersCulled;
		} else {
			lastEmittersUpdating = 0;
			lastEmittersCulled = sceneEmitters.size();
		}

		// Update and compact (swap-with-last when removing)
		ParticleBuffer buf = particleBuffer;
		int n = buf.count;
		for (int i = 0; i < n; ) {
			if (buf.emitter[i] != null && emittersCulledThisFrame.contains(buf.emitter[i])) {
				buf.swap(i, n - 1);
				n--;
				continue;
			}
			buf.life[i] -= dt;
			if (buf.life[i] <= 0) {
				buf.swap(i, n - 1);
				n--;
				continue;
			}
			if ((buf.flags[i] & ParticleBuffer.FLAG_COLOUR_INCREMENT) != 0 && buf.life[i] >= buf.colourTransitionEndLife[i]) {
				buf.colorR[i] = Math.max(0f, Math.min(1f, buf.colorR[i] + buf.colourIncR[i] * dt));
				buf.colorG[i] = Math.max(0f, Math.min(1f, buf.colorG[i] + buf.colourIncG[i] * dt));
				buf.colorB[i] = Math.max(0f, Math.min(1f, buf.colorB[i] + buf.colourIncB[i] * dt));
				buf.colorA[i] = Math.max(0f, Math.min(1f, buf.colorA[i] + buf.colourIncA[i] * dt));
			}
			if (buf.scaleIncPerSec[i] != 0f && buf.life[i] >= buf.scaleTransitionEndLife[i]) {
				buf.size[i] += buf.scaleIncPerSec[i] * dt;
			}
			if (buf.speedIncPerSec[i] != 0f && buf.life[i] >= buf.speedTransitionEndLife[i]) {
				float vx = buf.velX[i], vy = buf.velY[i], vz = buf.velZ[i];
				float cur = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
				if (cur > VEL_EPS) {
					float scale = (cur + buf.speedIncPerSec[i] * dt) / cur;
					buf.velX[i] = vx * scale;
					buf.velY[i] = vy * scale;
					buf.velZ[i] = vz * scale;
				}
			}
			if (buf.distanceFalloffType[i] == 1 || buf.distanceFalloffType[i] == 2) {
				float dx = buf.posX[i] - buf.emitterOriginX[i];
				float dy = buf.posY[i] - buf.emitterOriginY[i];
				float dz = buf.posZ[i] - buf.emitterOriginZ[i];
				float curSpeed = (float) Math.sqrt(buf.velX[i] * buf.velX[i] + buf.velY[i] * buf.velY[i] + buf.velZ[i] * buf.velZ[i]);
				if (curSpeed > VEL_EPS) {
					float scale;
					if (buf.distanceFalloffType[i] == 1) {
						float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / 4f;
						scale = Math.max(0f, 1f - buf.distanceFalloffStrength[i] * dist * dt / FALLOFF_DIV_LINEAR);
					} else {
						float distSq = dx * dx + dy * dy + dz * dz;
						scale = Math.max(0f, 1f - buf.distanceFalloffStrength[i] * distSq * dt / FALLOFF_DIV_SQUARED);
					}
					buf.velX[i] *= scale;
					buf.velY[i] *= scale;
					buf.velZ[i] *= scale;
				}
			}
			buf.posX[i] += buf.velX[i] * dt;
			buf.posY[i] += buf.velY[i] * dt;
			buf.posZ[i] += buf.velZ[i] * dt;
			if (buf.hasLevelBounds[i] && ctx != null) {
				int posXi = (int) buf.posX[i];
				int posZi = (int) buf.posZ[i];
				int pl = buf.plane[i];
				float ceiling = buf.upperBoundLevel[i] == -2 ? Float.MAX_VALUE
					: getTerrainHeight(ctx, posXi, posZi, buf.upperBoundLevel[i] == -1 ? pl : buf.upperBoundLevel[i]);
				float floor = buf.lowerBoundLevel[i] == -2 ? -Float.MAX_VALUE
					: (buf.lowerBoundLevel[i] == -1
						? (pl < 3 ? getTerrainHeight(ctx, posXi, posZi, pl + 1) : getTerrainHeight(ctx, posXi, posZi, pl) - 2048f)
						: getTerrainHeight(ctx, posXi, posZi, Math.min(3, buf.lowerBoundLevel[i] + 1)));
				if (buf.posY[i] > ceiling || buf.posY[i] < floor) {
					buf.swap(i, n - 1);
					n--;
					continue;
				}
			}
			i++;
		}
		buf.count = n;
	}

	private static float getTerrainHeight(SceneContext ctx, int localX, int localZ, int plane) {
		int sceneExX = Math.max(0, Math.min(EXTENDED_SCENE_SIZE - 2, (localX >> LOCAL_COORD_BITS) + ctx.sceneOffset));
		int sceneExY = Math.max(0, Math.min(EXTENDED_SCENE_SIZE - 2, (localZ >> LOCAL_COORD_BITS) + ctx.sceneOffset));
		int[][][] tileHeights = ctx.scene.getTileHeights();
		int x = localX & (LOCAL_TILE_SIZE - 1);
		int y = localZ & (LOCAL_TILE_SIZE - 1);
		int h0 = (x * tileHeights[plane][sceneExX + 1][sceneExY] + (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneExX][sceneExY]) >> LOCAL_COORD_BITS;
		int h1 = (x * tileHeights[plane][sceneExX + 1][sceneExY + 1] + (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneExX][sceneExY + 1]) >> LOCAL_COORD_BITS;
		return (float) ((y * h1 + (LOCAL_TILE_SIZE - y) * h0) >> LOCAL_COORD_BITS);
	}
}
