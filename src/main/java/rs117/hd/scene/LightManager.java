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
import rs117.hd.scene.lights.Alignment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightType;
import rs117.hd.scene.lights.SceneLight;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static java.lang.Math.cos;
import static java.lang.Math.pow;
import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
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
	private EntityHiderPlugin entityHiderPlugin;

	public final ArrayList<SceneLight> WORLD_LIGHTS = new ArrayList<>();
	public final ListMultimap<Integer, Light> NPC_LIGHTS = ArrayListMultimap.create();
	public final ListMultimap<Integer, Light> OBJECT_LIGHTS = ArrayListMultimap.create();
	public final ListMultimap<Integer, Light> PROJECTILE_LIGHTS = ArrayListMultimap.create();
	public final ListMultimap<Integer, Light> GRAPHICS_OBJECT_LIGHTS = ArrayListMultimap.create();

	long lastFrameTime = -1;
	boolean configChanged = false;

	private EntityHiderConfig entityHiderConfig;

	static final float TWO_PI = (float) (2 * Math.PI);

	public void loadConfig(Gson gson, ResourcePath path) {
		try {
			Light[] lights;
			try {
				lights = path.loadJson(gson, Light[].class);
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

			for (Light lightDef : lights) {
				// Map values from [0, 255] in gamma color space to [0, 1] in linear color space
				// Also ensure that each color always has 4 components with sensible defaults
				float[] linearRGBA = { 0, 0, 0, 1 };
				for (int i = 0; i < Math.min(lightDef.color.length, linearRGBA.length); i++)
					linearRGBA[i] = ColorUtils.srgbToLinear(lightDef.color[i] / 255f);
				lightDef.color = linearRGBA;

				if (lightDef.worldX != null && lightDef.worldY != null) {
					SceneLight light = new SceneLight(lightDef);
					light.worldPoint = new WorldPoint(lightDef.worldX, lightDef.worldY, lightDef.plane);
					WORLD_LIGHTS.add(light);
				}
				lightDef.npcIds.forEach(id -> NPC_LIGHTS.put(id, lightDef));
				lightDef.objectIds.forEach(id -> OBJECT_LIGHTS.put(id, lightDef));
				lightDef.projectileIds.forEach(id -> PROJECTILE_LIGHTS.put(id, lightDef));
				lightDef.graphicsObjectIds.forEach(id -> GRAPHICS_OBJECT_LIGHTS.put(id, lightDef));
			}

			log.debug("Loaded {} lights", lights.length);
			configChanged = true;
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

	public void update(SceneContext sceneContext) {
		assert client.isClientThread();

		if (client.getGameState() != GameState.LOGGED_IN)
			return;

		if (configChanged) {
			configChanged = false;
			loadSceneLights(sceneContext);

			// check the NPCs in the scene to make sure they have lights assigned, if applicable,
			// for scenarios in which HD mode or dynamic lights were disabled during NPC spawn
			client.getNpcs().forEach(npc -> addNpcLights(sceneContext, npc));
		}

		long frameTime = System.currentTimeMillis() - lastFrameTime;
		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		int[][][] tileHeights = sceneContext.scene.getTileHeights();

		Iterator<SceneLight> lightIterator = sceneContext.lights.iterator();
		while (lightIterator.hasNext()) {
			SceneLight light = lightIterator.next();
			light.distanceSquared = Integer.MAX_VALUE;

			if (light.projectile != null) {
				if (light.projectile.getRemainingCycles() <= 0) {
					lightIterator.remove();
					sceneContext.projectiles.remove(light.projectile);
					continue;
				}

				light.x = (int) light.projectile.getX();
				light.y = (int) light.projectile.getY();
				light.z = (int) light.projectile.getZ() - light.height;

				light.visible = projectileLightVisible();
			} else if (light.graphicsObject != null) {
				if (light.graphicsObject.finished()) {
					lightIterator.remove();
					continue;
				}

				light.x = light.graphicsObject.getLocation().getX();
				light.y = light.graphicsObject.getLocation().getY();
				light.z = light.graphicsObject.getZ() - light.height;
			}

			if (light.npc != null)
			{
				if (light.npc != client.getCachedNPCs()[light.npc.getIndex()])
				{
					lightIterator.remove();
					continue;
				}

				light.x = light.npc.getLocalLocation().getX();
				light.y = light.npc.getLocalLocation().getY();

				// Offset the light's position based on its Alignment
				if (light.alignment == Alignment.NORTH ||
					light.alignment == Alignment.NORTHEAST ||
					light.alignment == Alignment.NORTHWEST)
					light.y += LOCAL_HALF_TILE_SIZE;
				if (light.alignment == Alignment.SOUTH ||
					light.alignment == Alignment.SOUTHEAST ||
					light.alignment == Alignment.SOUTHWEST)
					light.y -= LOCAL_HALF_TILE_SIZE;
				if (light.alignment == Alignment.EAST ||
					light.alignment == Alignment.SOUTHEAST ||
					light.alignment == Alignment.NORTHEAST)
					light.x += LOCAL_HALF_TILE_SIZE;
				if (light.alignment == Alignment.WEST ||
					light.alignment == Alignment.SOUTHWEST ||
					light.alignment == Alignment.NORTHWEST)
					light.x -= LOCAL_HALF_TILE_SIZE;

				int plane = client.getPlane();
				light.plane = plane;
				int npcTileX = light.npc.getLocalLocation().getSceneX() + SceneUploader.SCENE_OFFSET;
				int npcTileY = light.npc.getLocalLocation().getSceneY() + SceneUploader.SCENE_OFFSET;

				// Some NPCs, such as Crystalline Hunllef in The Gauntlet, sometimes return scene X/Y values far outside the possible range.
				if (npcTileX < EXTENDED_SCENE_SIZE && npcTileY < EXTENDED_SCENE_SIZE && npcTileX >= 0 && npcTileY >= 0) {
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
					light.z = (int) tileHeight - 1 - light.height;

					light.visible = npcLightVisible(light.npc);
				}
				else
				{
					light.visible = false;
				}
			}

			if (light.type == LightType.FLICKER)
			{
				long repeatMs = 60000;
				int offset = light.randomOffset;
				float t = TWO_PI * ((System.currentTimeMillis() + offset) % repeatMs) / repeatMs;

				float flicker = (float) (
					pow(cos(11 * t), 2) +
						pow(cos(17 * t), 4) +
						pow(cos(23 * t), 6) +
						pow(cos(31 * t), 2) +
						pow(cos(71 * t), 2) / 3 +
						pow(cos(151 * t), 2) / 7
				) / 4.335f;

				float maxFlicker = 1f + (light.range / 100f);
				float minFlicker = 1f - (light.range / 100f);

				flicker = minFlicker + (maxFlicker - minFlicker) * flicker;

				light.currentStrength = light.strength * flicker;
				light.currentSize = (int) (light.radius * flicker * 1.5f);
			}
			else if (light.type == LightType.PULSE)
			{
				float duration = light.duration / 1000f;
				float range = light.range / 100f;
				float fullRange = range * 2f;
				float change = (frameTime / 1000f) / duration;
//				change = change % 1.0f;

				light.currentAnimation += change % 1.0f;
				// lock animation to 0-1
				light.currentAnimation = light.currentAnimation % 1.0f;

				float output;

				if (light.currentAnimation > 0.5f)
				{
					// light is shrinking
					output = 1f - (light.currentAnimation - 0.5f) * 2;
				}
				else
				{
					// light is expanding
					output = light.currentAnimation * 2f;
				}

				float multiplier = (1.0f - range) + output * fullRange;

				light.currentSize = (int) (light.radius * multiplier);
				light.currentStrength = light.strength * multiplier;
			}
			else
			{
				light.currentStrength = light.strength;
				light.currentSize = light.radius;
				light.currentColor = light.color;
			}
			// Apply fade-in
			if (light.fadeInDuration > 0) {
				light.currentStrength *= Math.min((float) light.currentFadeIn / (float) light.fadeInDuration, 1.0f);

				light.currentFadeIn += frameTime;
			}

			// Calculate the distance between the player and the light to determine which
			// lights to display based on the 'max dynamic lights' config option
			int distX = sceneContext.cameraFocalPoint[0] - light.x;
			int distY = sceneContext.cameraFocalPoint[1] - light.y;
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

		sceneContext.lights.sort(Comparator.comparingInt(light -> light.distanceSquared));

		lastFrameTime = System.currentTimeMillis();
	}

	private boolean npcLightVisible(NPC npc) {
		try {
			// getModel may throw an exception from vanilla client code
			if (npc.getModel() == null)
				return false;
		} catch (Exception ex) {
			// Vanilla handles exceptions thrown in `DrawCallbacks#draw` gracefully, but here we have to handle them
			return false;
		}

		if (pluginManager.isPluginEnabled(entityHiderPlugin)) {
			boolean isPet = npc.getComposition().isFollower();

			if (client.getFollower() != null && client.getFollower().getIndex() == npc.getIndex())
				return true;

			if (entityHiderConfig.hideNPCs() && !isPet)
				return false;

			if (entityHiderConfig.hidePets() && isPet)
				return false;
		}

		return plugin.configNpcLights;
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

	public void loadSceneLights(SceneContext sceneContext)
	{
		sceneContext.lights.clear();
		sceneContext.projectiles.clear();

		for (SceneLight light : WORLD_LIGHTS)
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
							if (gameObject.getRenderable() instanceof Actor) {
								// rarely these tile game objects are actors with weird properties
								// we skip those
								continue;
							}

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

	public ArrayList<SceneLight> getVisibleLights(int maxLights) {
		SceneContext sceneContext = plugin.getSceneContext();
		ArrayList<SceneLight> visibleLights = new ArrayList<>();

		if (sceneContext == null)
			return visibleLights;

		int maxDistanceSquared = plugin.getDrawDistance() * LOCAL_TILE_SIZE;
		maxDistanceSquared *= maxDistanceSquared;

		for (SceneLight light : sceneContext.lights) {
			if (light.distanceSquared > maxDistanceSquared)
				break;

			if (!light.visible)
				continue;

			if (!light.visibleFromOtherPlanes) {
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

	@Subscribe
	public void onProjectileMoved(ProjectileMoved projectileMoved) {
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		Projectile projectile = projectileMoved.getProjectile();
		if (!sceneContext.projectiles.add(projectile))
			return;

		for (Light lightDef : PROJECTILE_LIGHTS.get(projectile.getId())) {
			SceneLight light = new SceneLight(lightDef);
			light.projectile = projectile;
			light.x = (int) projectile.getX();
			light.y = (int) projectile.getY();
			light.z = (int) projectile.getZ();
			light.plane = projectile.getFloor();
			light.fadeInDuration = 300;

			sceneContext.lights.add(light);
		}
	}

	private void addNpcLights(SceneContext sceneContext, NPC npc)
	{
		if (sceneContext == null)
			return;

		for (Light lightDef : NPC_LIGHTS.get(npc.getId())) {
			// prevent duplicate lights being spawned for the same NPC
			if (sceneContext.lights.stream().anyMatch(x -> x.npc == npc))
				continue;

			SceneLight light = new SceneLight(lightDef);
			light.plane = -1;
			light.npc = npc;
			light.visible = false;

			sceneContext.lights.add(light);
		}
	}

	public void removeNpcLight(SceneContext sceneContext, NPC npc)
	{
		if (sceneContext != null)
			sceneContext.lights.removeIf(light -> light.npc == npc);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned npcSpawned)
	{
		addNpcLights(plugin.getSceneContext(), npcSpawned.getNpc());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged npcChanged)
	{
		SceneContext sceneContext = plugin.getSceneContext();
		removeNpcLight(sceneContext, npcChanged.getNpc());
		addNpcLights(sceneContext, npcChanged.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned npcDespawned)
	{
		removeNpcLight(plugin.getSceneContext(), npcDespawned.getNpc());
	}

	private void addObjectLight(SceneContext sceneContext, TileObject tileObject, int plane)
	{
		addObjectLight(sceneContext, tileObject, plane, 1, 1, -1);
	}

	private void addObjectLight(SceneContext sceneContext, TileObject tileObject, int plane, int sizeX, int sizeY, int orientation) {
		for (Light lightDef : OBJECT_LIGHTS.get(tileObject.getId())) {
			// prevent objects at plane -1 and below from having lights
			if (tileObject.getPlane() <= -1)
				continue;

			// prevent duplicate lights being spawned for the same object
			int hash = tileObjectHash(tileObject);
			boolean isDuplicate = sceneContext.lights.stream()
				.anyMatch(light -> light.object == tileObject || hash == tileObjectHash(light.object));
			if (isDuplicate)
				continue;

			int localPlane = tileObject.getPlane();
			SceneLight light = new SceneLight(lightDef);
			light.plane = localPlane;

			LocalPoint localPoint = tileObject.getLocalLocation();
			int lightX = localPoint.getX();
			int lightY = localPoint.getY();
			int localSizeX = sizeX * LOCAL_TILE_SIZE;
			int localSizeY = sizeY * LOCAL_TILE_SIZE;

			if (orientation != -1 && light.alignment != Alignment.CENTER) {
				float radius = localSizeX / 2f;
				if (!light.alignment.radial)
					radius = (float) Math.sqrt(localSizeX * localSizeX + localSizeX * localSizeX) / 2;

				if (!light.alignment.relative)
					orientation = 0;
				orientation += light.alignment.orientation;
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
			light.z = (int) tileHeight - light.height - 1;
			light.object = tileObject;

			sceneContext.lights.add(light);
		}
	}

	private void removeObjectLight(TileObject tileObject)
	{
		SceneContext sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;

		LocalPoint localLocation = tileObject.getLocalLocation();
		sceneContext.lights.removeIf(light ->
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
		for (Light lightDef : GRAPHICS_OBJECT_LIGHTS.get(graphicsObject.getId())) {
			SceneLight light = new SceneLight(lightDef);
			light.graphicsObject = graphicsObject;
			light.x = graphicsObject.getLocation().getX();
			light.y = graphicsObject.getLocation().getY();
			light.z = graphicsObject.getZ();
			light.plane = graphicsObject.getLevel();
			light.fadeInDuration = 300;

			sceneContext.lights.add(light);
		}
	}

	private int tileObjectHash(@Nullable TileObject tileObject)
	{
		if (tileObject == null)
			return 0;

		LocalPoint local = tileObject.getLocalLocation();
		int hash = local.getX();
		hash = hash * 31 + local.getY();
		hash = hash * 31 + tileObject.getPlane();
		hash = hash * 31 + tileObject.getId();
		return hash;
	}

	private void updateWorldLightPosition(SceneContext sceneContext, SceneLight light)
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
			light.z = sceneContext.scene.getTileHeights()[light.plane][tileExX][tileExY] - light.height - 1;
		}

		if (light.alignment == Alignment.NORTH || light.alignment == Alignment.NORTHEAST || light.alignment == Alignment.NORTHWEST)
			light.y += LOCAL_HALF_TILE_SIZE;
		if (light.alignment == Alignment.EAST || light.alignment == Alignment.NORTHEAST || light.alignment == Alignment.SOUTHEAST)
			light.x += LOCAL_HALF_TILE_SIZE;
		if (light.alignment == Alignment.SOUTH || light.alignment == Alignment.SOUTHEAST || light.alignment == Alignment.SOUTHWEST)
			light.y -= LOCAL_HALF_TILE_SIZE;
		if (light.alignment == Alignment.WEST || light.alignment == Alignment.NORTHWEST || light.alignment == Alignment.SOUTHWEST)
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
