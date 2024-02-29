/*
 * Copyright (c) 2019 Abex
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.scene;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.entityhider.EntityHiderConfig;
import net.runelite.client.plugins.entityhider.EntityHiderPlugin;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.MaxDynamicLights;
import rs117.hd.scene.lights.Alignment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.scene.lights.LightType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static java.lang.Math.abs;
import static java.lang.Math.cos;
import static java.lang.Math.pow;
import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.HDUtils.TWO_PI;
import static rs117.hd.utils.HDUtils.fract;
import static rs117.hd.utils.HDUtils.mod;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class LightManager {
	private static final ResourcePath LIGHTS_PATH = Props.getPathOrDefault(
		"rlhd.lights-path",
		() -> path(LightManager.class, "lights.json")
	);

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private EntityHiderPlugin entityHiderPlugin;

	public final ArrayList<Light> WORLD_LIGHTS = new ArrayList<>();
	public final ListMultimap<Integer, LightDefinition> NPC_LIGHTS = ArrayListMultimap.create();
	public final ListMultimap<Integer, LightDefinition> OBJECT_LIGHTS = ArrayListMultimap.create();
	public final ListMultimap<Integer, LightDefinition> PROJECTILE_LIGHTS = ArrayListMultimap.create();
	public final ListMultimap<Integer, LightDefinition> GRAPHICS_OBJECT_LIGHTS = ArrayListMultimap.create();

	boolean configChanged = false;

	private EntityHiderConfig entityHiderConfig;

	public void loadConfig(Gson gson, ResourcePath path, boolean firstRun) {
		try {
			LightDefinition[] lights;
			try {
				lights = path.loadJson(gson, LightDefinition[].class);
				if (lights == null) {
					log.warn("Skipping empty lights.json");
					return;
				}
			} catch (IOException ex) {
				log.error("Failed to load lights", ex);
				return;
			}

			WORLD_LIGHTS.clear();
			NPC_LIGHTS.clear();
			OBJECT_LIGHTS.clear();
			PROJECTILE_LIGHTS.clear();
			GRAPHICS_OBJECT_LIGHTS.clear();

			for (LightDefinition lightDef : lights) {
				if (lightDef.worldX != null && lightDef.worldY != null) {
					Light light = new Light(lightDef);
					light.worldPoint = new WorldPoint(lightDef.worldX, lightDef.worldY, lightDef.plane);
					WORLD_LIGHTS.add(light);
				}
				lightDef.npcIds.forEach(id -> NPC_LIGHTS.put(id, lightDef));
				lightDef.objectIds.forEach(id -> OBJECT_LIGHTS.put(id, lightDef));
				lightDef.projectileIds.forEach(id -> PROJECTILE_LIGHTS.put(id, lightDef));
				lightDef.graphicsObjectIds.forEach(id -> GRAPHICS_OBJECT_LIGHTS.put(id, lightDef));
			}

			log.debug("Loaded {} lights", lights.length);
			configChanged = !firstRun;
		} catch (Exception ex) {
			log.error("Failed to parse light configuration", ex);
		}
	}

	public void startUp() {
		entityHiderConfig = configManager.getConfig(EntityHiderConfig.class);
		LIGHTS_PATH.watch((path, first) -> loadConfig(plugin.getGson(), path, first));
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
	}

	public void update(SceneContext sceneContext) {
		assert client.isClientThread();

		if (client.getGameState() != GameState.LOGGED_IN || config.maxDynamicLights() == MaxDynamicLights.NONE)
			return;

		if (configChanged) {
			configChanged = false;
			loadSceneLights(sceneContext, null);

			// check the NPCs in the scene to make sure they have lights assigned, if applicable,
			// for scenarios in which HD mode or dynamic lights were disabled during NPC spawn
			client.getNpcs().forEach(this::addNpcLights);
		}

		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		int[][][] tileHeights = sceneContext.scene.getTileHeights();

		for (Light light : sceneContext.lights) {
			light.distanceSquared = Integer.MAX_VALUE;

			light.elapsedTime += plugin.deltaClientTime;
			if (light.elapsedTime < light.spawnDelay) {
				light.visible = false;
				continue;
			}

			if (light.def.fixedDespawnTime && light.elapsedTime >= light.spawnDelay + light.despawnDelay)
				light.markedForRemoval = true;

			if (light.object != null) {
				light.visible = true;
				if (light.impostorObjectId != 0) {
					var def = client.getObjectDefinition(light.object.getId());
					if (def.getImpostorIds() != null) {
						// Only show the light if the impostor is currently active
						var impostor = def.getImpostor();
						light.visible = impostor != null && impostor.getId() == light.impostorObjectId;
					}
				}
			} else if (light.projectile != null) {
				if (light.projectile.getRemainingCycles() <= 0) {
					light.markedForRemoval = true;
				} else {
					light.x = (int) light.projectile.getX();
					light.y = (int) light.projectile.getY();
					light.z = (int) light.projectile.getZ() - light.def.height;
					light.visible = projectileLightVisible();
				}
			} else if (light.graphicsObject != null) {
				if (light.graphicsObject.finished()) {
					light.markedForRemoval = true;
				} else {
					light.x = light.graphicsObject.getLocation().getX();
					light.y = light.graphicsObject.getLocation().getY();
					light.z = light.graphicsObject.getZ() - light.def.height;
					light.visible = true;
				}
			} else if (light.actor != null) {
				if (light.actor instanceof NPC && light.actor != client.getCachedNPCs()[((NPC) light.actor).getIndex()] ||
					light.actor instanceof Player && light.actor != client.getCachedPlayers()[((Player) light.actor).getId()]
				) {
					light.markedForRemoval = true;
					continue;
				}

				var lp = light.actor.getLocalLocation();
				light.x = lp.getX();
				light.y = lp.getY();

				// Offset the light's position based on its Alignment
				if (light.def.alignment == Alignment.NORTH ||
					light.def.alignment == Alignment.NORTHEAST ||
					light.def.alignment == Alignment.NORTHWEST)
					light.y += LOCAL_HALF_TILE_SIZE;
				if (light.def.alignment == Alignment.SOUTH ||
					light.def.alignment == Alignment.SOUTHEAST ||
					light.def.alignment == Alignment.SOUTHWEST)
					light.y -= LOCAL_HALF_TILE_SIZE;
				if (light.def.alignment == Alignment.EAST ||
					light.def.alignment == Alignment.SOUTHEAST ||
					light.def.alignment == Alignment.NORTHEAST)
					light.x += LOCAL_HALF_TILE_SIZE;
				if (light.def.alignment == Alignment.WEST ||
					light.def.alignment == Alignment.SOUTHWEST ||
					light.def.alignment == Alignment.NORTHWEST)
					light.x -= LOCAL_HALF_TILE_SIZE;

				int plane = client.getPlane();
				light.plane = plane;

				// Some NPCs, such as Crystalline Hunllef in The Gauntlet, sometimes return scene X/Y values far outside the possible range.
				int npcTileX = lp.getSceneX() + SceneUploader.SCENE_OFFSET;
				int npcTileY = lp.getSceneY() + SceneUploader.SCENE_OFFSET;
				if (npcTileX < 0 || npcTileY < 0 || npcTileX >= EXTENDED_SCENE_SIZE || npcTileY >= EXTENDED_SCENE_SIZE) {
					light.visible = false;
				} else {
					// Tile null check is to prevent oddities caused by - once again - Crystalline Hunllef.
					// May also apply to other NPCs in instances.
					if (tiles[plane][npcTileX][npcTileY] != null && tiles[plane][npcTileX][npcTileY].getBridge() != null) {
						plane++;
					}

					// Interpolate between tile heights based on specific scene coordinates.
					float lerpX = (light.x % LOCAL_TILE_SIZE) / (float) LOCAL_TILE_SIZE;
					float lerpY = (light.y % LOCAL_TILE_SIZE) / (float) LOCAL_TILE_SIZE;
					int baseTileX = (int) Math.floor(light.x / (float) LOCAL_TILE_SIZE) + SceneUploader.SCENE_OFFSET;
					int baseTileY = (int) Math.floor(light.y / (float) LOCAL_TILE_SIZE) + SceneUploader.SCENE_OFFSET;
					float heightNorth = HDUtils.lerp(
						tileHeights[plane][baseTileX][baseTileY + 1],
						tileHeights[plane][baseTileX + 1][baseTileY + 1],
						lerpX
					);
					float heightSouth = HDUtils.lerp(
						tileHeights[plane][baseTileX][baseTileY],
						tileHeights[plane][baseTileX + 1][baseTileY],
						lerpX
					);
					float tileHeight = HDUtils.lerp(heightSouth, heightNorth, lerpY);
					light.z = (int) tileHeight - 1 - light.def.height;

					light.visible = actorLightVisible(light.actor);
				}
			}

			if (light.visible && !light.def.animationIds.isEmpty()) {
				light.visible = false;
				if (light.actor != null) {
					light.visible = light.def.animationIds.contains(light.actor.getAnimation());
				} else if (light.object instanceof GameObject) {
					var renderable = ((GameObject) light.object).getRenderable();
					if (renderable instanceof DynamicObject) {
						var animation = ((DynamicObject) renderable).getAnimation();
						light.visible = animation != null && light.def.animationIds.contains(animation.getId());
					}
				} else if (light.projectile != null) {
					var animation = light.projectile.getAnimation();
					light.visible = animation != null && light.def.animationIds.contains(animation.getId());
				} else if (light.graphicsObject != null) {
					var animation = light.graphicsObject.getAnimation();
					light.visible = animation != null && light.def.animationIds.contains(animation.getId());
				}
			}

			if (light.spotAnimId != -1 && light.actor != null && !light.actor.hasSpotAnim(light.spotAnimId))
				light.markedForRemoval = true;

			if (light.def.type == LightType.FLICKER) {
				double t = TWO_PI * (mod(plugin.elapsedTime, 60) / 60 + light.randomOffset);
				float flicker = (float) (
					pow(cos(11 * t), 3) +
					pow(cos(17 * t), 6) +
					pow(cos(23 * t), 2) +
					pow(cos(31 * t), 6) +
					pow(cos(71 * t), 4) +
					pow(cos(151 * t), 6) / 2
				) / 4.335f;

				float maxFlicker = 1f + (light.def.range / 100f);
				float minFlicker = 1f - (light.def.range / 100f);

				flicker = minFlicker + (maxFlicker - minFlicker) * flicker;

				light.strength = light.def.strength * flicker;
				light.radius = (int) (light.def.radius * 1.5f);
			} else if (light.def.type == LightType.PULSE) {
				light.animation = fract(light.animation + plugin.deltaClientTime / light.duration);

				float output = 1 - 2 * abs(light.animation - .5f);
				float range = light.def.range / 100f;
				float fullRange = range * 2f;
				float multiplier = (1.0f - range) + output * fullRange;

				light.radius = (int) (light.def.radius * multiplier);
				light.strength = light.def.strength * multiplier;
			} else {
				light.strength = light.def.strength;
				light.radius = light.def.radius;
				light.color = light.def.color;
			}

			if (light.fadeInDuration > 0)
				light.strength *= Math.min(1, (light.elapsedTime - light.spawnDelay) / light.fadeInDuration);

			// Calculate the distance between the player and the light to determine which
			// lights to display based on the 'max dynamic lights' config option
			int distX = plugin.cameraFocalPoint[0] - light.x;
			int distY = plugin.cameraFocalPoint[1] - light.y;
			light.distanceSquared = distX * distX + distY * distY + light.z * light.z;

			int tileX = (int) Math.floor(light.x / 128f) + SceneUploader.SCENE_OFFSET;
			int tileY = (int) Math.floor(light.y / 128f) + SceneUploader.SCENE_OFFSET;

			light.belowFloor = false;
			light.aboveFloor = false;

			if (tileX < EXTENDED_SCENE_SIZE && tileY < EXTENDED_SCENE_SIZE && tileX >= 0 && tileY >= 0 && light.plane >= 0) {
				Tile aboveTile = light.plane < 3 ? tiles[light.plane + 1][tileX][tileY] : null;

				if (aboveTile != null && (aboveTile.getSceneTilePaint() != null || aboveTile.getSceneTileModel() != null)) {
					light.belowFloor = true;
				}

				Tile lightTile = tiles[light.plane][tileX][tileY];

				if (lightTile != null && (lightTile.getSceneTilePaint() != null || lightTile.getSceneTileModel() != null)) {
					light.aboveFloor = true;
				}
			}
		}

		Iterator<Light> lightIterator = sceneContext.lights.iterator();
		while (lightIterator.hasNext()) {
			var light = lightIterator.next();
			if (!light.markedForRemoval)
				continue;

			// If the light's despawn time isn't fixed, calculate when it should finish despawning
			if (light.scheduledDespawnTime == -1) {
				float minFadeTime = light.def.fadeOverlap ?
					Math.max(light.fadeInDuration, light.fadeOutDuration) :
					light.fadeInDuration + light.fadeOutDuration;
				float minLifetime = light.spawnDelay + minFadeTime;
				float lifetime = Math.max(minLifetime, light.elapsedTime);
				light.scheduledDespawnTime = lifetime + Math.max(light.despawnDelay, light.fadeOutDuration);
			}

			float timeUntilDespawn = light.scheduledDespawnTime - light.elapsedTime;
			if (light.fadeOutDuration > 0)
				light.strength *= Math.min(1, timeUntilDespawn / light.fadeOutDuration);

			// Despawn the light
			if (timeUntilDespawn <= 0) {
				sceneContext.projectiles.remove(light.projectile);
				lightIterator.remove();
			}
		}

		sceneContext.lights.sort(Comparator.comparingInt(light -> light.distanceSquared));
	}

	private boolean actorLightVisible(@Nonnull Actor actor) {
		try {
			// getModel may throw an exception from vanilla client code
			if (actor.getModel() == null)
				return false;
		} catch (Exception ex) {
			// Vanilla handles exceptions thrown in `DrawCallbacks#draw` gracefully, but here we have to handle them
			return false;
		}

		boolean entityHiderEnabled = pluginManager.isPluginEnabled(entityHiderPlugin);

		if (actor instanceof NPC) {
			if (!plugin.configNpcLights)
				return false;

			if (entityHiderEnabled) {
				var npc = (NPC) actor;
				boolean isPet = npc.getComposition().isFollower();

				if (client.getFollower() != null && client.getFollower().getIndex() == npc.getIndex())
					return true;

				if (entityHiderConfig.hideNPCs() && !isPet)
					return false;

				return !entityHiderConfig.hidePets() || !isPet;
			}
		} else if (actor instanceof Player) {
			if (entityHiderEnabled) {
				var player = (Player) actor;
				Player local = client.getLocalPlayer();
				if (local == null || player.getName() == null)
					return true;

				if (player == local)
					return !entityHiderConfig.hideLocalPlayer();

				if (entityHiderConfig.hideAttackers() && player.getInteracting() == local)
					return false;

				if (player.isFriend())
					return !entityHiderConfig.hideFriends();
				if (player.isFriendsChatMember())
					return !entityHiderConfig.hideFriendsChatMembers();
				if (player.isClanMember())
					return !entityHiderConfig.hideClanChatMembers();
				if (client.getIgnoreContainer().findByName(player.getName()) != null)
					return !entityHiderConfig.hideIgnores();

				return !entityHiderConfig.hideOthers();
			}
		}

		return true;
	}

	private boolean projectileLightVisible()
	{
		if (pluginManager.isPluginEnabled(entityHiderPlugin))
		{
			if (entityHiderConfig.hideProjectiles())
			{
				return false;
			}
		}

		return plugin.configProjectileLights;
	}

	public void loadSceneLights(SceneContext sceneContext, @Nullable SceneContext oldSceneContext)
	{
		assert client.isClientThread();

		// Copy over NPC and projectile lights from the old scene
		ArrayList<Light> lightsToKeep = new ArrayList<>();
		if (oldSceneContext != null)
			for (Light light : oldSceneContext.lights)
				if (light.actor != null || light.projectile != null)
					lightsToKeep.add(light);

		sceneContext.lights.clear();
		sceneContext.lights.addAll(lightsToKeep);
		sceneContext.projectiles.clear();
		for (var l : lightsToKeep)
			if (l.projectile != null)
				sceneContext.projectiles.add(l.projectile);

		for (Light light : WORLD_LIGHTS)
		{
			assert light.worldPoint != null;
			if (sceneContext.regionIds.contains(light.worldPoint.getRegionID()))
			{
				sceneContext.lights.add(light);
				updateWorldLightPosition(sceneContext, light);
			}
		}

		for (Tile[][] plane : sceneContext.scene.getExtendedTiles()) {
			for (Tile[] column : plane) {
				for (Tile tile : column) {
					if (tile == null) {
						continue;
					}

					DecorativeObject decorativeObject = tile.getDecorativeObject();
					if (decorativeObject != null && decorativeObject.getRenderable() != null) {
						addObjectLight(sceneContext, decorativeObject, tile.getRenderLevel());
					}

					WallObject wallObject = tile.getWallObject();
					if (wallObject != null && wallObject.getRenderable1() != null) {
						int orientation = HDUtils.convertWallObjectOrientation(wallObject.getOrientationA());
						addObjectLight(sceneContext, wallObject, tile.getRenderLevel(), 1, 1, orientation);
					}

					GroundObject groundObject = tile.getGroundObject();
					if (groundObject != null && groundObject.getRenderable() != null) {
						addObjectLight(sceneContext, groundObject, tile.getRenderLevel());
					}

					for (GameObject gameObject : tile.getGameObjects()) {
						if (gameObject != null) {
							// Skip players & NPCs
							if (gameObject.getRenderable() instanceof Actor)
								continue;

							addObjectLight(
								sceneContext,
								gameObject,
								tile.getRenderLevel(),
								gameObject.sizeX(),
								gameObject.sizeY(),
								gameObject.getOrientation());
						}
					}
				}
			}
		}
	}

	public ArrayList<Light> getVisibleLights(int maxLights) {
		SceneContext sceneContext = plugin.getSceneContext();
		ArrayList<Light> visibleLights = new ArrayList<>();

		if (sceneContext == null)
			return visibleLights;

		int maxDistanceSquared = plugin.getDrawDistance() * LOCAL_TILE_SIZE;
		maxDistanceSquared *= maxDistanceSquared;

		for (Light light : sceneContext.lights) {
			if (light.distanceSquared > maxDistanceSquared)
				break;

			if (!light.visible)
				continue;

			if (!light.def.visibleFromOtherPlanes) {
				// Hide certain lights on planes lower than the player to prevent light 'leaking' through the floor
				if (light.plane < client.getPlane() && light.belowFloor)
					continue;
				// Hide any light that is above the current plane and is above a solid floor
				if (light.plane > client.getPlane() && light.aboveFloor)
					continue;
			}

			visibleLights.add(light);

			if (visibleLights.size() >= maxLights)
				break;
		}

		return visibleLights;
	}

	private void removeLightIf(Predicate<Light> predicate) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext != null) {
			sceneContext.lights.forEach(light -> {
				if (predicate.test(light))
					light.markedForRemoval = true;
			});
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved projectileMoved) {
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		Projectile projectile = projectileMoved.getProjectile();
		if (!sceneContext.projectiles.add(projectile))
			return;

		for (LightDefinition lightDef : PROJECTILE_LIGHTS.get(projectile.getId())) {
			Light light = new Light(lightDef);
			light.projectile = projectile;
			light.x = (int) projectile.getX();
			light.y = (int) projectile.getY();
			light.z = (int) projectile.getZ();
			light.plane = projectile.getFloor();
			light.visible = projectileLightVisible();

			sceneContext.lights.add(light);
		}
	}

	private void addSpotAnimLights(Actor actor) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		for (var spotAnim : actor.getSpotAnims()) {
			for (var lightDef : GRAPHICS_OBJECT_LIGHTS.get(spotAnim.getId())) {
				Light light = new Light(lightDef);
				light.plane = -1;
				light.spotAnimId = spotAnim.getId();
				light.actor = actor;
				light.visible = false;
				sceneContext.lights.add(light);
			}
		}
	}

	public void removeSpotAnimLights(Actor actor) {
		removeLightIf(light -> light.actor == actor);
	}

	private void addNpcLights(NPC npc)
	{
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		var modelOverride = modelOverrideManager.getOverride(
			ModelHash.packUuid(ModelHash.TYPE_NPC, npc.getId()),
			sceneContext.localToWorld(npc.getLocalLocation(), client.getPlane())
		);
		if (modelOverride.hide)
			return;

		for (LightDefinition lightDef : NPC_LIGHTS.get(npc.getId())) {
			// Prevent duplicate lights from being spawned for the same NPC
			if (sceneContext.lights.stream().anyMatch(x -> x.actor == npc && x.def == lightDef))
				continue;

			Light light = new Light(lightDef);
			light.plane = -1;
			light.actor = npc;
			light.visible = false;
			sceneContext.lights.add(light);
		}

		addSpotAnimLights(npc);
	}

	public void removeNpcLight(NPC npc) {
		removeLightIf(light -> light.actor == npc);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned npcSpawned) {
		addNpcLights(npcSpawned.getNpc());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged npcChanged) {
		removeNpcLight(npcChanged.getNpc());
		addNpcLights(npcChanged.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned npcDespawned)
	{
		removeNpcLight(npcDespawned.getNpc());
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned playerSpawned) {
		addSpotAnimLights(playerSpawned.getPlayer());
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged playerChanged) {
		removeSpotAnimLights(playerChanged.getPlayer());
		addSpotAnimLights(playerChanged.getPlayer());
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged graphicChanged) {
		var actor = graphicChanged.getActor();
		removeSpotAnimLights(actor);
		addSpotAnimLights(actor);
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned playerDespawned) {
		removeSpotAnimLights(playerDespawned.getPlayer());
	}

	private void addObjectLight(SceneContext sceneContext, TileObject tileObject, int plane) {
		addObjectLight(sceneContext, tileObject, plane, 1, 1, -1);
	}

	private void addObjectLight(SceneContext sceneContext, TileObject tileObject, int plane, int sizeX, int sizeY, int orientation) {
		int id = tileObject.getId();
		if (tileObject instanceof GameObject) {
			var def = client.getObjectDefinition(id);
			if (def.getImpostorIds() != null) {
				// Add a light for every possible impostor object
				for (int impostorId : def.getImpostorIds())
					addObjectLight(sceneContext, tileObject, impostorId, plane, sizeX, sizeY, orientation);
				return;
			}
		}

		addObjectLight(sceneContext, tileObject, tileObject.getId(), plane, sizeX, sizeY, orientation);
	}

	private void addObjectLight(
		SceneContext sceneContext,
		TileObject tileObject,
		int objectId,
		int plane,
		int sizeX,
		int sizeY,
		int orientation
	) {
		for (LightDefinition lightDef : OBJECT_LIGHTS.get(objectId)) {
			// prevent objects at plane -1 and below from having lights
			if (tileObject.getPlane() <= -1)
				continue;

			// prevent the same light from being spawned more than once per object
			long hash = tileObjectHash(tileObject);
			boolean isDuplicate = sceneContext.lights.stream()
				.anyMatch(light -> {
					boolean sameObject = light.object == tileObject || hash == tileObjectHash(light.object);
					boolean sameLight = light.def == lightDef;
					return sameObject && sameLight;
				});
			if (isDuplicate)
				continue;

			int localPlane = tileObject.getPlane();
			Light light = new Light(lightDef);
			light.plane = localPlane;
			if (objectId != tileObject.getId()) {
				light.impostorObjectId = objectId;
				light.visible = false;
			}

			LocalPoint localPoint = tileObject.getLocalLocation();
			int lightX = localPoint.getX();
			int lightY = localPoint.getY();
			int localSizeX = sizeX * LOCAL_TILE_SIZE;
			int localSizeY = sizeY * LOCAL_TILE_SIZE;

			if (orientation != -1 && light.def.alignment != Alignment.CENTER) {
				float radius = localSizeX / 2f;
				if (!light.def.alignment.radial)
					radius = (float) Math.sqrt(localSizeX * localSizeX + localSizeX * localSizeX) / 2;

				if (!light.def.alignment.relative)
					orientation = 0;
				orientation += light.def.alignment.orientation;
				orientation %= 2048;

				float sine = SINE[orientation] / 65536f;
				float cosine = COSINE[orientation] / 65536f;
				cosine /= (float) localSizeX / (float) localSizeY;

				int offsetX = (int) (radius * sine);
				int offsetY = (int) (radius * cosine);

				lightX += offsetX;
				lightY += offsetY;
			}

			float tileX = (float) lightX / LOCAL_TILE_SIZE + SceneUploader.SCENE_OFFSET;
			float tileY = (float) lightY / LOCAL_TILE_SIZE + SceneUploader.SCENE_OFFSET;
			float lerpX = (lightX % LOCAL_TILE_SIZE) / (float) LOCAL_TILE_SIZE;
			float lerpY = (lightY % LOCAL_TILE_SIZE) / (float) LOCAL_TILE_SIZE;
			int tileMinX = (int) Math.floor(tileX);
			int tileMinY = (int) Math.floor(tileY);
			int tileMaxX = tileMinX + 1;
			int tileMaxY = tileMinY + 1;
			tileMinX = HDUtils.clamp(tileMinX, 0, EXTENDED_SCENE_SIZE - 1);
			tileMinY = HDUtils.clamp(tileMinY, 0, EXTENDED_SCENE_SIZE - 1);
			tileMaxX = HDUtils.clamp(tileMaxX, 0, EXTENDED_SCENE_SIZE - 1);
			tileMaxY = HDUtils.clamp(tileMaxY, 0, EXTENDED_SCENE_SIZE - 1);

			int[][][] tileHeights = sceneContext.scene.getTileHeights();
			float heightNorth = HDUtils.lerp(
				tileHeights[plane][tileMinX][tileMaxY],
				tileHeights[plane][tileMaxX][tileMaxY],
				lerpX
			);
			float heightSouth = HDUtils.lerp(
				tileHeights[plane][tileMinX][tileMinY],
				tileHeights[plane][tileMaxX][tileMinY],
				lerpX
			);
			float tileHeight = HDUtils.lerp(heightSouth, heightNorth, lerpY);

			light.x = lightX;
			light.y = lightY;
			light.z = (int) tileHeight - light.def.height - 1;
			light.object = tileObject;

			sceneContext.lights.add(light);
		}
	}

	private void removeObjectLight(TileObject tileObject)
	{
		LocalPoint localLocation = tileObject.getLocalLocation();
		removeLightIf(light ->
			light.object == tileObject &&
			light.x == localLocation.getX() &&
			light.y == localLocation.getY() &&
			light.plane == tileObject.getPlane());
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated graphicsObjectCreated) {
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		GraphicsObject graphicsObject = graphicsObjectCreated.getGraphicsObject();
		for (LightDefinition lightDef : GRAPHICS_OBJECT_LIGHTS.get(graphicsObject.getId())) {
			Light light = new Light(lightDef);
			light.graphicsObject = graphicsObject;
			var lp = graphicsObject.getLocation();
			light.x = lp.getX();
			light.y = lp.getY();
			light.z = graphicsObject.getZ();
			light.plane = graphicsObject.getLevel();

			sceneContext.lights.add(light);
		}
	}

	private long tileObjectHash(@Nullable TileObject tileObject)
	{
		if (tileObject == null)
			return 0;

		LocalPoint local = tileObject.getLocalLocation();
		long hash = local.getX();
		hash = hash * 31 + local.getY();
		hash = hash * 31 + tileObject.getPlane();
		hash = hash * 31 + tileObject.getId();
		return hash;
	}

	private void updateWorldLightPosition(SceneContext sceneContext, Light light)
	{
		assert light.worldPoint != null;

		Optional<LocalPoint> firstLocalPoint = sceneContext.worldInstanceToLocals(light.worldPoint).stream().findFirst();
		if (firstLocalPoint.isEmpty()) {
			return;
		}

		LocalPoint local = firstLocalPoint.get();

		light.x = local.getX() + LOCAL_HALF_TILE_SIZE;
		light.y = local.getY() + LOCAL_HALF_TILE_SIZE;
		int tileExX = local.getSceneX() + SceneUploader.SCENE_OFFSET;
		int tileExY = local.getSceneY() + SceneUploader.SCENE_OFFSET;
		if (tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE) {
			light.z = sceneContext.scene.getTileHeights()[light.plane][tileExX][tileExY] - light.def.height - 1;
		}

		if (light.def.alignment == Alignment.NORTH || light.def.alignment == Alignment.NORTHEAST
			|| light.def.alignment == Alignment.NORTHWEST)
			light.y += LOCAL_HALF_TILE_SIZE;
		if (light.def.alignment == Alignment.EAST || light.def.alignment == Alignment.NORTHEAST
			|| light.def.alignment == Alignment.SOUTHEAST)
			light.x += LOCAL_HALF_TILE_SIZE;
		if (light.def.alignment == Alignment.SOUTH || light.def.alignment == Alignment.SOUTHEAST
			|| light.def.alignment == Alignment.SOUTHWEST)
			light.y -= LOCAL_HALF_TILE_SIZE;
		if (light.def.alignment == Alignment.WEST || light.def.alignment == Alignment.NORTHWEST
			|| light.def.alignment == Alignment.SOUTHWEST)
			light.x -= LOCAL_HALF_TILE_SIZE;
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned gameObjectSpawned)
	{
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;
		GameObject gameObject = gameObjectSpawned.getGameObject();
		addObjectLight(sceneContext, gameObject, gameObjectSpawned.getTile().getRenderLevel(), gameObject.sizeX(), gameObject.sizeY(), gameObject.getOrientation());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned gameObjectDespawned)
	{
		removeObjectLight(gameObjectDespawned.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned wallObjectSpawned)
	{
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;
		WallObject wallObject = wallObjectSpawned.getWallObject();
		addObjectLight(sceneContext, wallObject, wallObjectSpawned.getTile().getRenderLevel(), 1, 1, wallObject.getOrientationA());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned wallObjectDespawned)
	{
		removeObjectLight(wallObjectDespawned.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned decorativeObjectSpawned)
	{
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;
		addObjectLight(sceneContext, decorativeObjectSpawned.getDecorativeObject(), decorativeObjectSpawned.getTile().getRenderLevel());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned decorativeObjectDespawned)
	{
		removeObjectLight(decorativeObjectDespawned.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned groundObjectSpawned)
	{
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;
		addObjectLight(sceneContext, groundObjectSpawned.getGroundObject(), groundObjectSpawned.getTile().getRenderLevel());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned groundObjectDespawned)
	{
		removeObjectLight(groundObjectDespawned.getGroundObject());
	}
}
