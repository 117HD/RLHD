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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.entityhider.EntityHiderConfig;
import net.runelite.client.plugins.entityhider.EntityHiderPlugin;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.DynamicLights;
import rs117.hd.config.MoonBehavior;
import rs117.hd.data.ObjectType;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.scene.lights.Alignment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.scene.lights.LightTimeOfDay;
import rs117.hd.scene.lights.LightType;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.HDUtils.isSphereIntersectingFrustum;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;
import static rs117.hd.utils.collections.Util.quickSort;

@Singleton
@Slf4j
public class LightManager {
	private static final float[] SKY_LUMA_WEIGHTS = { 0.2126f, 0.7152f, 0.0722f };
	private static final float NIGHT_RADIUS_BOOST_FRACTION = 0.25f;
	private static final float NIGHT_STAGGER_RAMP_WIDTH = 0.08f;

	private static final ResourcePath LIGHTS_PATH = Props
		.getFile("rlhd.lights-path", () -> path(LightManager.class, "lights.json"));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

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
	private EnvironmentManager environmentManager;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private EntityHiderPlugin entityHiderPlugin;

	private final ArrayList<Light> WORLD_LIGHTS = new ArrayList<>();
	private final ListMultimap<Integer, LightDefinition> NPC_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> OBJECT_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> PROJECTILE_LIGHTS = ArrayListMultimap.create();
	private final ListMultimap<Integer, LightDefinition> GRAPHICS_OBJECT_LIGHTS = ArrayListMultimap.create();

	private final Renderable[] imposterRenderables = new Renderable[2];
	private boolean reloadLights;
	private EntityHiderConfig entityHiderConfig;
	private int currentPlane;
	private float previousNightLightFactor = -1f;

	public void loadConfig(Gson gson, ResourcePath path) {
		LightDefinition[] lights;
		try (var ignored = gamevalManager.obtainHandle()) {
			lights = path.loadJson(gson, LightDefinition[].class);
			if (lights == null) {
				log.warn("Skipping empty lights.json");
				return;
			}
		} catch (IOException ex) {
			log.error("Failed to load lights", ex);
			return;
		}

		clientThread.invoke(() -> {
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
		});
	}

	public void startUp() {
		entityHiderConfig = configManager.getConfig(EntityHiderConfig.class);
		LIGHTS_PATH.watch(path -> loadConfig(plugin.getGson(), path));
		eventBus.register(this);
	}

	public void shutDown() {
		WORLD_LIGHTS.clear();
		NPC_LIGHTS.clear();
		OBJECT_LIGHTS.clear();
		PROJECTILE_LIGHTS.clear();
		GRAPHICS_OBJECT_LIGHTS.clear();

		eventBus.unregister(this);
	}

