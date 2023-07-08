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
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;
import rs117.hd.data.materials.GroundMaterial;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.Overlay;
import rs117.hd.data.materials.Underlay;
import rs117.hd.data.materials.UvType;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.utils.HDUtils;

import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.NORMAL_SIZE;
import static rs117.hd.HdPlugin.SCALAR_BYTES;
import static rs117.hd.HdPlugin.UV_SIZE;
import static rs117.hd.HdPlugin.VERTEX_SIZE;

@SuppressWarnings("UnnecessaryLocalVariable")
@Singleton
@Slf4j
public
class SceneUploader {
	public static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy

	private static final float[] UP_NORMAL = { 0, -1, 0 };

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

	public void upload(SceneContext sceneContext) {
		Stopwatch stopwatch = Stopwatch.createStarted();

		for (int z = 0; z < Constants.MAX_Z; ++z) {
			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < Constants.EXTENDED_SCENE_SIZE; ++y) {
					Tile tile = sceneContext.scene.getExtendedTiles()[z][x][y];
					upload(sceneContext, tile, x, y, z);
				}
			}
		}

		stopwatch.stop();
		log.debug(
			"Scene upload time: {}, unique models: {}, size: {} MB",
			stopwatch,
			sceneContext.uniqueModels,
			String.format(
				"%.2f",
				(
					sceneContext.getVertexOffset() * (VERTEX_SIZE + NORMAL_SIZE) * SCALAR_BYTES +
					sceneContext.getUvOffset() * UV_SIZE * SCALAR_BYTES
				) / 1e6
			)
		);
	}

	public void fillGaps(SceneContext sceneContext) {
		int sceneMin = sceneContext.expandedMapLoadingChunks * -8;
		int sceneMax = SCENE_SIZE + sceneContext.expandedMapLoadingChunks * 8;

		Tile[][][] extendedTiles = sceneContext.scene.getExtendedTiles();
		for (int tileZ = 0; tileZ < Constants.MAX_Z; ++tileZ) {
			for (int tileExX = 0; tileExX < Constants.EXTENDED_SCENE_SIZE; ++tileExX) {
				for (int tileExY = 0; tileExY < Constants.EXTENDED_SCENE_SIZE; ++tileExY) {
					int tileX = tileExX - SCENE_OFFSET;
					int tileY = tileExY - SCENE_OFFSET;
					Tile tile = extendedTiles[tileZ][tileExX][tileExY];

					SceneTilePaint paint = null;
					SceneTileModel model = null;
					int renderLevel = tileZ;
					if (tile != null) {
						paint = tile.getSceneTilePaint();
						model = tile.getSceneTileModel();
						renderLevel = tile.getRenderLevel();
					}
					boolean hasTilePaint = paint != null && paint.getNeColor() != 12345678;

					int[] worldPoint = sceneContext.sceneToWorld(tileX, tileY, tileZ);

					boolean fillGaps =
						tileZ == 0 &&
						tileX > sceneMin &&
						tileY > sceneMin &&
						tileX < sceneMax - 1 &&
						tileY < sceneMax - 1 &&
						Area.OVERWORLD.containsPoint(worldPoint);

					if (fillGaps) {
						int tileRegionID = HDUtils.worldToRegionID(worldPoint);
						int[] regions = client.getMapRegions();

						fillGaps = false;
						for (int region : regions) {
							if (region == tileRegionID) {
								fillGaps = true;
								break;
							}
						}
					}

					if (fillGaps) {
						int vertexOffset = sceneContext.getVertexOffset();
						int uvOffset = sceneContext.getUvOffset();
						int vertexCount = 0;

						if (model == null) {
							if (!hasTilePaint) {
								uploadBlackTile(sceneContext, tileExX, tileExY, renderLevel);
								vertexCount = 6;
							}
						} else {
							int[] uploadedTileModelData = uploadHDTileModelSurface(sceneContext, tile, model, true);
							vertexCount = uploadedTileModelData[0];
						}

						if (vertexCount > 0) {
							sceneContext.staticUnorderedModelBuffer
								.ensureCapacity(8)
								.getBuffer()
								.put(vertexOffset)
								.put(uvOffset)
								.put(vertexCount / 3)
								.put(sceneContext.staticVertexCount)
								.put(0)
								.put(tileX * LOCAL_TILE_SIZE)
								.put(0)
								.put(tileY * LOCAL_TILE_SIZE);
							sceneContext.staticVertexCount += vertexCount;
						}
					}
				}
			}
		}
	}

	private void uploadModel(SceneContext sceneContext, Tile tile, long hash, Model model, int orientation, ObjectType objectType) {
		// deduplicate hillskewed models
		if (model.getUnskewedModel() != null)
			model = model.getUnskewedModel();

		if (model.getSceneId() == sceneContext.id)
			return; // model has already been uploaded

		// pack a bit into bufferoffset that we can use later to hide
		// some low-importance objects based on Level of Detail setting
		model.setBufferOffset(sceneContext.getVertexOffset());
		model.setUvBufferOffset(sceneContext.getUvOffset());
		modelPusher.pushModel(sceneContext, tile, hash, model, objectType, orientation, false);
		if (sceneContext.modelPusherResults[1] == 0)
			model.setUvBufferOffset(-1);

		model.setSceneId(sceneContext.id);
		++sceneContext.uniqueModels;
	}

	private void upload(SceneContext sceneContext, @Nullable Tile tile, int tileExX, int tileExY, int tileZ) {
		int tileX = tileExX - SCENE_OFFSET;
		int tileY = tileExY - SCENE_OFFSET;
		int renderLevel = tileZ;
		boolean hasTilePaint = false;
		SceneTileModel sceneTileModel = null;

		if (tile != null) {
			Tile bridge = tile.getBridge();
			if (bridge != null)
				upload(sceneContext, bridge, tileExX, tileExY, tileZ);

			renderLevel = tile.getRenderLevel();
			SceneTilePaint sceneTilePaint = tile.getSceneTilePaint();
			hasTilePaint = sceneTilePaint != null && sceneTilePaint.getNeColor() != 12345678;
			if (hasTilePaint) {
				// Set offsets before pushing new data
				int vertexOffset = sceneContext.getVertexOffset();
				int uvOffset = sceneContext.getUvOffset();
				int[] uploadedTilePaintData = upload(sceneContext, tile, sceneTilePaint);

				int vertexCount = uploadedTilePaintData[0];
				int uvCount = uploadedTilePaintData[1];
				int hasUnderwaterTerrain = uploadedTilePaintData[2];

				// Opening the right-click menu causes the game to stop drawing hidden tiles, which prevents us from drawing underwater tiles
				// below the boats at Pest Control. To work around this, we can instead draw all water tiles that never appear on top of any
				// other model, all at once at the start of the frame. This bypasses any issues with draw order, and even partially solves the
				// draw order artifacts resulting from skipped geometry updates for our extension to unlocked FPS.
				final int[][][] tileHeights = sceneContext.scene.getTileHeights();
				if (hasUnderwaterTerrain == 1 && tileHeights[renderLevel][tileExX][tileExY] >= -16) {
					// Draw the underwater tile at the start of each frame
					sceneContext.staticUnorderedModelBuffer
						.ensureCapacity(8)
						.getBuffer()
						.put(vertexOffset)
						.put(uvOffset)
						.put(2) // 2 faces
						.put(sceneContext.staticVertexCount)
						.put(0)
						.put(tileX * LOCAL_TILE_SIZE)
						.put(0)
						.put(tileY * LOCAL_TILE_SIZE);
					sceneContext.staticVertexCount += 6;

					// Since we're now drawing this tile's underwater geometry at the beginning of the frame, remove it from the draw callback
					vertexCount -= 6;
					uvCount -= 6;
					vertexOffset += 6;
					uvOffset += 6;
				}

				if (uvCount <= 0)
					uvOffset = -1;

				sceneTilePaint.setBufferLen(vertexCount);
				sceneTilePaint.setBufferOffset(vertexOffset);
				sceneTilePaint.setUvBufferOffset(uvOffset);
			}

			sceneTileModel = tile.getSceneTileModel();
			if (sceneTileModel != null) {
				// Set offsets before pushing new data
				sceneTileModel.setBufferOffset(sceneContext.getVertexOffset());
				sceneTileModel.setUvBufferOffset(sceneContext.getUvOffset());
				int[] uploadedTileModelData = upload(sceneContext, tile, sceneTileModel);

				final int bufferLength = uploadedTileModelData[0];
				final int uvBufferLength = uploadedTileModelData[1];
				final int underwaterTerrain = uploadedTileModelData[2];
				if (uvBufferLength <= 0)
					sceneTileModel.setUvBufferOffset(-1);
				// pack a boolean into the buffer length of tiles so we can tell
				// which tiles have procedurally-generated underwater terrain
				int packedBufferLength = bufferLength << 1 | underwaterTerrain;

				sceneTileModel.setBufferLen(packedBufferLength);
			}

			WallObject wallObject = tile.getWallObject();
			if (wallObject != null) {
				Renderable renderable1 = wallObject.getRenderable1();
				if (renderable1 instanceof Model) {
					uploadModel(sceneContext, tile, wallObject.getHash(), (Model) renderable1,
						HDUtils.convertWallObjectOrientation(wallObject.getOrientationA()),
						ObjectType.WALL_OBJECT
					);
				}

				Renderable renderable2 = wallObject.getRenderable2();
				if (renderable2 instanceof Model) {
					uploadModel(sceneContext, tile, wallObject.getHash(), (Model) renderable2,
						HDUtils.convertWallObjectOrientation(wallObject.getOrientationB()),
						ObjectType.WALL_OBJECT
					);
				}
			}

			GroundObject groundObject = tile.getGroundObject();
			if (groundObject != null) {
				Renderable renderable = groundObject.getRenderable();
				if (renderable instanceof Model) {
					uploadModel(sceneContext, tile, groundObject.getHash(), (Model) renderable,
						HDUtils.getBakedOrientation(groundObject.getConfig()),
						ObjectType.GROUND_OBJECT
					);
				}
			}

			DecorativeObject decorativeObject = tile.getDecorativeObject();
			if (decorativeObject != null) {
				Renderable renderable = decorativeObject.getRenderable();
				if (renderable instanceof Model) {
					uploadModel(sceneContext, tile, decorativeObject.getHash(), (Model) renderable,
						HDUtils.getBakedOrientation(decorativeObject.getConfig()),
						ObjectType.DECORATIVE_OBJECT
					);
				}

				Renderable renderable2 = decorativeObject.getRenderable2();
				if (renderable2 instanceof Model) {
					uploadModel(sceneContext, tile, decorativeObject.getHash(), (Model) renderable2,
						HDUtils.getBakedOrientation(decorativeObject.getConfig()),
						ObjectType.DECORATIVE_OBJECT
					);
				}
			}

			GameObject[] gameObjects = tile.getGameObjects();
			for (GameObject gameObject : gameObjects) {
				if (gameObject == null) {
					continue;
				}

				Renderable renderable = gameObject.getRenderable();
				if (renderable instanceof Model) {
					uploadModel(sceneContext, tile, gameObject.getHash(), (Model) gameObject.getRenderable(),
						HDUtils.getBakedOrientation(gameObject.getConfig()), ObjectType.GAME_OBJECT
					);
				}
			}
		}
	}

	private int[] upload(SceneContext sceneContext, Tile tile, SceneTilePaint sceneTilePaint) {
		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int[] bufferLengths;

		bufferLengths = uploadHDTilePaintUnderwater(sceneContext, tile, sceneTilePaint);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		bufferLengths = uploadHDTilePaintSurface(sceneContext, tile, sceneTilePaint);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
	}

	private int[] uploadHDTilePaintSurface(SceneContext sceneContext, Tile tile, SceneTilePaint sceneTilePaint) {
		final Scene scene = sceneContext.scene;
		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileExX = tileX + SceneUploader.SCENE_OFFSET;
		final int tileExY = tileY + SceneUploader.SCENE_OFFSET;
		final int tileZ = tile.getRenderLevel();

		final int localX = 0;
		final int localY = 0;

		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		final int[][][] tileHeights = scene.getTileHeights();
		int swHeight = tileHeights[tileZ][tileExX][tileExY];
		int seHeight = tileHeights[tileZ][tileExX + 1][tileExY];
		int neHeight = tileHeights[tileZ][tileExX + 1][tileExY + 1];
		int nwHeight = tileHeights[tileZ][tileExX][tileExY + 1];

		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int localSwVertexX = localX;
		int localSwVertexY = localY;
		int localSeVertexX = localX + LOCAL_TILE_SIZE;
		int localSeVertexY = localY;
		int localNwVertexX = localX;
		int localNwVertexY = localY + LOCAL_TILE_SIZE;
		int localNeVertexX = localX + LOCAL_TILE_SIZE;
		int localNeVertexY = localY + LOCAL_TILE_SIZE;

		int[] vertexKeys = ProceduralGenerator.tileVertexKeys(scene, tile);
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

			WaterType waterType = proceduralGenerator.tileWaterType(scene, tile, sceneTilePaint);
			if (waterType == WaterType.NONE) {
				swMaterial = Material.fromVanillaTexture(tileTexture);
				seMaterial = Material.fromVanillaTexture(tileTexture);
				neMaterial = Material.fromVanillaTexture(tileTexture);
				nwMaterial = Material.fromVanillaTexture(tileTexture);

				swNormals = sceneContext.vertexTerrainNormals.getOrDefault(swVertexKey, swNormals);
				seNormals = sceneContext.vertexTerrainNormals.getOrDefault(seVertexKey, seNormals);
				neNormals = sceneContext.vertexTerrainNormals.getOrDefault(neVertexKey, neNormals);
				nwNormals = sceneContext.vertexTerrainNormals.getOrDefault(nwVertexKey, nwNormals);

				if (plugin.configGroundBlending && !proceduralGenerator.useDefaultColor(scene, tile) && sceneTilePaint.getTexture() == -1) {
					// get the vertices' colors and textures from hashmaps

					swColor = sceneContext.vertexTerrainColor.getOrDefault(swVertexKey, swColor);
					seColor = sceneContext.vertexTerrainColor.getOrDefault(seVertexKey, seColor);
					neColor = sceneContext.vertexTerrainColor.getOrDefault(neVertexKey, neColor);
					nwColor = sceneContext.vertexTerrainColor.getOrDefault(nwVertexKey, nwColor);

					if (plugin.configGroundTextures) {
						swMaterial = sceneContext.vertexTerrainTexture.getOrDefault(swVertexKey, swMaterial);
						seMaterial = sceneContext.vertexTerrainTexture.getOrDefault(seVertexKey, seMaterial);
						neMaterial = sceneContext.vertexTerrainTexture.getOrDefault(neVertexKey, neMaterial);
						nwMaterial = sceneContext.vertexTerrainTexture.getOrDefault(nwVertexKey, nwMaterial);
					}
				}
				else if (plugin.configGroundTextures && !shouldSkipTile(baseX + tileX, baseY + tileY))
				{
					GroundMaterial groundMaterial;

					Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
					if (overlay != Overlay.NONE)
					{
						groundMaterial = overlay.groundMaterial;

						swColor = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(swColor)));
						seColor = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(seColor)));
						nwColor = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(nwColor)));
						neColor = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(neColor)));
					}
					else
					{
						Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
						groundMaterial = underlay.groundMaterial;

						swColor = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(swColor)));
						seColor = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(seColor)));
						nwColor = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(nwColor)));
						neColor = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(neColor)));
					}

					swMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY);
					seMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY);
					nwMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY + 1);
					neMaterial = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY + 1);
				}
				else if (plugin.configWinterTheme)
				{
					Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
					if (overlay != Overlay.NONE)
					{
						swColor = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(swColor)));
						seColor = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(seColor)));
						nwColor = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(nwColor)));
						neColor = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(neColor)));
					}
					else
					{
						Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
						swColor = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(swColor)));
						seColor = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(seColor)));
						nwColor = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(nwColor)));
						neColor = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(neColor)));
					}
				}
			}
			else
			{
				// set colors for the shoreline to create a foam effect in the water shader

				swColor = seColor = nwColor = neColor = 127;

				if (sceneContext.vertexIsWater.containsKey(swVertexKey) && sceneContext.vertexIsLand.containsKey(swVertexKey))
					swColor = 0;
				if (sceneContext.vertexIsWater.containsKey(seVertexKey) && sceneContext.vertexIsLand.containsKey(seVertexKey))
					seColor = 0;
				if (sceneContext.vertexIsWater.containsKey(nwVertexKey) && sceneContext.vertexIsLand.containsKey(nwVertexKey))
					nwColor = 0;
				if (sceneContext.vertexIsWater.containsKey(neVertexKey) && sceneContext.vertexIsLand.containsKey(neVertexKey))
					neColor = 0;
			}

			if (sceneContext.vertexIsOverlay.containsKey(neVertexKey) && sceneContext.vertexIsUnderlay.containsKey(neVertexKey))
				neVertexIsOverlay = true;
			if (sceneContext.vertexIsOverlay.containsKey(nwVertexKey) && sceneContext.vertexIsUnderlay.containsKey(nwVertexKey))
				nwVertexIsOverlay = true;
			if (sceneContext.vertexIsOverlay.containsKey(seVertexKey) && sceneContext.vertexIsUnderlay.containsKey(seVertexKey))
				seVertexIsOverlay = true;
			if (sceneContext.vertexIsOverlay.containsKey(swVertexKey) && sceneContext.vertexIsUnderlay.containsKey(swVertexKey))
				swVertexIsOverlay = true;


			int swTerrainData = packTerrainData(true, 0, waterType, tileZ);
			int seTerrainData = packTerrainData(true, 0, waterType, tileZ);
			int nwTerrainData = packTerrainData(true, 0, waterType, tileZ);
			int neTerrainData = packTerrainData(true, 0, waterType, tileZ);

			sceneContext.stagingBufferNormals.ensureCapacity(24);
			sceneContext.stagingBufferNormals.put(neNormals[0], neNormals[2], neNormals[1], neTerrainData);
			sceneContext.stagingBufferNormals.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);
			sceneContext.stagingBufferNormals.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);

			sceneContext.stagingBufferNormals.put(swNormals[0], swNormals[2], swNormals[1], swTerrainData);
			sceneContext.stagingBufferNormals.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);
			sceneContext.stagingBufferNormals.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);


			sceneContext.stagingBufferVertices.ensureCapacity(24);
			sceneContext.stagingBufferVertices.put(localNeVertexX, neHeight, localNeVertexY, neColor);
			sceneContext.stagingBufferVertices.put(localNwVertexX, nwHeight, localNwVertexY, nwColor);
			sceneContext.stagingBufferVertices.put(localSeVertexX, seHeight, localSeVertexY, seColor);

			sceneContext.stagingBufferVertices.put(localSwVertexX, swHeight, localSwVertexY, swColor);
			sceneContext.stagingBufferVertices.put(localSeVertexX, seHeight, localSeVertexY, seColor);
			sceneContext.stagingBufferVertices.put(localNwVertexX, nwHeight, localNwVertexY, nwColor);

			bufferLength += 6;


			int packedMaterialDataSW = modelPusher.packMaterialData(
				swMaterial, tileTexture, ModelOverride.NONE, UvType.GEOMETRY, swVertexIsOverlay);
			int packedMaterialDataSE = modelPusher.packMaterialData(
				seMaterial, tileTexture, ModelOverride.NONE, UvType.GEOMETRY, seVertexIsOverlay);
			int packedMaterialDataNW = modelPusher.packMaterialData(
				nwMaterial, tileTexture, ModelOverride.NONE, UvType.GEOMETRY, nwVertexIsOverlay);
			int packedMaterialDataNE = modelPusher.packMaterialData(
				neMaterial, tileTexture, ModelOverride.NONE, UvType.GEOMETRY, neVertexIsOverlay);

			sceneContext.stagingBufferUvs.ensureCapacity(24);
			sceneContext.stagingBufferUvs.put(0, 0, 0, packedMaterialDataNE);
			sceneContext.stagingBufferUvs.put(1, 0, 0, packedMaterialDataNW);
			sceneContext.stagingBufferUvs.put(0, 1, 0, packedMaterialDataSE);

			sceneContext.stagingBufferUvs.put(1, 1, 0, packedMaterialDataSW);
			sceneContext.stagingBufferUvs.put(0, 1, 0, packedMaterialDataSE);
			sceneContext.stagingBufferUvs.put(1, 0, 0, packedMaterialDataNW);

			uvBufferLength += 6;
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	private int[] uploadHDTilePaintUnderwater(SceneContext sceneContext, Tile tile, SceneTilePaint sceneTilePaint) {
		final Scene scene = sceneContext.scene;
		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileExX = tileX + SceneUploader.SCENE_OFFSET;
		final int tileExY = tileY + SceneUploader.SCENE_OFFSET;
		final int tileZ = tile.getRenderLevel();

		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		if (baseX >= 2816 && baseX <= 2970 && baseY <= 5375 && baseY >= 5220) {
			// fix for God Wars Dungeon's water rendering over zamorak bridge
			return new int[] { 0, 0, 0 };
		}

		final int[][][] tileHeights = scene.getTileHeights();
		int swHeight = tileHeights[tileZ][tileExX][tileExY];
		int seHeight = tileHeights[tileZ][tileExX + 1][tileExY];
		int neHeight = tileHeights[tileZ][tileExX + 1][tileExY + 1];
		int nwHeight = tileHeights[tileZ][tileExX][tileExY + 1];

		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int localSwVertexX = 0;
		int localSwVertexY = 0;
		int localSeVertexX = LOCAL_TILE_SIZE;
		int localSeVertexY = 0;
		int localNwVertexX = 0;
		int localNwVertexY = LOCAL_TILE_SIZE;
		int localNeVertexX = LOCAL_TILE_SIZE;
		int localNeVertexY = LOCAL_TILE_SIZE;

		int[] vertexKeys = ProceduralGenerator.tileVertexKeys(scene, tile);
		int swVertexKey = vertexKeys[0];
		int seVertexKey = vertexKeys[1];
		int nwVertexKey = vertexKeys[2];
		int neVertexKey = vertexKeys[3];

		if (sceneContext.tileIsWater[tileZ][tileExX][tileExY]) {
			// underwater terrain

			underwaterTerrain = 1;

			int swColor = 6676;
			int seColor = 6676;
			int neColor = 6676;
			int nwColor = 6676;

			int swDepth = sceneContext.vertexUnderwaterDepth.getOrDefault(swVertexKey, 0);
			int seDepth = sceneContext.vertexUnderwaterDepth.getOrDefault(seVertexKey, 0);
			int nwDepth = sceneContext.vertexUnderwaterDepth.getOrDefault(nwVertexKey, 0);
			int neDepth = sceneContext.vertexUnderwaterDepth.getOrDefault(neVertexKey, 0);

			float[] swNormals = sceneContext.vertexTerrainNormals.getOrDefault(swVertexKey, UP_NORMAL);
			float[] seNormals = sceneContext.vertexTerrainNormals.getOrDefault(seVertexKey, UP_NORMAL);
			float[] nwNormals = sceneContext.vertexTerrainNormals.getOrDefault(nwVertexKey, UP_NORMAL);
			float[] neNormals = sceneContext.vertexTerrainNormals.getOrDefault(neVertexKey, UP_NORMAL);

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

			WaterType waterType = proceduralGenerator.tileWaterType(scene, tile, sceneTilePaint);

			int swTerrainData = packTerrainData(true, Math.max(1, swDepth), waterType, tileZ);
			int seTerrainData = packTerrainData(true, Math.max(1, seDepth), waterType, tileZ);
			int nwTerrainData = packTerrainData(true, Math.max(1, nwDepth), waterType, tileZ);
			int neTerrainData = packTerrainData(true, Math.max(1, neDepth), waterType, tileZ);

			sceneContext.stagingBufferNormals.ensureCapacity(24);
			sceneContext.stagingBufferNormals.put(neNormals[0], neNormals[2], neNormals[1], neTerrainData);
			sceneContext.stagingBufferNormals.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);
			sceneContext.stagingBufferNormals.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);

			sceneContext.stagingBufferNormals.put(swNormals[0], swNormals[2], swNormals[1], swTerrainData);
			sceneContext.stagingBufferNormals.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);
			sceneContext.stagingBufferNormals.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);

			sceneContext.stagingBufferVertices.ensureCapacity(24);
			sceneContext.stagingBufferVertices.put(localNeVertexX, neHeight + neDepth, localNeVertexY, neColor);
			sceneContext.stagingBufferVertices.put(localNwVertexX, nwHeight + nwDepth, localNwVertexY, nwColor);
			sceneContext.stagingBufferVertices.put(localSeVertexX, seHeight + seDepth, localSeVertexY, seColor);

			sceneContext.stagingBufferVertices.put(localSwVertexX, swHeight + swDepth, localSwVertexY, swColor);
			sceneContext.stagingBufferVertices.put(localSeVertexX, seHeight + seDepth, localSeVertexY, seColor);
			sceneContext.stagingBufferVertices.put(localNwVertexX, nwHeight + nwDepth, localNwVertexY, nwColor);

			bufferLength += 6;

			int packedMaterialDataSW = modelPusher.packMaterialData(
				swMaterial, -1, ModelOverride.NONE, UvType.GEOMETRY, false);
			int packedMaterialDataSE = modelPusher.packMaterialData(
				seMaterial, -1, ModelOverride.NONE, UvType.GEOMETRY, false);
			int packedMaterialDataNW = modelPusher.packMaterialData(
				nwMaterial, -1, ModelOverride.NONE, UvType.GEOMETRY, false);
			int packedMaterialDataNE = modelPusher.packMaterialData(
				neMaterial, -1, ModelOverride.NONE, UvType.GEOMETRY, false);

			sceneContext.stagingBufferUvs.ensureCapacity(24);
			sceneContext.stagingBufferUvs.put(0, 0, 0, packedMaterialDataNE);
			sceneContext.stagingBufferUvs.put(1, 0, 0, packedMaterialDataNW);
			sceneContext.stagingBufferUvs.put(0, 1, 0, packedMaterialDataSE);

			sceneContext.stagingBufferUvs.put(1, 1, 0, packedMaterialDataSW);
			sceneContext.stagingBufferUvs.put(0, 1, 0, packedMaterialDataSE);
			sceneContext.stagingBufferUvs.put(1, 0, 0, packedMaterialDataNW);

			uvBufferLength += 6;
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	private int[] upload(SceneContext sceneContext, Tile tile, SceneTileModel sceneTileModel)
	{
		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int[] bufferLengths;

		bufferLengths = uploadHDTileModelSurface(sceneContext, tile, sceneTileModel, false);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		bufferLengths = uploadHDTileModelUnderwater(sceneContext, tile, sceneTileModel);
		assert bufferLengths[0] == bufferLength || bufferLengths[0] == 0;
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	private int[] uploadHDTileModelSurface(SceneContext sceneContext, Tile tile, SceneTileModel sceneTileModel, boolean fillGaps) {
		final Scene scene = sceneContext.scene;
		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileExX = tileX + SceneUploader.SCENE_OFFSET;
		final int tileExY = tileY + SceneUploader.SCENE_OFFSET;
		final int tileZ = tile.getRenderLevel();

		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		if (sceneContext.skipTile[tileZ][tileExX][tileExY]) {
			return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
		}

		final int[] faceColorA = sceneTileModel.getTriangleColorA();
		final int[] faceColorB = sceneTileModel.getTriangleColorB();
		final int[] faceColorC = sceneTileModel.getTriangleColorC();

		final int[] faceTextures = sceneTileModel.getTriangleTextureId();

		final int faceCount = sceneTileModel.getFaceX().length;

		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		for (int face = 0; face < faceCount; ++face) {
			int colorA = faceColorA[face];
			int colorB = faceColorB[face];
			int colorC = faceColorC[face];

			int[][] localVertices = ProceduralGenerator.faceLocalVertices(tile, face);

			int[] vertexKeys = ProceduralGenerator.faceVertexKeys(tile, face);
			int vertexKeyA = vertexKeys[0];
			int vertexKeyB = vertexKeys[1];
			int vertexKeyC = vertexKeys[2];

			boolean vertexAIsOverlay = false;
			boolean vertexBIsOverlay = false;
			boolean vertexCIsOverlay = false;

			int textureIndex = -1;
			Material materialA = Material.NONE;
			Material materialB = Material.NONE;
			Material materialC = Material.NONE;

			float[] normalsA = UP_NORMAL;
			float[] normalsB = UP_NORMAL;
			float[] normalsC = UP_NORMAL;

			WaterType waterType = WaterType.NONE;

			boolean isHidden = colorA == 12345678;
			if (fillGaps) {
				if (!isHidden)
					continue;
				colorA = colorB = colorC = 0;
			} else {
				if (isHidden)
					continue;

				waterType = proceduralGenerator.faceWaterType(scene, tile, face, sceneTileModel);
				if (waterType == WaterType.NONE) {
					if (faceTextures != null) {
						textureIndex = faceTextures[face];
						materialA = Material.fromVanillaTexture(textureIndex);
						materialB = Material.fromVanillaTexture(textureIndex);
						materialC = Material.fromVanillaTexture(textureIndex);
					}

					normalsA = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyA, normalsA);
					normalsB = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyB, normalsB);
					normalsC = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyC, normalsC);

					if (plugin.configGroundBlending &&
						!(ProceduralGenerator.isOverlayFace(tile, face) && proceduralGenerator.useDefaultColor(scene, tile)) &&
						materialA == Material.NONE
					) {
						// get the vertices' colors and textures from hashmaps

						colorA = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyA, colorA);
						colorB = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyB, colorB);
						colorC = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyC, colorC);

						if (plugin.configGroundTextures) {
							materialA = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyA, materialA);
							materialB = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyB, materialB);
							materialC = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyC, materialC);
						}
					} else if (plugin.configGroundTextures) {
						// ground textures without blending

						GroundMaterial groundMaterial;

						if (ProceduralGenerator.isOverlayFace(tile, face)) {
							Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
							groundMaterial = overlay.groundMaterial;

							colorA = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(colorA)));
							colorB = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(colorB)));
							colorC = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(colorC)));
						} else {
							Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
							groundMaterial = underlay.groundMaterial;

							colorA = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(colorA)));
							colorB = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(colorB)));
							colorC = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(colorC)));
						}

						materialA = groundMaterial.getRandomMaterial(
							tileZ,
							baseX + tileX + (int) Math.floor((float) localVertices[0][0] / LOCAL_TILE_SIZE),
							baseY + tileY + (int) Math.floor((float) localVertices[0][1] / LOCAL_TILE_SIZE)
						);
						materialB = groundMaterial.getRandomMaterial(
							tileZ,
							baseX + tileX + (int) Math.floor((float) localVertices[1][0] / LOCAL_TILE_SIZE),
							baseY + tileY + (int) Math.floor((float) localVertices[1][1] / LOCAL_TILE_SIZE)
						);
						materialC = groundMaterial.getRandomMaterial(
							tileZ,
							baseX + tileX + (int) Math.floor((float) localVertices[2][0] / LOCAL_TILE_SIZE),
							baseY + tileY + (int) Math.floor((float) localVertices[2][1] / LOCAL_TILE_SIZE)
						);
					} else if (plugin.configWinterTheme) {
						if (ProceduralGenerator.isOverlayFace(tile, face)) {
							Overlay overlay = Overlay.getOverlay(scene, tile, plugin);

							colorA = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(colorA)));
							colorB = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(colorB)));
							colorC = HDUtils.colorHSLToInt(overlay.modifyColor(HDUtils.colorIntToHSL(colorC)));
						} else {
							Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);

							colorA = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(colorA)));
							colorB = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(colorB)));
							colorC = HDUtils.colorHSLToInt(underlay.modifyColor(HDUtils.colorIntToHSL(colorC)));
						}
					}
				} else {
					// set colors for the shoreline to create a foam effect in the water shader
					colorA = colorB = colorC = 127;
					if (sceneContext.vertexIsWater.containsKey(vertexKeyA) && sceneContext.vertexIsLand.containsKey(vertexKeyA))
						colorA = 0;
					if (sceneContext.vertexIsWater.containsKey(vertexKeyB) && sceneContext.vertexIsLand.containsKey(vertexKeyB))
						colorB = 0;
					if (sceneContext.vertexIsWater.containsKey(vertexKeyC) && sceneContext.vertexIsLand.containsKey(vertexKeyC))
						colorC = 0;
				}

				if (sceneContext.vertexIsOverlay.containsKey(vertexKeyA) && sceneContext.vertexIsUnderlay.containsKey(vertexKeyA))
					vertexAIsOverlay = true;
				if (sceneContext.vertexIsOverlay.containsKey(vertexKeyB) && sceneContext.vertexIsUnderlay.containsKey(vertexKeyB))
					vertexBIsOverlay = true;
				if (sceneContext.vertexIsOverlay.containsKey(vertexKeyC) && sceneContext.vertexIsUnderlay.containsKey(vertexKeyC))
					vertexCIsOverlay = true;
			}

			int aTerrainData = packTerrainData(true, 0, waterType, tileZ);
			int bTerrainData = packTerrainData(true, 0, waterType, tileZ);
			int cTerrainData = packTerrainData(true, 0, waterType, tileZ);

			sceneContext.stagingBufferNormals.ensureCapacity(12);
			sceneContext.stagingBufferNormals.put(normalsA[0], normalsA[2], normalsA[1], aTerrainData);
			sceneContext.stagingBufferNormals.put(normalsB[0], normalsB[2], normalsB[1], bTerrainData);
			sceneContext.stagingBufferNormals.put(normalsC[0], normalsC[2], normalsC[1], cTerrainData);

			sceneContext.stagingBufferVertices.ensureCapacity(12);
			sceneContext.stagingBufferVertices.put(localVertices[0][0], localVertices[0][2], localVertices[0][1], colorA);
			sceneContext.stagingBufferVertices.put(localVertices[1][0], localVertices[1][2], localVertices[1][1], colorB);
			sceneContext.stagingBufferVertices.put(localVertices[2][0], localVertices[2][2], localVertices[2][1], colorC);

			bufferLength += 3;

			int packedMaterialDataA = modelPusher.packMaterialData(
				materialA, textureIndex, ModelOverride.NONE, UvType.GEOMETRY, vertexAIsOverlay);
			int packedMaterialDataB = modelPusher.packMaterialData(
				materialB, textureIndex, ModelOverride.NONE, UvType.GEOMETRY, vertexBIsOverlay);
			int packedMaterialDataC = modelPusher.packMaterialData(
				materialC, textureIndex, ModelOverride.NONE, UvType.GEOMETRY, vertexCIsOverlay);

			sceneContext.stagingBufferUvs.ensureCapacity(12);
			sceneContext.stagingBufferUvs.put(1 - localVertices[0][0] / 128f, 1 - localVertices[0][1] / 128f, 0, packedMaterialDataA);
			sceneContext.stagingBufferUvs.put(1 - localVertices[1][0] / 128f, 1 - localVertices[1][1] / 128f, 0, packedMaterialDataB);
			sceneContext.stagingBufferUvs.put(1 - localVertices[2][0] / 128f, 1 - localVertices[2][1] / 128f, 0, packedMaterialDataC);

			uvBufferLength += 3;
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	private int[] uploadHDTileModelUnderwater(SceneContext sceneContext, Tile tile, SceneTileModel sceneTileModel) {
		final Scene scene = sceneContext.scene;
		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileExX = tileX + SceneUploader.SCENE_OFFSET;
		final int tileExY = tileY + SceneUploader.SCENE_OFFSET;
		final int tileZ = tile.getRenderLevel();

		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		if (sceneContext.skipTile[tileZ][tileExX][tileExY]) {
			return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
		}

		final int[] faceColorA = sceneTileModel.getTriangleColorA();
		final int faceCount = sceneTileModel.getFaceX().length;

		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		if (baseX >= 2816 && baseX <= 2970 && baseY <= 5375 && baseY >= 5220) {
			// fix for God Wars Dungeon's water rendering over zamorak bridge
			return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
		}

		if (sceneContext.tileIsWater[tileZ][tileExX][tileExY]) {
			underwaterTerrain = 1;

			// underwater terrain
			for (int face = 0; face < faceCount; ++face) {
				int colorA = 6676;
				int colorB = 6676;
				int colorC = 6676;

				if (faceColorA[face] == 12345678)
					continue;

				int[][] localVertices = ProceduralGenerator.faceLocalVertices(tile, face);

				Material materialA = Material.NONE;
				Material materialB = Material.NONE;
				Material materialC = Material.NONE;

				int[] vertexKeys = ProceduralGenerator.faceVertexKeys(tile, face);
				int vertexKeyA = vertexKeys[0];
				int vertexKeyB = vertexKeys[1];
				int vertexKeyC = vertexKeys[2];

				int depthA = sceneContext.vertexUnderwaterDepth.getOrDefault(vertexKeyA, 0);
				int depthB = sceneContext.vertexUnderwaterDepth.getOrDefault(vertexKeyB, 0);
				int depthC = sceneContext.vertexUnderwaterDepth.getOrDefault(vertexKeyC, 0);

				if (plugin.configGroundTextures) {
					GroundMaterial groundMaterial = GroundMaterial.UNDERWATER_GENERIC;

					int tileVertexX = Math.round((float) localVertices[0][0] / (float) LOCAL_TILE_SIZE) + tileX + baseX;
					int tileVertexY = Math.round((float) localVertices[0][1] / (float) LOCAL_TILE_SIZE) + tileY + baseY;
					materialA = groundMaterial.getRandomMaterial(tileZ, tileVertexX, tileVertexY);

					tileVertexX = Math.round((float) localVertices[1][0] / (float) LOCAL_TILE_SIZE) + tileX + baseX;
					tileVertexY = Math.round((float) localVertices[1][1] / (float) LOCAL_TILE_SIZE) + tileY + baseY;
					materialB = groundMaterial.getRandomMaterial(tileZ, tileVertexX, tileVertexY);

					tileVertexX = Math.round((float) localVertices[2][0] / (float) LOCAL_TILE_SIZE) + tileX + baseX;
					tileVertexY = Math.round((float) localVertices[2][1] / (float) LOCAL_TILE_SIZE) + tileY + baseY;
					materialC = groundMaterial.getRandomMaterial(tileZ, tileVertexX, tileVertexY);
				}

				float[] normalsA = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyA, UP_NORMAL);
				float[] normalsB = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyB, UP_NORMAL);
				float[] normalsC = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyC, UP_NORMAL);

				WaterType waterType = proceduralGenerator.faceWaterType(scene, tile, face, sceneTileModel);

				int aTerrainData = packTerrainData(true, Math.max(1, depthA), waterType, tileZ);
				int bTerrainData = packTerrainData(true, Math.max(1, depthB), waterType, tileZ);
				int cTerrainData = packTerrainData(true, Math.max(1, depthC), waterType, tileZ);

				sceneContext.stagingBufferNormals.ensureCapacity(12);
				sceneContext.stagingBufferNormals.put(normalsA[0], normalsA[2], normalsA[1], aTerrainData);
				sceneContext.stagingBufferNormals.put(normalsB[0], normalsB[2], normalsB[1], bTerrainData);
				sceneContext.stagingBufferNormals.put(normalsC[0], normalsC[2], normalsC[1], cTerrainData);

				sceneContext.stagingBufferVertices.ensureCapacity(12);
				sceneContext.stagingBufferVertices.put(localVertices[0][0], localVertices[0][2] + depthA, localVertices[0][1], colorA);
				sceneContext.stagingBufferVertices.put(localVertices[1][0], localVertices[1][2] + depthB, localVertices[1][1], colorB);
				sceneContext.stagingBufferVertices.put(localVertices[2][0], localVertices[2][2] + depthC, localVertices[2][1], colorC);

				bufferLength += 3;

				int packedMaterialDataA = modelPusher.packMaterialData(
					materialA, -1, ModelOverride.NONE, UvType.GEOMETRY, false);
				int packedMaterialDataB = modelPusher.packMaterialData(
					materialB, -1, ModelOverride.NONE, UvType.GEOMETRY, false);
				int packedMaterialDataC = modelPusher.packMaterialData(
					materialC, -1, ModelOverride.NONE, UvType.GEOMETRY, false);

				sceneContext.stagingBufferUvs.ensureCapacity(12);
				sceneContext.stagingBufferUvs.put(1 - localVertices[0][0] / 128f, 1 - localVertices[0][1] / 128f, 0, packedMaterialDataA);
				sceneContext.stagingBufferUvs.put(1 - localVertices[1][0] / 128f, 1 - localVertices[1][1] / 128f, 0, packedMaterialDataB);
				sceneContext.stagingBufferUvs.put(1 - localVertices[2][0] / 128f, 1 - localVertices[2][1] / 128f, 0, packedMaterialDataC);

				uvBufferLength += 3;
			}
		}

		return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
	}

	private void uploadBlackTile(SceneContext sceneContext, int tileExX, int tileExY, int tileZ) {
		final Scene scene = sceneContext.scene;

		int color = 0;
		int fromX = 0;
		int fromY = 0;
		int toX = LOCAL_TILE_SIZE;
		int toY = LOCAL_TILE_SIZE;

		final int[][][] tileHeights = scene.getTileHeights();
		int swHeight = tileHeights[tileZ][tileExX][tileExY];
		int seHeight = tileHeights[tileZ][tileExX + 1][tileExY];
		int neHeight = tileHeights[tileZ][tileExX + 1][tileExY + 1];
		int nwHeight = tileHeights[tileZ][tileExX][tileExY + 1];

		int terrainData = packTerrainData(true, 0, WaterType.NONE, tileZ);

		sceneContext.stagingBufferNormals.ensureCapacity(24);
		sceneContext.stagingBufferNormals.put(0, -1, 0, terrainData);
		sceneContext.stagingBufferNormals.put(0, -1, 0, terrainData);
		sceneContext.stagingBufferNormals.put(0, -1, 0, terrainData);

		sceneContext.stagingBufferNormals.put(0, -1, 0, terrainData);
		sceneContext.stagingBufferNormals.put(0, -1, 0, terrainData);
		sceneContext.stagingBufferNormals.put(0, -1, 0, terrainData);

		sceneContext.stagingBufferVertices.ensureCapacity(24);
		sceneContext.stagingBufferVertices.put(toX, neHeight, toY, color);
		sceneContext.stagingBufferVertices.put(fromX, nwHeight, toY, color);
		sceneContext.stagingBufferVertices.put(toX, seHeight, fromY, color);

		sceneContext.stagingBufferVertices.put(fromX, swHeight, fromY, color);
		sceneContext.stagingBufferVertices.put(toX, seHeight, fromY, color);
		sceneContext.stagingBufferVertices.put(fromX, nwHeight, toY, color);

		int packedMaterialData = modelPusher.packMaterialData(Material.BLACK, -1, ModelOverride.NONE, UvType.GEOMETRY, false);

		sceneContext.stagingBufferUvs.ensureCapacity(24);
		sceneContext.stagingBufferUvs.put(0, 0, 0, packedMaterialData);
		sceneContext.stagingBufferUvs.put(1, 0, 0, packedMaterialData);
		sceneContext.stagingBufferUvs.put(0, 1, 0, packedMaterialData);

		sceneContext.stagingBufferUvs.put(1, 1, 0, packedMaterialData);
		sceneContext.stagingBufferUvs.put(0, 1, 0, packedMaterialData);
		sceneContext.stagingBufferUvs.put(1, 0, 0, packedMaterialData);
	}

	private boolean shouldSkipTile(int worldX, int worldY) {
		// Horrible hack to solve for poorly textured bridge west of shilo
		// https://github.com/RS117/RLHD/issues/166
		return worldX == 2796 && worldY >= 2961 && worldY <= 2967;
	}

	public static int packTerrainData(boolean isTerrain, int waterDepth, WaterType waterType, int plane) {
		// 11-bit water depth | 5-bit water type | 2-bit plane | terrain flag
		int terrainData = waterDepth << 8 | waterType.ordinal() << 3 | plane << 1 | (isTerrain ? 1 : 0);
		assert (terrainData & ~0xFFFFFF) == 0 : "Only the lower 24 bits are usable, since we pass this into shaders as a float";
		return terrainData;
	}
}
