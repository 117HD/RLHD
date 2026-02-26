/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import com.google.common.base.Stopwatch;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.lights.Alignment;
import rs117.hd.scene.particles.emitter.EmitterDefinitionManager;
import rs117.hd.scene.particles.emitter.EmitterPlacement;
import rs117.hd.scene.particles.emitter.ObjectEmitterBinding;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.data.ObjectType;
import rs117.hd.utils.HDUtils;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.HDUtils.isSphereIntersectingFrustum;
import static rs117.hd.utils.MathUtils.*;

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
	private final Deque<Particle> particlePool = new ArrayDeque<>(512);

	private final float[] spawnOrigin = new float[3];
	private final float[] spawnPosScratch = new float[3];
	private final int[] spawnOffsetScratch = new int[2];
	private final int[] localScratch = new int[3];
	private final float[] updatePosOut = new float[3];
	private final int[] planeOutScratch = new int[1];
	private ParticleEmitter[] emitterIterationArray = new ParticleEmitter[0];
	private final Map<TileObject, float[]> objectPositionCache = new HashMap<>();

	@Getter
	private int lastEmittersUpdating, lastEmittersCulled;

	public Particle obtainParticle() {
		Particle p = particlePool.poll();
		if (p == null) return new Particle();
		p.resetForPool();
		return p;
	}

	public void releaseParticle(Particle p) {
		if (p != null) particlePool.offer(p);
	}

	public int getMaxParticles() {
		return MAX_PARTICLES;
	}

	public void addSpawnedParticleToBuffer(Particle p, float ox, float oy, float oz, ParticleEmitter emitter) {
		ParticleDefinition def = emitter.getDefinition();
		p.emitter = emitter;
		p.emitterOriginX = ox;
		p.emitterOriginY = oy;
		p.emitterOriginZ = oz;
		if (def != null) {
			if (def.colourIncrementPerSecond != null) {
				p.colourIncrementPerSecond = def.colourIncrementPerSecond;
				p.colourTransitionEndLife = p.maxLife - def.colourTransitionSecondsConstant;
			}
			if (def.targetScale >= 0f) {
				p.scaleIncrementPerSecond = def.scaleIncrementPerSecondCached;
				p.scaleTransitionEndLife = p.maxLife - def.scaleTransitionSecondsConstant;
			}
			if (def.targetSpeed >= 0f) {
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
		particleBuffer.addFrom(p);
		releaseParticle(p);
	}

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
		particleBuffer.ensureCapacity(MAX_PARTICLES);
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
		if (ctx == null || emitterDefinitionManager.getObjectBindingsByType().isEmpty()) return;
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
		// Do not preload particle textures at all; they are lazily loaded on first use in ParticleTextureLoader.getTextureId.
		// Preloading (even only emitter-used textures) breaks shadows on some setups.
		loadSceneParticles(plugin.getSceneContext());
	}

	public void recreateEmittersFromPlacements(@Nullable SceneContext ctx) {
		final List<ParticleEmitter> definitionEmitters = emitterDefinitionManager.getDefinitionEmitters();
		if (!definitionEmitters.isEmpty()) {
			sceneEmitters.removeAll(definitionEmitters);
			definitionEmitters.clear();
		}

		particleTextureLoader.setActiveTextureName(particleDefinitionLoader.getDefaultTexturePath());

		final Map<String, ParticleDefinition> definitions = particleDefinitionLoader.getDefinitions();
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
			final ParticleDefinition def = definitions.get(pid);
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

	private ParticleEmitter createEmitterFromDefinition(ParticleDefinition def, WorldPoint wp) {
		def.postDecode();
		if (def.fallbackDefinition != null)
			def = def.fallbackDefinition;
		float syMin = def.spreadYawMin * UNITS_TO_RAD;
		float syMax = def.spreadYawMax * UNITS_TO_RAD;
		float spMin = def.spreadPitchMin * UNITS_TO_RAD;
		float spMax = def.spreadPitchMax * UNITS_TO_RAD;
		float sizeMin = def.minScale;
		float sizeMax = def.maxScale;
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
		if (def.targetSpeed >= 0f)
			e.targetSpeed(def.targetSpeed, def.speedTransitionPercent / 100f * 2f);
		if (def.targetScale >= 0f)
			e.targetScale(def.targetScale, scaleTrans);
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

	public Map<String, ParticleDefinition> getDefinitions() {
		return particleDefinitionLoader.getDefinitions();
	}

	@Nullable
	public ParticleDefinition getDefinition(String id) {
		return particleDefinitionLoader.getDefinition(id);
	}

	public List<String> getDefinitionIdsOrdered() {
		return particleDefinitionLoader.getDefinitionIdsOrdered();
	}

	public void applyDefinitionToEmitter(ParticleEmitter emitter, ParticleDefinition def) {
		if (emitter == null || def == null) return;
		WorldPoint wp = emitter.getWorldPoint();
		if (wp == null) return;
		def.postDecode();
		if (def.fallbackDefinition != null)
			def = def.fallbackDefinition;
		// spread 0–2048 angle units (float), speed/scale from JSON as-is
		float syMin = def.spreadYawMin * UNITS_TO_RAD;
		float syMax = def.spreadYawMax * UNITS_TO_RAD;
		float spMin = def.spreadPitchMin * UNITS_TO_RAD;
		float spMax = def.spreadPitchMax * UNITS_TO_RAD;
		float sizeMin = def.minScale;
		float sizeMax = def.maxScale;
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
		if (def.targetSpeed >= 0f)
			emitter.targetSpeed(def.targetSpeed, def.speedTransitionPercent / 100f * 2f);
		else
			emitter.targetSpeed(ParticleDefinition.NO_TARGET, 1f);
		if (def.targetScale >= 0f)
			emitter.targetScale(def.targetScale, scaleTrans);
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
		ParticleDefinition def = particleDefinitionLoader.getDefinition(definitionId);
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
		if (tileObject.getPlane() < 0 || emitterDefinitionManager.getObjectBindingsByType().isEmpty())
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
		List<ObjectEmitterBinding> bindings = emitterDefinitionManager.getObjectBindingsByType().get(objectId);
		if (bindings == null || bindings.isEmpty()) return;
		WorldPoint wp = tileObject.getWorldLocation();
		List<ParticleEmitter> created = new ArrayList<>();
		for (ObjectEmitterBinding binding : bindings) {
			ParticleDefinition def = particleDefinitionLoader.getDefinition(binding.getParticleId());
			if (def == null) continue;
			ParticleEmitter e = createEmitterFromDefinition(def, wp);
			e.particleId(def.id);
			e.positionOffset(binding.getOffsetX(), binding.getOffsetY(), binding.getOffsetZ());
			e.setAlignment(binding.getAlignment());
			e.setTileObject(tileObject);
			if (tileObject instanceof GameObject) {
				GameObject go = (GameObject) tileObject;
				e.setOrientation(HDUtils.getModelOrientation(go.getConfig()));
				e.setSizeX(go.sizeX());
				e.setSizeY(go.sizeY());
			} else {
				e.setSizeX(1);
				e.setSizeY(1);
			}
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
			objectPositionCache.clear();
			float drawDistance = (float) (plugin.getDrawDistance() * LOCAL_TILE_SIZE);
			final float halfTile = LOCAL_TILE_SIZE / 2f;
			float farSq = drawDistance + halfTile + halfTile * 2;
			farSq *= farSq;
			final long gameCycle = client.getGameCycle();
			final int maxParticles = MAX_PARTICLES;
			final int[] cameraShift = plugin.cameraShift;
			final float[][] cameraFrustum = plugin.cameraFrustum;
			final int[][][] tileHeights = ctx.scene.getTileHeights();
			ParticleBuffer buf = particleBuffer;

			int numEmitters = sceneEmitters.size();
			if (emitterIterationArray.length < numEmitters)
				emitterIterationArray = new ParticleEmitter[numEmitters];
			sceneEmitters.toArray(emitterIterationArray);
			for (int e = 0; e < numEmitters; e++) {
				ParticleEmitter emitter = emitterIterationArray[e];
				ParticleDefinition def = emitter.getDefinition();
				boolean skipCulling = def != null && def.displayWhenCulled;

				TileObject obj = emitter.getTileObject();
				if (obj != null) {
					LocalPoint lp = obj.getLocalLocation();
					float dx = plugin.cameraFocalPoint[0] - lp.getX();
					float dz = plugin.cameraFocalPoint[1] - lp.getY();
					if (dx * dx + dz * dz >= farSq) {
						if (!skipCulling) emittersCulledThisFrame.add(emitter);
						continue;
					}
				}

				if (!getEmitterSpawnPosition(ctx, emitter, updatePosOut, planeOutScratch, tileHeights, spawnOrigin, spawnPosScratch, spawnOffsetScratch, localScratch, objectPositionCache)) {
					if (!skipCulling) emittersCulledThisFrame.add(emitter);
					continue;
				}
				int plane = planeOutScratch[0];

				boolean visible = true;
				float distX = plugin.cameraFocalPoint[0] - updatePosOut[0];
				float distZ = plugin.cameraFocalPoint[1] - updatePosOut[2];
				float distanceSquared = distX * distX + distZ * distZ;
				float maxRadius = halfTile * 2;
				float near = -maxRadius * maxRadius;
				float far = drawDistance + halfTile + maxRadius;
				far *= far;
				if (distanceSquared <= near || distanceSquared >= far)
					visible = false;
				if (visible) {
					visible = isSphereIntersectingFrustum(
						updatePosOut[0] + cameraShift[0],
						updatePosOut[1],
						updatePosOut[2] + cameraShift[1],
						maxRadius,
						cameraFrustum,
						4
					);
				}
				if (!visible) {
					if (!skipCulling) emittersCulledThisFrame.add(emitter);
					continue;
				}

				if (buf.count >= maxParticles) continue;
				if (def != null && def.hasLevelBounds) {
					int lower = def.lowerBoundLevel == -2 ? 0 : (def.lowerBoundLevel == -1 ? plane : def.lowerBoundLevel);
					int upper = def.upperBoundLevel == -2 ? 3 : (def.upperBoundLevel == -1 ? plane : def.upperBoundLevel);
					if (plane < lower || plane > upper) continue;
				}
				if (def != null)
					emitter.setDirectionYaw((float) def.directionYaw * UNITS_TO_RAD);
				emitter.tick(dt, gameCycle, updatePosOut[0], updatePosOut[1], updatePosOut[2], plane, this);
			}
			lastEmittersCulled = emittersCulledThisFrame.size();
			lastEmittersUpdating = sceneEmitters.size() - lastEmittersCulled;
		} else {
			lastEmittersUpdating = 0;
			lastEmittersCulled = sceneEmitters.size();
		}

		ParticleBuffer buf = particleBuffer;
		int n = buf.count;
		int tickDelta = Math.max(1, (int) Math.round(dt * 50));
		tickDelta = Math.min(tickDelta, 10);
		for (int i = 0; i < n; ) {
			if (EmittedParticle.tick(buf, i, tickDelta, this)) {
				buf.swap(i, n - 1);
				n--;
			} else {
				i++;
			}
		}
		buf.count = n;

		if (ctx != null && ctx.sceneBase != null) {
			float maxDistSq = (float) (plugin.getDrawDistance() * LOCAL_TILE_SIZE);
			maxDistSq *= maxDistSq;
			float cx = plugin.cameraPosition[0];
			float cy = plugin.cameraPosition[1];
			float cz = plugin.cameraPosition[2];
			for (int i = 0; i < buf.count; i++) {
				float px = (float) (buf.xFixed[i] >> 12);
				float py = (float) (buf.yFixed[i] >> 12);
				float pz = (float) (buf.zFixed[i] >> 12);
				float dx = px - cx;
				float dy = py - cy;
				float dz = pz - cz;
				if (dx * dx + dy * dy + dz * dz <= maxDistSq)
					buf.syncRefToFloat(i);
			}
		} else {
			for (int i = 0; i < buf.count; i++)
				buf.syncRefToFloat(i);
		}
	}

	@Nullable
	private static Model getModelFromTileObject(TileObject obj) {
		Renderable r = null;
		if (obj instanceof GameObject)
			r = ((GameObject) obj).getRenderable();
		else if (obj instanceof DecorativeObject)
			r = ((DecorativeObject) obj).getRenderable();
		else if (obj instanceof WallObject)
			r = ((WallObject) obj).getRenderable1();
		else if (obj instanceof GroundObject)
			r = ((GroundObject) obj).getRenderable();
		if (r == null) return null;
		if (r instanceof Model) return (Model) r;
		if (r instanceof DynamicObject) return ((DynamicObject) r).getModelZbuf();
		return r.getModel();
	}

	/**
	 * Current orientation of the tile object in JAU (0–2048). Used so emitter offsets rotate with the object.
	 */
	private static int getObjectOrientation(TileObject tileObject) {
		if (tileObject instanceof GroundObject)
			return HDUtils.getModelOrientation(((GroundObject) tileObject).getConfig());
		if (tileObject instanceof DecorativeObject)
			return HDUtils.getModelOrientation(((DecorativeObject) tileObject).getConfig());
		if (tileObject instanceof WallObject)
			return HDUtils.convertWallObjectOrientation(((WallObject) tileObject).getOrientationA());
		if (tileObject instanceof GameObject)
			return HDUtils.getModelOrientation(((GameObject) tileObject).getConfig());
		return 0;
	}

	/**
	 * Same object-type position offset as LightManager.spawnLights (for position/height parity with lights).
	 */
	private static void getObjectPositionOffset(TileObject tileObject, int[] outOffset) {
		outOffset[0] = 0;
		outOffset[1] = 0;
		if (tileObject instanceof GroundObject) {
			// no offset
		} else if (tileObject instanceof DecorativeObject) {
			var object = (DecorativeObject) tileObject;
			int ori = HDUtils.getModelOrientation(object.getConfig());
			switch (ObjectType.fromConfig(object.getConfig())) {
				case WallDecorDiagonalNoOffset:
				case WallDecorDiagonalOffset:
				case WallDecorDiagonalBoth:
					ori = (ori + 512) % 2048;
					outOffset[0] = SINE[ori] * 64 >> 16;
					outOffset[1] = COSINE[ori] * 64 >> 16;
					break;
			}
			outOffset[0] += object.getXOffset();
			outOffset[1] += object.getYOffset();
		} else if (tileObject instanceof WallObject) {
			// no offset
		} else if (tileObject instanceof GameObject) {
			var object = (GameObject) tileObject;
			int ori = HDUtils.getModelOrientation(object.getConfig());
			int offsetDist = 64;
			switch (ObjectType.fromConfig(object.getConfig())) {
				case RoofEdgeDiagonalCorner:
				case RoofDiagonalWithRoofEdge:
					ori += 1024;
					offsetDist = round(offsetDist / sqrt(2));
				case WallDiagonal:
					ori = (ori + 2048 - 256) % 2048;
					outOffset[0] = SINE[ori] * offsetDist >> 16;
					outOffset[1] = COSINE[ori] * offsetDist >> 16;
					break;
			}
		}
	}

	/**
	 * Computes the spawn position (local x, y, z) for an emitter — same as used in update().
	 * Used by ParticleGizmoOverlay to draw the gizmo where particles start.
	 */
	public boolean getEmitterSpawnPosition(@Nullable SceneContext ctx, ParticleEmitter emitter, float[] outPos, int[] outPlane) {
		if (ctx == null || ctx.sceneBase == null || outPos == null || outPos.length < 3)
			return false;
		int[][][] tileHeights = ctx.scene.getTileHeights();
		return getEmitterSpawnPosition(ctx, emitter, outPos, outPlane, tileHeights, null, null, null, null, null);
	}

	private boolean getEmitterSpawnPosition(@Nullable SceneContext ctx, ParticleEmitter emitter, float[] outPos, int[] outPlane,
			int[][][] tileHeights, float[] scratchOrigin, float[] scratchPos, int[] scratchOffset, int[] scratchLocal,
			@Nullable Map<TileObject, float[]> positionCache) {
		if (ctx == null || ctx.sceneBase == null || outPos == null || outPos.length < 3 || tileHeights == null)
			return false;
		float halfTile = LOCAL_TILE_SIZE / 2f;
		int sceneOffset = ctx.sceneOffset;
		float[] origin = scratchOrigin != null ? scratchOrigin : new float[3];
		float[] pos = scratchPos != null ? scratchPos : new float[3];
		int[] offset = scratchOffset != null ? scratchOffset : new int[2];
		TileObject obj = emitter.getTileObject();
		int plane;
		if (obj != null) {
			if (positionCache != null) {
				float[] cached = positionCache.get(obj);
				if (cached != null) {
					outPos[0] = cached[0];
					outPos[1] = cached[1];
					outPos[2] = cached[2];
					if (outPlane != null && outPlane.length >= 1)
						outPlane[0] = (int) cached[3];
					return true;
				}
			}
			LocalPoint lp = obj.getLocalLocation();
			plane = obj.getPlane();
			getObjectPositionOffset(obj, offset);
			int posX = lp.getX() + offset[0];
			int posZ = lp.getY() + offset[1];
			origin[0] = posX;
			origin[2] = posZ;
			float baseTerrain = getTerrainHeight(ctx, posX, posZ, plane, tileHeights);
			Model model = getModelFromTileObject(obj);
			float objHeight = baseTerrain + (model != null ? model.getBottomY() : 0);
			origin[1] = Math.max(objHeight, baseTerrain);
		} else {
			WorldPoint wp = emitter.getWorldPoint();
			if (wp == null) return false;
			int[] local = scratchLocal != null ? ctx.worldToLocalFirst(wp, scratchLocal) : ctx.worldToLocalFirst(wp);
			if (local == null) return false;
			plane = local[2];
			int tileExX = local[0] / LOCAL_TILE_SIZE + sceneOffset;
			int tileExY = local[1] / LOCAL_TILE_SIZE + sceneOffset;
			origin[0] = local[0] + halfTile;
			origin[2] = local[1] + halfTile;
			if (tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE)
				origin[1] = tileHeights[plane][tileExX][tileExY] - emitter.getHeightOffset();
			else
				origin[1] = 0;
		}
		pos[0] = origin[0];
		pos[1] = origin[1];
		pos[2] = origin[2];
		int baseOrientation = obj != null ? getObjectOrientation(obj) : emitter.getOrientation();
		int orientation = emitter.getAlignment().relative ? mod(baseOrientation + emitter.getAlignment().orientation, 2048) : emitter.getAlignment().orientation;
		if (emitter.getAlignment() != Alignment.CUSTOM) {
			int localSizeX = emitter.getSizeX() * LOCAL_TILE_SIZE;
			int localSizeY = emitter.getSizeY() * LOCAL_TILE_SIZE;
			float radius = emitter.getAlignment().radial ? localSizeX / 2f : (float) Math.sqrt(localSizeX * localSizeX + localSizeY * localSizeY) / 2;
			float sine = sin(orientation * JAU_TO_RAD);
			float cosine = cos(orientation * JAU_TO_RAD);
			cosine /= (float) localSizeX / (float) Math.max(localSizeY, 1);
			pos[0] += (int) (radius * sine);
			pos[2] += (int) (radius * cosine);
		}
		if (obj != null) {
			float sin = sin(orientation * JAU_TO_RAD);
			float cos = cos(orientation * JAU_TO_RAD);
			float x = emitter.getOffsetX();
			float z = emitter.getOffsetY();
			pos[0] += -cos * x - sin * z;
			pos[1] -= emitter.getOffsetZ();
			pos[2] += -cos * z + sin * x;
		}
		outPos[0] = pos[0];
		outPos[1] = pos[1];
		outPos[2] = pos[2];
		if (outPlane != null && outPlane.length >= 1)
			outPlane[0] = plane;
		if (obj != null && positionCache != null) {
			float[] cached = new float[] { outPos[0], outPos[1], outPos[2], plane };
			positionCache.put(obj, cached);
		}
		return true;
	}

	public static float getTerrainHeight(SceneContext ctx, int localX, int localZ, int plane) {
		return getTerrainHeight(ctx, localX, localZ, plane, ctx != null && ctx.sceneBase != null ? ctx.scene.getTileHeights() : null);
	}

	private static float getTerrainHeight(SceneContext ctx, int localX, int localZ, int plane, int[][][] tileHeights) {
		if (tileHeights == null)
			tileHeights = ctx.scene.getTileHeights();
		int sceneExX = Math.max(0, Math.min(EXTENDED_SCENE_SIZE - 2, (localX >> LOCAL_COORD_BITS) + ctx.sceneOffset));
		int sceneExY = Math.max(0, Math.min(EXTENDED_SCENE_SIZE - 2, (localZ >> LOCAL_COORD_BITS) + ctx.sceneOffset));
		int x = localX & (LOCAL_TILE_SIZE - 1);
		int y = localZ & (LOCAL_TILE_SIZE - 1);
		int h0 = (x * tileHeights[plane][sceneExX + 1][sceneExY] + (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneExX][sceneExY]) >> LOCAL_COORD_BITS;
		int h1 = (x * tileHeights[plane][sceneExX + 1][sceneExY + 1] + (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneExX][sceneExY + 1]) >> LOCAL_COORD_BITS;
		return (float) ((y * h1 + (LOCAL_TILE_SIZE - y) * h0) >> LOCAL_COORD_BITS);
	}
}
