/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

import com.google.common.base.Stopwatch;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.WaterType;
import rs117.hd.data.materials.GroundMaterial;
import rs117.hd.data.materials.Overlay;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.Underlay;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Random;

@SuppressWarnings("UnnecessaryLocalVariable")
@Singleton
@Slf4j
public
class SceneUploader
{
	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	public ProceduralGenerator proceduralGenerator;

	@Inject
	private ModelPusher modelPusher;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	public int sceneId = new Random().nextInt();
	private int offset;
	private int uvOffset;

	private final float[] UP_NORMAL = { 0, -1, 0 };

	public void upload(Scene scene, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer)
	{
		Stopwatch stopwatch = Stopwatch.createStarted();

		++sceneId;
		offset = 0;
		uvOffset = 0;
		vertexBuffer.clear();
		uvBuffer.clear();
		normalBuffer.clear();

		for (int z = 0; z < Constants.MAX_Z; ++z)
		{
			for (int x = 0; x < Constants.SCENE_SIZE; ++x)
			{
				for (int y = 0; y < Constants.SCENE_SIZE; ++y)
				{
					Tile tile = scene.getTiles()[z][x][y];
					if (tile != null)
					{
						upload(tile, vertexBuffer, uvBuffer, normalBuffer);
					}
				}
			}
		}

		stopwatch.stop();
		log.debug("Scene upload time: {}", stopwatch);
	}

	private void uploadModel(long hash, Model model, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer, int tileZ, int tileX, int tileY, ObjectType objectType)
	{
		if (model.getSceneId() == sceneId)
		{
			return; // model has already been uploaded
		}

		ModelOverride modelOverride = modelOverrideManager.getOverride(hash);

		byte skipObject = 0b00;
		if (client.getBaseX() + tileX == 2558 && client.getBaseY() + tileY >= 3249 && client.getBaseY() + tileY <= 3252)
		{
			// fix for water by khazard spirit tree
			// marks object to never be drawn
			skipObject = 0b11;
		}

		// pack a bit into bufferoffset that we can use later to hide
		// some low-importance objects based on Level of Detail setting
		model.setBufferOffset(offset << 2 | skipObject);
		if (model.getFaceTextures() != null || (plugin.configModelTextures && modelOverride.baseMaterial != Material.NONE))
		{
			model.setUvBufferOffset(uvOffset);
		}
		else
		{
			model.setUvBufferOffset(-1);
		}
		model.setSceneId(sceneId);

		final int[] lengths = modelPusher.pushModel(hash, model, vertexBuffer, uvBuffer, normalBuffer, tileX, tileY, tileZ, modelOverride, objectType, true);

		offset += lengths[0];
		uvOffset += lengths[1];
	}

	private void upload(Tile tile, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer)
	{
		Tile bridge = tile.getBridge();
		if (bridge != null)
		{
			upload(bridge, vertexBuffer, uvBuffer, normalBuffer);
		}

		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileZ = tile.getRenderLevel();

		SceneTilePaint sceneTilePaint = tile.getSceneTilePaint();
		if (sceneTilePaint != null)
		{
			int[] uploadedTilePaintData = upload(
				tile, sceneTilePaint,
				tileZ, tileX, tileY,
				vertexBuffer, uvBuffer, normalBuffer
			);

			final int bufferLength = uploadedTilePaintData[0];
			final int uvBufferLength = uploadedTilePaintData[1];
			final int underwaterTerrain = uploadedTilePaintData[2];
			// pack a boolean into the buffer length of tiles so we can tell
			// which tiles have procedurally generated underwater terrain.
			// shift the bufferLength to make space for the boolean:
			int packedBufferLength = bufferLength << 1 | underwaterTerrain;
			sceneTilePaint.setBufferOffset(offset);
			sceneTilePaint.setUvBufferOffset(uvBufferLength > 0 ? uvOffset : -1);
			sceneTilePaint.setBufferLen(packedBufferLength);
			offset += bufferLength;
			uvOffset += uvBufferLength;
		}

		SceneTileModel sceneTileModel = tile.getSceneTileModel();
		if (sceneTileModel != null)
		{
			int[] uploadedTileModelData = upload(
				tile, sceneTileModel,
				tileZ, tileX, tileY,
				vertexBuffer, uvBuffer, normalBuffer
			);

			final int bufferLength = uploadedTileModelData[0];
			final int uvBufferLength = uploadedTileModelData[1];
			final int underwaterTerrain = uploadedTileModelData[2];
			// pack a boolean into the buffer length of tiles so we can tell
			// which tiles have procedurally-generated underwater terrain
			int packedBufferLength = bufferLength << 1 | underwaterTerrain;
			sceneTileModel.setBufferOffset(offset);
			sceneTileModel.setUvBufferOffset(uvBufferLength > 0 ? uvOffset : -1);
			sceneTileModel.setBufferLen(packedBufferLength);
			offset += bufferLength;
			uvOffset += uvBufferLength;
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject != null)
		{
			Renderable renderable1 = wallObject.getRenderable1();
			if (renderable1 instanceof Model)
			{
				uploadModel(wallObject.getHash(), (Model) renderable1, vertexBuffer, uvBuffer, normalBuffer, tileZ, tileX,
					tileY, ObjectType.WALL_OBJECT);
			}

			Renderable renderable2 = wallObject.getRenderable2();
			if (renderable2 instanceof Model)
			{
				uploadModel(wallObject.getHash(), (Model) renderable2, vertexBuffer, uvBuffer, normalBuffer, tileZ, tileX,
					tileY, ObjectType.WALL_OBJECT);
			}
		}

		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null)
		{
			Renderable renderable = groundObject.getRenderable();
			if (renderable instanceof Model)
			{
				uploadModel(groundObject.getHash(), (Model) renderable, vertexBuffer, uvBuffer, normalBuffer, tileZ, tileX,
					tileY, ObjectType.GROUND_OBJECT);
			}
		}

