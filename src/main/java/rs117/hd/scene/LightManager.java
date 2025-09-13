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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
import rs117.hd.config.DynamicLights;
import rs117.hd.data.ObjectType;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.lights.Alignment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.scene.lights.LightType;
import rs117.hd.scene.lights.TileObjectImpostorTracker;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class LightManager {
	private static final ResourcePath LIGHTS_PATH = Props
		.getFile("rlhd.lights-path", () -> path(LightManager.class, "lights.json"));

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
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private EntityHiderPlugin entityHiderPlugin;

	@Inject
	private FrameTimer frameTimer;

	private final ArrayList<Light> WORLD_LIGHTS = new ArrayList<>();
	private final ListMultimap<Integer, LightDefinition> NPC_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> OBJECT_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> PROJECTILE_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> GRAPHICS_OBJECT_LIGHTS = ArrayListMultimap.create();

	private boolean reloadLights;
	private EntityHiderConfig entityHiderConfig;
	private int currentPlane;

	public void loadConfig(Gson gson, ResourcePath path) {
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
				lightDef.normalize();
				if (lightDef.worldX != null && lightDef.worldY != null) {
					Light light = new Light(lightDef);
					light.worldPoint = new WorldPoint(lightDef.worldX, lightDef.worldY, lightDef.plane);
					light.persistent = true;
					WORLD_LIGHTS.add(light);
				}
				lightDef.npcIds.forEach(id -> NPC_LIGHTS.put(id, lightDef));
				lightDef.objectIds.forEach(id -> OBJECT_LIGHTS.put(id, lightDef));
				lightDef.projectileIds.forEach(id -> PROJECTILE_LIGHTS.put(id, lightDef));
				lightDef.graphicsObjectIds.forEach(id -> GRAPHICS_OBJECT_LIGHTS.put(id, lightDef));
			}

			log.debug("Loaded {} lights", lights.length);

			// Reload lights once on plugin startup, and whenever lights.json should be hot-swapped.
			// If we don't reload on startup, NPCs won't have lights added until RuneLite fires events
			reloadLights = true;
		} catch (Exception ex) {
			log.error("Failed to parse light configuration", ex);
		}
	}

	public void startUp() {
		entityHiderConfig = configManager.getConfig(EntityHiderConfig.class);
		LIGHTS_PATH.watch(path -> loadConfig(plugin.getGson(), path));
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
	}

	public void update(@Nonnull SceneContext sceneContext) {
		assert client.isClientThread();

		if (plugin.configDynamicLights == DynamicLights.NONE || client.getGameState() != GameState.LOGGED_IN) {
			sceneContext.numVisibleLights = 0;
			return;
		}

		if (reloadLights) {
			reloadLights = false;
			loadSceneLights(sceneContext, null);

			client.getNpcs().forEach(npc -> {
				addNpcLights(npc);
				addSpotanimLights(npc);
			});
		}

		// These should never occur, but just in case...
		if (sceneContext.knownProjectiles.size() > 10000) {
			log.warn("Too many projectiles tracked: {}. Clearing...", sceneContext.knownProjectiles.size());
			sceneContext.knownProjectiles.clear();
		}
		if (sceneContext.lights.size() > 10000) {
			log.warn("Too many lights: {}. Clearing...", sceneContext.lights.size());
			sceneContext.lights.clear();
		}

		int drawDistance = plugin.getDrawDistance() * LOCAL_TILE_SIZE;
		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		int[][][] tileHeights = sceneContext.scene.getTileHeights();
		var cachedNpcs = client.getTopLevelWorldView().npcs();
		var cachedPlayers = client.getTopLevelWorldView().players();
		int plane = client.getPlane();
		boolean changedPlanes = false;

		if (plane != currentPlane) {
			currentPlane = plane;
			changedPlanes = true;
		}

		float cosYaw = cos(plugin.cameraOrientation[0]);
		float sinYaw = sin(plugin.cameraOrientation[0]);
		float cosPitch = cos(plugin.cameraOrientation[1]);
		float sinPitch = sin(plugin.cameraOrientation[1]);
		float[] viewDir = {
			cosPitch * -sinYaw,
			sinPitch,
			cosPitch * cosYaw
		};
		float[] cameraToLight = new float[3];

		for (Light light : sceneContext.lights) {
			// Ways lights may get deleted:
			// - animation-specific:
			//   effectively spawn when the animation they're attached to starts playing, and despawns when it stops,
			//   but they are typically replayable, so they don't fully despawn until marked for removal by something else
			// - spotanim & projectile lights:
			//   automatically marked for removal upon completion
			// - actor lights:
			//   may be automatically marked for removal if the actor becomes invalid
			// - other lights:
			//   despawn when marked for removal by a RuneLite despawn event
			// - fixed lifetime && !replayable:
			//   All non-replayable lights with a fixed lifetime will be automatically marked for removal when done playing

			// Light fade-in and fade-out are based on whether the parent currently exists
			// Additionally, lights have an overruling fade-out when being deprioritized

			// Whatever the light is attached to is presumed to exist if it's not marked for removal yet
			boolean parentExists = !light.markedForRemoval;
			boolean hiddenTemporarily = false;

			if (light.tileObject != null) {
				if (!light.markedForRemoval && light.animationSpecific && light.tileObject instanceof GameObject) {
					int animationId = -1;
					var renderable = ((GameObject) light.tileObject).getRenderable();
					if (renderable instanceof DynamicObject) {
						var anim = ((DynamicObject) renderable).getAnimation();
						if (anim != null)
							animationId = anim.getId();
					}
					parentExists = light.def.animationIds.contains(animationId);
				}
			} else if (light.projectile != null) {
				light.origin[0] = (int) light.projectile.getX();
				light.origin[1] = (int) light.projectile.getZ() - light.def.height;
				light.origin[2] = (int) light.projectile.getY();
				if (light.projectile.getRemainingCycles() <= 0) {
					light.markedForRemoval = true;
				} else {
					hiddenTemporarily = !shouldShowProjectileLights();
					if (light.animationSpecific) {
						var animation = light.projectile.getAnimation();
						parentExists = animation != null && light.def.animationIds.contains(animation.getId());
					}
					light.orientation = light.projectile.getOrientation();
				}
			} else if (light.graphicsObject != null) {
				light.origin[0] = light.graphicsObject.getLocation().getX();
				light.origin[1] = light.graphicsObject.getZ() - light.def.height;
				light.origin[2] = light.graphicsObject.getLocation().getY();
				if (light.graphicsObject.finished()) {
					light.markedForRemoval = true;
				} else if (light.animationSpecific) {
					var animation = light.graphicsObject.getAnimation();
					parentExists = animation != null && light.def.animationIds.contains(animation.getId());
				}
			} else if (light.actor != null && !light.markedForRemoval) {
				if (light.actor instanceof NPC && light.actor != cachedNpcs.byIndex(((NPC) light.actor).getIndex()) ||
					light.actor instanceof Player && light.actor != cachedPlayers.byIndex(((Player) light.actor).getId()) ||
					light.spotanimId != -1 && !light.actor.hasSpotAnim(light.spotanimId)
				) {
					parentExists = false;
					light.markedForRemoval = true;
				} else {
					var lp = light.actor.getLocalLocation();
					light.origin[0] = lp.getX();
					light.origin[2] = lp.getY();
					light.plane = plane;
					light.orientation = light.actor.getCurrentOrientation();

					if (light.animationSpecific)
						parentExists = light.def.animationIds.contains(light.actor.getAnimation());

					int tileExX = ((int) light.origin[0] >> LOCAL_COORD_BITS) + SCENE_OFFSET;
					int tileExY = ((int) light.origin[2] >> LOCAL_COORD_BITS) + SCENE_OFFSET;

					// Some NPCs, such as Crystalline Hunllef in The Gauntlet, sometimes return scene X/Y values far outside the possible range.
					Tile tile;
					if (tileExX >= 0 && tileExY >= 0 &&
						tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE &&
						(tile = tiles[plane][tileExX][tileExY]) != null
					) {
						if (!light.def.ignoreActorHiding &&
							!(light.actor instanceof NPC && ((NPC) light.actor).getComposition().getSize() > 1)
						) {
							// Check if the actor is hidden by another actor on the same tile
							for (var gameObject : tile.getGameObjects()) {
								if (gameObject == null || !(gameObject.getRenderable() instanceof Actor))
									continue;

								// Assume only the first actor at the same exact location will be rendered
								if (gameObject.getX() == light.origin[0] && gameObject.getY() == light.origin[2]) {
									hiddenTemporarily = gameObject.getRenderable() != light.actor;
									break;
								}
							}
						}

						if (!hiddenTemporarily)
							hiddenTemporarily = !isActorLightVisible(light.actor);

						// Tile null check is to prevent oddities caused by - once again - Crystalline Hunllef.
						// May also apply to other NPCs in instances.
						if (tile.getBridge() != null)
							plane++;

						// Interpolate between tile heights based on specific scene coordinates
						float lerpX = fract(light.origin[0] / (float) LOCAL_TILE_SIZE);
						float lerpY = fract(light.origin[2] / (float) LOCAL_TILE_SIZE);
						float heightNorth = mix(
							tileHeights[plane][tileExX][tileExY + 1],
							tileHeights[plane][tileExX + 1][tileExY + 1],
							lerpX
						);
						float heightSouth = mix(
							tileHeights[plane][tileExX][tileExY],
							tileHeights[plane][tileExX + 1][tileExY],
							lerpX
						);
						float tileHeight = mix(heightSouth, heightNorth, lerpY);
						light.origin[1] = (int) tileHeight - 1 - light.def.height;
					}
				}
			}

			light.pos[0] = light.origin[0];
			light.pos[1] = light.origin[1];
			light.pos[2] = light.origin[2];

			int orientation = 0;
			if (light.alignment.relative)
				orientation = mod(light.orientation + light.alignment.orientation, 2048);

			if (light.alignment == Alignment.CUSTOM) {
				// orientation 0 = south
				float sin = sin(orientation * JAU_TO_RAD);
				float cos = cos(orientation * JAU_TO_RAD);
				float x = light.offset[0];
				float z = light.offset[2];
				light.pos[0] += -cos * x - sin * z;
				light.pos[1] += light.offset[1];
				light.pos[2] += -cos * z + sin * x;
			} else {
				int localSizeX = light.sizeX * LOCAL_TILE_SIZE;
				int localSizeY = light.sizeY * LOCAL_TILE_SIZE;

				float radius = localSizeX / 2f;
				if (!light.alignment.radial)
					radius = sqrt(localSizeX * localSizeX + localSizeX * localSizeX) / 2;

				float sine = SINE[orientation] / 65536f;
				float cosine = COSINE[orientation] / 65536f;
				cosine /= (float) localSizeX / (float) localSizeY;

				int offsetX = (int) (radius * sine);
				int offsetY = (int) (radius * cosine);

				light.pos[0] += offsetX;
				light.pos[2] += offsetY;
			}

			// This is a little bit slow, so only update it when necessary
			if (light.prevPlane != light.plane) {
				light.prevPlane = light.plane;
				light.belowFloor = false;
				light.aboveFloor = false;
				int tileExX = ((int) light.pos[0] >> LOCAL_COORD_BITS) + SCENE_OFFSET;
				int tileExY = ((int) light.pos[2] >> LOCAL_COORD_BITS) + SCENE_OFFSET;
				if (light.plane >= 0 && tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE) {
					byte hasTile = sceneContext.filledTiles[tileExX][tileExY];
					if ((hasTile & (1 << light.plane + 1)) != 0)
						light.belowFloor = true;
					if ((hasTile & (1 << light.plane)) != 0)
						light.aboveFloor = true;
				}
			}

			if (!hiddenTemporarily && !light.def.visibleFromOtherPlanes) {
				// Hide certain lights on planes lower than the player to prevent light 'leaking' through the floor
				if (light.plane < plane && light.belowFloor)
					hiddenTemporarily = true;
				// Hide any light that is above the current plane and is above a solid floor
				if (light.plane > plane && light.aboveFloor)
					hiddenTemporarily = true;
			}

			if (parentExists != light.parentExists) {
				light.parentExists = parentExists;
				if (parentExists) {
					// Reset the light if it's replayable and the parent just spawned
					if (light.replayable) {
						light.elapsedTime = 0;
						light.changedVisibilityAt = -1;
						if (light.dynamicLifetime)
							light.lifetime = -1;
					}
				} else if (light.lifetime == -1) {
					// Schedule despawning of the light if the parent just despawned, and the light isn't already scheduled to despawn
					float minLifetime = light.spawnDelay + light.fadeInDuration;
					light.lifetime = max(minLifetime, light.elapsedTime) + light.despawnDelay;
				}
			}

			if (hiddenTemporarily != light.hiddenTemporarily)
				light.toggleTemporaryVisibility(changedPlanes);

			light.elapsedTime += plugin.deltaClientTime;

			light.visible = light.spawnDelay < light.elapsedTime && (light.lifetime == -1 || light.elapsedTime < light.lifetime);

			// If the light is temporarily hidden, keep it visible only while fading out
			if (light.visible && light.hiddenTemporarily)
				light.visible = light.changedVisibilityAt != -1 && light.elapsedTime - light.changedVisibilityAt < Light.VISIBILITY_FADE;

			if (light.visible) {
				// Prioritize lights closer to the focal point
				float distX = plugin.cameraFocalPoint[0] - light.pos[0];
				float distZ = plugin.cameraFocalPoint[1] - light.pos[2];
				light.distanceSquared = distX * distX + distZ * distZ;

				float maxRadius = light.def.radius;
				switch (light.def.type) {
					case FLICKER:
						maxRadius *= 1.5f;
						break;
					case PULSE:
						maxRadius *= 1 + light.def.range / 100f;
						break;
				}

				// Hide lights which cannot possibly affect the visible scene,
				// by either being behind the camera, or too far beyond the edge of the scene
				float near = -maxRadius * maxRadius;
				float far = drawDistance + LOCAL_HALF_TILE_SIZE + maxRadius;
				far *= far;
				light.visible = near < light.distanceSquared && light.distanceSquared < far;
			}
		}

		// Order visible lights first, then by distance. Leave hidden lights unordered at the end.
		sceneContext.lights.sort((a, b) -> a.visible && b.visible ?
			Float.compare(a.distanceSquared, b.distanceSquared) :
			Boolean.compare(b.visible, a.visible));

		// Count number of visible lights
		sceneContext.numVisibleLights = 0;
		int maxLights = plugin.configTiledLighting ? UBOLights.MAX_LIGHTS : plugin.configDynamicLights.getMaxSceneLights();
		for (Light light : sceneContext.lights) {
			// Exit early once encountering the first invisible light, or the light limit is reached
			if (!light.visible || sceneContext.numVisibleLights >= maxLights)
				break;

			sceneContext.numVisibleLights++;

			// If the light was temporarily hidden, begin fading in
			if (!light.withinViewingDistance && light.hiddenTemporarily)
				light.toggleTemporaryVisibility(changedPlanes);
			light.withinViewingDistance = true;

			if (light.def.type == LightType.FLICKER) {
				float t = TWO_PI * (mod(plugin.elapsedTime, 60) / 60 + light.randomOffset);
				float flicker = (
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
				float multiplier = 1 + (2 * output - 1) * light.def.range / 100;
				light.radius = light.def.radius * multiplier;
				light.strength = light.def.strength * multiplier;
			} else {
				light.strength = light.def.strength;
				light.radius = light.def.radius;
				light.color = light.def.color;
			}

			// Spawn & despawn fade-in and fade-out
			if (light.fadeInDuration > 0)
				light.strength *= saturate((light.elapsedTime - light.spawnDelay) / light.fadeInDuration);
			if (light.fadeOutDuration > 0 && light.lifetime != -1)
				light.strength *= saturate((light.lifetime - light.elapsedTime) / light.fadeOutDuration);

			light.applyTemporaryVisibilityFade();
		}

		for (int i = sceneContext.lights.size() - 1; i >= sceneContext.numVisibleLights; i--) {
			Light light = sceneContext.lights.get(i);
			light.withinViewingDistance = false;

			// Automatically despawn non-replayable fixed lifetime lights when they expire
			if (!light.replayable && light.lifetime != -1 && light.lifetime < light.elapsedTime)
				light.markedForRemoval = true;

			if (light.markedForRemoval) {
				sceneContext.lights.remove(i);
				if (light.projectile != null && --light.projectileRefCounter[0] == 0)
					sceneContext.knownProjectiles.remove(light.projectile);
			}
		}
	}

	private boolean isActorLightVisible(@Nonnull Actor actor) {
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

	private boolean shouldShowProjectileLights() {
		return plugin.configProjectileLights && !(pluginManager.isPluginEnabled(entityHiderPlugin) && entityHiderConfig.hideProjectiles());
	}

	public void loadSceneLights(SceneContext sceneContext, @Nullable SceneContext oldSceneContext)
	{
		assert client.isClientThread();

		if (oldSceneContext == null) {
			sceneContext.lights.clear();
			sceneContext.trackedTileObjects.clear();
			sceneContext.trackedVarps.clear();
			sceneContext.trackedVarbits.clear();
			sceneContext.knownProjectiles.clear();
		} else {
			// Copy over NPC and projectile lights from the old scene
			ArrayList<Light> lightsToKeep = new ArrayList<>();
			for (Light light : oldSceneContext.lights)
				if (light.actor != null || light.projectile != null)
					lightsToKeep.add(light);

			sceneContext.lights.addAll(lightsToKeep);
			for (var light : lightsToKeep)
				if (light.projectile != null && oldSceneContext.knownProjectiles.contains(light.projectile))
					sceneContext.knownProjectiles.add(light.projectile);
		}

		for (Light light : WORLD_LIGHTS) {
			assert light.worldPoint != null;
			if (sceneContext.sceneBounds.contains(light.worldPoint))
				addWorldLight(sceneContext, light);
		}

		for (Tile[][] plane : sceneContext.scene.getExtendedTiles()) {
			for (Tile[] column : plane) {
				for (Tile tile : column) {
					if (tile == null)
						continue;

					DecorativeObject decorativeObject = tile.getDecorativeObject();
					if (decorativeObject != null)
						handleObjectSpawn(sceneContext, decorativeObject);

					WallObject wallObject = tile.getWallObject();
					if (wallObject != null)
						handleObjectSpawn(sceneContext, wallObject);

					GroundObject groundObject = tile.getGroundObject();
					if (groundObject != null && groundObject.getRenderable() != null)
						handleObjectSpawn(sceneContext, groundObject);

					for (GameObject gameObject : tile.getGameObjects()) {
						// Skip nulls, players & NPCs
						if (gameObject == null || gameObject.getRenderable() instanceof Actor)
							continue;

						handleObjectSpawn(sceneContext, gameObject);
					}
				}
			}
		}
	}

	private void removeLightIf(Predicate<Light> predicate) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;
		removeLightIf(sceneContext, predicate);
	}

	private void removeLightIf(@Nonnull SceneContext sceneContext, Predicate<Light> predicate) {
		for (var light : sceneContext.lights)
			if (predicate.test(light))
				light.markedForRemoval = true;
	}

	private void addSpotanimLights(Actor actor) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		int[] worldPos = sceneContext.localToWorld(actor.getLocalLocation());

		for (var spotAnim : actor.getSpotAnims()) {
			int spotAnimId = spotAnim.getId();
			for (var def : GRAPHICS_OBJECT_LIGHTS.get(spotAnim.getId())) {
				if (def.areas.length > 0) {
					boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
					if (!isInArea)
						continue;
				}
				if (def.excludeAreas.length > 0) {
					boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
					if (isInArea)
						continue;
				}

				boolean isDuplicate = sceneContext.lights.stream()
					.anyMatch(light ->
						light.spotanimId == spotAnimId &&
						light.actor == actor &&
						light.def == def);
				if (isDuplicate)
					continue;

				Light light = new Light(def);
				light.plane = -1;
				light.spotanimId = spotAnimId;
				light.actor = actor;
				sceneContext.lights.add(light);
			}
		}
	}

	private void addNpcLights(NPC npc)
	{
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		int uuid = ModelHash.packUuid(ModelHash.TYPE_NPC, npc.getId());
		int[] worldPos = sceneContext.localToWorld(npc.getLocalLocation());

		var modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		for (LightDefinition def : NPC_LIGHTS.get(npc.getId())) {
			if (def.areas.length > 0) {
				boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
				if (!isInArea)
					continue;
			}
			if (def.excludeAreas.length > 0) {
				boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
				if (isInArea)
					continue;
			}

			// Prevent duplicate lights from being spawned for the same NPC
			boolean isDuplicate = sceneContext.lights.stream()
				.anyMatch(light ->
					light.actor == npc &&
					light.def == def &&
					!light.markedForRemoval);
			if (isDuplicate)
				continue;

			Light light = new Light(def);
			light.plane = -1;
			light.actor = npc;
			sceneContext.lights.add(light);
		}
	}

	private void handleObjectSpawn(TileObject object) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext != null)
			handleObjectSpawn(sceneContext, object);
	}

	private void handleObjectSpawn(
		@Nonnull SceneContext sceneContext,
		@Nonnull TileObject tileObject
	) {
		if (sceneContext.trackedTileObjects.containsKey(tileObject))
			return;

		var tracker = new TileObjectImpostorTracker(tileObject);
		sceneContext.trackedTileObjects.put(tileObject, tracker);

		// prevent objects at plane -1 and below from having lights
		if (tileObject.getPlane() < 0)
			return;

		ObjectComposition def = client.getObjectDefinition(tileObject.getId());
		tracker.impostorIds = def.getImpostorIds();
		if (tracker.impostorIds != null) {
			tracker.impostorVarbit = def.getVarbitId();
			tracker.impostorVarp = def.getVarPlayerId();
			if (tracker.impostorVarbit != -1)
				sceneContext.trackedVarbits.put(tracker.impostorVarbit, tracker);
			if (tracker.impostorVarp != -1)
				sceneContext.trackedVarps.put(tracker.impostorVarp, tracker);
		}

		trackImpostorChanges(sceneContext, tracker);
	}

	private void handleObjectDespawn(TileObject tileObject) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		var tracker = sceneContext.trackedTileObjects.remove(tileObject);
		if (tracker == null)
			return;

		if (tracker.spawnedAnyLights) {
			long hash = tracker.lightHash(tracker.impostorId);
			removeLightIf(sceneContext, l -> l.hash == hash);
		}

		if (tracker.impostorVarbit != -1)
			sceneContext.trackedVarbits.remove(tracker.impostorVarbit, tracker);
		if (tracker.impostorVarp != -1)
			sceneContext.trackedVarps.remove(tracker.impostorVarp, tracker);
	}

	private void trackImpostorChanges(@Nonnull SceneContext sceneContext, TileObjectImpostorTracker tracker) {
		int impostorId = -1;
		if (tracker.impostorIds != null) {
			try {
				int impostorIndex = -1;
				if (tracker.impostorVarbit != -1) {
					impostorIndex = client.getVarbitValue(tracker.impostorVarbit);
				} else if (tracker.impostorVarp != -1) {
					impostorIndex = client.getVarpValue(tracker.impostorVarp);
				}
				if (impostorIndex >= 0)
					impostorId = tracker.impostorIds[min(impostorIndex, tracker.impostorIds.length - 1)];
			} catch (Exception ex) {
				log.debug("Error getting impostor:", ex);
			}
		}

		// Don't do anything if the impostor is the same, unless the object just spawned
		if (impostorId == tracker.impostorId && !tracker.justSpawned)
			return;

		int sizeX = 1;
		int sizeY = 1;
		Renderable[] renderables = new Renderable[2];
		int[] orientations = { 0, 0 };
		int[] offset = { 0, 0 };

		var tileObject = tracker.tileObject;
		if (tileObject instanceof GroundObject) {
			var object = (GroundObject) tileObject;
			renderables[0] = object.getRenderable();
			orientations[0] = HDUtils.getModelOrientation(object.getConfig());
		} else if (tileObject instanceof DecorativeObject) {
			var object = (DecorativeObject) tileObject;
			renderables[0] = object.getRenderable();
			renderables[1] = object.getRenderable2();
			int ori = orientations[0] = orientations[1] = HDUtils.getModelOrientation(object.getConfig());
			switch (ObjectType.fromConfig(object.getConfig())) {
				case WallDecorDiagonalNoOffset:
				case WallDecorDiagonalOffset:
				case WallDecorDiagonalBoth:
					ori = (ori + 512) % 2048;
					offset[0] = SINE[ori] * 64 >> 16;
					offset[1] = COSINE[ori] * 64 >> 16;
					break;
			}
			offset[0] += object.getXOffset();
			offset[1] += object.getYOffset();
		} else if (tileObject instanceof WallObject) {
			var object = (WallObject) tileObject;
			renderables[0] = object.getRenderable1();
			renderables[1] = object.getRenderable2();
			orientations[0] = HDUtils.convertWallObjectOrientation(object.getOrientationA());
			orientations[1] = HDUtils.convertWallObjectOrientation(object.getOrientationB());
		} else if (tileObject instanceof GameObject) {
			var object = (GameObject) tileObject;
			sizeX = object.sizeX();
			sizeY = object.sizeY();
			renderables[0] = object.getRenderable();
			int ori = orientations[0] = HDUtils.getModelOrientation(object.getConfig());
			int offsetDist = 64;
			switch (ObjectType.fromConfig(object.getConfig())) {
				case RoofEdgeDiagonalCorner:
				case RoofDiagonalWithRoofEdge:
					ori += 1024;
					offsetDist = round(offsetDist / sqrt(2));
				case WallDiagonal:
					ori = (ori + 2048 - 256) % 2048;
					offset[0] = SINE[ori] * offsetDist >> 16;
					offset[1] = COSINE[ori] * offsetDist >> 16;
					break;
			}
		} else {
			log.warn("Unhandled TileObject type: id: {}, hash: {}", tileObject.getId(), tileObject.getHash());
			return;
		}

		// Despawn old lights, if we spawned any for the previous impostor
		if (tracker.spawnedAnyLights) {
			long oldHash = tracker.lightHash(tracker.impostorId);
			removeLightIf(sceneContext, l -> l.hash == oldHash);
			tracker.spawnedAnyLights = false;
		}

		long newHash = tracker.lightHash(impostorId);
		List<LightDefinition> lights = OBJECT_LIGHTS.get(impostorId == -1 ? tileObject.getId() : impostorId);
		HashSet<LightDefinition> onlySpawnOnce = new HashSet<>();

		LocalPoint lp = tileObject.getLocalLocation();
		int lightX = lp.getX() + offset[0];
		int lightZ = lp.getY() + offset[1];
		int plane = tileObject.getPlane();

		// Spawn animation-specific lights for each DynamicObject renderable, and non-animation-based lights
		for (int i = 0; i < 2; i++) {
			var renderable = renderables[i];
			if (renderable == null)
				continue;

			for (LightDefinition def : lights) {
				if (def.areas.length > 0) {
					int[] worldPos = sceneContext.localToWorld(lightX, lightZ, plane);
					boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
					if (!isInArea)
						continue;
				}
				if (def.excludeAreas.length > 0) {
					int[] worldPos = sceneContext.localToWorld(lightX, lightZ, plane);
					boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
					if (isInArea)
						continue;
				}

				// Rarely, it may be necessary to specify which of the two possible renderables the light should be attached to
				if (def.renderableIndex == -1) {
					// If unspecified, spawn it for the first non-null renderable
					if (onlySpawnOnce.contains(def))
						continue;
					onlySpawnOnce.add(def);
				} else if (def.renderableIndex != i) {
					continue;
				}

				int tileExX = clamp(lp.getSceneX() + SCENE_OFFSET, 0, EXTENDED_SCENE_SIZE - 2);
				int tileExY = clamp(lp.getSceneY() + SCENE_OFFSET, 0, EXTENDED_SCENE_SIZE - 2);
				float lerpX = fract(lightX / (float) LOCAL_TILE_SIZE);
				float lerpZ = fract(lightZ / (float) LOCAL_TILE_SIZE);
				int tileZ = clamp(plane, 0, MAX_Z - 1);

				Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
				Tile tile = tiles[tileZ][tileExX][tileExY];
				if (tile != null && tile.getBridge() != null && tileZ < MAX_Z - 1)
					tileZ++;

				int[][][] tileHeights = sceneContext.scene.getTileHeights();
				float heightNorth = mix(
					tileHeights[tileZ][tileExX][tileExY + 1],
					tileHeights[tileZ][tileExX + 1][tileExY + 1],
					lerpX
				);
				float heightSouth = mix(
					tileHeights[tileZ][tileExX][tileExY],
					tileHeights[tileZ][tileExX + 1][tileExY],
					lerpX
				);
				float tileHeight = mix(heightSouth, heightNorth, lerpZ);

				Light light = new Light(def);
				light.hash = newHash;
				light.tileObject = tileObject;
				light.plane = plane;
				light.orientation = orientations[i];
				light.origin[0] = lightX;
				light.origin[1] = (int) tileHeight - light.def.height - 1;
				light.origin[2] = lightZ;
				light.sizeX = sizeX;
				light.sizeY = sizeY;
				sceneContext.lights.add(light);
				tracker.spawnedAnyLights = true;
			}
		}

		tracker.impostorId = impostorId;
		tracker.justSpawned = false;
	}

	private void addWorldLight(SceneContext sceneContext, Light light) {
		assert light.worldPoint != null;
		sceneContext.worldToLocals(light.worldPoint).forEach(local -> {
			int tileExX = local[0] / LOCAL_TILE_SIZE + SCENE_OFFSET;
			int tileExY = local[1] / LOCAL_TILE_SIZE + SCENE_OFFSET;
			if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
				return;

			var copy = new Light(light.def);
			copy.plane = local[2];
			copy.persistent = light.persistent;
			copy.origin[0] = local[0] + LOCAL_HALF_TILE_SIZE;
			copy.origin[1] = sceneContext.scene.getTileHeights()[local[2]][tileExX][tileExY] - copy.def.height - 1;
			copy.origin[2] = local[1] + LOCAL_HALF_TILE_SIZE;
			sceneContext.lights.add(copy);
		});
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved projectileMoved) {
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		// Since there's no spawn & despawn events for projectiles, add when they move for the first time
		Projectile projectile = projectileMoved.getProjectile();
		if (!sceneContext.knownProjectiles.add(projectile))
			return;

		int[] worldPos = sceneContext.localToWorld((int) projectile.getX(), (int) projectile.getY(), projectile.getFloor());

		int[] refCounter = { 0 };
		for (LightDefinition def : PROJECTILE_LIGHTS.get(projectile.getId())) {
			if (def.areas.length > 0) {
				boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
				if (!isInArea)
					continue;
			}
			if (def.excludeAreas.length > 0) {
				boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
				if (isInArea)
					continue;
			}

			Light light = new Light(def);
			light.projectile = projectile;
			light.projectileRefCounter = refCounter;
			refCounter[0]++;
			light.origin[0] = (int) projectile.getX();
			light.origin[1] = (int) projectile.getZ();
			light.origin[2] = (int) projectile.getY();
			light.plane = projectile.getFloor();

			sceneContext.lights.add(light);
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned spawn) {
		NPC npc = spawn.getNpc();
		addNpcLights(npc);
		addSpotanimLights(npc);
	}

	@Subscribe
	public void onNpcChanged(NpcChanged change) {
		// Respawn non-spotanim lights
		NPC npc = change.getNpc();
		removeLightIf(light -> light.actor == npc && light.spotanimId == -1);
		addNpcLights(change.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned despawn) {
		NPC npc = despawn.getNpc();
		removeLightIf(light -> light.actor == npc);
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned spawn) {
		addSpotanimLights(spawn.getPlayer());
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged change) {
		// Don't add spotanim lights on player change events, since it breaks death & respawn lights
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged change) {
		addSpotanimLights(change.getActor());
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned despawn) {
		Player player = despawn.getPlayer();
		removeLightIf(light -> light.actor == player);
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated graphicsObjectCreated) {
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		GraphicsObject graphicsObject = graphicsObjectCreated.getGraphicsObject();
		var lp = graphicsObject.getLocation();
		int[] worldPos = sceneContext.localToWorld(lp, graphicsObject.getLevel());

		for (LightDefinition def : GRAPHICS_OBJECT_LIGHTS.get(graphicsObject.getId())) {
			if (def.areas.length > 0) {
				boolean isInArea = Arrays.stream(def.areas).anyMatch(aabb -> aabb.contains(worldPos));
				if (!isInArea)
					continue;
			}
			if (def.excludeAreas.length > 0) {
				boolean isInArea = Arrays.stream(def.excludeAreas).anyMatch(aabb -> aabb.contains(worldPos));
				if (isInArea)
					continue;
			}

			Light light = new Light(def);
			light.graphicsObject = graphicsObject;
			light.origin[0] = lp.getX();
			light.origin[1] = graphicsObject.getZ();
			light.origin[2] = lp.getY();
			light.plane = worldPos[2];
			sceneContext.lights.add(light);
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned spawn) {
		handleObjectSpawn(spawn.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned despawn) {
		handleObjectDespawn(despawn.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned spawn) {
		handleObjectSpawn(spawn.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned despawn) {
		handleObjectDespawn(despawn.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned spawn) {
		handleObjectSpawn(spawn.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned despawn) {
		handleObjectDespawn(despawn.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned spawn) {
		handleObjectSpawn(spawn.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned despawn) {
		handleObjectDespawn(despawn.getGroundObject());
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		if (plugin.enableDetailedTimers)
			frameTimer.begin(Timer.IMPOSTOR_TRACKING);
		// Check if the event is specifically a varbit change first,
		// since all varbit changes are necessarily also varp changes
		if (event.getVarbitId() != -1) {
			for (var tracker : sceneContext.trackedVarbits.get(event.getVarbitId()))
				trackImpostorChanges(sceneContext, tracker);
		} else if (event.getVarpId() != -1) {
			for (var tracker : sceneContext.trackedVarps.get(event.getVarpId()))
				trackImpostorChanges(sceneContext, tracker);
		}
		if (plugin.enableDetailedTimers)
			frameTimer.end(Timer.IMPOSTOR_TRACKING);
	}
}