	public void update(@Nonnull SceneContext sceneContext, int[] cameraShift, float[][] cameraFrustum) {
		assert client.isClientThread();

		if (plugin.configDynamicLights == DynamicLights.NONE || client.getGameState() != GameState.LOGGED_IN) {
			sceneContext.numVisibleLights = 0;
			return;
		}

		if (reloadLights) {
			reloadLights = false;
			sceneContext.lights.clear();
			sceneContext.knownProjectiles.clear();
			loadSceneLights(sceneContext);
			swapSceneLights(sceneContext, null);

			client.getNpcs().forEach(npc -> {
				addNpcLights(npc);
				addSpotanimLights(npc);
			});
		}

		float nightLightFactor = 1f;
		boolean overworldDayNightActive = false;
		boolean nightFactorRising = true;
		if (environmentManager.isOverworld() && config.enableDaylightCycle()) {
			overworldDayNightActive = true;
			DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
			DaylightCycle daylightCycle = forcedMode != null ? forcedMode : config.daylightCycle();
			TimeOfDay.setCycleMode(daylightCycle);
			TimeOfDay.setDayLength(config.dayLength());
			nightLightFactor = TimeOfDay.getNightLightFactor(plugin.latLong, config.cycleDurationMinutes());
			nightFactorRising = previousNightLightFactor < 0 || nightLightFactor >= previousNightLightFactor;
			previousNightLightFactor = nightLightFactor;
		} else {
			previousNightLightFactor = -1f;
		}

		if (config.enableDaylightCycle()) {
			DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
			DaylightCycle daylightCycle = forcedMode != null ? forcedMode : config.daylightCycle();
			TimeOfDay.setCycleMode(daylightCycle);
			TimeOfDay.setDayLength(config.dayLength());
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
		int gameCycle = client.getGameCycle();
		final int plane = client.getPlane();
		boolean changedPlanes = false;

		if (plane != currentPlane) {
			currentPlane = plane;
			changedPlanes = true;
			reloadObjectLights(sceneContext);
			reloadWorldLights(sceneContext);
		}

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
			boolean hiddenTemporarily = light.hiddenTemporarily;

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
				hiddenTemporarily = !shouldShowProjectileLights();
				if (light.projectile.getRemainingCycles() <= 0) {
					light.markedForRemoval = true;
				} else {
					if (light.animationSpecific) {
						if (light.def.waitForAnimation && gameCycle < light.projectile.getStartCycle()) {
							parentExists = false;
						} else if (!light.def.animationIds.isEmpty()) {
							var animation = light.projectile.getAnimation();
							parentExists = animation != null && light.def.animationIds.contains(animation.getId());
						}
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
					if (light.def.waitForAnimation && gameCycle < light.graphicsObject.getStartCycle()) {
						parentExists = false;
					} else if (!light.def.animationIds.isEmpty()) {
						var animation = light.graphicsObject.getAnimation();
						parentExists = animation != null && light.def.animationIds.contains(animation.getId());
					}
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

					if (light.animationSpecific) {
						if (light.spotanimId != -1) {
							if (light.def.waitForAnimation) {
								parentExists = false;
								for (var spotanim : light.actor.getSpotAnims()) {
									if (spotanim.getId() == light.spotanimId) {
										if (gameCycle >= spotanim.getStartCycle())
											parentExists = true;
										break;
									}
								}
							}
						} else {
							parentExists = light.def.animationIds.contains(light.actor.getAnimation());
						}
					}

					int tileExX = ((int) light.origin[0] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
					int tileExY = ((int) light.origin[2] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;

					// Some NPCs, such as Crystalline Hunllef in The Gauntlet, sometimes return scene X/Y values far outside the possible range.
					Tile tile;
					if (tileExX >= 0 && tileExY >= 0 &&
						tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE &&
						(tile = tiles[plane][tileExX][tileExY]) != null
					) {
						hiddenTemporarily = !isActorLightVisible(light.actor);

						if (!light.def.ignoreActorHiding &&
							!(light.actor instanceof NPC && ((NPC) light.actor).getComposition().getSize() > 1)
						) {
							// Check if the actor is hidden by another actor on the same tile
							for (var gameObject : tile.getGameObjects()) {
								if (gameObject == null || !(gameObject.getRenderable() instanceof Actor))
									continue;

								// Assume only the first actor at the same exact location will be rendered
								if (gameObject.getX() == round(light.origin[0]) &&
									gameObject.getY() == round(light.origin[2]) &&
									gameObject.getRenderable() != light.actor
								) {
									hiddenTemporarily = true;
									break;
								}
							}
						}

						// Interpolate between tile heights based on specific scene coordinates
						int tileZ = plane;
						if (tile.getBridge() != null)
							tileZ++;
						float lerpX = fract(light.origin[0] / (float) LOCAL_TILE_SIZE);
						float lerpY = fract(light.origin[2] / (float) LOCAL_TILE_SIZE);
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
				int tileExX = ((int) light.pos[0] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
				int tileExY = ((int) light.pos[2] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
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
				} else if (light.def.despawnWithParent) {
					light.lifetime = 0;
				} else if (light.lifetime == -1) {
					// Schedule despawning of the light if the parent just despawned, and the light isn't already scheduled to despawn
					float minLifetime = light.spawnDelay + light.fadeInDuration;
					light.lifetime = max(minLifetime, light.elapsedTime) + light.despawnDelay;
				}
			}

			if (hiddenTemporarily != light.hiddenTemporarily)
				light.toggleTemporaryVisibility(changedPlanes);

			light.elapsedTime += plugin.deltaClientTime;

			light.visible = light.spawnDelay <= light.elapsedTime && (light.lifetime == -1 || light.elapsedTime < light.lifetime);

			// If the light is temporarily hidden, keep it visible only while fading out
			if (light.visible && light.hiddenTemporarily)
				light.visible = light.changedVisibilityAt != -1 && light.elapsedTime - light.changedVisibilityAt < Light.VISIBILITY_FADE;

			// dayNightOnly lights require the cycle setting; outside overworld they behave as static lights
			if (light.visible && light.def.dayNightOnly && !config.enableDaylightCycle())
				light.visible = false;

			if (light.visible) {
				// Prioritize lights closer to the focal point
				float distX = plugin.cameraFocalPoint[0] - light.pos[0];
				float distZ = plugin.cameraFocalPoint[1] - light.pos[2];
				light.distanceSquared = distX * distX + distZ * distZ;

				float maxRadius = light.def.radius;
				if (overworldDayNightActive) {
					float phaseFactor = getEffectiveNightFactor(light, nightLightFactor, nightFactorRising);
					float radiusScale = getNightRadiusScale(light.def, phaseFactor, nightLightFactor);
					maxRadius *= radiusScale;
					if (isTimeRestricted(light.def) && getNightStrengthScale(light.def, phaseFactor, nightLightFactor) < 0.001f)
						light.visible = false;
				}
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
				if (!plugin.orthographicProjection) {
					float near = -maxRadius * maxRadius;
					float far = drawDistance + LOCAL_HALF_TILE_SIZE + maxRadius;
					far *= far;
					light.visible = near < light.distanceSquared && light.distanceSquared < far;

					// Check that the light is within the camera's frustum specifically: left, right, bottom, top
					// The above check already covers the near plane
					if (plugin.configTiledLighting && light.visible) {
						light.visible = isSphereIntersectingFrustum(
							light.pos[0] + cameraShift[0],
							light.pos[1],
							light.pos[2] + cameraShift[1],
							maxRadius, // use max radius, since the radius hasn't been updated yet
							cameraFrustum,
							4
						);
					}
				}
			}
		}

		// Order visible lights first, then by distance. Leave hidden lights unordered at the end.
		quickSort(sceneContext.lights,
			(a, b) -> a.visible && b.visible ?
				Float.compare(a.distanceSquared, b.distanceSquared) :
				Boolean.compare(b.visible, a.visible)
		);

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

			applyTimeOfDayColor(sceneContext, light);

			// Spawn & despawn fade-in and fade-out
			if (light.fadeInDuration > 0)
				light.strength *= saturate((light.elapsedTime - light.spawnDelay) / light.fadeInDuration);
			if (light.fadeOutDuration > 0 && light.lifetime != -1)
				light.strength *= saturate((light.lifetime - light.elapsedTime) / light.fadeOutDuration);

			if (overworldDayNightActive) {
				float phaseFactor = getEffectiveNightFactor(light, nightLightFactor, nightFactorRising);
				light.strength *= getNightStrengthScale(light.def, phaseFactor, nightLightFactor);
				light.radius *= getNightRadiusScale(light.def, phaseFactor, nightLightFactor);
			}

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

	/**
	 * Remap the global dusk-to-dawn factor through per-light on/off windows.
	 */
	private static float getEffectiveNightFactor(Light light, float nightLightFactor, boolean rising) {
		LightDefinition def = light.def;

		LightTimeOfDay on = def.timeOfDay;
		if (on == null)
			return nightLightFactor;

		if (rising) {
			float[] window = getPhaseWindow(light, on, def.staggered);
			return remapNightWindow(nightLightFactor, window[0], window[1]);
		}

		LightTimeOfDay offPhase = def.timeOfDayOff != null ? def.timeOfDayOff : on;
		float[] window = getPhaseWindow(light, offPhase, def.staggered);
		float offStart = window[0];
		float offEnd = window[1];

		if (nightLightFactor >= offEnd)
			return 1f;
		if (nightLightFactor <= offStart)
			return 0f;

		float t = (nightLightFactor - offStart) / (offEnd - offStart);
		return t * t * (3f - 2f * t);
	}

	private static float[] getPhaseWindow(Light light, LightTimeOfDay phase, boolean staggered) {
		if (!staggered)
			return new float[] { phase.start, phase.end };

		float phaseSpan = phase.end - phase.start;
		float rampWidth = Math.min(NIGHT_STAGGER_RAMP_WIDTH, phaseSpan);
		float maxOffset = Math.max(0, phaseSpan - rampWidth);
		float offset = getNightStaggerOffset(light) * maxOffset;
		return new float[] { phase.start + offset, phase.start + offset + rampWidth };
	}

	private static float remapNightWindow(float nightLightFactor, float start, float end) {
		if (nightLightFactor <= start)
			return 0f;
		if (nightLightFactor >= end)
			return 1f;

		float t = (nightLightFactor - start) / (end - start);
		return t * t * (3f - 2f * t);
	}

	private static float getNightStaggerOffset(Light light) {
		int hash = Float.floatToIntBits(light.pos[0]);
		hash ^= Float.floatToIntBits(light.pos[1]) * 374761393;
		hash ^= Float.floatToIntBits(light.pos[2]) * 668265263;
		hash ^= light.plane * 912271;
		hash ^= hash >>> 16;
		hash *= 0x85ebca6b;
		hash ^= hash >>> 13;
		hash *= 0xc2b2ae35;
		hash ^= hash >>> 16;
		return (hash & 0x7FFFFFFF) / 2147483647f;
	}

	private static boolean isTimeRestricted(LightDefinition def) {
		return def.timeOfDay != null;
	}

	private int[] getLightWorldPos(SceneContext sceneContext, Light light) {
		if (light.worldPoint != null)
			return new int[] { light.worldPoint.getX(), light.worldPoint.getY(), light.worldPoint.getPlane() };
		return sceneContext.localToWorld((int) light.pos[0], (int) light.pos[2], light.plane);
	}

	private void applyTimeOfDayColor(SceneContext sceneContext, Light light) {
		System.arraycopy(light.def.color, 0, light.color, 0, 3);

		if (!light.def.followDayNight || !config.enableDaylightCycle())
			return;

		EnvironmentManager.OutdoorSkySample sky = environmentManager.sampleOutdoorSky(
			getLightWorldPos(sceneContext, light),
			plugin.latLong,
			config.cycleDurationMinutes(),
			config.minimumBrightness()
		);

		float defLuma = dot(light.def.color, SKY_LUMA_WEIGHTS);
		float noonLuma = dot(sky.noonHorizonLinear, SKY_LUMA_WEIGHTS);

		float[] lightColor = Arrays.copyOf(sky.horizonLinear, 3);

		double sunAltDeg = Math.toDegrees(TimeOfDay.getSunAngles(plugin.latLong, config.cycleDurationMinutes())[1]);

		// At night, blend the dark sky horizon toward moonColor: reduces the blue cast
		// and adds silver moonlight filtering through tunnel openings.
		// moonStrengthFloor drives a minimum timeScale so deep-night lights are
		// visibly lit by the moon even when brightnessMultiplier is near zero.
		float moonStrengthFloor = 0;
		if (sunAltDeg < 5) {
			MoonBehavior moonBehavior = config.moonBehavior();
			// ALWAYS_NIGHT freezes getModifiedDate at midnight on a fixed epoch, so getMoonDate
			// returns a static date where the moon may be below the horizon. Use the same fixed
			// position that FIXED_NIGHT uses so moonlight is always visible in both modes.
			DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
			DaylightCycle effectiveCycle = forcedMode != null ? forcedMode : config.daylightCycle();
			double moonAltDeg = (effectiveCycle == DaylightCycle.ALWAYS_NIGHT)
				? Math.toDegrees(TimeOfDay.getFixedNightMoonAngles()[1])
				: TimeOfDay.getMoonAltitudeDegrees(plugin.latLong, config.cycleDurationMinutes(), moonBehavior);
			float moonIllumFrac = TimeOfDay.getMoonIlluminationFraction(config.cycleDurationMinutes(), moonBehavior);
			if (moonAltDeg > -5 && moonIllumFrac > 0.01f) {
				float sunFade = (float) Math.max(0.0, Math.min(1.0, (5.0 - sunAltDeg) / 10.0));
				float moonEl = (float) Math.min(1.0, Math.max(0.0, (moonAltDeg + 5.0) / 25.0));
				float moonElSmooth = moonEl * moonEl * (3 - 2 * moonEl);
				float moonBlend = moonIllumFrac * 0.25f * moonElSmooth * sunFade;
				lightColor = mix(lightColor, environmentManager.currentMoonColor, moonBlend);
				moonStrengthFloor = moonIllumFrac * 0.12f * moonElSmooth;
			}
		}

		// Desaturate toward gray as the sun climbs — high sun produces whiter, more neutral light.
		if (sunAltDeg > 0) {
			float desat = smoothstep(0f, 90f, (float) sunAltDeg) * 0.75f;
			float luma = dot(lightColor, SKY_LUMA_WEIGHTS);
			for (int i = 0; i < 3; i++)
				lightColor[i] = mix(lightColor[i], luma, desat);
		}

		System.arraycopy(lightColor, 0, light.color, 0, 3);
		float horizonLuma = dot(lightColor, SKY_LUMA_WEIGHTS);
		float peakScale = defLuma / max(noonLuma, 1e-4f);
		float timeScale = max(min(horizonLuma / max(noonLuma, 1e-4f), 1f) * sky.brightnessMultiplier, moonStrengthFloor);
		light.strength *= peakScale * timeScale;
	}

	/**
	 * Strength scale when the day/night cycle is active in overworld areas.
	 * nightMultiplier is the peak-darkness target: 0 = off, 0.5 = half default, 1 = default, >1 = boosted.
	 * Always-on lights blend from default (day) toward that target.
	 * timeOfDay / staggered lights multiply their phase fade by the same night target curve.
	 */
	private static float getNightStrengthScale(LightDefinition def, float phaseFactor, float globalNightFactor) {
		float nightScale = lerpNightScale(def.nightMultiplier, globalNightFactor);
		if (isTimeRestricted(def))
			return phaseFactor * nightScale;
		return nightScale;
	}

	/**
	 * Radius scale when the day/night cycle is active.
	 * Values below 1 only reduce strength; radius stays at default unless the light is fully off (0).
	 * Values above 1 grow radius by a smaller fraction than brightness.
	 */
	private static float getNightRadiusScale(LightDefinition def, float phaseFactor, float globalNightFactor) {
		float multiplier = def.nightMultiplier;
		if (isTimeRestricted(def)) {
			if (multiplier <= 0)
				return 0;
			if (multiplier < 1)
				return phaseFactor;
			return phaseFactor * lerpNightRadiusScale(multiplier, globalNightFactor);
		}
		if (multiplier <= 0)
			return lerpNightScale(0, globalNightFactor);
		if (multiplier < 1)
			return 1;
		return lerpNightRadiusScale(multiplier, globalNightFactor);
	}

	private static float lerpNightScale(float multiplier, float nightLightFactor) {
		return 1 + (multiplier - 1) * nightLightFactor;
	}

	private static float lerpNightRadiusScale(float multiplier, float nightLightFactor) {
		float radiusFraction = multiplier > 1f ? NIGHT_RADIUS_BOOST_FRACTION : 1f;
		return 1 + (multiplier - 1) * nightLightFactor * radiusFraction;
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

	public void loadSceneLights(SceneContext sceneContext) {
		for (Light light : WORLD_LIGHTS) {
			assert light.worldPoint != null;
			if (sceneContext.sceneBounds.contains(light.worldPoint))
				addWorldLight(sceneContext, light);
		}

		scanSceneObjectLights(sceneContext);
	}

	/**
	 * Rebuild tile object lights after a plane change or zone reload.
	 * Spawn events are not fired for objects already in the scene, so we rescan manually.
	 */
	public void reloadObjectLights(SceneContext sceneContext) {
		sceneContext.lights.removeIf(light -> light.tileObject != null);
		scanSceneObjectLights(sceneContext);

		for (Light light : sceneContext.lights) {
			if (light.tileObject != null) {
				light.fadeInDuration = 0;
				light.hiddenTemporarily = false;
				light.changedVisibilityAt = -1;
				light.prevPlane = -1;
			}
		}
	}

	private void reloadWorldLights(SceneContext sceneContext) {
		sceneContext.lights.removeIf(light -> light.worldPoint != null);
		for (Light light : WORLD_LIGHTS) {
			if (sceneContext.sceneBounds.contains(light.worldPoint))
				addWorldLight(sceneContext, light);
		}
		for (Light light : sceneContext.lights) {
			if (light.worldPoint != null) {
				light.fadeInDuration = 0;
				light.hiddenTemporarily = false;
				light.changedVisibilityAt = -1;
				light.prevPlane = -1;
			}
		}
	}

	private void scanSceneObjectLights(SceneContext sceneContext) {
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

	public void swapSceneLights(SceneContext sceneContext, @Nullable SceneContext oldSceneContext) {
		// Force lights to instantly appear when spawning them as part of a new scene
		for (int i = 0; i < sceneContext.lights.size(); i++)
			sceneContext.lights.get(i).fadeInDuration = 0;

		// Set the plane to an unreachable plane, forcing the first `toggleTemporaryVisibility` call to not fade
		currentPlane = -1;

		if (oldSceneContext == null)
			return;

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

	private int getImpostorId(TileObject tileObject) {
		ObjectComposition def = client.getObjectDefinition(tileObject.getId());
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
			} catch (Exception ex) {
				log.debug("Error getting impostor:", ex);
			}
		}
		return tileObject.getId();
	}

	public void handleObjectSpawn(
		@Nonnull SceneContext sceneContext,
		@Nonnull TileObject tileObject
	) {
		// prevent objects at plane -1 and below from having lights
		if (tileObject.getPlane() < 0)
			return;

		// GameObjects with DynamicObject renderables may be impostors, so handle those in swapScene
		int tileObjectId = tileObject.getId();
		if (tileObject instanceof GameObject &&
			((GameObject) tileObject).getRenderable() instanceof DynamicObject
		) {
			if (client.isClientThread()) {
				tileObjectId = getImpostorId(tileObject);
			} else {
				sceneContext.lightSpawnsToHandleOnClientThread.add(tileObject);
				return;
			}
		}

		for (int i = 0; i < sceneContext.lights.size(); ++i) {
			var light = sceneContext.lights.get(i);
			if (light.tileObject == tileObject) {
				if (light.tileObjectId == tileObjectId)
					return; // Duplicate spawn, probably from spawn event right after scene load

				// Schedule despawning of the old light
				light.markedForRemoval = true;
			}
		}

		spawnLights(sceneContext, tileObject, tileObjectId);
	}

	private void handleObjectDespawn(TileObject tileObject) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		int impostorId = getImpostorId(tileObject);
		removeLightIf(sceneContext, l -> l.tileObject == tileObject && l.tileObjectId == impostorId);
	}

	private void spawnLights(@Nonnull SceneContext sceneContext, TileObject tileObject, int impostorId) {
		int sizeX = 1;
		int sizeY = 1;
		int[] orientations = { 0, 0 };
		int[] offsets = { 0, 0, 0, 0 };

		if (tileObject instanceof GroundObject) {
			var object = (GroundObject) tileObject;
			imposterRenderables[0] = object.getRenderable();
			imposterRenderables[1] = null;
			orientations[0] = HDUtils.getModelOrientation(object.getConfig());
		} else if (tileObject instanceof DecorativeObject) {
			var object = (DecorativeObject) tileObject;
			imposterRenderables[0] = object.getRenderable();
			imposterRenderables[1] = object.getRenderable2();
			int config = object.getConfig();
			orientations[0] = orientations[1] = HDUtils.getModelOrientation(config);
			// WallDecorDiagonalNoOffset -> +180 deg rotation for the 1st renderable
			// WallDecorDiagonalBoth     -> +180 deg rotation for the 2nd renderable
			// HDUtils.getModelOrientation assumes we are working with the 1st renderable, so handle the 2nd here
			var type = ObjectType.fromConfig(config);
			if (type == ObjectType.WallDecorDiagonalBoth)
				orientations[1] = (orientations[1] + 1024) % 2048;
			for (int i = 0; i < 2; i++) {
				switch (type) {
					case WallDecorDiagonalOffset:
					case WallDecorDiagonalNoOffset:
					case WallDecorDiagonalBoth:
						int ori = (2048 - orientations[i]) % 2048;
						// Offset the light by half a tile in the direction of the model
						offsets[2 * i] = COSINE[ori] * 64 >> 16;
						offsets[2 * i + 1] = SINE[ori] * 64 >> 16;
						break;
				}
			}
			offsets[0] += object.getXOffset();
			offsets[1] += object.getYOffset();
			offsets[2] += object.getXOffset2();
			offsets[3] += object.getYOffset2();
		} else if (tileObject instanceof WallObject) {
			var object = (WallObject) tileObject;
			imposterRenderables[0] = object.getRenderable1();
			imposterRenderables[1] = object.getRenderable2();
			orientations[0] = HDUtils.convertWallObjectOrientation(object.getOrientationA());
			orientations[1] = HDUtils.convertWallObjectOrientation(object.getOrientationB());
		} else if (tileObject instanceof GameObject) {
			var object = (GameObject) tileObject;
			sizeX = object.sizeX();
			sizeY = object.sizeY();
			imposterRenderables[0] = object.getRenderable();
			imposterRenderables[1] = null;
			int ori = orientations[0] = HDUtils.getModelOrientation(object.getConfig());
			int offsetDist = 64;
			switch (ObjectType.fromConfig(object.getConfig())) {
				case RoofEdgeDiagonalCorner:
				case RoofDiagonalWithRoofEdge:
					ori += 1024;
					offsetDist = round(offsetDist / sqrt(2));
				case WallDiagonal:
					ori = mod(ori - 256, 2048);
					offsets[0] = SINE[ori] * offsetDist >> 16;
					offsets[1] = COSINE[ori] * offsetDist >> 16;
					break;
			}
		} else {
			log.warn("Unhandled TileObject type: id: {}, hash: {}", tileObject.getId(), tileObject.getHash());
			return;
		}

		List<LightDefinition> lights = OBJECT_LIGHTS.get(impostorId == -1 ? tileObject.getId() : impostorId);

		LocalPoint lp = tileObject.getLocalLocation();
		int plane = tileObject.getPlane();

		// Spawn animation-specific lights for each DynamicObject renderable, and non-animation-based lights
		for (int i = 0; i < 2; i++) {
			var renderable = imposterRenderables[i];
			if (renderable == null)
				continue;

			int lightX = lp.getX() + offsets[2 * i];
			int lightZ = lp.getY() + offsets[2 * i + 1];

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

				// It may be necessary to specify which of the two possible renderables the light should be attached to
				if (def.renderableIndex != -1 && def.renderableIndex != i)
					continue;

				int tileExX = clamp(lp.getSceneX() + sceneContext.sceneOffset, 0, EXTENDED_SCENE_SIZE - 2);
				int tileExY = clamp(lp.getSceneY() + sceneContext.sceneOffset, 0, EXTENDED_SCENE_SIZE - 2);
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
				light.tileObject = tileObject;
				light.tileObjectId = impostorId;
				light.plane = plane;
				light.orientation = orientations[i];
				light.origin[0] = lightX;
				light.origin[1] = (int) tileHeight - light.def.height - 1;
				light.origin[2] = lightZ;
				light.sizeX = sizeX;
				light.sizeY = sizeY;
				sceneContext.lights.add(light);
			}
		}
	}

	private void addWorldLight(SceneContext sceneContext, Light light) {
		assert light.worldPoint != null;
		sceneContext.worldToLocals(light.worldPoint).forEach(local -> {
			int tileExX = local[0] / LOCAL_TILE_SIZE + sceneContext.sceneOffset;
			int tileExY = local[1] / LOCAL_TILE_SIZE + sceneContext.sceneOffset;
			if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
				return;

			var copy = new Light(light.def);
			copy.worldPoint = light.worldPoint;
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

	// TODO: Check whether this is still necessary. If so, we could track varbits/varps within each light
//	@Subscribe
//	public void onVarbitChanged(VarbitChanged event) {
//		var ctx = plugin.getSceneContext();
//		if (!(ctx instanceof LegacySceneContext))
//			return;
//		var sceneContext = (LegacySceneContext) ctx;
//
//		if (plugin.enableDetailedTimers)
//			frameTimer.begin(Timer.IMPOSTOR_TRACKING);
//		// Check if the event is specifically a varbit change first,
//		// since all varbit changes are necessarily also varp changes
//		if (event.getVarbitId() != -1) {
//			for (var tracker : sceneContext.trackedVarbits.get(event.getVarbitId()))
//				trackImpostorChanges(sceneContext, tracker);
//		} else if (event.getVarpId() != -1) {
//			for (var tracker : sceneContext.trackedVarps.get(event.getVarpId()))
//				trackImpostorChanges(sceneContext, tracker);
//		}
//		if (plugin.enableDetailedTimers)
//			frameTimer.end(Timer.IMPOSTOR_TRACKING);
//	}
}