		DecorativeObject decorativeObject = tile.getDecorativeObject();
		if (decorativeObject != null)
		{

			Renderable renderable = decorativeObject.getRenderable();
			if (renderable instanceof Model)
			{
				uploadModel(decorativeObject.getHash(), (Model) renderable, vertexBuffer, uvBuffer, normalBuffer, tileZ, tileX,
					tileY, ObjectType.DECORATIVE_OBJECT);
			}

			Renderable renderable2 = decorativeObject.getRenderable2();
			if (renderable2 instanceof Model)
			{
				uploadModel(decorativeObject.getHash(), (Model) renderable2, vertexBuffer, uvBuffer, normalBuffer, tileZ, tileX,
					tileY, ObjectType.DECORATIVE_OBJECT);
			}
		}

		GameObject[] gameObjects = tile.getGameObjects();
		for (GameObject gameObject : gameObjects)
		{
			if (gameObject == null)
			{
				continue;
			}

			Renderable renderable = gameObject.getRenderable();
			if (renderable instanceof Model)
			{
				uploadModel(gameObject.getHash(), (Model) gameObject.getRenderable(), vertexBuffer, uvBuffer, normalBuffer, tileZ, tileX,
					tileY, ObjectType.GAME_OBJECT);
			}
		}
	}

	int[] upload(Tile tile, SceneTilePaint sceneTilePaint, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer)
	{
		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int[] bufferLengths;

		bufferLengths = uploadHDTilePaintSurface(tile, sceneTilePaint, tileZ, tileX, tileY,
			vertexBuffer, uvBuffer, normalBuffer);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		bufferLengths = uploadHDTilePaintUnderwater(tile, sceneTilePaint, tileZ, tileX, tileY,
			vertexBuffer, uvBuffer, normalBuffer);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	int[] uploadHDTilePaintSurface(Tile tile, SceneTilePaint sceneTilePaint, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer)
	{
		final int localX = 0;
		final int localY = 0;

		int baseX = client.getBaseX();
		int baseY = client.getBaseY();

		final int[][][] tileHeights = client.getTileHeights();
		int swHeight = tileHeights[tileZ][tileX][tileY];
		int seHeight = tileHeights[tileZ][tileX + 1][tileY];
		int neHeight = tileHeights[tileZ][tileX + 1][tileY + 1];
		int nwHeight = tileHeights[tileZ][tileX][tileY + 1];

		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int localSwVertexX = localX;
		int localSwVertexY = localY;
		int localSeVertexX = localX + Perspective.LOCAL_TILE_SIZE;
		int localSeVertexY = localY;
		int localNwVertexX = localX;
		int localNwVertexY = localY + Perspective.LOCAL_TILE_SIZE;
		int localNeVertexX = localX + Perspective.LOCAL_TILE_SIZE;
		int localNeVertexY = localY + Perspective.LOCAL_TILE_SIZE;

		int[] vertexKeys = proceduralGenerator.tileVertexKeys(tile);
		int swVertexKey = vertexKeys[0];
		int seVertexKey = vertexKeys[1];
		int nwVertexKey = vertexKeys[2];
		int neVertexKey = vertexKeys[3];

		// Ignore certain tiles that aren't supposed to be visible,
		// but which we can still make a height-adjusted version of for underwater
		if (sceneTilePaint.getNeColor() != 12345678)
		{
			int swColor = sceneTilePaint.getSwColor();
			int seColor = sceneTilePaint.getSeColor();
			int neColor = sceneTilePaint.getNeColor();
			int nwColor = sceneTilePaint.getNwColor();

			int tileTexture = sceneTilePaint.getTexture();

			boolean neVertexIsOverlay = false;
			boolean nwVertexIsOverlay = false;
			boolean seVertexIsOverlay = false;
			boolean swVertexIsOverlay = false;

			Material swMaterial = Material.NONE;
			Material seMaterial = Material.NONE;
			Material neMaterial = Material.NONE;
			Material nwMaterial = Material.NONE;

			float[] swNormals = UP_NORMAL;
			float[] seNormals = UP_NORMAL;
			float[] neNormals = UP_NORMAL;
			float[] nwNormals = UP_NORMAL;

			WaterType waterType = proceduralGenerator.tileWaterType(tile, sceneTilePaint);
			if (waterType == WaterType.NONE)
			{
				swMaterial = Material.getTexture(tileTexture);
				seMaterial = Material.getTexture(tileTexture);
				neMaterial = Material.getTexture(tileTexture);
				nwMaterial = Material.getTexture(tileTexture);

				swNormals = proceduralGenerator.vertexTerrainNormals.getOrDefault(swVertexKey, swNormals);
				seNormals = proceduralGenerator.vertexTerrainNormals.getOrDefault(seVertexKey, seNormals);
				neNormals = proceduralGenerator.vertexTerrainNormals.getOrDefault(neVertexKey, neNormals);
				nwNormals = proceduralGenerator.vertexTerrainNormals.getOrDefault(nwVertexKey, nwNormals);

				if (proceduralGenerator.vertexIsWater.containsKey(swVertexKey) && proceduralGenerator.vertexIsLand.containsKey(swVertexKey))
					swColor = 0;
				if (proceduralGenerator.vertexIsWater.containsKey(seVertexKey) && proceduralGenerator.vertexIsLand.containsKey(seVertexKey))
					seColor = 0;
				if (proceduralGenerator.vertexIsWater.containsKey(nwVertexKey) && proceduralGenerator.vertexIsLand.containsKey(nwVertexKey))
					nwColor = 0;
				if (proceduralGenerator.vertexIsWater.containsKey(neVertexKey) && proceduralGenerator.vertexIsLand.containsKey(neVertexKey))
					neColor = 0;

				if (plugin.configGroundBlending && !proceduralGenerator.useDefaultColor(tile) && sceneTilePaint.getTexture() == -1)
				{
					// get the vertices' colors and textures from hashmaps

					swColor = proceduralGenerator.vertexTerrainColor.getOrDefault(swVertexKey, swColor);
					seColor = proceduralGenerator.vertexTerrainColor.getOrDefault(seVertexKey, seColor);
					neColor = proceduralGenerator.vertexTerrainColor.getOrDefault(neVertexKey, neColor);
					nwColor = proceduralGenerator.vertexTerrainColor.getOrDefault(nwVertexKey, nwColor);

					if (plugin.configGroundTextures)
					{
						swMaterial = proceduralGenerator.vertexTerrainTexture.getOrDefault(swVertexKey, swMaterial);
						seMaterial = proceduralGenerator.vertexTerrainTexture.getOrDefault(seVertexKey, seMaterial);
						neMaterial = proceduralGenerator.vertexTerrainTexture.getOrDefault(neVertexKey, neMaterial);
						nwMaterial = proceduralGenerator.vertexTerrainTexture.getOrDefault(nwVertexKey, nwMaterial);
					}
				}
				else if (plugin.configGroundTextures && !shouldSkipTile(baseX + tileX, baseY + tileY))
				{
					GroundMaterial groundMaterial;

					Overlay overlay = Overlay.getOverlay((int) client.getScene().getOverlayIds()[tileZ][tileX][tileY], tile, client, config);
					if (overlay != Overlay.NONE)
					{
						groundMaterial = overlay.groundMaterial;

						swColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(swColor)));
						seColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(seColor)));
						nwColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(nwColor)));
						neColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(neColor)));
					}
					else
					{
						Underlay underlay = Underlay.getUnderlay((int) client.getScene().getUnderlayIds()[tileZ][tileX][tileY], tile, client, config);
						groundMaterial = underlay.groundMaterial;

						swColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(swColor)));
						seColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(seColor)));
						nwColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(nwColor)));
						neColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(neColor)));
					}

					swMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY);
					seMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY);
					nwMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY + 1);
					neMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY + 1);
				}
				else if (plugin.configWinterTheme)
				{
					Overlay overlay = Overlay.getOverlay((int) client.getScene().getOverlayIds()[tileZ][tileX][tileY], tile, client, config);
					if (overlay != Overlay.NONE)
					{
						swColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(swColor)));
						seColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(seColor)));
						nwColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(nwColor)));
						neColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(neColor)));
					}
					else
					{
						Underlay underlay = Underlay.getUnderlay((int) client.getScene().getUnderlayIds()[tileZ][tileX][tileY], tile, client, config);
						swColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(swColor)));
						seColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(seColor)));
						nwColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(nwColor)));
						neColor = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(neColor)));
					}
				}
			}
			else
			{
				// set colors for the shoreline to create a foam effect in the water shader

				swColor = seColor = nwColor = neColor = 127;

				if (proceduralGenerator.vertexIsWater.containsKey(swVertexKey) && proceduralGenerator.vertexIsLand.containsKey(swVertexKey))
				{
					swColor = 0;
				}
				if (proceduralGenerator.vertexIsWater.containsKey(seVertexKey) && proceduralGenerator.vertexIsLand.containsKey(seVertexKey))
				{
					seColor = 0;
				}
				if (proceduralGenerator.vertexIsWater.containsKey(nwVertexKey) && proceduralGenerator.vertexIsLand.containsKey(nwVertexKey))
				{
					nwColor = 0;
				}
				if (proceduralGenerator.vertexIsWater.containsKey(neVertexKey) && proceduralGenerator.vertexIsLand.containsKey(neVertexKey))
				{
					neColor = 0;
				}
			}

			if (proceduralGenerator.vertexIsOverlay.containsKey(neVertexKey) && proceduralGenerator.vertexIsUnderlay.containsKey(neVertexKey))
				neVertexIsOverlay = true;
			if (proceduralGenerator.vertexIsOverlay.containsKey(nwVertexKey) && proceduralGenerator.vertexIsUnderlay.containsKey(nwVertexKey))
				nwVertexIsOverlay = true;
			if (proceduralGenerator.vertexIsOverlay.containsKey(seVertexKey) && proceduralGenerator.vertexIsUnderlay.containsKey(seVertexKey))
				seVertexIsOverlay = true;
			if (proceduralGenerator.vertexIsOverlay.containsKey(swVertexKey) && proceduralGenerator.vertexIsUnderlay.containsKey(swVertexKey))
				swVertexIsOverlay = true;

			int swTerrainData = packTerrainData(0, waterType, tileZ);
			int seTerrainData = packTerrainData(0, waterType, tileZ);
			int nwTerrainData = packTerrainData(0, waterType, tileZ);
			int neTerrainData = packTerrainData(0, waterType, tileZ);

			normalBuffer.ensureCapacity(24);
			normalBuffer.put(neNormals[0], neNormals[2], neNormals[1], neTerrainData);
			normalBuffer.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);
			normalBuffer.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);

			normalBuffer.put(swNormals[0], swNormals[2], swNormals[1], swTerrainData);
			normalBuffer.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);
			normalBuffer.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);

			vertexBuffer.ensureCapacity(24);
			vertexBuffer.put(localNeVertexX, neHeight, localNeVertexY, neColor);
			vertexBuffer.put(localNwVertexX, nwHeight, localNwVertexY, nwColor);
			vertexBuffer.put(localSeVertexX, seHeight, localSeVertexY, seColor);

			vertexBuffer.put(localSwVertexX, swHeight, localSwVertexY, swColor);
			vertexBuffer.put(localSeVertexX, seHeight, localSeVertexY, seColor);
			vertexBuffer.put(localNwVertexX, nwHeight, localNwVertexY, nwColor);

			bufferLength += 6;

			int packedMaterialDataSW = modelPusher.packMaterialData(swMaterial, swVertexIsOverlay);
			int packedMaterialDataSE = modelPusher.packMaterialData(seMaterial, seVertexIsOverlay);
			int packedMaterialDataNW = modelPusher.packMaterialData(nwMaterial, nwVertexIsOverlay);
			int packedMaterialDataNE = modelPusher.packMaterialData(neMaterial, neVertexIsOverlay);

			uvBuffer.ensureCapacity(24);
			uvBuffer.put(packedMaterialDataNE, 1.0f, 1.0f, 0f);
			uvBuffer.put(packedMaterialDataNW, 0.0f, 1.0f, 0f);
			uvBuffer.put(packedMaterialDataSE, 1.0f, 0.0f, 0f);

			uvBuffer.put(packedMaterialDataSW, 0.0f, 0.0f, 0f);
			uvBuffer.put(packedMaterialDataSE, 1.0f, 0.0f, 0f);
			uvBuffer.put(packedMaterialDataNW, 0.0f, 1.0f, 0f);

			uvBufferLength += 6;
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	int[] uploadHDTilePaintUnderwater(Tile tile, SceneTilePaint sceneTilePaint, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer)
	{

		int baseX = client.getBaseX();
		int baseY = client.getBaseY();

		if (baseX >= 2816 && baseX <= 2970 && baseY <= 5375 && baseY >= 5220)
		{
			// fix for God Wars Dungeon's water rendering over zamorak bridge
			return new int[]{0, 0, 0};
		}

		final int[][][] tileHeights = client.getTileHeights();
		int swHeight = tileHeights[tileZ][tileX][tileY];
		int seHeight = tileHeights[tileZ][tileX + 1][tileY];
		int neHeight = tileHeights[tileZ][tileX + 1][tileY + 1];
		int nwHeight = tileHeights[tileZ][tileX][tileY + 1];

		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int localSwVertexX = 0;
		int localSwVertexY = 0;
		int localSeVertexX = Perspective.LOCAL_TILE_SIZE;
		int localSeVertexY = 0;
		int localNwVertexX = 0;
		int localNwVertexY = Perspective.LOCAL_TILE_SIZE;
		int localNeVertexX = Perspective.LOCAL_TILE_SIZE;
		int localNeVertexY = Perspective.LOCAL_TILE_SIZE;

		int[] vertexKeys = proceduralGenerator.tileVertexKeys(tile);
		int swVertexKey = vertexKeys[0];
		int seVertexKey = vertexKeys[1];
		int nwVertexKey = vertexKeys[2];
		int neVertexKey = vertexKeys[3];

		if (proceduralGenerator.tileIsWater[tileZ][tileX][tileY])
		{
			// underwater terrain

			underwaterTerrain = 1;

			int swColor = 6676;
			int seColor = 6676;
			int neColor = 6676;
			int nwColor = 6676;

			int swDepth = proceduralGenerator.vertexUnderwaterDepth.getOrDefault(swVertexKey, 0);
			int seDepth = proceduralGenerator.vertexUnderwaterDepth.getOrDefault(seVertexKey, 0);
			int nwDepth = proceduralGenerator.vertexUnderwaterDepth.getOrDefault(nwVertexKey, 0);
			int neDepth = proceduralGenerator.vertexUnderwaterDepth.getOrDefault(neVertexKey, 0);

			float[] swNormals = proceduralGenerator.vertexTerrainNormals.getOrDefault(swVertexKey, UP_NORMAL);
			float[] seNormals = proceduralGenerator.vertexTerrainNormals.getOrDefault(seVertexKey, UP_NORMAL);
			float[] nwNormals = proceduralGenerator.vertexTerrainNormals.getOrDefault(nwVertexKey, UP_NORMAL);
			float[] neNormals = proceduralGenerator.vertexTerrainNormals.getOrDefault(neVertexKey, UP_NORMAL);

			Material swMaterial = Material.NONE;
			Material seMaterial = Material.NONE;
			Material nwMaterial = Material.NONE;
			Material neMaterial = Material.NONE;

			if (plugin.configGroundTextures)
			{
				GroundMaterial groundMaterial = GroundMaterial.UNDERWATER_GENERIC;

				swMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY);
				seMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY);
				nwMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY + 1);
				neMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY + 1);
			}

			WaterType waterType = proceduralGenerator.tileWaterType(tile, sceneTilePaint);

			int swTerrainData = packTerrainData(Math.max(1, swDepth), waterType, tileZ);
			int seTerrainData = packTerrainData(Math.max(1, seDepth), waterType, tileZ);
			int nwTerrainData = packTerrainData(Math.max(1, nwDepth), waterType, tileZ);
			int neTerrainData = packTerrainData(Math.max(1, neDepth), waterType, tileZ);

			normalBuffer.ensureCapacity(24);
			normalBuffer.put(neNormals[0], neNormals[2], neNormals[1], neTerrainData);
			normalBuffer.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);
			normalBuffer.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);

			normalBuffer.put(swNormals[0], swNormals[2], swNormals[1], swTerrainData);
			normalBuffer.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);
			normalBuffer.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);

			vertexBuffer.ensureCapacity(24);
			vertexBuffer.put(localNeVertexX, neHeight + neDepth, localNeVertexY, neColor);
			vertexBuffer.put(localNwVertexX, nwHeight + nwDepth, localNwVertexY, nwColor);
			vertexBuffer.put(localSeVertexX, seHeight + seDepth, localSeVertexY, seColor);

			vertexBuffer.put(localSwVertexX, swHeight + swDepth, localSwVertexY, swColor);
			vertexBuffer.put(localSeVertexX, seHeight + seDepth, localSeVertexY, seColor);
			vertexBuffer.put(localNwVertexX, nwHeight + nwDepth, localNwVertexY, nwColor);

			bufferLength += 6;

			int packedMaterialDataSW = modelPusher.packMaterialData(swMaterial, false);
			int packedMaterialDataSE = modelPusher.packMaterialData(seMaterial, false);
			int packedMaterialDataNW = modelPusher.packMaterialData(nwMaterial, false);
			int packedMaterialDataNE = modelPusher.packMaterialData(neMaterial, false);

			uvBuffer.ensureCapacity(24);
			uvBuffer.put(packedMaterialDataNE, 1.0f, 1.0f, 0f);
			uvBuffer.put(packedMaterialDataNW, 0.0f, 1.0f, 0f);
			uvBuffer.put(packedMaterialDataSE, 1.0f, 0.0f, 0f);

			uvBuffer.put(packedMaterialDataSW, 0.0f, 0.0f, 0f);
			uvBuffer.put(packedMaterialDataSE, 1.0f, 0.0f, 0f);
			uvBuffer.put(packedMaterialDataNW, 0.0f, 1.0f, 0f);

			uvBufferLength += 6;
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	int[] upload(Tile tile, SceneTileModel sceneTileModel, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer)
	{
		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int[] bufferLengths;

		bufferLengths = uploadHDTileModelSurface(tile, sceneTileModel, tileZ, tileX, tileY, vertexBuffer, uvBuffer, normalBuffer);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		bufferLengths = uploadHDTileModelUnderwater(tile, sceneTileModel, tileZ, tileX, tileY, vertexBuffer, uvBuffer, normalBuffer);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	int[] uploadHDTileModelSurface(Tile tile, SceneTileModel sceneTileModel, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer)
	{
		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		if (proceduralGenerator.skipTile[tileZ][tileX][tileY])
		{
			return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
		}

		final int[] faceColorA = sceneTileModel.getTriangleColorA();
		final int[] faceColorB = sceneTileModel.getTriangleColorB();
		final int[] faceColorC = sceneTileModel.getTriangleColorC();

		final int[] faceTextures = sceneTileModel.getTriangleTextureId();

		final int faceCount = sceneTileModel.getFaceX().length;

		int baseX = client.getBaseX();
		int baseY = client.getBaseY();

		for (int face = 0; face < faceCount; ++face)
		{
			int colorA = faceColorA[face];
			int colorB = faceColorB[face];
			int colorC = faceColorC[face];

			if (colorA == 12345678)
			{
				continue;
			}

			int[][] localVertices = proceduralGenerator.faceLocalVertices(tile, face);

			int[] vertexKeys = proceduralGenerator.faceVertexKeys(tile, face);
			int vertexKeyA = vertexKeys[0];
			int vertexKeyB = vertexKeys[1];
			int vertexKeyC = vertexKeys[2];

			boolean vertexAIsOverlay = false;
			boolean vertexBIsOverlay = false;
			boolean vertexCIsOverlay = false;

			Material materialA = Material.NONE;
			Material materialB = Material.NONE;
			Material materialC = Material.NONE;

			float[] normalsA = UP_NORMAL;
			float[] normalsB = UP_NORMAL;
			float[] normalsC = UP_NORMAL;

			WaterType waterType = proceduralGenerator.faceWaterType(tile, face, sceneTileModel);
			if (waterType == WaterType.NONE)
			{
				if (faceTextures != null)
				{
					materialA = Material.getTexture(faceTextures[face]);
					materialB = Material.getTexture(faceTextures[face]);
					materialC = Material.getTexture(faceTextures[face]);
				}

				normalsA = proceduralGenerator.vertexTerrainNormals.getOrDefault(vertexKeyA, normalsA);
				normalsB = proceduralGenerator.vertexTerrainNormals.getOrDefault(vertexKeyB, normalsB);
				normalsC = proceduralGenerator.vertexTerrainNormals.getOrDefault(vertexKeyC, normalsC);

				if (plugin.configGroundBlending && !(proceduralGenerator.isOverlayFace(tile, face) && proceduralGenerator.useDefaultColor(tile)) && materialA == Material.NONE)
				{
					// get the vertices' colors and textures from hashmaps

					colorA = proceduralGenerator.vertexTerrainColor.getOrDefault(vertexKeyA, colorA);
					colorB = proceduralGenerator.vertexTerrainColor.getOrDefault(vertexKeyB, colorB);
					colorC = proceduralGenerator.vertexTerrainColor.getOrDefault(vertexKeyC, colorC);

					if (plugin.configGroundTextures)
					{
						materialA = proceduralGenerator.vertexTerrainTexture.getOrDefault(vertexKeyA, materialA);
						materialB = proceduralGenerator.vertexTerrainTexture.getOrDefault(vertexKeyB, materialB);
						materialC = proceduralGenerator.vertexTerrainTexture.getOrDefault(vertexKeyC, materialC);
					}
				}
				else if (plugin.configGroundTextures)
				{
					// ground textures without blending

					GroundMaterial groundMaterial;

					if (proceduralGenerator.isOverlayFace(tile, face))
					{
						Overlay overlay = Overlay.getOverlay((int) client.getScene().getOverlayIds()[tileZ][tileX][tileY], tile, client, config);
						groundMaterial = overlay.groundMaterial;

						colorA = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(colorA)));
						colorB = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(colorB)));
						colorC = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(colorC)));
					}
					else
					{
						Underlay underlay = Underlay.getUnderlay((int) client.getScene().getUnderlayIds()[tileZ][tileX][tileY], tile, client, config);
						groundMaterial = underlay.groundMaterial;

						colorA = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(colorA)));
						colorB = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(colorB)));
						colorC = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(colorC)));
					}

					materialA = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + (int) Math.floor((float) localVertices[0][0] / Perspective.LOCAL_TILE_SIZE), baseY + tileY + (int) Math.floor((float) localVertices[0][1] / Perspective.LOCAL_TILE_SIZE));
					materialB = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + (int) Math.floor((float) localVertices[1][0] / Perspective.LOCAL_TILE_SIZE), baseY + tileY + (int) Math.floor((float) localVertices[1][1] / Perspective.LOCAL_TILE_SIZE));
					materialC = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + (int) Math.floor((float) localVertices[2][0] / Perspective.LOCAL_TILE_SIZE), baseY + tileY + (int) Math.floor((float) localVertices[2][1] / Perspective.LOCAL_TILE_SIZE));
				}
				else if (plugin.configWinterTheme)
				{
					if (proceduralGenerator.isOverlayFace(tile, face))
					{
						Overlay overlay = Overlay.getOverlay((int) client.getScene().getOverlayIds()[tileZ][tileX][tileY], tile, client, config);

						colorA = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(colorA)));
						colorB = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(colorB)));
						colorC = HDUtils.colorHSLToInt(proceduralGenerator.recolorOverlay(overlay, HDUtils.colorIntToHSL(colorC)));
					}
					else
					{
						Underlay underlay = Underlay.getUnderlay((int) client.getScene().getUnderlayIds()[tileZ][tileX][tileY], tile, client, config);

						colorA = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(colorA)));
						colorB = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(colorB)));
						colorC = HDUtils.colorHSLToInt(proceduralGenerator.recolorUnderlay(underlay, HDUtils.colorIntToHSL(colorC)));
					}
				}
			}
			else
			{
				// set colors for the shoreline to create a foam effect in the water shader
				colorA = colorB = colorC = 127;
				if (proceduralGenerator.vertexIsWater.containsKey(vertexKeyA) && proceduralGenerator.vertexIsLand.containsKey(vertexKeyA))
				{
					colorA = 0;
				}
				if (proceduralGenerator.vertexIsWater.containsKey(vertexKeyB) && proceduralGenerator.vertexIsLand.containsKey(vertexKeyB))
				{
					colorB = 0;
				}
				if (proceduralGenerator.vertexIsWater.containsKey(vertexKeyC) && proceduralGenerator.vertexIsLand.containsKey(vertexKeyC))
				{
					colorC = 0;
				}
			}

			if (proceduralGenerator.vertexIsOverlay.containsKey(vertexKeyA) && proceduralGenerator.vertexIsUnderlay.containsKey(vertexKeyA))
			{
				vertexAIsOverlay = true;
			}
			if (proceduralGenerator.vertexIsOverlay.containsKey(vertexKeyB) && proceduralGenerator.vertexIsUnderlay.containsKey(vertexKeyB))
			{
				vertexBIsOverlay = true;
			}
			if (proceduralGenerator.vertexIsOverlay.containsKey(vertexKeyC) && proceduralGenerator.vertexIsUnderlay.containsKey(vertexKeyC))
			{
				vertexCIsOverlay = true;
			}

			int aTerrainData = packTerrainData(0, waterType, tileZ);
			int bTerrainData = packTerrainData(0, waterType, tileZ);
			int cTerrainData = packTerrainData(0, waterType, tileZ);

			normalBuffer.ensureCapacity(12);
			normalBuffer.put(normalsA[0], normalsA[2], normalsA[1], aTerrainData);
			normalBuffer.put(normalsB[0], normalsB[2], normalsB[1], bTerrainData);
			normalBuffer.put(normalsC[0], normalsC[2], normalsC[1], cTerrainData);

			vertexBuffer.ensureCapacity(12);
			vertexBuffer.put(localVertices[0][0], localVertices[0][2], localVertices[0][1], colorA);
			vertexBuffer.put(localVertices[1][0], localVertices[1][2], localVertices[1][1], colorB);
			vertexBuffer.put(localVertices[2][0], localVertices[2][2], localVertices[2][1], colorC);

			bufferLength += 3;

			int packedMaterialDataA = modelPusher.packMaterialData(materialA, vertexAIsOverlay);
			int packedMaterialDataB = modelPusher.packMaterialData(materialB, vertexBIsOverlay);
			int packedMaterialDataC = modelPusher.packMaterialData(materialC, vertexCIsOverlay);

			uvBuffer.ensureCapacity(12);
			uvBuffer.put(packedMaterialDataA, localVertices[0][0] / 128f, localVertices[0][1] / 128f, 0f);
			uvBuffer.put(packedMaterialDataB, localVertices[1][0] / 128f, localVertices[1][1] / 128f, 0f);
			uvBuffer.put(packedMaterialDataC, localVertices[2][0] / 128f, localVertices[2][1] / 128f, 0f);

			uvBufferLength += 3;
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	int[] uploadHDTileModelUnderwater(Tile tile, SceneTileModel sceneTileModel, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer)
	{
		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		if (proceduralGenerator.skipTile[tileZ][tileX][tileY])
		{
			return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
		}

		final int[] faceColorA = sceneTileModel.getTriangleColorA();
		final int faceCount = sceneTileModel.getFaceX().length;

		int baseX = client.getBaseX();
		int baseY = client.getBaseY();

		if (baseX >= 2816 && baseX <= 2970 && baseY <= 5375 && baseY >= 5220)
		{
			// fix for God Wars Dungeon's water rendering over zamorak bridge
			return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
		}

		if (proceduralGenerator.tileIsWater[tileZ][tileX][tileY])
		{
			underwaterTerrain = 1;

			// underwater terrain
			for (int face = 0; face < faceCount; ++face)
			{
				int colorA = 6676;
				int colorB = 6676;
				int colorC = 6676;

				if (faceColorA[face] == 12345678)
				{
					continue;
				}

				int[][] localVertices = proceduralGenerator.faceLocalVertices(tile, face);

				Material materialA = Material.NONE;
				Material materialB = Material.NONE;
				Material materialC = Material.NONE;

				int[] vertexKeys = proceduralGenerator.faceVertexKeys(tile, face);
				int vertexKeyA = vertexKeys[0];
				int vertexKeyB = vertexKeys[1];
				int vertexKeyC = vertexKeys[2];

				int depthA = proceduralGenerator.vertexUnderwaterDepth.getOrDefault(vertexKeyA, 0);
				int depthB = proceduralGenerator.vertexUnderwaterDepth.getOrDefault(vertexKeyB, 0);
				int depthC = proceduralGenerator.vertexUnderwaterDepth.getOrDefault(vertexKeyC, 0);

				if (plugin.configGroundTextures)
				{
					GroundMaterial groundMaterial = GroundMaterial.UNDERWATER_GENERIC;

					int tileVertexX = Math.round((float)localVertices[0][0] / (float)Perspective.LOCAL_TILE_SIZE) + tileX + baseX;
					int tileVertexY = Math.round((float)localVertices[0][1] / (float)Perspective.LOCAL_TILE_SIZE) + tileY + baseY;
					materialA = groundMaterial.getRandomMaterial(tileZ, tileVertexX, tileVertexY);

					tileVertexX = Math.round((float)localVertices[1][0] / (float)Perspective.LOCAL_TILE_SIZE) + tileX + baseX;
					tileVertexY = Math.round((float)localVertices[1][1] / (float)Perspective.LOCAL_TILE_SIZE) + tileY + baseY;
					materialB = groundMaterial.getRandomMaterial(tileZ, tileVertexX, tileVertexY);

					tileVertexX = Math.round((float)localVertices[2][0] / (float)Perspective.LOCAL_TILE_SIZE) + tileX + baseX;
					tileVertexY = Math.round((float)localVertices[2][1] / (float)Perspective.LOCAL_TILE_SIZE) + tileY + baseY;
					materialC = groundMaterial.getRandomMaterial(tileZ, tileVertexX, tileVertexY);
				}

				float[] normalsA = proceduralGenerator.vertexTerrainNormals.getOrDefault(vertexKeyA, UP_NORMAL);
				float[] normalsB = proceduralGenerator.vertexTerrainNormals.getOrDefault(vertexKeyB, UP_NORMAL);
				float[] normalsC = proceduralGenerator.vertexTerrainNormals.getOrDefault(vertexKeyC, UP_NORMAL);

				WaterType waterType = proceduralGenerator.faceWaterType(tile, face, sceneTileModel);

				int aTerrainData = packTerrainData(Math.max(1, depthA), waterType, tileZ);
				int bTerrainData = packTerrainData(Math.max(1, depthB), waterType, tileZ);
				int cTerrainData = packTerrainData(Math.max(1, depthC), waterType, tileZ);

				normalBuffer.ensureCapacity(12);
				normalBuffer.put(normalsA[0], normalsA[2], normalsA[1], aTerrainData);
				normalBuffer.put(normalsB[0], normalsB[2], normalsB[1], bTerrainData);
				normalBuffer.put(normalsC[0], normalsC[2], normalsC[1], cTerrainData);

				vertexBuffer.ensureCapacity(12);
				vertexBuffer.put(localVertices[0][0], localVertices[0][2] + depthA, localVertices[0][1], colorA);
				vertexBuffer.put(localVertices[1][0], localVertices[1][2] + depthB, localVertices[1][1], colorB);
				vertexBuffer.put(localVertices[2][0], localVertices[2][2] + depthC, localVertices[2][1], colorC);

				bufferLength += 3;

				int packedMaterialDataA = modelPusher.packMaterialData(materialA, false);
				int packedMaterialDataB = modelPusher.packMaterialData(materialB, false);
				int packedMaterialDataC = modelPusher.packMaterialData(materialC, false);

				uvBuffer.ensureCapacity(12);
				uvBuffer.put(packedMaterialDataA, localVertices[0][0] / 128f, localVertices[0][1] / 128f, 0f);
				uvBuffer.put(packedMaterialDataB, localVertices[1][0] / 128f, localVertices[1][1] / 128f, 0f);
				uvBuffer.put(packedMaterialDataC, localVertices[2][0] / 128f, localVertices[2][1] / 128f, 0f);

				uvBufferLength += 3;
			}
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	private int packTerrainData(int waterDepth, WaterType waterType, int plane)
	{
		byte isTerrain = 0b1;
		return waterDepth << 8 | waterType.ordinal() << 3 | plane << 1 | isTerrain;
	}

	private boolean shouldSkipTile(int worldX, int worldY) {
		// Horrible hack to solve for poorly textured bridge west of shilo
		// https://github.com/RS117/RLHD/issues/166
		return worldX == 2796 && worldY >= 2961 && worldY <= 2967;
	}
}
