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
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.NORMAL_SIZE;
import static rs117.hd.HdPlugin.UV_SIZE;
import static rs117.hd.HdPlugin.VERTEX_SIZE;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.scene.tile_overrides.TileOverride.NONE;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
@SuppressWarnings("UnnecessaryLocalVariable")
public class SceneUploader {
	public static final int SCENE_ID_MASK = 0xFFFF;
	public static final int EXCLUDED_FROM_SCENE_BUFFER = 0xFFFFFFFF;

	private static final float[] UP_NORMAL = { 0, -1, 0 };

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private AreaManager areaManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	public ProceduralGenerator proceduralGenerator;

	@Inject
	private ModelPusher modelPusher;

	public void upload(SceneContext sceneContext) {
		Stopwatch stopwatch = Stopwatch.createStarted();

		var scene = sceneContext.scene;
		sceneContext.enableAreaHiding =
			config.hideUnrelatedAreas() &&
			sceneContext.sceneBase != null &&
			!sceneContext.forceDisableAreaHiding;
		sceneContext.fillGaps = config.fillGapsInTerrain();

		if (sceneContext.enableAreaHiding) {
			sceneContext.possibleAreas = Arrays
				.stream(areaManager.areasWithAreaHiding)
				.filter(area -> sceneContext.sceneBounds.intersects(area.aabbs))
				.toArray(Area[]::new);

			if (log.isDebugEnabled() && sceneContext.possibleAreas.length > 0) {
				log.debug(
					"Hiding areas outside of {}",
					Arrays.stream(sceneContext.possibleAreas)
						.distinct()
						.map(Area::toString)
						.collect(Collectors.joining(", "))
				);
			}
		}

		// The scene can be prepared early when loaded synchronously
		if (client.isClientThread())
			prepareBeforeSwap(sceneContext);

		sceneContext.staticCustomTilesOffset = sceneContext.staticVertexCount;
		var tiles = scene.getExtendedTiles();
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
					Tile tile = tiles[z][x][y];
					if (tile != null)
						upload(sceneContext, tile, x, y);
				}
			}
		}
		sceneContext.staticCustomTilesVertexCount = sceneContext.staticVertexCount - sceneContext.staticCustomTilesOffset;

		stopwatch.stop();
		log.debug(
			"Scene upload time: {}, unique models: {}, size: {} MB",
			stopwatch,
			sceneContext.uniqueModels,
			String.format(
				"%.2f",
				(
					sceneContext.getVertexOffset() * 4L * (VERTEX_SIZE + NORMAL_SIZE) +
					sceneContext.getUvOffset() * 4L * UV_SIZE
				) / 1e6
			)
		);
	}

	public void prepareBeforeSwap(SceneContext sceneContext) {
		assert client.isClientThread();
		if (sceneContext.isPrepared)
			return;
		sceneContext.isPrepared = true;

		// At this point, the player's position & plane has been updated, so area hiding can be set up
		if (sceneContext.enableAreaHiding)
			removeTilesOutsideCurrentArea(sceneContext);

		// Gaps need to be filled right before scene swap, since map regions aren't updated earlier
		if (sceneContext.fillGaps) {
			sceneContext.staticGapFillerTilesOffset = sceneContext.staticVertexCount;
			fillGaps(sceneContext);
			sceneContext.staticGapFillerTilesVertexCount = sceneContext.staticVertexCount - sceneContext.staticGapFillerTilesOffset;
		}
	}

	public void updatePlayerArea(SceneContext sceneContext) {
		if (!sceneContext.enableAreaHiding) {
			sceneContext.currentArea = null;
			return;
		}

		assert sceneContext.sceneBase != null;
		var lp = client.getLocalPlayer().getLocalLocation();
		int[] worldPos = {
			sceneContext.sceneBase[0] + lp.getSceneX(),
			sceneContext.sceneBase[1] + lp.getSceneY(),
			sceneContext.sceneBase[2] + client.getPlane()
		};

		if (sceneContext.currentArea == null || !sceneContext.currentArea.containsPoint(false, worldPos)) {
			sceneContext.currentArea = null;
			for (var area : sceneContext.possibleAreas) {
				if (area.containsPoint(false, worldPos)) {
					sceneContext.currentArea = area;
					break;
				}
			}
		}
	}

	private void removeTilesOutsideCurrentArea(SceneContext sceneContext) {
		assert sceneContext.sceneBase != null;
		updatePlayerArea(sceneContext);
		if (sceneContext.currentArea == null)
			return;

		var tiles = sceneContext.scene.getExtendedTiles();
		int baseExX = sceneContext.sceneBase[0] - SCENE_OFFSET;
		int baseExY = sceneContext.sceneBase[1] - SCENE_OFFSET;
		int basePlane = sceneContext.sceneBase[2];
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
					Tile tile = tiles[z][x][y];
					if (tile == null)
						continue;

					if (!sceneContext.currentArea.containsPoint(baseExX + x, baseExY + y, basePlane + z))
						sceneContext.scene.removeTile(tile);
				}
			}
		}
	}

	private void fillGaps(SceneContext sceneContext) {
		if (sceneContext.sceneBase == null)
			return;

		var area = sceneContext.currentArea;
		if (area != null && !area.fillGaps)
			return;

		int sceneMin = -sceneContext.expandedMapLoadingChunks * CHUNK_SIZE;
		int sceneMax = SCENE_SIZE + sceneContext.expandedMapLoadingChunks * CHUNK_SIZE;
		int baseExX = sceneContext.sceneBase[0];
		int baseExY = sceneContext.sceneBase[1];
		int basePlane = sceneContext.sceneBase[2];
		Material blackMaterial = materialManager.getMaterial("BLACK");

		Tile[][][] extendedTiles = sceneContext.scene.getExtendedTiles();
		for (int tileZ = 0; tileZ < MAX_Z; ++tileZ) {
			for (int tileExX = 0; tileExX < EXTENDED_SCENE_SIZE; ++tileExX) {
				for (int tileExY = 0; tileExY < EXTENDED_SCENE_SIZE; ++tileExY) {
					if (area != null && !area.containsPoint(baseExX + tileExX, baseExY + tileExY, basePlane + tileZ))
						continue;

					int tileX = tileExX - SCENE_OFFSET;
					int tileY = tileExY - SCENE_OFFSET;
					Tile tile = extendedTiles[tileZ][tileExX][tileExY];

					SceneTilePaint paint;
					SceneTileModel model = null;
					int renderLevel = tileZ;
					if (tile != null) {
						renderLevel = tile.getRenderLevel();
						paint = tile.getSceneTilePaint();
						model = tile.getSceneTileModel();

						if (model == null) {
							boolean hasTilePaint = paint != null && paint.getNeColor() != HIDDEN_HSL;
							if (!hasTilePaint) {
								tile = tile.getBridge();
								if (tile != null) {
									renderLevel = tile.getRenderLevel();
									paint = tile.getSceneTilePaint();
									model = tile.getSceneTileModel();
									hasTilePaint = paint != null && paint.getNeColor() != HIDDEN_HSL;
								}
							}

							if (hasTilePaint)
								continue;
						}
					}

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
						int vertexCount;

						if (model == null) {
							uploadCustomTile(sceneContext, tileExX, tileExY, renderLevel, blackMaterial);
							vertexCount = 6;
						} else {
							int[] worldPos = sceneContext.sceneToWorld(tileX, tileY, tileZ);
							int[] uploadedTileModelData = uploadHDTileModelSurface(sceneContext, tile, worldPos, model, true);
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

	private void uploadModel(SceneContext sceneContext, Tile tile, int uuid, Model model, int orientation) {
		// deduplicate hillskewed models
		if (model.getUnskewedModel() != null)
			model = model.getUnskewedModel();

		if (model.getSceneId() == EXCLUDED_FROM_SCENE_BUFFER)
			return;

		int[] worldPos = sceneContext.localToWorld(tile.getLocalLocation(), tile.getPlane());
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		int sceneId = modelOverride.hashCode() << 16 | sceneContext.id;

		// check if the model has already been uploaded
		if ((model.getSceneId() & SCENE_ID_MASK) == sceneContext.id) {
			// if the same model is being uploaded, but with a different model override,
			// exclude it from the scene buffer to avoid conflicts
			if (model.getSceneId() != sceneId)
				model.setSceneId(EXCLUDED_FROM_SCENE_BUFFER);
			return;
		}

		int vertexOffset = sceneContext.getVertexOffset();
		int uvOffset = sceneContext.getUvOffset();

		if (modelOverride.hide) {
			vertexOffset = -1;
		} else {
			modelPusher.pushModel(sceneContext, tile, uuid, model, modelOverride, orientation, false);
			if (sceneContext.modelPusherResults[1] == 0)
				uvOffset = -1;
		}

		model.setBufferOffset(vertexOffset);
		model.setUvBufferOffset(uvOffset);
		model.setSceneId(sceneId);
		++sceneContext.uniqueModels;
	}

	private void upload(SceneContext sceneContext, @Nonnull Tile tile, int tileExX, int tileExY) {
		Tile bridge = tile.getBridge();
		if (bridge != null)
			upload(sceneContext, bridge, tileExX, tileExY);

		int[] worldPos = sceneContext.localToWorld(tile.getLocalLocation(), tile.getPlane());
		var override = tileOverrideManager.getOverride(sceneContext, tile, worldPos);

		SceneTilePaint sceneTilePaint = tile.getSceneTilePaint();
		if (sceneTilePaint != null || override.forced) {
			sceneContext.filledTiles[tileExX][tileExY] |= (byte) (1 << tile.getPlane());

			boolean depthTested = override.depthTested ||
								  override.forced && (sceneTilePaint == null || sceneTilePaint.getNeColor() == HIDDEN_HSL);

			// Set offsets before pushing new data
			int vertexOffset = sceneContext.getVertexOffset();
			int uvOffset = sceneContext.getUvOffset();
			int[] uploadedTilePaintData = upload(sceneContext, tile, worldPos, override, sceneTilePaint);

			int vertexCount = uploadedTilePaintData[0];
			int uvCount = uploadedTilePaintData[1];

			// Opening the right-click menu causes the game to stop drawing hidden tiles, which prevents us from drawing underwater tiles
			// below the boats at Pest Control, or any other custom tile. To work around this, we can instead draw all hidden tiles at once
			// at the start of the frame. This currently means they will only draw correctly if they're always behind everything else.
			if (vertexCount > 0 && depthTested) {
				int tileX = tileExX - SCENE_OFFSET;
				int tileY = tileExY - SCENE_OFFSET;

				// Draw the tile at the start of each frame
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

				// Since we're now drawing this tile at the beginning of the frame, remove its vertices from the draw callback
				vertexCount = 0;
				uvCount = 0;
			}

			if (uvCount <= 0)
				uvOffset = -1;

			if (sceneTilePaint != null) {
				sceneTilePaint.setBufferLen(vertexCount);
				sceneTilePaint.setBufferOffset(vertexOffset);
				sceneTilePaint.setUvBufferOffset(uvOffset);
			}
		}

		var sceneTileModel = tile.getSceneTileModel();
		if (sceneTileModel != null) {
			sceneContext.filledTiles[tileExX][tileExY] |= (byte) (1 << tile.getPlane());

			// Set offsets before pushing new data
			sceneTileModel.setBufferOffset(sceneContext.getVertexOffset());
			sceneTileModel.setUvBufferOffset(sceneContext.getUvOffset());
			int[] uploadedTileModelData = upload(sceneContext, tile, worldPos, sceneTileModel);

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
				uploadModel(
					sceneContext,
					tile,
					ModelHash.packUuid(ModelHash.TYPE_WALL_OBJECT, wallObject.getId()),
					(Model) renderable1,
					HDUtils.convertWallObjectOrientation(wallObject.getOrientationA())
				);
			}

			Renderable renderable2 = wallObject.getRenderable2();
			if (renderable2 instanceof Model) {
				uploadModel(
					sceneContext,
					tile,
					ModelHash.packUuid(ModelHash.TYPE_WALL_OBJECT, wallObject.getId()),
					(Model) renderable2,
					HDUtils.convertWallObjectOrientation(wallObject.getOrientationB())
				);
			}
		}

		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null) {
			Renderable renderable = groundObject.getRenderable();
			if (renderable instanceof Model) {
				uploadModel(
					sceneContext,
					tile,
					ModelHash.packUuid(ModelHash.TYPE_GROUND_OBJECT, groundObject.getId()),
					(Model) renderable,
					HDUtils.getModelPreOrientation(groundObject.getConfig())
				);
			}
		}

		DecorativeObject decorativeObject = tile.getDecorativeObject();
		if (decorativeObject != null) {
			Renderable renderable = decorativeObject.getRenderable();
			int orientation = HDUtils.getModelPreOrientation(decorativeObject.getConfig());
			if (renderable instanceof Model) {
				uploadModel(
					sceneContext,
					tile,
					ModelHash.packUuid(ModelHash.TYPE_DECORATIVE_OBJECT, decorativeObject.getId()),
					(Model) renderable,
					orientation
				);
			}

			Renderable renderable2 = decorativeObject.getRenderable2();
			if (renderable2 instanceof Model) {
				uploadModel(
					sceneContext,
					tile,
					ModelHash.packUuid(ModelHash.TYPE_DECORATIVE_OBJECT, decorativeObject.getId()),
					(Model) renderable2,
					orientation
				);
			}
		}

		GameObject[] gameObjects = tile.getGameObjects();
		for (GameObject gameObject : gameObjects) {
			if (gameObject == null)
				continue;

			Renderable renderable = gameObject.getRenderable();
			if (renderable instanceof Model) {
				uploadModel(sceneContext,
					tile,
					ModelHash.packUuid(ModelHash.TYPE_GAME_OBJECT, gameObject.getId()),
					(Model) gameObject.getRenderable(),
					HDUtils.getModelPreOrientation(gameObject.getConfig())
				);
			}
		}
	}

	private int[] upload(SceneContext sceneContext, Tile tile, int[] worldPos, TileOverride override, @Nullable SceneTilePaint paint) {
		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int[] bufferLengths;
		WaterType waterType = WaterType.NONE;
		if (paint != null)
			waterType = proceduralGenerator.seasonalWaterType(override, paint.getTexture());

		bufferLengths = uploadHDTilePaintUnderwater(sceneContext, tile, worldPos, waterType);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		bufferLengths = uploadHDTilePaintSurface(sceneContext, tile, worldPos, waterType, paint, override);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
	}

	private int[] uploadHDTilePaintSurface(
		SceneContext sceneContext,
		Tile tile,
		int[] worldPos,
		WaterType waterType,
		@Nullable SceneTilePaint paint,
		TileOverride override
	) {
		final Scene scene = sceneContext.scene;
		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileExX = tileX + SCENE_OFFSET;
		final int tileExY = tileY + SCENE_OFFSET;
		final int tileZ = tile.getRenderLevel();

		final int localX = 0;
		final int localY = 0;

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

		int uvOrientation = 0;
		float uvScale = 1;

		// Ignore certain tiles that aren't supposed to be visible,
		// but which we can still make a height-adjusted version of for underwater
		if ((paint != null && paint.getNeColor() != HIDDEN_HSL) || override.forced)
		{
			int swColor = 0;
			int seColor = 0;
			int neColor = 0;
			int nwColor = 0;
			int textureId = -1;

			if (paint != null) {
				swColor = paint.getSwColor();
				seColor = paint.getSeColor();
				neColor = paint.getNeColor();
				nwColor = paint.getNwColor();
				textureId = paint.getTexture();
			}

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

			if (waterType == WaterType.NONE) {
				if (textureId != -1) {
					var material = materialManager.fromVanillaTexture(textureId);
					// Disable tile overrides for newly introduced vanilla textures
					if (material.isFallbackVanillaMaterial)
						override = NONE;
					swMaterial = seMaterial = neMaterial = nwMaterial = material;
				}

				swNormals = sceneContext.vertexTerrainNormals.getOrDefault(swVertexKey, swNormals);
				seNormals = sceneContext.vertexTerrainNormals.getOrDefault(seVertexKey, seNormals);
				neNormals = sceneContext.vertexTerrainNormals.getOrDefault(neVertexKey, neNormals);
				nwNormals = sceneContext.vertexTerrainNormals.getOrDefault(nwVertexKey, nwNormals);

				boolean useBlendedMaterialAndColor =
					plugin.configGroundBlending &&
					textureId == -1 &&
					!proceduralGenerator.useDefaultColor(tile, override);
				GroundMaterial groundMaterial = null;
				if (override != TileOverride.NONE) {
					groundMaterial = override.groundMaterial;
					uvOrientation = override.uvOrientation;
					uvScale = override.uvScale;
					if (!useBlendedMaterialAndColor) {
						swColor = override.modifyColor(swColor);
						seColor = override.modifyColor(seColor);
						nwColor = override.modifyColor(nwColor);
						neColor = override.modifyColor(neColor);
					}
					swHeight -= override.heightOffset;
					seHeight -= override.heightOffset;
					neHeight -= override.heightOffset;
					nwHeight -= override.heightOffset;
				} else if (textureId == -1) {
					// Fall back to the default ground material if the tile is untextured
					groundMaterial = override.groundMaterial;
				}

				if (useBlendedMaterialAndColor) {
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
				} else if (plugin.configGroundTextures && groundMaterial != null) {
					swMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1], worldPos[2]);
					seMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1], worldPos[2]);
					nwMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1] + 1, worldPos[2]);
					neMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1] + 1, worldPos[2]);
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


			float terrainData = (float) packTerrainData(true, 0, waterType, tileZ);

			sceneContext.stagingBufferNormals.ensureCapacity(24);
			sceneContext.stagingBufferNormals.put(neNormals[0], neNormals[2], neNormals[1], terrainData);
			sceneContext.stagingBufferNormals.put(nwNormals[0], nwNormals[2], nwNormals[1], terrainData);
			sceneContext.stagingBufferNormals.put(seNormals[0], seNormals[2], seNormals[1], terrainData);

			sceneContext.stagingBufferNormals.put(swNormals[0], swNormals[2], swNormals[1], terrainData);
			sceneContext.stagingBufferNormals.put(seNormals[0], seNormals[2], seNormals[1], terrainData);
			sceneContext.stagingBufferNormals.put(nwNormals[0], nwNormals[2], nwNormals[1], terrainData);


			sceneContext.stagingBufferVertices.ensureCapacity(24);
			sceneContext.stagingBufferVertices.put((float) localNeVertexX, neHeight, localNeVertexY, neColor);
			sceneContext.stagingBufferVertices.put((float) localNwVertexX, nwHeight, localNwVertexY, nwColor);
			sceneContext.stagingBufferVertices.put((float) localSeVertexX, seHeight, localSeVertexY, seColor);

			sceneContext.stagingBufferVertices.put((float) localSwVertexX, swHeight, localSwVertexY, swColor);
			sceneContext.stagingBufferVertices.put((float) localSeVertexX, seHeight, localSeVertexY, seColor);
			sceneContext.stagingBufferVertices.put((float) localNwVertexX, nwHeight, localNwVertexY, nwColor);

			bufferLength += 6;


			int packedMaterialDataSW = swMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, swVertexIsOverlay);
			int packedMaterialDataSE = seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, seVertexIsOverlay);
			int packedMaterialDataNW = nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, nwVertexIsOverlay);
			int packedMaterialDataNE = neMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, neVertexIsOverlay);

			float uvcos = -uvScale, uvsin = 0;
			if (uvOrientation % 2048 != 0) {
				float rad = -uvOrientation * JAU_TO_RAD;
				uvcos = cos(rad) * -uvScale;
				uvsin = sin(rad) * -uvScale;
			}
			float uvx = worldPos[0];
			float uvy = worldPos[1];
			float tmp = uvx;
			uvx = uvx * uvcos - uvy * uvsin;
			uvy = tmp * uvsin + uvy * uvcos;

			sceneContext.stagingBufferUvs.ensureCapacity(24);
			sceneContext.stagingBufferUvs.put(uvx, uvy, 0, packedMaterialDataNE);
			sceneContext.stagingBufferUvs.put(uvx - uvcos, uvy - uvsin, 0, packedMaterialDataNW);
			sceneContext.stagingBufferUvs.put(uvx + uvsin, uvy - uvcos, 0, packedMaterialDataSE);

			sceneContext.stagingBufferUvs.put(uvx - uvcos + uvsin, uvy - uvsin - uvcos, 0, packedMaterialDataSW);
			sceneContext.stagingBufferUvs.put(uvx + uvsin, uvy - uvcos, 0, packedMaterialDataSE);
			sceneContext.stagingBufferUvs.put(uvx - uvcos, uvy - uvsin, 0, packedMaterialDataNW);

			uvBufferLength += 6;
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	private int[] uploadHDTilePaintUnderwater(SceneContext sceneContext, Tile tile, int[] worldPos, WaterType waterType) {
		final Scene scene = sceneContext.scene;
		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileExX = tileX + SCENE_OFFSET;
		final int tileExY = tileY + SCENE_OFFSET;
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
				swMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1], worldPos[2]);
				seMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1], worldPos[2]);
				nwMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1] + 1, worldPos[2]);
				neMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1] + 1, worldPos[2]);
			}

			float swTerrainData = (float) packTerrainData(true, max(1, swDepth), waterType, tileZ);
			float seTerrainData = (float) packTerrainData(true, max(1, seDepth), waterType, tileZ);
			float nwTerrainData = (float) packTerrainData(true, max(1, nwDepth), waterType, tileZ);
			float neTerrainData = (float) packTerrainData(true, max(1, neDepth), waterType, tileZ);

			sceneContext.stagingBufferNormals.ensureCapacity(24);
			sceneContext.stagingBufferNormals.put(neNormals[0], neNormals[2], neNormals[1], neTerrainData);
			sceneContext.stagingBufferNormals.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);
			sceneContext.stagingBufferNormals.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);

			sceneContext.stagingBufferNormals.put(swNormals[0], swNormals[2], swNormals[1], swTerrainData);
			sceneContext.stagingBufferNormals.put(seNormals[0], seNormals[2], seNormals[1], seTerrainData);
			sceneContext.stagingBufferNormals.put(nwNormals[0], nwNormals[2], nwNormals[1], nwTerrainData);

			sceneContext.stagingBufferVertices.ensureCapacity(24);
			sceneContext.stagingBufferVertices.put((float) localNeVertexX, neHeight + neDepth, localNeVertexY, neColor);
			sceneContext.stagingBufferVertices.put((float) localNwVertexX, nwHeight + nwDepth, localNwVertexY, nwColor);
			sceneContext.stagingBufferVertices.put((float) localSeVertexX, seHeight + seDepth, localSeVertexY, seColor);

			sceneContext.stagingBufferVertices.put((float) localSwVertexX, swHeight + swDepth, localSwVertexY, swColor);
			sceneContext.stagingBufferVertices.put((float) localSeVertexX, seHeight + seDepth, localSeVertexY, seColor);
			sceneContext.stagingBufferVertices.put((float) localNwVertexX, nwHeight + nwDepth, localNwVertexY, nwColor);

			bufferLength += 6;

			int packedMaterialDataSW = swMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
			int packedMaterialDataSE = seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
			int packedMaterialDataNW = nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
			int packedMaterialDataNE = neMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);

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

	private int[] upload(SceneContext sceneContext, Tile tile, int[] worldPos, SceneTileModel sceneTileModel)
	{
		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		int[] bufferLengths;

		bufferLengths = uploadHDTileModelSurface(sceneContext, tile, worldPos, sceneTileModel, false);
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		bufferLengths = uploadHDTileModelUnderwater(sceneContext, tile, worldPos, sceneTileModel);
		assert bufferLengths[0] == bufferLength || bufferLengths[0] == 0;
		bufferLength += bufferLengths[0];
		uvBufferLength += bufferLengths[1];
		underwaterTerrain += bufferLengths[2];

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	private int[] uploadHDTileModelSurface(SceneContext sceneContext, Tile tile, int[] worldPos, SceneTileModel model, boolean fillGaps) {
		final Scene scene = sceneContext.scene;
		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileExX = tileX + SCENE_OFFSET;
		final int tileExY = tileY + SCENE_OFFSET;
		final int tileZ = tile.getRenderLevel();

		if (sceneContext.skipTile[tileZ][tileExX][tileExY])
			return new int[3];

		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		final int[] faceColorA = model.getTriangleColorA();
		final int[] faceColorB = model.getTriangleColorB();
		final int[] faceColorC = model.getTriangleColorC();
		final int[] faceTextures = model.getTriangleTextureId();
		final int faceCount = model.getFaceX().length;

		int overlayId = OVERLAY_FLAG | scene.getOverlayIds()[tileZ][tileExX][tileExY];
		int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];
		var overlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, overlayId);
		var underlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, underlayId);

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

			int textureId;
			Material materialA = Material.NONE;
			Material materialB = Material.NONE;
			Material materialC = Material.NONE;

			int uvOrientation = 0;
			float uvScale = 1;

			float[] normalsA = UP_NORMAL;
			float[] normalsB = UP_NORMAL;
			float[] normalsC = UP_NORMAL;

			WaterType waterType = WaterType.NONE;

			boolean isHidden = colorA == HIDDEN_HSL;
			if (fillGaps) {
				if (!isHidden)
					continue;
				colorA = colorB = colorC = 0;
			} else {
				boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, face);
				var override = isOverlay ? overlayOverride : underlayOverride;
				if (isHidden && !override.forced)
					continue;

				textureId = faceTextures == null ? -1 : faceTextures[face];
				waterType = proceduralGenerator.seasonalWaterType(override, textureId);
				if (waterType == WaterType.NONE) {
					if (textureId != -1) {
						var material = materialManager.fromVanillaTexture(textureId);
						// Disable tile overrides for newly introduced vanilla textures
						if (material.isFallbackVanillaMaterial)
							override = NONE;
						materialA = materialB = materialC = material;
					}

					normalsA = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyA, normalsA);
					normalsB = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyB, normalsB);
					normalsC = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyC, normalsC);

					GroundMaterial groundMaterial = null;

					boolean useBlendedMaterialAndColor =
						plugin.configGroundBlending &&
						textureId == -1 &&
						!(isOverlay && proceduralGenerator.useDefaultColor(tile, override));
					if (override != TileOverride.NONE) {
						groundMaterial = override.groundMaterial;
						uvOrientation = override.uvOrientation;
						uvScale = override.uvScale;
						if (!useBlendedMaterialAndColor) {
							colorA = override.modifyColor(colorA);
							colorB = override.modifyColor(colorB);
							colorC = override.modifyColor(colorC);
						}
					} else if (textureId == -1) {
						// Fall back to the default ground material if the tile is untextured
						groundMaterial = override.groundMaterial;
					}

					if (useBlendedMaterialAndColor) {
						// get the vertices' colors and textures from hashmaps
						colorA = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyA, colorA);
						colorB = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyB, colorB);
						colorC = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyC, colorC);

						if (plugin.configGroundTextures) {
							materialA = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyA, materialA);
							materialB = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyB, materialB);
							materialC = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyC, materialC);
						}
					} else if (plugin.configGroundTextures && groundMaterial != null) {
						materialA = groundMaterial.getRandomMaterial(
							worldPos[0] + (localVertices[0][0] >> LOCAL_COORD_BITS),
							worldPos[1] + (localVertices[0][1] >> LOCAL_COORD_BITS),
							worldPos[2]
						);
						materialB = groundMaterial.getRandomMaterial(
							worldPos[0] + (localVertices[1][0] >> LOCAL_COORD_BITS),
							worldPos[1] + (localVertices[1][1] >> LOCAL_COORD_BITS),
							worldPos[2]
						);
						materialC = groundMaterial.getRandomMaterial(
							worldPos[0] + (localVertices[2][0] >> LOCAL_COORD_BITS),
							worldPos[1] + (localVertices[2][1] >> LOCAL_COORD_BITS),
							worldPos[2]
						);
					}
				} else {
					// set colors for the shoreline to create a foam effect in the water shader
					textureId = -1;
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

				for (int i = 0; i < 3; i++)
					localVertices[i][2] -= override.heightOffset;
			}

			float terrainData = (float) packTerrainData(true, 0, waterType, tileZ);

			sceneContext.stagingBufferNormals.ensureCapacity(12);
			sceneContext.stagingBufferNormals.put(normalsA[0], normalsA[2], normalsA[1], terrainData);
			sceneContext.stagingBufferNormals.put(normalsB[0], normalsB[2], normalsB[1], terrainData);
			sceneContext.stagingBufferNormals.put(normalsC[0], normalsC[2], normalsC[1], terrainData);

			sceneContext.stagingBufferVertices.ensureCapacity(12);
			sceneContext.stagingBufferVertices.put((float) localVertices[0][0], localVertices[0][2], localVertices[0][1], colorA);
			sceneContext.stagingBufferVertices.put((float) localVertices[1][0], localVertices[1][2], localVertices[1][1], colorB);
			sceneContext.stagingBufferVertices.put((float) localVertices[2][0], localVertices[2][2], localVertices[2][1], colorC);

			bufferLength += 3;

			int[] packedMaterialData = {
				materialA.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexAIsOverlay),
				materialB.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexBIsOverlay),
				materialC.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexCIsOverlay)
			};

			float uvcos = -uvScale, uvsin = 0;
			if (uvOrientation % 2048 != 0) {
				float rad = -uvOrientation * JAU_TO_RAD;
				uvcos = cos(rad) * -uvScale;
				uvsin = sin(rad) * -uvScale;
			}

			sceneContext.stagingBufferUvs.ensureCapacity(12);
			for (int i = 0; i < 3; i++) {
				float uvx = worldPos[0] + localVertices[i][0] / 128f - 1;
				float uvy = worldPos[1] + localVertices[i][1] / 128f - 1;
				float tmp = uvx;
				uvx = uvx * uvcos - uvy * uvsin;
				uvy = tmp * uvsin + uvy * uvcos;

				sceneContext.stagingBufferUvs.put(uvx, uvy, 0, packedMaterialData[i]);
			}

			uvBufferLength += 3;
		}

		return new int[]{bufferLength, uvBufferLength, underwaterTerrain};
	}

	private int[] uploadHDTileModelUnderwater(SceneContext sceneContext, Tile tile, int[] worldPos, SceneTileModel model) {
		final Scene scene = sceneContext.scene;
		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileExX = tileX + SCENE_OFFSET;
		final int tileExY = tileY + SCENE_OFFSET;
		final int tileZ = tile.getRenderLevel();

		int bufferLength = 0;
		int uvBufferLength = 0;
		int underwaterTerrain = 0;

		if (sceneContext.skipTile[tileZ][tileExX][tileExY]) {
			return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
		}

		final int[] faceColorA = model.getTriangleColorA();
		final int faceCount = model.getFaceX().length;
		final int[] faceTextures = model.getTriangleTextureId();

		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		if (baseX >= 2816 && baseX <= 2970 && baseY <= 5375 && baseY >= 5220) {
			// fix for God Wars Dungeon's water rendering over zamorak bridge
			return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
		}

		if (sceneContext.tileIsWater[tileZ][tileExX][tileExY]) {
			underwaterTerrain = 1;

			int overlayId = OVERLAY_FLAG | scene.getOverlayIds()[tileZ][tileExX][tileExY];
			int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];
			var overlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, overlayId);
			var underlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, underlayId);

			// underwater terrain
			for (int face = 0; face < faceCount; ++face) {
				int colorA = 6676;
				int colorB = 6676;
				int colorC = 6676;

				boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, face);
				var override = isOverlay ? overlayOverride : underlayOverride;
				if (faceColorA[face] == HIDDEN_HSL && !override.forced)
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
					materialA = groundMaterial.getRandomMaterial(
						worldPos[0] + (localVertices[0][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (localVertices[0][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
					materialB = groundMaterial.getRandomMaterial(
						worldPos[0] + (localVertices[1][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (localVertices[1][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
					materialC = groundMaterial.getRandomMaterial(
						worldPos[0] + (localVertices[2][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (localVertices[2][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
				}

				float[] normalsA = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyA, UP_NORMAL);
				float[] normalsB = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyB, UP_NORMAL);
				float[] normalsC = sceneContext.vertexTerrainNormals.getOrDefault(vertexKeyC, UP_NORMAL);

				int textureId = faceTextures == null ? -1 : faceTextures[face];
				WaterType waterType = proceduralGenerator.seasonalWaterType(override, textureId);

				float aTerrainData = (float) packTerrainData(true, max(1, depthA), waterType, tileZ);
				float bTerrainData = (float) packTerrainData(true, max(1, depthB), waterType, tileZ);
				float cTerrainData = (float) packTerrainData(true, max(1, depthC), waterType, tileZ);

				sceneContext.stagingBufferNormals.ensureCapacity(12);
				sceneContext.stagingBufferNormals.put(normalsA[0], normalsA[2], normalsA[1], aTerrainData);
				sceneContext.stagingBufferNormals.put(normalsB[0], normalsB[2], normalsB[1], bTerrainData);
				sceneContext.stagingBufferNormals.put(normalsC[0], normalsC[2], normalsC[1], cTerrainData);

				sceneContext.stagingBufferVertices.ensureCapacity(12);
				sceneContext.stagingBufferVertices.put(
					(float) localVertices[0][0],
					localVertices[0][2] + depthA,
					localVertices[0][1],
					colorA
				);
				sceneContext.stagingBufferVertices.put(
					(float) localVertices[1][0],
					localVertices[1][2] + depthB,
					localVertices[1][1],
					colorB
				);
				sceneContext.stagingBufferVertices.put(
					(float) localVertices[2][0],
					localVertices[2][2] + depthC,
					localVertices[2][1],
					colorC
				);

				bufferLength += 3;

				int packedMaterialDataA = materialA.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
				int packedMaterialDataB = materialB.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
				int packedMaterialDataC = materialC.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);

				sceneContext.stagingBufferUvs.ensureCapacity(12);
				sceneContext.stagingBufferUvs.put(1 - localVertices[0][0] / 128f, 1 - localVertices[0][1] / 128f, 0, packedMaterialDataA);
				sceneContext.stagingBufferUvs.put(1 - localVertices[1][0] / 128f, 1 - localVertices[1][1] / 128f, 0, packedMaterialDataB);
				sceneContext.stagingBufferUvs.put(1 - localVertices[2][0] / 128f, 1 - localVertices[2][1] / 128f, 0, packedMaterialDataC);

				uvBufferLength += 3;
			}
		}

		return new int[] { bufferLength, uvBufferLength, underwaterTerrain };
	}

	private void uploadCustomTile(SceneContext sceneContext, int tileExX, int tileExY, int tileZ, Material material) {
		final Scene scene = sceneContext.scene;

		int color = 0;
		float fromX = 0;
		float fromY = 0;
		float toX = LOCAL_TILE_SIZE;
		float toY = LOCAL_TILE_SIZE;

		final int[][][] tileHeights = scene.getTileHeights();
		int swHeight = tileHeights[tileZ][tileExX][tileExY];
		int seHeight = tileHeights[tileZ][tileExX + 1][tileExY];
		int neHeight = tileHeights[tileZ][tileExX + 1][tileExY + 1];
		int nwHeight = tileHeights[tileZ][tileExX][tileExY + 1];

		float terrainData = (float) packTerrainData(true, 0, WaterType.NONE, tileZ);

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

		int packedMaterialData = material.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);

		sceneContext.stagingBufferUvs.ensureCapacity(24);
		sceneContext.stagingBufferUvs.put(0, 0, 0, packedMaterialData);
		sceneContext.stagingBufferUvs.put(1, 0, 0, packedMaterialData);
		sceneContext.stagingBufferUvs.put(0, 1, 0, packedMaterialData);

		sceneContext.stagingBufferUvs.put(1, 1, 0, packedMaterialData);
		sceneContext.stagingBufferUvs.put(0, 1, 0, packedMaterialData);
		sceneContext.stagingBufferUvs.put(1, 0, 0, packedMaterialData);
	}

	public static int packTerrainData(boolean isTerrain, int waterDepth, WaterType waterType, int plane) {
		// Up to 16-bit water depth | 5-bit water type | 2-bit plane | terrain flag
		assert waterType.index < 1 << 5 : "Too many water types";
		int terrainData = (waterDepth & 0xFFFF) << 8 | waterType.index << 3 | plane << 1 | (isTerrain ? 1 : 0);
		assert (terrainData & ~0xFFFFFF) == 0 : "Only the lower 24 bits are usable, since we pass this into shaders as a float";
		return terrainData;
	}
}
