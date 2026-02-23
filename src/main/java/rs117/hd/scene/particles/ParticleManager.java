/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import com.google.gson.Gson;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.emitter.EmitterConfigEntry;
import rs117.hd.scene.particles.emitter.EmitterPlacement;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.scene.particles.emitter.ParticleEmitterDefinition;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Perspective.LOCAL_COORD_BITS;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.utils.MathUtils.min;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class ParticleManager {

	@Inject
	Client client;

	@Inject
	EventBus eventBus;

	@Inject
	HdPlugin plugin;

	@Inject
	GamevalManager gamevalManager;

	private static final int MAX_PARTICLES = 4096;
	private static final float UNITS_TO_RAD = (float) (2 * Math.PI / 2048);

	private static final ResourcePath PARTICLES_CONFIG_PATH = path(ParticleManager.class, "particles.json");
	private static final ResourcePath EMITTERS_CONFIG_PATH = path(ParticleManager.class, "emitters.json");
	private static final ResourcePath PARTICLE_TEXTURES_PATH = Props.getFolder("rlhd.particle-texture-path", () -> path(ParticleManager.class, "..", "textures", "particles"));

	private final Map<String, ParticleEmitterDefinition> definitions = new LinkedHashMap<>();
	private final List<ParticleEmitterDefinition> definitionsOrdered = new ArrayList<>();
	@Getter
	private final List<EmitterPlacement> placements = new ArrayList<>();
	private final ListMultimap<Integer, String> objectEmittersByType = ArrayListMultimap.create();
	private final Map<TileObject, List<ParticleEmitter>> objectSpawnedEmitters = new LinkedHashMap<>();
	@Getter
	private final List<ParticleEmitter> emitters = new ArrayList<>();
	private final List<ParticleEmitter> definitionEmitters = new ArrayList<>();
	@Getter
	private final List<Particle> particles = new ArrayList<>();
	private final List<Particle> toRemove = new ArrayList<>();
	private final Particle spawnParticle = new Particle();
	private String texturePath;
	private boolean eventBusRegistered;
	private boolean reloadObjectEmitters;
	private int lastEmittersUpdating;
	private int lastEmittersCulled;

	public void addEmitter(ParticleEmitter emitter) {
		if (emitter != null && !emitters.contains(emitter))
			emitters.add(emitter);
	}

	public void removeEmitter(ParticleEmitter emitter) {
		emitters.remove(emitter);
	}

	public void clear() {
		removeAllObjectSpawnedEmitters();
		emitters.clear();
		definitionEmitters.clear();
		particles.clear();
	}

	public void startUp() {
		if (!eventBusRegistered) {
			eventBus.register(this);
			eventBusRegistered = true;
		}
	}

	public void shutDown() {
		if (eventBusRegistered) {
			eventBus.unregister(this);
			eventBusRegistered = false;
		}
		removeAllObjectSpawnedEmitters();
	}

	private void removeAllObjectSpawnedEmitters() {
		for (List<ParticleEmitter> list : objectSpawnedEmitters.values()) {
			for (ParticleEmitter e : list)
				emitters.remove(e);
		}
		objectSpawnedEmitters.clear();
	}

	public void loadConfig(Gson gson) {
		loadConfig(gson, PARTICLES_CONFIG_PATH, EMITTERS_CONFIG_PATH);
	}

	public void loadConfig(Gson gson, ResourcePath particlesPath, ResourcePath emittersPath) {
		ParticleEmitterDefinition[] defs;
		try {
			defs = particlesPath.loadJson(gson, ParticleEmitterDefinition[].class);
		} catch (IOException ex) {
			log.error("[Particles] Failed to load particles.json", ex);
			return;
		}
		definitions.clear();
		definitionsOrdered.clear();
		if (defs != null) {
			for (ParticleEmitterDefinition def : defs) {
				if (def.id != null && !def.id.isEmpty())
					def.id = def.id.toUpperCase();
				def.parseHexColours();
				def.postDecode();
				if (def.id == null || def.id.isEmpty()) {
					log.warn("[Particles] Skipping definition with missing id");
					continue;
				}
				if (definitions.put(def.id, def) != null)
					log.warn("[Particles] Duplicate particle id: {}", def.id);
				definitionsOrdered.add(def);
				if (texturePath == null && def.texture != null && !def.texture.isEmpty())
					texturePath = def.texture;
			}
		}

		try {
			EmitterConfigEntry[] entries = emittersPath.loadJson(gson, EmitterConfigEntry[].class);
			placements.clear();
			objectEmittersByType.clear();
			if (entries != null) {
				for (EmitterConfigEntry entry : entries) {
					if (entry.particleId == null || entry.particleId.isEmpty()) continue;
					String pid = entry.particleId.toUpperCase();
					if (entry.placements != null) {
						for (int[] p : entry.placements) {
							if (p != null && p.length >= 3) {
								EmitterPlacement pl = new EmitterPlacement();
								pl.worldX = p[0];
								pl.worldY = p[1];
								pl.plane = p[2];
								pl.particleId = pid;
								placements.add(pl);
							}
						}
					}
					if (entry.objectEmitters != null && gamevalManager.getObjects() != null) {
						for (String name : entry.objectEmitters) {
							if (name == null || name.isEmpty()) continue;
							Integer id = gamevalManager.getObjects().get(name);
							if (id != null)
								objectEmittersByType.put(id, pid);
							else
								log.warn("[Particles] Unknown object gameval in emitters.json: {}", name);
						}
					}
				}
			}
		} catch (IOException ex) {
			log.error("[Particles] Failed to load emitters.json", ex);
			placements.clear();
			objectEmittersByType.clear();
		}

		removeAllObjectSpawnedEmitters();
		recreateEmittersFromPlacements();
		reloadObjectEmitters = true;
		int objectBindingCount = objectEmittersByType.size();
		log.info("[Particles] Loaded {} definitions, {} world placements, {} object id bindings, {} emitters", definitions.size(), placements.size(), objectBindingCount, definitionEmitters.size());
	}

	public void recreateEmittersFromPlacements() {
		emitters.removeAll(definitionEmitters);
		definitionEmitters.clear();
		texturePath = null;
		for (ParticleEmitterDefinition def : definitions.values()) {
			if (def.texture != null && !def.texture.isEmpty())
				texturePath = def.texture;
			if (texturePath != null) break;
		}
		for (EmitterPlacement place : placements) {
			String pid = place.particleId != null ? place.particleId.toUpperCase() : null;
			ParticleEmitterDefinition def = definitions.get(pid);
			if (def == null) {
				log.warn("[Particles] Unknown particleId in emitters.json: {}", place.particleId);
				continue;
			}
			WorldPoint wp = new WorldPoint(place.worldX, place.worldY, place.plane);
			ParticleEmitter e = createEmitterFromDefinition(def, wp);
			e.particleId(def.id);
			emitters.add(e);
			definitionEmitters.add(e);
		}
	}

	private ParticleEmitter createEmitterFromDefinition(ParticleEmitterDefinition def, WorldPoint wp) {
		def.postDecode();
		if (def.fallbackEmitterType >= 0 && def.fallbackEmitterType < definitionsOrdered.size()) {
			def = definitionsOrdered.get(def.fallbackEmitterType);
		}
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

	public int getLastEmittersUpdating() { return lastEmittersUpdating; }
	public int getLastEmittersCulled() { return lastEmittersCulled; }

	public Map<String, ParticleEmitterDefinition> getDefinitions() {
		return definitions;
	}

	@Nullable
	public ParticleEmitterDefinition getDefinition(String id) {
		return definitions.get(id);
	}

	public List<String> getDefinitionIdsOrdered() {
		List<String> ids = new ArrayList<>();
		for (ParticleEmitterDefinition def : definitionsOrdered)
			if (def.id != null && !def.id.isEmpty())
				ids.add(def.id);
		return ids;
	}

	public void applyDefinitionToEmitter(ParticleEmitter emitter, ParticleEmitterDefinition def) {
		if (emitter == null || def == null) return;
		WorldPoint wp = emitter.getWorldPoint();
		if (wp == null) return;
		def.postDecode();
		if (def.fallbackEmitterType >= 0 && def.fallbackEmitterType < definitionsOrdered.size())
			def = definitionsOrdered.get(def.fallbackEmitterType);
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
			texturePath = def.texture;
	}

	@Nullable
	public String getTexturePath() {
		return texturePath;
	}

	@Nullable
	public ResourcePath getTextureResourcePath() {
		if (texturePath == null || texturePath.isEmpty()) return null;
		return PARTICLE_TEXTURES_PATH.resolve(texturePath);
	}

	public static ResourcePath getParticleTexturesPath() {
		return PARTICLE_TEXTURES_PATH;
	}

	public List<String> getAvailableTextureNames() {
		Set<String> names = new LinkedHashSet<>();
		names.add("");
		for (ParticleEmitterDefinition def : definitionsOrdered)
			if (def.texture != null && !def.texture.isEmpty())
				names.add(def.texture);
		return new ArrayList<>(names);
	}

	public static ResourcePath getParticlesConfigPath() {
		return PARTICLES_CONFIG_PATH;
	}

	public static ResourcePath getEmittersConfigPath() {
		return EMITTERS_CONFIG_PATH;
	}

	public ParticleEmitter placeEmitter(WorldPoint worldPoint) {
		ParticleEmitter e = new ParticleEmitter().at(worldPoint);
		addEmitter(e);
		log.info("[Particles] Placed emitter at world ({}, {}, {}), total emitters={}", worldPoint.getX(), worldPoint.getY(), worldPoint.getPlane(), emitters.size());
		return e;
	}

	/** Spawns an emitter from a definition at the given world point and adds it to the scene. */
	@Nullable
	public ParticleEmitter spawnEmitterFromDefinition(String definitionId, WorldPoint wp) {
		ParticleEmitterDefinition def = definitions.get(definitionId);
		if (def == null) return null;
		ParticleEmitter e = createEmitterFromDefinition(def, wp);
		e.particleId(def.id);
		addEmitter(e);
		return e;
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
		if (tileObject.getPlane() < 0 || objectEmittersByType.isEmpty())
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
		List<String> particleIds = objectEmittersByType.get(objectId);
		if (particleIds == null || particleIds.isEmpty()) return;
		WorldPoint wp = tileObject.getWorldLocation();
		List<ParticleEmitter> created = new ArrayList<>();
		for (String particleId : particleIds) {
			ParticleEmitterDefinition def = definitions.get(particleId);
			if (def == null) continue;
			ParticleEmitter e = createEmitterFromDefinition(def, wp);
			e.particleId(def.id);
			emitters.add(e);
			created.add(e);
			if (def.texture != null && !def.texture.isEmpty())
				texturePath = def.texture;
		}
		if (!created.isEmpty())
			objectSpawnedEmitters.put(tileObject, created);
	}

	private void handleObjectDespawn(TileObject tileObject) {
		List<ParticleEmitter> list = objectSpawnedEmitters.remove(tileObject);
		if (list != null) {
			for (ParticleEmitter e : list)
				emitters.remove(e);
		}
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
		Set<ParticleEmitter> culledEmitters = new HashSet<>();
		if (ctx != null && ctx.sceneBase != null) {
			float cx = plugin.cameraPosition[0];
			float cy = plugin.cameraPosition[1];
			float cz = plugin.cameraPosition[2];
			float maxDistSq = (float) (plugin.getDrawDistance() * LOCAL_TILE_SIZE);
			maxDistSq *= maxDistSq;
			float[][] frustum = plugin.cameraFrustum;
			float cullRadius = LOCAL_TILE_SIZE / 2f;
			for (ParticleEmitter emitter : emitters) {
				boolean skipCulling = emitter.getDefinition() != null && emitter.getDefinition().displayWhenCulled;
				WorldPoint wp = emitter.getWorldPoint();
				if (wp == null) {
					if (!skipCulling) culledEmitters.add(emitter);
					continue;
				}
				var optLocal = ctx.worldToLocals(wp).findFirst();
				if (optLocal.isEmpty()) {
					if (!skipCulling) culledEmitters.add(emitter);
					continue;
				}
				int[] local = optLocal.get();
				int localX = local[0];
				int localY = local[1];
				int plane = local[2];
				int tileExX = localX / LOCAL_TILE_SIZE + ctx.sceneOffset;
				int tileExY = localY / LOCAL_TILE_SIZE + ctx.sceneOffset;
				float heightOff = emitter.getHeightOffset();
				float spawnY = 0f;
				if (tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE) {
					int[][] planeHeights = ctx.scene.getTileHeights()[plane];
					spawnY = planeHeights[tileExX][tileExY] - heightOff;
				}
				float ex = localX + (LOCAL_TILE_SIZE / 2f);
				float ez = localY + (LOCAL_TILE_SIZE / 2f);
				float dx = ex - cx;
				float dy = spawnY - cy;
				float dz = ez - cz;
				if (dx * dx + dy * dy + dz * dz > maxDistSq) {
					if (!skipCulling) culledEmitters.add(emitter);
					continue;
				}
				if (!HDUtils.isSphereIntersectingFrustum(ex, spawnY, ez, cullRadius, frustum, frustum.length)) {
					if (!skipCulling) culledEmitters.add(emitter);
				}
			}
			lastEmittersCulled = culledEmitters.size();
			lastEmittersUpdating = emitters.size() - lastEmittersCulled;

			if (reloadObjectEmitters && !objectEmittersByType.isEmpty()) {
				reloadObjectEmitters = false;
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
			}
			for (ParticleEmitter emitter : emitters) {
				if (culledEmitters.contains(emitter)) continue;
				WorldPoint wp = emitter.getWorldPoint();
				if (wp == null) continue;
				var optLocal = ctx.worldToLocals(wp).findFirst();
				if (optLocal.isEmpty())
					continue;
				int[] local = optLocal.get();
				int localX = local[0];
				int localY = local[1];
				int plane = local[2];
				int tileExX = localX / LOCAL_TILE_SIZE + ctx.sceneOffset;
				int tileExY = localY / LOCAL_TILE_SIZE + ctx.sceneOffset;
				float heightOff = emitter.getHeightOffset();
				float spawnY = 0f;
				if (tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE) {
					int[][] planeHeights = ctx.scene.getTileHeights()[plane];
					spawnY = planeHeights[tileExX][tileExY] - heightOff;
				}
				ParticleEmitterDefinition def = emitter.getDefinition();
				if (def != null && def.hasLevelBounds) {
					// -2 = disabled, -1 = current level (emitter plane), 0-3 = specific plane
					int lower = def.lowerBoundLevel == -2 ? 0 : (def.lowerBoundLevel == -1 ? plane : def.lowerBoundLevel);
					int upper = def.upperBoundLevel == -2 ? 3 : (def.upperBoundLevel == -1 ? plane : def.upperBoundLevel);
					if (plane < lower || plane > upper) continue;
				}
				// RS: only spawn when inside emission time window (uses client game cycle)
				if (!emitter.isEmissionAllowedAtCycle(client.getGameCycle())) continue;
				float ox = localX + (LOCAL_TILE_SIZE / 2f);
				float oz = localY + (LOCAL_TILE_SIZE / 2f);
				int toSpawn = emitter.advanceEmission(dt);
				for (int i = 0; i < toSpawn && particles.size() < MAX_PARTICLES; i++) {
					if (emitter.spawn(spawnParticle, ox, spawnY, oz, plane)) {
						Particle p = new Particle();
						p.emitter = emitter;
						p.setPosition(spawnParticle.position[0], spawnParticle.position[1], spawnParticle.position[2]);
						p.setVelocity(spawnParticle.velocity[0], spawnParticle.velocity[1], spawnParticle.velocity[2]);
						p.life = spawnParticle.life;
						p.maxLife = spawnParticle.maxLife;
						p.size = spawnParticle.size;
						p.plane = spawnParticle.plane;
						p.setColor(spawnParticle.color[0], spawnParticle.color[1], spawnParticle.color[2], spawnParticle.color[3]);
						System.arraycopy(spawnParticle.initialColor, 0, p.initialColor, 0, 4);
						p.targetColor = spawnParticle.targetColor;
						p.colorTransitionPct = spawnParticle.colorTransitionPct;
						p.alphaTransitionPct = spawnParticle.alphaTransitionPct;
						p.targetScale = spawnParticle.targetScale;
						p.scaleTransition = spawnParticle.scaleTransition;
						p.targetSpeed = spawnParticle.targetSpeed;
						p.speedTransition = spawnParticle.speedTransition;
						if (def != null && def.targetColourArgb != 0) {
							float ticksToSec = 64f;
							float u8 = 256f * 256f;
							p.colourIncrementPerSecond = new float[4];
							p.colourIncrementPerSecond[0] = def.redIncrementPerTick * ticksToSec / u8;
							p.colourIncrementPerSecond[1] = def.greenIncrementPerTick * ticksToSec / u8;
							p.colourIncrementPerSecond[2] = def.blueIncrementPerTick * ticksToSec / u8;
							p.colourIncrementPerSecond[3] = def.alphaIncrementPerTick * ticksToSec / u8;
							p.colourTransitionEndLife = p.maxLife - def.colourTransitionTicks / ticksToSec;
						}
						if (def != null && def.targetScaleDecoded >= 0) {
							p.scaleIncrementPerSecond = def.scaleIncrementPerTick * 64f / 16384f * 4f;
							p.scaleTransitionEndLife = p.maxLife - def.scaleTransitionTicks / 64f;
						}
						if (def != null && def.targetSpeed >= 0) {
							p.speedIncrementPerSecond = def.speedIncrementPerTick * 64f / 16384f;
							p.speedTransitionEndLife = p.maxLife - def.speedTransitionTicks / 64f;
						}
						p.emitterOriginX = ox;
						p.emitterOriginY = spawnY;
						p.emitterOriginZ = oz;
						if (def != null) {
							p.distanceFalloffType = def.distanceFalloffType;
							p.distanceFalloffStrength = def.distanceFalloffStrength;
							p.clipToTerrain = def.clipToTerrain;
							p.hasLevelBounds = def.hasLevelBounds;
							p.upperBoundLevel = def.upperBoundLevel;
							p.lowerBoundLevel = def.lowerBoundLevel;
						}
						particles.add(p);
					}
				}

			}
		} else {
			lastEmittersUpdating = 0;
			lastEmittersCulled = emitters.size();
		}

		toRemove.clear();
		for (Particle p : particles) {
			if (p.emitter != null && culledEmitters.contains(p.emitter)) continue;
			p.life -= dt;
			if (p.life <= 0) {
				toRemove.add(p);
				continue;
			}
			if (p.colourIncrementPerSecond != null && p.life >= p.colourTransitionEndLife) {
				for (int i = 0; i < 4; i++) {
					p.color[i] += p.colourIncrementPerSecond[i] * dt;
					p.color[i] = Math.max(0f, Math.min(1f, p.color[i]));
				}
			}
			if (p.scaleIncrementPerSecond != 0f && p.life >= p.scaleTransitionEndLife) {
				p.size += p.scaleIncrementPerSecond * dt;
			}
			if (p.speedIncrementPerSecond != 0f && p.life >= p.speedTransitionEndLife) {
				float vx = p.velocity[0], vy = p.velocity[1], vz = p.velocity[2];
				float cur = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
				if (cur > 1e-6f) {
					float newSpeed = cur + p.speedIncrementPerSecond * dt;
					float scale = newSpeed / cur;
					p.velocity[0] = vx * scale;
					p.velocity[1] = vy * scale;
					p.velocity[2] = vz * scale;
				}
			}
			if (p.distanceFalloffType == 1) {
				float dx = p.position[0] - p.emitterOriginX;
				float dy = p.position[1] - p.emitterOriginY;
				float dz = p.position[2] - p.emitterOriginZ;
				float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / 4f;
				float curSpeed = (float) Math.sqrt(p.velocity[0] * p.velocity[0] + p.velocity[1] * p.velocity[1] + p.velocity[2] * p.velocity[2]);
				if (curSpeed > 1e-6f) {
					float falloff = p.distanceFalloffStrength * dist * dt / (1 << 18);
					float scale = Math.max(0f, 1f - falloff);
					p.velocity[0] *= scale;
					p.velocity[1] *= scale;
					p.velocity[2] *= scale;
				}
			} else if (p.distanceFalloffType == 2) {
				float dx = p.position[0] - p.emitterOriginX;
				float dy = p.position[1] - p.emitterOriginY;
				float dz = p.position[2] - p.emitterOriginZ;
				float distSq = dx * dx + dy * dy + dz * dz;
				float curSpeed = (float) Math.sqrt(p.velocity[0] * p.velocity[0] + p.velocity[1] * p.velocity[1] + p.velocity[2] * p.velocity[2]);
				if (curSpeed > 1e-6f) {
					float falloff = p.distanceFalloffStrength * distSq * dt / (1 << 28);
					float scale = Math.max(0f, 1f - falloff);
					p.velocity[0] *= scale;
					p.velocity[1] *= scale;
					p.velocity[2] *= scale;
				}
			}
			p.position[0] += p.velocity[0] * dt;
			p.position[1] += p.velocity[1] * dt;
			p.position[2] += p.velocity[2] * dt;
			if (p.hasLevelBounds && ctx != null) {
				float ceiling = p.upperBoundLevel == -2 ? Float.MAX_VALUE
					: getTerrainHeight(ctx, (int) p.position[0], (int) p.position[2], p.upperBoundLevel == -1 ? p.plane : p.upperBoundLevel);
				float floor = p.lowerBoundLevel == -2 ? -Float.MAX_VALUE
					: (p.lowerBoundLevel == -1
						? (p.plane < 3 ? getTerrainHeight(ctx, (int) p.position[0], (int) p.position[2], p.plane + 1) : getTerrainHeight(ctx, (int) p.position[0], (int) p.position[2], p.plane) - 2048f)
						: getTerrainHeight(ctx, (int) p.position[0], (int) p.position[2], Math.min(3, p.lowerBoundLevel + 1)));
				if (p.position[1] > ceiling || p.position[1] < floor) toRemove.add(p);
			}
		}
		particles.removeAll(toRemove);
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
