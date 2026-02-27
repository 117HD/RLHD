/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import com.google.common.base.Stopwatch;
import rs117.hd.scene.particles.core.buffer.ParticleBuffer;
import rs117.hd.scene.particles.core.MovingParticle;
import rs117.hd.scene.particles.core.Particle;
import rs117.hd.scene.particles.core.ParticleSystem;
import rs117.hd.scene.particles.definition.ParticleDefinition;
import rs117.hd.scene.particles.core.ParticleTextureLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
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
import rs117.hd.scene.particles.emitter.EmitterConfigEntry;
import rs117.hd.scene.particles.emitter.EmitterPlacement;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.data.ObjectType;
import rs117.hd.utils.HDUtils;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class ParticleManager {

	private static final int MAX_PARTICLES = 4096;

	public ParticleManager() {
		this.particleSystem = new ParticleSystem(MAX_PARTICLES);
	}
	private static final float UNITS_TO_RAD = (float) (2 * Math.PI / 2048);

	public static final class ParticleRenderContext {
		public float cameraX, cameraY, cameraZ;
		public float maxDistSq;
		public float[][] frustum;
		public int totalOnPlane;
		public int culledDistance;
		public int culledFrustum;
	}

	/**
	 * Filters particles by plane, distance, frustum. Fills visible indices and distance-squared; returns count.
	 */
	public final int filterVisibleParticles(ParticleBuffer buf, ParticleRenderContext ctx, int plane, long l,
			int[] outVisibleIndices, float[] outDistSq) {
		ctx.totalOnPlane = 0;
		ctx.culledDistance = 0;
		ctx.culledFrustum = 0;
		int n = 0;
		int maxOut = outVisibleIndices != null ? outVisibleIndices.length : 0;
		int maxDistSqLen = outDistSq != null ? outDistSq.length : 0;
		int frustumLen = ctx.frustum != null ? ctx.frustum.length : 0;
		for (int i = 0; i < buf.count; i++) {
			if (buf.plane[i] != plane)
				continue;
			ctx.totalOnPlane++;
			float dx = buf.posX[i] - ctx.cameraX;
			float dy = buf.posY[i] - ctx.cameraY;
			float dz = buf.posZ[i] - ctx.cameraZ;
			float dSq = dx * dx + dy * dy + dz * dz;
			if (dSq > ctx.maxDistSq) {
				ctx.culledDistance++;
				continue;
			}
			if (frustumLen > 0 && !HDUtils.isSphereIntersectingFrustum(buf.posX[i], buf.posY[i], buf.posZ[i], buf.size[i], ctx.frustum, frustumLen)) {
				ctx.culledFrustum++;
				continue;
			}
			if (n < maxOut && outVisibleIndices != null)
				outVisibleIndices[n] = i;
			if (n < maxDistSqLen && outDistSq != null)
				outDistSq[n] = dSq;
			n++;
			if (maxOut > 0 && n >= maxOut)
				break;
		}
		return n;
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
	private ParticleDefinition particleDefinitions;

	@Inject
	private ParticleTextureLoader particleTextureLoader;

	@Inject
	private ClientThread clientThread;

	@Getter
	private final ParticleSystem particleSystem;

	private final float[] spawnOrigin = new float[3];
	private final float[] spawnPosScratch = new float[3];
	private final int[] spawnOffsetScratch = new int[2];
	private final int[] localScratch = new int[3];
	private final float[] updatePosOut = new float[3];
	private final int[] planeOutScratch = new int[1];
	private ParticleEmitter[] emitterIterationArray = new ParticleEmitter[0];
	private final List<ParticleEmitter> performanceTestEmitters = new ArrayList<>();

	@Getter
	private boolean continuousRandomSpawn;

	@Getter
	private int lastEmittersUpdating, lastEmittersCulled;

	public void setContinuousRandomSpawn(boolean on) {
		this.continuousRandomSpawn = on;
	}

	public List<ParticleEmitter> getSceneEmitters() {
		return particleSystem.getEmitters();
	}

	public Map<TileObject, List<ParticleEmitter>> getEmittersByTileObject() {
		return particleSystem.getEmittersByTileObject();
	}

	public ParticleBuffer getParticleBuffer() {
		return particleSystem.getRenderBuffer();
	}

	public Set<ParticleEmitter> getEmittersCulledThisFrame() {
		return particleSystem.getEmittersCulledThisFrame();
	}

	public Particle obtainParticle() {
		return particleSystem.getPool().obtain();
	}

	public void releaseParticle(Particle p) {
		particleSystem.getPool().release(p);
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
			if (def.scale.targetScale >= 0) {
				p.scaleIncrementPerSecond = def.scaleIncrementPerSecondCached;
				p.scaleTransitionEndLife = p.maxLife - def.scaleTransitionSecondsConstant;
			}
			if (def.speed.targetSpeed >= 0) {
				p.speedIncrementPerSecond = def.speedIncrementPerSecondCached;
				p.speedTransitionEndLife = p.maxLife - def.speedTransitionSecondsConstant;
			}
			p.distanceFalloffType = def.physics.distanceFalloffType;
			p.distanceFalloffStrength = def.physics.distanceFalloffStrength;
			p.clipToTerrain = def.physics.clipToTerrain;
			p.hasLevelBounds = def.hasLevelBounds;
			p.upperBoundLevel = def.physics.upperBoundLevel;
			p.lowerBoundLevel = def.physics.lowerBoundLevel;
		}
		particleSystem.getRenderBuffer().addFrom(p);
		releaseParticle(p);
	}

	public void addEmitter(ParticleEmitter emitter) {
		particleSystem.addEmitter(emitter);
	}

	public void removeEmitter(ParticleEmitter emitter) {
		particleSystem.removeEmitter(emitter);
	}

	public void clear() {
		removeAllObjectSpawnedEmitters();
		particleSystem.getEmitters().clear();
		emitterDefinitionManager.getDefinitionEmitters().clear();
		particleSystem.getRenderBuffer().clear();
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
		for (List<ParticleEmitter> list : particleSystem.getEmittersByTileObject().values())
			particleSystem.removeEmitters(list);
		particleSystem.getEmittersByTileObject().clear();
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
		log.info("Finished loading scene particle emitters (count={}, took={}ms)", particleSystem.getEmitters().size(), sw.elapsed(TimeUnit.MILLISECONDS));
	}

	private void loadConfig() {
		Runnable onReload = () -> clientThread.invoke(this::applyConfig);
		emitterDefinitionManager.startup(onReload);
		particleDefinitions.startup(onReload);
		applyConfig();
		log.info("[Particles] Loaded: Textures(size={}, time={}ms), Definitions(size={}, time={}ms), Emitters(placements={}, objects={}, time={}ms)",
			particleTextureLoader.getLastTextureCount(), particleTextureLoader.getLastLoadTimeMs(),
			particleDefinitions.getLastDefinitionCount(), particleDefinitions.getLastLoadTimeMs(),
			emitterDefinitionManager.getLastPlacements(), emitterDefinitionManager.getLastObjectBindings(), emitterDefinitionManager.getLastLoadTimeMs());
	}

	private void applyConfig() {
		emitterDefinitionManager.loadConfig();
		particleDefinitions.loadConfig();
		// Do not preload particle textures at all; they are lazily loaded on first use in ParticleTextureLoader.getTextureId.
		// Preloading (even only emitter-used textures) breaks shadows on some setups.
		loadSceneParticles(plugin.getSceneContext());
	}

	public void recreateEmittersFromPlacements(@Nullable SceneContext ctx) {
		final List<ParticleEmitter> definitionEmitters = emitterDefinitionManager.getDefinitionEmitters();
		if (!definitionEmitters.isEmpty()) {
			particleSystem.removeEmitters(definitionEmitters);
			definitionEmitters.clear();
		}

		particleTextureLoader.setActiveTextureName(particleDefinitions.getDefaultTexturePath());

		final Map<String, ParticleDefinition> definitions = particleDefinitions.getDefinitions();
		if (definitions.isEmpty()) {
			log.debug("[Particles] No particle definitions loaded, skipping emitter recreation.");
			return;
		}

		final List<EmitterPlacement> placements = emitterDefinitionManager.getPlacements();
		if (placements.isEmpty()) {
			return;
		}

		for (EmitterPlacement place : placements) {
			if (place.getParticleId() == null) {
				continue;
			}

			final String pid = place.getParticleId().toUpperCase();
			final ParticleDefinition def = definitions.get(pid);
			if (def == null) {
				continue;
			}

			final WorldPoint wp = new WorldPoint(place.getWorldX(), place.getWorldY(), place.getPlane());
			final ParticleEmitter emitter = createEmitterFromDefinition(def, wp);
			emitter.particleId(def.id);

			particleSystem.addEmitter(emitter);
			definitionEmitters.add(emitter);
		}
	}

	private ParticleEmitter createEmitterFromDefinition(ParticleDefinition def, WorldPoint wp) {
		def.postDecode();
		float syMin = def.spread.yawMin * UNITS_TO_RAD;
		float syMax = def.spread.yawMax * UNITS_TO_RAD;
		float spMin = def.spread.pitchMin * UNITS_TO_RAD;
		float spMax = def.spread.pitchMax * UNITS_TO_RAD;
		float sizeMin = def.scale.minScale;
		float sizeMax = def.scale.maxScale;
		float scaleTrans = def.scale.scaleTransitionPercent > 0 ? def.scale.scaleTransitionPercent / 100f * 2f : 1f;
		float[] colorMin = argbToFloat(def.colours.minColourArgb);
		float[] colorMax = argbToFloat(def.colours.maxColourArgb);
		float[] targetColor = def.colours.targetColourArgb != 0 ? argbToFloat(def.colours.targetColourArgb) : null;
		float lifeMin = def.emission.minDelay / 64f;
		float lifeMax = Math.max(lifeMin, def.emission.maxDelay / 64f);
		ParticleEmitter e = new ParticleEmitter()
			.at(wp)
			.heightOffset(def.general.heightOffset)
			.direction((float) def.general.directionYaw * UNITS_TO_RAD, (float) def.general.directionPitch * UNITS_TO_RAD)
			.spreadYaw(syMin, syMax)
			.spreadPitch(spMin, spMax)
			.speed(def.speed.minSpeed, def.speed.maxSpeed)
			.particleLifetime(lifeMin, lifeMax)
			.size(sizeMin, sizeMax)
			.color(colorMin[0], colorMin[1], colorMin[2], colorMin[3])
			.colorRange(colorMin, colorMax)
			.uniformColorVariation(def.colours.uniformColourVariation)
			.emissionBurst(def.emission.minSpawn, def.emission.maxSpawn, def.emission.initialSpawn);
		if (def.speed.targetSpeed >= 0)
			e.targetSpeed(def.speed.targetSpeed, def.speed.speedTransitionPercent / 100f * 2f);
		if (def.scale.targetScale >= 0)
			e.targetScale(def.scale.targetScale, scaleTrans);
		if (targetColor != null)
			e.targetColor(targetColor, def.colours.colourTransitionPercent, def.colours.alphaTransitionPercent);
		e.setDefinition(def);
		e.setEmissionTime(client.getGameCycle(), def.emission.emissionCycleDuration, def.emission.emissionTimeThreshold, def.emission.emitOnlyBeforeTime, def.emission.loopEmission);
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
		return particleDefinitions.getDefinitions();
	}

	@Nullable
	public ParticleDefinition getDefinition(String id) {
		return particleDefinitions.getDefinition(id);
	}

	public List<String> getDefinitionIdsOrdered() {
		return particleDefinitions.getDefinitionIdsOrdered();
	}

	/**
	 * Apply the current definition for the given particle id to all emitters that use that id,
	 * including both tile emitters (from emitters.json) and object emitters (bound to game objects).
	 * Use after editing the definition in the panel so emitters update in-game.
	 */
	public void applyDefinitionToEmittersWithId(String particleId) {
		if (particleId == null || particleId.isEmpty()) return;
		ParticleDefinition def = particleDefinitions.getDefinition(particleId);
		if (def == null) return;
		String pid = particleId.toUpperCase();
		for (ParticleEmitter emitter : particleSystem.getEmitters()) {
			String eid = emitter.getParticleId();
			if (eid != null && eid.equalsIgnoreCase(pid))
				applyDefinitionToEmitter(emitter, def);
		}
	}

	public void applyDefinitionToEmitter(ParticleEmitter emitter, ParticleDefinition def) {
		if (emitter == null || def == null) return;
		def.postDecode();
		// spread 0–2048 angle units (float), speed/scale from JSON as-is
		float syMin = def.spread.yawMin * UNITS_TO_RAD;
		float syMax = def.spread.yawMax * UNITS_TO_RAD;
		float spMin = def.spread.pitchMin * UNITS_TO_RAD;
		float spMax = def.spread.pitchMax * UNITS_TO_RAD;
		float sizeMin = def.scale.minScale;
		float sizeMax = def.scale.maxScale;
		float scaleTrans = def.scale.scaleTransitionPercent > 0 ? def.scale.scaleTransitionPercent / 100f * 2f : 1f;
		float[] colorMin = argbToFloat(def.colours.minColourArgb);
		float[] colorMax = argbToFloat(def.colours.maxColourArgb);
		float[] targetColor = def.colours.targetColourArgb != 0 ? argbToFloat(def.colours.targetColourArgb) : null;
		float lifeMin = def.emission.minDelay / 64f;
		float lifeMax = Math.max(lifeMin, def.emission.maxDelay / 64f);
		WorldPoint wp = emitter.getWorldPoint();
		if (wp != null)
			emitter.at(wp);
		emitter.heightOffset(def.general.heightOffset);
		emitter.direction((float) def.general.directionYaw * UNITS_TO_RAD, (float) def.general.directionPitch * UNITS_TO_RAD);
		emitter.spreadYaw(syMin, syMax);
		emitter.spreadPitch(spMin, spMax);
		emitter.speed(def.speed.minSpeed, def.speed.maxSpeed);
		emitter.particleLifetime(lifeMin, lifeMax);
		emitter.size(sizeMin, sizeMax);
		emitter.color(colorMin[0], colorMin[1], colorMin[2], colorMin[3]);
		emitter.colorRange(colorMin, colorMax);
		emitter.uniformColorVariation(def.colours.uniformColourVariation);
		emitter.emissionBurst(def.emission.minSpawn, def.emission.maxSpawn, def.emission.initialSpawn);
		if (def.speed.targetSpeed >= 0)
			emitter.targetSpeed(def.speed.targetSpeed, def.speed.speedTransitionPercent / 100f * 2f);
		else
			emitter.targetSpeed(ParticleDefinition.NO_TARGET, 1f);
		if (def.scale.targetScale >= 0)
			emitter.targetScale(def.scale.targetScale, scaleTrans);
		if (targetColor != null)
			emitter.targetColor(targetColor, def.colours.colourTransitionPercent, def.colours.alphaTransitionPercent);
		emitter.particleId(def.id);
		emitter.setDefinition(def);
		emitter.setEmissionTime(client.getGameCycle(), def.emission.emissionCycleDuration, def.emission.emissionTimeThreshold, def.emission.emitOnlyBeforeTime, def.emission.loopEmission);
		String tex = def.texture.file;
		if (tex != null && !tex.isEmpty())
			particleTextureLoader.setActiveTextureName(tex);
	}

	public List<EmitterPlacement> getPlacements() {
		return emitterDefinitionManager.getPlacements();
	}

	@Nullable
	public String getActiveTextureName() {
		return particleTextureLoader.getActiveTextureName();
	}

	public List<String> getAvailableTextureNames() {
		return particleDefinitions.getAvailableTextureNames();
	}

	public ParticleEmitter placeEmitter(WorldPoint worldPoint) {
		ParticleEmitter e = new ParticleEmitter().at(worldPoint);
		addEmitter(e);
		log.info("[Particles] Placed emitter at world ({}, {}, {}), total emitters={}", worldPoint.getX(), worldPoint.getY(), worldPoint.getPlane(), particleSystem.getEmitters().size());
		return e;
	}

	@Nullable
	public ParticleEmitter spawnEmitterFromDefinition(String definitionId, WorldPoint wp) {
		ParticleDefinition def = particleDefinitions.getDefinition(definitionId);
		if (def == null) return null;
		ParticleEmitter e = createEmitterFromDefinition(def, wp);
		e.particleId(def.id);
		addEmitter(e);
		return e;
	}

	public int spawnPerformanceTestEmitters() {
		despawnPerformanceTestEmitters();
		String[] ids = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" };
		int[][] offsets = {
			{ 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
			{ 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 }, { 2, 0 }
		};
		Player player = client.getLocalPlayer();
		if (player == null) return 0;
		WorldPoint base = player.getWorldLocation();
		for (int i = 0; i < ids.length; i++) {
			ParticleEmitter e = spawnEmitterFromDefinition(ids[i], base.dx(offsets[i][0]).dy(offsets[i][1]));
			if (e != null) performanceTestEmitters.add(e);
		}
		log.info("[Particles] Performance test: spawned {} emitters around player", performanceTestEmitters.size());
		return performanceTestEmitters.size();
	}

	public void despawnPerformanceTestEmitters() {
		for (ParticleEmitter e : performanceTestEmitters)
			particleSystem.removeEmitter(e);
		performanceTestEmitters.clear();
	}

	public boolean hasPerformanceTestEmitters() {
		return !performanceTestEmitters.isEmpty();
	}

	/**
	 * Spawns up to count moving particles at random positions around the player.
	 * Uses definition "0" for particle appearance. Returns number spawned.
	 */
	public int spawnRandomParticles(int count) {
		return spawnRandomParticlesInternal(count, true);
	}

	private int spawnRandomParticlesInternal(int count, boolean logResult) {
		SceneContext ctx = plugin.getSceneContext();
		Player player = client.getLocalPlayer();
		if (ctx == null || ctx.sceneBase == null || player == null)
			return 0;
		WorldPoint wp = player.getWorldLocation();
		int[] local = ctx.worldToLocalFirst(wp);
		if (local == null) return 0;
		ParticleDefinition def = particleDefinitions.getDefinition("0");
		if (def == null) def = particleDefinitions.getDefinitions().values().stream().findFirst().orElse(null);
		if (def == null) return 0;
		int plane = local[2];
		float halfTile = LOCAL_TILE_SIZE / 2f;
		float baseX = local[0] + halfTile;
		float baseZ = local[1] + halfTile;
		float baseY = getTerrainHeight(ctx, (int) baseX, (int) baseZ, plane) + 64;
		ParticleEmitter emitter = createEmitterFromDefinition(def, wp);
		emitter.particleId(def.id);
		emitter.setDefinition(def);
		float radius = 6f * LOCAL_TILE_SIZE;
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		ParticleBuffer buf = particleSystem.getRenderBuffer();
		int spawned = 0;
		int target = Math.min(count, MAX_PARTICLES);
		for (int i = 0; i < target; i++) {
			if (buf.count >= MAX_PARTICLES) break;
			Particle p = obtainParticle();
			if (p == null) break;
			float ox = baseX + (rng.nextFloat() * 2f - 1f) * radius;
			float oy = baseY + (rng.nextFloat() * 2f - 1f) * radius * 0.5f;
			float oz = baseZ + (rng.nextFloat() * 2f - 1f) * radius;
			if (!emitter.spawnInto(p, ox, oy, oz, plane)) {
				releaseParticle(p);
				continue;
			}
			addSpawnedParticleToBuffer(p, ox, oy, oz, emitter);
			spawned++;
		}
		if (logResult && spawned > 0)
			log.info("[Particles] Spawned {} random particles around player", spawned);
		return spawned;
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
		List<EmitterConfigEntry.ObjectBinding> bindings = emitterDefinitionManager.getObjectBindingsByType().get(objectId);
		if (bindings == null || bindings.isEmpty()) return;
		WorldPoint wp = tileObject.getWorldLocation();
		List<ParticleEmitter> created = new ArrayList<>();
		for (EmitterConfigEntry.ObjectBinding binding : bindings) {
			ParticleDefinition def = particleDefinitions.getDefinition(binding.particleId);
			if (def == null) continue;
			ParticleEmitter e = createEmitterFromDefinition(def, wp);
			e.particleId(def.id);
			e.positionOffset(binding.offsetX, binding.offsetY, binding.offsetZ);
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
			particleSystem.addEmitter(e);
			created.add(e);
			String tex = def.texture.file;
			if (tex != null && !tex.isEmpty())
				particleTextureLoader.setActiveTextureName(tex);
		}
		if (!created.isEmpty())
			particleSystem.getEmittersByTileObject().put(tileObject, created);
	}

	private void handleObjectDespawn(TileObject tileObject) {
		List<ParticleEmitter> list = particleSystem.getEmittersByTileObject().remove(tileObject);
		if (list != null)
			particleSystem.removeEmitters(list);
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
		particleSystem.getEmittersCulledThisFrame().clear();
		if (ctx != null && ctx.sceneBase != null) {
			particleSystem.getObjectPositionCache().clear();
			final long gameCycle = client.getGameCycle();
			final int[][][] tileHeights = ctx.scene.getTileHeights();
			ParticleBuffer buf = particleSystem.getRenderBuffer();

			int numEmitters = particleSystem.getEmitters().size();
			if (emitterIterationArray.length < numEmitters)
				emitterIterationArray = new ParticleEmitter[numEmitters];
			particleSystem.getEmitters().toArray(emitterIterationArray);
			for (int e = 0; e < numEmitters; e++) {
				ParticleEmitter emitter = emitterIterationArray[e];
				ParticleDefinition def = emitter.getDefinition();

				if (!getEmitterSpawnPosition(ctx, emitter, updatePosOut, planeOutScratch, tileHeights, spawnOrigin, spawnPosScratch, spawnOffsetScratch, localScratch, particleSystem.getObjectPositionCache()))
					continue;
				int plane = planeOutScratch[0];

				if (buf.count >= MAX_PARTICLES) continue;
				if (def != null)
					emitter.setDirectionYaw((float) def.general.directionYaw * UNITS_TO_RAD);
				emitter.tick(dt, gameCycle, updatePosOut[0], updatePosOut[1], updatePosOut[2], plane, this);
			}
			lastEmittersCulled = 0;
			lastEmittersUpdating = numEmitters;

			if (continuousRandomSpawn && buf.count < MAX_PARTICLES) {
				int toSpawn = Math.min(150, MAX_PARTICLES - buf.count);
				if (toSpawn > 0)
					spawnRandomParticlesInternal(toSpawn, false);
			}
		} else {
			lastEmittersUpdating = 0;
			lastEmittersCulled = particleSystem.getEmitters().size();
		}

		ParticleBuffer buf = particleSystem.getRenderBuffer();
		int n = buf.count;
		int tickDelta = Math.max(1, (int) Math.round(dt * 50));
		tickDelta = Math.min(tickDelta, 10);
		for (int i = 0; i < n; ) {
			if (MovingParticle.tick(buf, i, tickDelta)) {
				buf.swap(i, n - 1);
				n--;
			} else {
				i++;
			}
		}
		buf.count = n;

		for (int i = 0; i < buf.count; i++)
			buf.syncRefToFloat(i);
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

	/**
	 * Returns emitters whose spawn position is on the given tile. Used by particle gizmo right-click menu.
	 */
	public List<ParticleEmitter> getEmittersAtTile(@Nullable SceneContext ctx, @Nullable Tile tile) {
		if (tile == null || particleSystem.getEmitters().isEmpty())
			return List.of();
		LocalPoint lp = tile.getLocalLocation();
		int tileX = lp.getX() / LOCAL_TILE_SIZE;
		int tileY = lp.getY() / LOCAL_TILE_SIZE;
		int plane = tile.getPlane();
		List<ParticleEmitter> result = new ArrayList<>();
		for (ParticleEmitter em : particleSystem.getEmitters()) {
			TileObject obj = em.getTileObject();
			if (obj != null) {
				LocalPoint objLp = obj.getLocalLocation();
				if (objLp.getX() / LOCAL_TILE_SIZE == tileX && objLp.getY() / LOCAL_TILE_SIZE == tileY && obj.getPlane() == plane)
					result.add(em);
			} else {
				WorldPoint wp = em.getWorldPoint();
				if (wp != null && ctx != null) {
					int[] local = ctx.worldToLocalFirst(wp);
					if (local != null && local[0] / LOCAL_TILE_SIZE == tileX && local[1] / LOCAL_TILE_SIZE == tileY && local[2] == plane)
						result.add(em);
				}
			}
		}
		return result;
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
