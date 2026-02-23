/*
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

import java.util.Arrays;
import java.util.HashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.renderer.legacy.LegacySceneContext;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.TzHaarRecolorType;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collection.Int2IntHashMap;
import rs117.hd.utils.collection.Int2ObjectHashMap;
import rs117.hd.utils.collection.IntHashSet;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.HDUtils.calculateSurfaceNormals;
import static rs117.hd.utils.HDUtils.fastVertexHash;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class ProceduralGenerator {
	public static final int[] DEPTH_LEVEL_SLOPE = new int[] { 150, 300, 470, 610, 700, 750, 820, 920, 1080, 1300, 1350, 1380 };
	public static final int MAX_DEPTH = DEPTH_LEVEL_SLOPE[DEPTH_LEVEL_SLOPE.length - 1];

	public static final int VERTICES_PER_FACE = 3;
	public static final boolean[][] TILE_OVERLAY_TRIS = new boolean[][]
		{
			/*  0 */ { true, true, true, true }, // Used by tilemodels of varying tri counts?
			/*  1 */ { false, true },
			/*  2 */ { false, false, true },
			/*  3 */ { false, false, true },
			/*  4 */ { false, true, true },
			/*  5 */ { false, true, true },
			/*  6 */ { false, false, true, true },
			/*  7 */ { false, false, false, true },
			/*  8 */ { false, true, true, true },
			/*  9 */ { false, false, false, true, true, true },
			/* 10 */ { true, true, true, false, false, false },
			/* 11 */ { true, true, false, false, false, false },
		};

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private WaterTypeManager waterTypeManager;

	public void generateSceneData(SceneContext sceneContext, SceneContext previousContext)
	{
		long timerTotal = System.currentTimeMillis();
		long timerCalculateTerrainNormals, timerGenerateTerrainData, timerGenerateUnderwaterTerrain;

		long startTime = System.currentTimeMillis();
		generateUnderwaterTerrain(sceneContext, previousContext);
		timerGenerateUnderwaterTerrain = (int)(System.currentTimeMillis() - startTime);
		startTime = System.currentTimeMillis();
		calculateTerrainNormals(sceneContext, previousContext);
		timerCalculateTerrainNormals = (int)(System.currentTimeMillis() - startTime);
		startTime = System.currentTimeMillis();
		generateTerrainData(sceneContext, previousContext);
		timerGenerateTerrainData = (int)(System.currentTimeMillis() - startTime);

		log.debug("procedural data generation took {}ms to complete", (System.currentTimeMillis() - timerTotal));
		log.debug("-- calculateTerrainNormals: {}ms", timerCalculateTerrainNormals);
		log.debug("-- generateTerrainData: {}ms", timerGenerateTerrainData);
		log.debug("-- generateUnderwaterTerrain: {}ms", timerGenerateUnderwaterTerrain);
	}

	public void clearSceneData(SceneContext sceneContext) {
		sceneContext.tileIsWater = null;
		sceneContext.vertexIsWater = null;
		sceneContext.vertexIsLand = null;
		sceneContext.vertexIsOverlay = null;
		sceneContext.vertexIsUnderlay = null;
		sceneContext.skipTile = null;
		sceneContext.vertexUnderwaterDepth = null;
		if (!(sceneContext instanceof LegacySceneContext))
			sceneContext.underwaterDepthLevels = null;
	}

	/**
	 * Iterates through all Tiles in a given Scene, producing color and
	 * material data for each vertex of each Tile. Then adds the resulting
	 * data to appropriate HashMaps.
	 */
	private void generateTerrainData(SceneContext sceneContext, SceneContext previousContext)
	{
		sceneContext.vertexTerrainColor = new Int2IntHashMap(previousContext != null && previousContext.vertexTerrainColor != null ? previousContext.vertexTerrainColor.size() : 16);
		// used for overriding potentially undesirable vertex colors
		// for example, colors that aren't supposed to be visible
		sceneContext.highPriorityColor = new IntHashSet(previousContext != null && previousContext.highPriorityColor != null ? previousContext.highPriorityColor.size() : 16);
		sceneContext.vertexTerrainTexture = new Int2ObjectHashMap<>(previousContext != null && previousContext.vertexTerrainTexture != null ? previousContext.vertexTerrainTexture.size() : 16, Material[]::new);
		// for faces without an overlay is set to true
		sceneContext.vertexIsUnderlay = new IntHashSet(previousContext != null && previousContext.vertexIsUnderlay != null ? previousContext.vertexIsUnderlay.size() : 16);
		// for faces with an overlay is set to true
		// the result of these maps can be used to determine the vertices
		// between underlays and overlays for custom blending
		sceneContext.vertexIsOverlay = new IntHashSet(previousContext != null && previousContext.vertexIsOverlay != null ? previousContext.vertexIsOverlay.size() : 16);

		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		int sizeX = sceneContext.sizeX;
		int sizeY = sceneContext.sizeZ;
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < sizeX; ++x)
				for (int y = 0; y < sizeY; ++y)
					if (tiles[z][x][y] != null)
						generateDataForTile(sceneContext, tiles[z][x][y], x, y);

			for (int x = 0; x < sizeX; ++x)
				for (int y = 0; y < sizeY; ++y)
					if (tiles[z][x][y] != null && tiles[z][x][y].getBridge() != null)
						generateDataForTile(sceneContext, tiles[z][x][y].getBridge(), x, y);
		}
	}

	/**
	 * Produces color and material data for the vertices of the provided Tile.
	 * Then adds the resulting data to appropriate HashMaps.
	 *
	 * @param sceneContext that the tile is associated with
	 * @param tile         to generate terrain data for
	 */
	private void generateDataForTile(SceneContext sceneContext, Tile tile, int tileExX, int tileExY)
	{
		int faceCount;
		if (tile.getSceneTilePaint() != null) {
			faceCount = 2;
		} else if (tile.getSceneTileModel() != null) {
			faceCount = tile.getSceneTileModel().getFaceX().length;
		} else {
			return;
		}

		int[] vertexHashes = new int[faceCount * VERTICES_PER_FACE];
		int[] vertexColors = new int[faceCount * VERTICES_PER_FACE];
		TileOverride[] vertexOverrides = new TileOverride[faceCount * VERTICES_PER_FACE];
		boolean[] vertexIsOverlay = new boolean[faceCount * VERTICES_PER_FACE];
		boolean[] vertexDefaultColor = new boolean[faceCount * VERTICES_PER_FACE];

		int tileX = tileExX - sceneContext.sceneOffset;
		int tileY = tileExY - sceneContext.sceneOffset;
		int tileZ = tile.getRenderLevel();
		int[] worldPos = sceneContext.sceneToWorld(tileX, tileY, tileZ);

		Scene scene = sceneContext.scene;
		if (tile.getSceneTilePaint() != null) {
			// tile paint

			var override = tileOverrideManager.getOverride(sceneContext, tile, worldPos);
			if (override.waterType != WaterType.NONE) {
				// skip water tiles
				return;
			}

			int swColor = tile.getSceneTilePaint().getSwColor();
			int seColor = tile.getSceneTilePaint().getSeColor();
			int nwColor = tile.getSceneTilePaint().getNwColor();
			int neColor = tile.getSceneTilePaint().getNeColor();

			vertexHashes = tileVertexKeys(sceneContext, tile);

			if (tileExX >= EXTENDED_SCENE_SIZE - 2 && tileExY >= EXTENDED_SCENE_SIZE - 2) {
				// reduce the black scene edges by assigning surrounding colors
				neColor = swColor;
				nwColor = swColor;
				seColor = swColor;
			} else if (tileExY >= EXTENDED_SCENE_SIZE - 2) {
				nwColor = swColor;
				neColor = seColor;
			} else if (tileExX >= EXTENDED_SCENE_SIZE - 2) {
				neColor = nwColor;
				seColor = swColor;
			}

			vertexColors[0] = swColor;
			vertexColors[1] = seColor;
			vertexColors[2] = nwColor;
			vertexColors[3] = neColor;

			for (int i = 0; i < 4; i++) {
				vertexOverrides[i] = override;
				vertexIsOverlay[i] = override.queriedAsOverlay;
			}
			if (useDefaultColor(tile, override))
				for (int i = 0; i < 4; i++)
					vertexDefaultColor[i] = true;
		}
		else if (tile.getSceneTileModel() != null)
		{
			// tile model

			SceneTileModel sceneTileModel = tile.getSceneTileModel();

			final int[] faceColorsA = sceneTileModel.getTriangleColorA();
			final int[] faceColorsB = sceneTileModel.getTriangleColorB();
			final int[] faceColorsC = sceneTileModel.getTriangleColorC();

			int overlayId = OVERLAY_FLAG | scene.getOverlayIds()[tileZ][tileExX][tileExY];
			int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];
			var overlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, overlayId);
			var underlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, underlayId);

			final int[][] vertices = new int[4][3];
			final int[] vertexKeys = new int[4];
			final int[] faceColors = new int[3];
			for (int face = 0; face < faceCount; face++) {
				faceColors[0] = faceColorsA[face];
				faceColors[1] = faceColorsB[face];
				faceColors[2] = faceColorsC[face];
				faceVertexKeys(tile, face, vertices, vertexKeys);

				for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
					boolean isOverlay = isOverlayFace(tile, face);
					var override = isOverlay ? overlayOverride : underlayOverride;
					if (override.waterType != WaterType.NONE)
						continue; // skip water faces

					vertexHashes[face * VERTICES_PER_FACE + vertex] = vertexKeys[vertex];

					int color = faceColors[vertex];
					vertexColors[face * VERTICES_PER_FACE + vertex] = color;

					vertexOverrides[face * VERTICES_PER_FACE + vertex] = override;
					vertexIsOverlay[face * VERTICES_PER_FACE + vertex] = isOverlay;

					if (isOverlay && useDefaultColor(tile, override))
						vertexDefaultColor[face * VERTICES_PER_FACE + vertex] = true;
				}
			}
		}

		for (int vertex = 0; vertex < vertexHashes.length; vertex++)
		{
			if (vertexHashes[vertex] == 0)
				continue;

			int color = vertexColors[vertex];
			var override = vertexOverrides[vertex];
			if (color < 0 || color == HIDDEN_HSL && !override.forced)
				continue;

			// if this vertex already has a 'high priority' color assigned,
			// skip assigning a 'low priority' color unless there is no color assigned.
			// Near-solid-black tiles that are used in some places under wall objects
			boolean lowPriorityColor = vertexColors[vertex] <= 2;

			float lightenMultiplier = 1.5f;
			int lightenBase = 15;
			int lightenAdd = 3;
			float darkenMultiplier = 0.5f;

			int[] vNormals = sceneContext.vertexTerrainNormals.getOrDefault(vertexHashes[vertex], new int[] { 0, 0, 0 });

			float dot = dot(vNormals);
			if (dot < EPSILON) {
				dot = 0;
			} else {
				// Approximately reverse vanilla tile lighting
				dot = (vNormals[0] + vNormals[1]) / sqrt(2 * dot);
			}
			int lightness = color & 0x7F;
			lightness = (int) mix(lightness, (int) (max(lightness - lightenAdd, 0) * lightenMultiplier) + lightenBase, max(dot, 0));
			lightness = (int) (1.25f * mix(lightness, (int) (lightness * darkenMultiplier), -min(dot, 0)));
			final int maxBrightness = 55; // reduces overexposure
			lightness = min(lightness, maxBrightness);
			color = color & ~0x7F | lightness;

			Material material = override.groundMaterial.getRandomMaterial(worldPos);
			boolean isOverlay = vertexIsOverlay[vertex] != override.blendedAsOpposite;
			color = override.modifyColor(color);

			vertexColors[vertex] = color;

			// mark the vertex as either an overlay or underlay.
			// this is used to determine how to blend between vertex colors
			if (isOverlay)
			{
				sceneContext.vertexIsOverlay.add(vertexHashes[vertex]);
			}
			else
			{
				sceneContext.vertexIsUnderlay.add(vertexHashes[vertex]);
			}

			// add color and texture to hashmap
			if ((!lowPriorityColor || !sceneContext.highPriorityColor.contains(vertexHashes[vertex])) && !vertexDefaultColor[vertex])
			{
				boolean shouldWrite = isOverlay || !sceneContext.vertexTerrainColor.containsKey(vertexHashes[vertex]);
				if (shouldWrite || !sceneContext.vertexTerrainColor.containsKey(vertexHashes[vertex]))
					sceneContext.vertexTerrainColor.put(vertexHashes[vertex], vertexColors[vertex]);

				if (shouldWrite || !sceneContext.vertexTerrainTexture.containsKey(vertexHashes[vertex]))
					sceneContext.vertexTerrainTexture.put(vertexHashes[vertex], material);

				if (!lowPriorityColor)
					sceneContext.highPriorityColor.add(vertexHashes[vertex]);
			}
		}
	}

	/**
	 * Generates underwater terrain data by iterating through all Tiles in a given
	 * Scene, increasing the depth of each tile based on its distance from the shore.
	 * Then stores the resulting data in a HashMap.
	 */
	private void generateUnderwaterTerrain(SceneContext sceneContext, SceneContext previousContext)
	{
		int sizeX = sceneContext.sizeX;
		int sizeY = sceneContext.sizeZ;
		// true if a tile contains at least 1 face which qualifies as water
		sceneContext.tileIsWater = new boolean[MAX_Z][sizeX][sizeY];
		// true if a vertex is part of a face which qualifies as water; non-existent if not
		sceneContext.vertexIsWater = new IntHashSet(previousContext != null && sceneContext.vertexIsWater != null ? sceneContext.vertexIsWater.size() : 0);
		// true if a vertex is part of a face which qualifies as land; non-existent if not
		// tiles along the shoreline will be true for both vertexIsWater and vertexIsLand
		sceneContext.vertexIsLand = new IntHashSet(previousContext != null && sceneContext.vertexIsLand != null ? sceneContext.vertexIsLand.size() : 0);
		// if true, the tile will be skipped when the scene is drawn
		// this is due to certain edge cases with water on the same X/Y on different planes
		sceneContext.skipTile = new boolean[MAX_Z][sizeX][sizeY];
		// the height adjustment for each vertex, to be applied to the vertex'
		// real height to create the underwater terrain
		sceneContext.vertexUnderwaterDepth = new Int2IntHashMap(previousContext != null && sceneContext.vertexUnderwaterDepth != null ? sceneContext.vertexUnderwaterDepth.size() : 0);
		// the basic 'levels' of underwater terrain, used to sink terrain based on its distance
		// from the shore, then used to produce the world-space height offset
		// 0 = land
		sceneContext.underwaterDepthLevels = new int[MAX_Z][sizeX + 1][sizeY + 1];
		// the world-space height offsets of each vertex on the tile grid
		// these offsets are interpolated to calculate offsets for vertices not on the grid (tilemodels)
		final int[][][] underwaterDepths = new int[MAX_Z][sizeX + 1][sizeY + 1];

		for (int z = 0; z < MAX_Z; ++z)
		{
			for (int x = 0; x < sizeX; ++x) {
				// set the array to 1 initially
				// this assumes that all vertices are water;
				// we will set non-water vertices to 0 in the next loop
				Arrays.fill(sceneContext.underwaterDepthLevels[z][x], 1);
			}
		}

		Scene scene = sceneContext.scene;
		final Tile[][][] tiles = scene.getExtendedTiles();
		final int[][] vertices = new int[4][3];
		final int[] vertexKeys = new int[4];

		// figure out which vertices are water and assign some data
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < sizeX; ++x) {
				for (int y = 0; y < sizeY; ++y) {
					if (tiles[z][x][y] == null) {
						sceneContext.underwaterDepthLevels[z][x][y] = 0;
						sceneContext.underwaterDepthLevels[z][x + 1][y] = 0;
						sceneContext.underwaterDepthLevels[z][x][y + 1] = 0;
						sceneContext.underwaterDepthLevels[z][x + 1][y + 1] = 0;
						continue;
					}

					Tile tile = tiles[z][x][y];
					if (tile.getBridge() != null) {
						tile = tile.getBridge();
					}

					if (tile.getSceneTilePaint() != null) {
						 tileVertexKeys(sceneContext, tile, vertexKeys);

						int[] worldPos = sceneContext.extendedSceneToWorld(x, y, tile.getRenderLevel());
						var override = tileOverrideManager.getOverride(sceneContext, tile, worldPos);
						if (seasonalWaterType(override, tile.getSceneTilePaint().getTexture()) == WaterType.NONE) {
							for (int vertexKey : vertexKeys)
								if (tile.getSceneTilePaint().getNeColor() != HIDDEN_HSL || override.forced)
									sceneContext.vertexIsLand.add(vertexKey);

							sceneContext.underwaterDepthLevels[z][x][y] = 0;
							sceneContext.underwaterDepthLevels[z][x + 1][y] = 0;
							sceneContext.underwaterDepthLevels[z][x][y + 1] = 0;
							sceneContext.underwaterDepthLevels[z][x + 1][y + 1] = 0;
						} else {
							// Stop tiles on the same X,Y coordinates on different planes from
							// each generating water. Prevents undesirable results in certain places.
							if (z > 0) {
								boolean continueLoop = false;

								for (int checkZ = 0; checkZ < z; ++checkZ) {
									if (sceneContext.tileIsWater[checkZ][x][y]) {
										sceneContext.underwaterDepthLevels[z][x][y] = 0;
										sceneContext.underwaterDepthLevels[z][x + 1][y] = 0;
										sceneContext.underwaterDepthLevels[z][x][y + 1] = 0;
										sceneContext.underwaterDepthLevels[z][x + 1][y + 1] = 0;

										sceneContext.skipTile[z][x][y] = true;

										continueLoop = true;

										break;
									}
								}

								if (continueLoop)
									continue;
							}

							sceneContext.tileIsWater[z][x][y] = true;

							for (int vertexKey : vertexKeys)
							{
								sceneContext.vertexIsWater.add(vertexKey);
							}
						}
					}
					else if (tile.getSceneTileModel() != null)
					{
						SceneTileModel model = tile.getSceneTileModel();

						int faceCount = model.getFaceX().length;

						int tileZ = tile.getRenderLevel();
						int[] worldPos = sceneContext.extendedSceneToWorld(x, y, tileZ);
						int overlayId = OVERLAY_FLAG | scene.getOverlayIds()[tileZ][x][y];
						int underlayId = scene.getUnderlayIds()[tileZ][x][y];
						var overlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, overlayId);
						var underlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, underlayId);

						// Stop tiles on the same X,Y coordinates on different planes from
						// each generating water. Prevents undesirable results in certain places.
						if (z > 0)
						{
							boolean tileIncludesWater = false;
							for (int face = 0; face < faceCount; face++)
							{
								var override = ProceduralGenerator.isOverlayFace(tile, face) ? overlayOverride : underlayOverride;
								int textureId = model.getTriangleTextureId() == null ? -1 :
									model.getTriangleTextureId()[face];
								if (seasonalWaterType(override, textureId) != WaterType.NONE)
								{
									tileIncludesWater = true;
									break;
								}
							}

							if (tileIncludesWater)
							{
								boolean continueLoop = false;

								for (int checkZ = 0; checkZ < z; ++checkZ)
								{
									if (sceneContext.tileIsWater[checkZ][x][y])
									{
										sceneContext.underwaterDepthLevels[z][x][y] = 0;
										sceneContext.underwaterDepthLevels[z][x + 1][y] = 0;
										sceneContext.underwaterDepthLevels[z][x][y + 1] = 0;
										sceneContext.underwaterDepthLevels[z][x + 1][y + 1] = 0;

										sceneContext.skipTile[z][x][y] = true;

										continueLoop = true;

										break;
									}
								}

								if (continueLoop)
									continue;
							}
						}

						for (int face = 0; face < faceCount; face++)
						{
							faceVertexKeys(tile, face, vertices, vertexKeys);

							var override = ProceduralGenerator.isOverlayFace(tile, face) ? overlayOverride : underlayOverride;
							int textureId = model.getTriangleTextureId() == null ? -1 :
								model.getTriangleTextureId()[face];
							if (seasonalWaterType(override, textureId) == WaterType.NONE)
							{
								for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
								{
									if (model.getTriangleColorA()[face] != HIDDEN_HSL || override.forced)
										sceneContext.vertexIsLand.add(vertexKeys[vertex]);

									if (vertices[vertex][0] % LOCAL_TILE_SIZE == 0 &&
										vertices[vertex][1] % LOCAL_TILE_SIZE == 0
									) {
										int vX = (vertices[vertex][0] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
										int vY = (vertices[vertex][1] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;

										sceneContext.underwaterDepthLevels[z][vX][vY] = 0;
									}
								}
							}
							else
							{
								sceneContext.tileIsWater[z][x][y] = true;

								for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
								{
									sceneContext.vertexIsWater.add(vertexKeys[vertex]);
								}
							}
						}
					}
					else
					{
						sceneContext.underwaterDepthLevels[z][x][y] = 0;
						sceneContext.underwaterDepthLevels[z][x + 1][y] = 0;
						sceneContext.underwaterDepthLevels[z][x][y + 1] = 0;
						sceneContext.underwaterDepthLevels[z][x + 1][y + 1] = 0;
					}
				}
			}
		}

		// Sink terrain further from shore by desired levels.
		for (int level = 0; level < DEPTH_LEVEL_SLOPE.length - 1; level++)
		{
			for (int z = 0; z < MAX_Z; ++z)
			{
				for (int x = 0; x < sceneContext.underwaterDepthLevels[z].length; x++)
				{
					for (int y = 0; y < sceneContext.underwaterDepthLevels[z][x].length; y++)
					{
						if (sceneContext.underwaterDepthLevels[z][x][y] == 0)
						{
							// Skip the tile if it isn't water.
							continue;
						}
						// If it's on the edge of the scene, reset the depth so
						// it creates a 'wall' to prevent fog from passing through.
						// Not incredibly effective, but better than nothing.
						if (x == 0 || y == 0 || x == EXTENDED_SCENE_SIZE || y == EXTENDED_SCENE_SIZE) {
							sceneContext.underwaterDepthLevels[z][x][y] = 0;
							continue;
						}

						int tileHeight = sceneContext.underwaterDepthLevels[z][x][y];
						if (sceneContext.underwaterDepthLevels[z][x - 1][y] < tileHeight)
						{
							// West
							continue;
						}
						if (x < sceneContext.underwaterDepthLevels[z].length - 1 && sceneContext.underwaterDepthLevels[z][x + 1][y] < tileHeight)
						{
							// East
							continue;
						}
						if (sceneContext.underwaterDepthLevels[z][x][y - 1] < tileHeight)
						{
							// South
							continue;
						}
						if (y < sceneContext.underwaterDepthLevels[z].length - 1 && sceneContext.underwaterDepthLevels[z][x][y + 1] < tileHeight)
						{
							// North
							continue;
						}
						// At this point, it's surrounded only by other depth-adjusted vertices.
						sceneContext.underwaterDepthLevels[z][x][y]++;
					}
				}
			}
		}

		// Adjust the height levels to world coordinate offsets and add to an array.
		for (int z = 0; z < MAX_Z; ++z)
		{
			for (int x = 0; x < sceneContext.underwaterDepthLevels[z].length; x++)
			{
				for (int y = 0; y < sceneContext.underwaterDepthLevels[z][x].length; y++)
				{
					if (sceneContext.underwaterDepthLevels[z][x][y] == 0)
					{
						continue;
					}
					int depth = DEPTH_LEVEL_SLOPE[sceneContext.underwaterDepthLevels[z][x][y] - 1];
					int heightOffset = (int) (depth * .55f); // legacy weirdness
					underwaterDepths[z][x][y] = heightOffset;
				}
			}
		}

		// Store the height offsets in a hashmap and calculate interpolated
		// height offsets for non-corner vertices.
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < sizeX; ++x) {
				for (int y = 0; y < sizeY; ++y) {
					if (!sceneContext.tileIsWater[z][x][y]) {
						continue;
					}

					Tile tile = tiles[z][x][y];
					if (tile == null) {
						continue;
					}

					if (tile.getBridge() != null) {
						tile = tile.getBridge();
					}
					if (tile.getSceneTilePaint() != null) {
						tileVertexKeys(sceneContext, tile, vertexKeys);

						int swVertexKey = vertexKeys[0];
						int seVertexKey = vertexKeys[1];
						int nwVertexKey = vertexKeys[2];
						int neVertexKey = vertexKeys[3];

						sceneContext.vertexUnderwaterDepth.put(swVertexKey, underwaterDepths[z][x][y]);
						sceneContext.vertexUnderwaterDepth.put(seVertexKey, underwaterDepths[z][x + 1][y]);
						sceneContext.vertexUnderwaterDepth.put(nwVertexKey, underwaterDepths[z][x][y + 1]);
						sceneContext.vertexUnderwaterDepth.put(neVertexKey, underwaterDepths[z][x + 1][y + 1]);
					}
					else if (tile.getSceneTileModel() != null)
					{
						SceneTileModel sceneTileModel = tile.getSceneTileModel();

						int faceCount = sceneTileModel.getFaceX().length;

						for (int face = 0; face < faceCount; face++)
						{
							faceVertexKeys(tile, face, vertices, vertexKeys);

							for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
							{
								if (vertices[vertex][0] % LOCAL_TILE_SIZE == 0 &&
									vertices[vertex][1] % LOCAL_TILE_SIZE == 0
								) {
									// The vertex is at the corner of the tile;
									// simply use the offset in the tile grid array.

									int vX = (vertices[vertex][0] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
									int vY = (vertices[vertex][1] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;

									sceneContext.vertexUnderwaterDepth.put(vertexKeys[vertex], underwaterDepths[z][vX][vY]);
								}
								else
								{
									// If the tile is a tile model and this vertex is shared only by faces that are water,
									// interpolate between the height offsets at each corner to get the height offset
									// of the vertex.

									float lerpX = fract(vertices[vertex][0] / (float) LOCAL_TILE_SIZE);
									float lerpY = fract(vertices[vertex][1] / (float) LOCAL_TILE_SIZE);
									float northHeightOffset = mix(underwaterDepths[z][x][y + 1], underwaterDepths[z][x + 1][y + 1], lerpX);
									float southHeightOffset = mix(underwaterDepths[z][x][y], underwaterDepths[z][x + 1][y], lerpX);
									int heightOffset = (int) mix(southHeightOffset, northHeightOffset, lerpY);

									if (!sceneContext.vertexIsLand.contains(vertexKeys[vertex]))
										sceneContext.vertexUnderwaterDepth.put(vertexKeys[vertex], heightOffset);
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Iterates through all Tiles in a given Scene, calculating vertex normals
	 * for each one, then stores resulting normal data in a HashMap.
	 */
	private void calculateTerrainNormals(SceneContext sceneContext, SceneContext previousSceneContext)
	{
		sceneContext.vertexTerrainNormals = new Int2ObjectHashMap<>(previousSceneContext != null && previousSceneContext.vertexTerrainNormals != null ? previousSceneContext.vertexTerrainNormals.size() : 16, int[][]::new);

		for (Tile[][] plane : sceneContext.scene.getExtendedTiles()) {
			for (Tile[] column : plane) {
				for (Tile tile : column) {
					if (tile != null) {
						boolean isBridge = false;

						if (tile.getBridge() != null) {
							calculateNormalsForTile(sceneContext, tile.getBridge(), false);
							isBridge = true;
						}
						calculateNormalsForTile(sceneContext, tile, isBridge);
					}
				}
			}
		}

		for(var entry : sceneContext.vertexTerrainNormals) {
			var n = normalize(vec(entry.value));
			for (int i = 0; i < 3; i++)
				entry.value[i] = GpuIntBuffer.normShort(n[i]);
		}
	}

	/**
	 * Calculates vertex normals for a given Tile,
	 * then stores resulting normal data in a HashMap.
	 *
	 * @param sceneContext that the tile is associated with
	 * @param tile         to calculate normals for
	 * @param isBridge     whether the tile is a bridge tile, i.e. tile above
	 */
	private void calculateNormalsForTile(SceneContext sceneContext, Tile tile, boolean isBridge)
	{
		// Make array of tile's tris with vertices
		int[][][] faceVertices; // Array of tile's tri vertices
		int[][] faceVertexKeys;

		if (tile.getSceneTileModel() != null)
		{
			// Tile model
			SceneTileModel tileModel = tile.getSceneTileModel();
			faceVertices = new int[tileModel.getFaceX().length][VERTICES_PER_FACE][3];
			faceVertexKeys = new int[tileModel.getFaceX().length][VERTICES_PER_FACE];

			final int[][] vertices = new int[4][3];
			final int[] vertexKeys = new int[4];
			for (int face = 0; face < tileModel.getFaceX().length; face++)
			{
				faceVertexKeys(tile, face, vertices, vertexKeys);

				faceVertices[face][0] = new int[]{vertices[0][0], vertices[0][1], vertices[0][2]};
				faceVertices[face][2] = new int[]{vertices[1][0], vertices[1][1], vertices[1][2]};
				faceVertices[face][1] = new int[]{vertices[2][0], vertices[2][1], vertices[2][2]};

				faceVertexKeys[face][0] = vertexKeys[0];
				faceVertexKeys[face][2] = vertexKeys[1];
				faceVertexKeys[face][1] = vertexKeys[2];
			}
		}
		else
		{
			faceVertices = new int[2][VERTICES_PER_FACE][3];
			faceVertexKeys = new int[VERTICES_PER_FACE][3];
			int[][] vertices = tileVertices(sceneContext, tile);
			faceVertices[0] = new int[][]{vertices[3], vertices[1], vertices[2]};
			faceVertices[1] = new int[][]{vertices[0], vertices[2], vertices[1]};

			int[] vertexKeys = tileVertexKeys(sceneContext, tile);
			faceVertexKeys[0] = new int[]{vertexKeys[3], vertexKeys[1], vertexKeys[2]};
			faceVertexKeys[1] = new int[]{vertexKeys[0], vertexKeys[2], vertexKeys[1]};
		}

		// Loop through tris to calculate and accumulate normals
		for (int face = 0; face < faceVertices.length; face++)
		{
			// XYZ
			int[] vertexHeights = new int[]{faceVertices[face][0][2], faceVertices[face][1][2], faceVertices[face][2][2]};
			if (!isBridge)
			{
				vertexHeights[0] += sceneContext.vertexUnderwaterDepth.getOrDefault(faceVertexKeys[face][0], 0);
				vertexHeights[1] += sceneContext.vertexUnderwaterDepth.getOrDefault(faceVertexKeys[face][1], 0);
				vertexHeights[2] += sceneContext.vertexUnderwaterDepth.getOrDefault(faceVertexKeys[face][2], 0);
			}

			int[] vertexNormals = calculateSurfaceNormals(
				ivec(
					faceVertices[face][0][0],
					faceVertices[face][0][1],
					vertexHeights[0]
				),
				ivec(
					faceVertices[face][1][0],
					faceVertices[face][1][1],
					vertexHeights[1]
				),
				ivec(
					faceVertices[face][2][0],
					faceVertices[face][2][1],
					vertexHeights[2]
				)
			);

			for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
				int vertexKey = faceVertexKeys[face][vertex];

				final int[] terrainNormal = sceneContext.vertexTerrainNormals.getOrDefault(vertexKey, null);
				if (terrainNormal != null) {
					add(terrainNormal, terrainNormal, vertexNormals);
				} else {
					sceneContext.vertexTerrainNormals.put(vertexKey, vertexNormals);
				}
			}
		}
	}

	public boolean useDefaultColor(Tile tile, TileOverride override)
	{
		if ((tile.getSceneTilePaint() != null && tile.getSceneTilePaint().getTexture() >= 0) ||
			(tile.getSceneTileModel() != null && tile.getSceneTileModel().getTriangleTextureId() != null))
		{
			// skip tiles with textures provided by default
			return true;
		}

		if (override == TileOverride.NONE)
			return false;

		return !override.blended;
	}

	public WaterType seasonalWaterType(TileOverride override, int textureId)
	{
		var waterType = override.waterType;

		// As a fallback, always consider vanilla textured water tiles as water
		// We purposefully ignore material replacements here such as ice from the winter theme
		if (waterType == WaterType.NONE) {
			if (130 <= textureId && textureId <= 189 || textureId == 208) {
				// New sailing water textures
				waterType = waterTypeManager.getFallback(textureId);
			} else {
				switch (textureId) {
					case 1:
					case 24:
						waterType = WaterType.WATER; // This used to be WATER_FLAT, but for sailing we want translucent water
						break;
					case 25:
						waterType = WaterType.SWAMP_WATER_FLAT;
						break;
				}
			}
		}

		// Disable the winter theme ice
//		if (waterType == WaterType.WATER && plugin.configSeasonalTheme == SeasonalTheme.WINTER)
//			return WaterType.ICE;

		return waterType;
	}

	private static boolean[] getTileOverlayTris(int tileShapeIndex)
	{
		if (tileShapeIndex >= TILE_OVERLAY_TRIS.length)
		{
			log.debug("getTileOverlayTris(): unknown tileShapeIndex ({})", tileShapeIndex);
			return new boolean[10]; // false
		}
		else
		{
			return TILE_OVERLAY_TRIS[tileShapeIndex];
		}
	}

	public static boolean isOverlayFace(Tile tile, int face) {
		int tileShapeIndex = tile.getSceneTileModel().getShape() - 1;
		if (face >= getTileOverlayTris(tileShapeIndex).length) {
			return false;
		}
		return getTileOverlayTris(tileShapeIndex)[face];
	}

	private static void tileVertices(SceneContext ctx, Tile tile, int[][] vertices) {
		int tileX = tile.getSceneLocation().getX();
		int tileY = tile.getSceneLocation().getY();
		int tileExX = tileX + ctx.sceneOffset;
		int tileExY = tileY + ctx.sceneOffset;
		int tileZ = tile.getRenderLevel();
		int[][][] tileHeights = ctx.scene.getTileHeights();

		// swVertex
		vertices[0][0] = tileX * LOCAL_TILE_SIZE;
		vertices[0][1] = tileY * LOCAL_TILE_SIZE;
		vertices[0][2] = tileHeights[tileZ][tileExX][tileExY];

		// seVertex
		vertices[1][0] = (tileX + 1) * LOCAL_TILE_SIZE;
		vertices[1][1] = tileY * LOCAL_TILE_SIZE;
		vertices[1][2] = tileHeights[tileZ][tileExX + 1][tileExY];

		// nwVertex
		vertices[2][0] = tileX * LOCAL_TILE_SIZE;
		vertices[2][1] = (tileY + 1) * LOCAL_TILE_SIZE;
		vertices[2][2] = tileHeights[tileZ][tileExX][tileExY + 1];

		//neVertex
		vertices[3][0] = (tileX + 1) * LOCAL_TILE_SIZE;
		vertices[3][1] = (tileY + 1) * LOCAL_TILE_SIZE;
		vertices[3][2] = tileHeights[tileZ][tileExX + 1][tileExY + 1];
	}

	private static int[][] tileVertices(SceneContext ctx, Tile tile) {
		int[][] vertices = new int[4][3];
		tileVertices(ctx, tile, vertices);
		return vertices;
	}

	private static void faceVertices(Tile tile, int face, int[][] vertices)
	{
		SceneTileModel sceneTileModel = tile.getSceneTileModel();

		final int[] faceA = sceneTileModel.getFaceX();
		final int[] faceB = sceneTileModel.getFaceY();
		final int[] faceC = sceneTileModel.getFaceZ();

		final int[] vertexX = sceneTileModel.getVertexX();
		final int[] vertexY = sceneTileModel.getVertexY();
		final int[] vertexZ = sceneTileModel.getVertexZ();

		int vertexFacesA = faceA[face];
		int vertexFacesB = faceB[face];
		int vertexFacesC = faceC[face];

		// scene X
		vertices[0][0] = vertexX[vertexFacesA];
		vertices[1][0] = vertexX[vertexFacesB];
		vertices[2][0] = vertexX[vertexFacesC];
		// scene Y
		vertices[0][1] = vertexZ[vertexFacesA];
		vertices[1][1] = vertexZ[vertexFacesB];
		vertices[2][1] = vertexZ[vertexFacesC];
		// scene Z - heights
		vertices[0][2] = vertexY[vertexFacesA];
		vertices[1][2] = vertexY[vertexFacesB];
		vertices[2][2] = vertexY[vertexFacesC];
	}

	private static int[][] faceVertices(Tile tile, int face)
	{
		int[][] vertices = new int[3][3];
		faceVertices(tile, face, vertices);
		return vertices;
	}

	/**
	 * Returns vertex positions in local coordinates, between 0 and 128.
	 */
	public static void faceLocalVertices(Tile tile, int face, int[][] vertices) {
		if (tile.getSceneTileModel() == null) {
			for (int[] vertex : vertices)
				Arrays.fill(vertex, 0);
			return;
		}

		int x = tile.getSceneLocation().getX();
		int y = tile.getSceneLocation().getY();
		int baseX = x * LOCAL_TILE_SIZE;
		int baseY = y * LOCAL_TILE_SIZE;

		faceVertices(tile, face, vertices);
		for (int[] vertex : vertices) {
			vertex[0] -= baseX;
			vertex[1] -= baseY;
		}
	}

	public static int[][] faceLocalVertices(Tile tile, int face) {
		int[][] vertices = new int[3][3];
		faceLocalVertices(tile, face, vertices);
		return vertices;
	}

	/**
	 * Gets the vertex keys of a Tile Paint tile for use in retrieving data from hashmaps.
	 * Writes the vertex keys in following order: SW, SE, NW, NE
	 *
	 * @param ctx that the tile is from
	 * @param tile to get the vertex keys of
	 */
	public static void tileVertexKeys(SceneContext ctx, Tile tile, int[][] tileVertices, int[] vertexHashes)
	{
		tileVertices(ctx, tile, tileVertices);
		for (int vertex = 0; vertex < tileVertices.length; ++vertex)
			vertexHashes[vertex] = fastVertexHash(tileVertices[vertex]);
	}

	public static void tileVertexKeys(SceneContext ctx, Tile tile, int[] vertexHashes)
	{
		int[][] vertices = new int[4][3];
		tileVertexKeys(ctx, tile, vertices, vertexHashes);
	}

	public static int[] tileVertexKeys(SceneContext ctx, Tile tile)
	{
		int[] vertexHashes = new int[4];
		tileVertexKeys(ctx, tile, vertexHashes);
		return vertexHashes;
	}

	public static void faceVertexKeys(Tile tile, int face, int[][] vertices, int[] vertexHashes)
	{
		faceVertices(tile, face, vertices);
		for (int vertex = 0; vertex < vertices.length; ++vertex)
			vertexHashes[vertex] = fastVertexHash(vertices[vertex]);
	}

	public static int[] faceVertexKeys(Tile tile, int face)
	{
		int[][] vertices = new int[3][3];
		int[] vertexHashes = new int[4];
		faceVertexKeys(tile, face, vertices, vertexHashes);
		return vertexHashes;
	}

	private static final int[] tzHaarRecolored = new int[3];
	// used when calculating the gradient to apply to the walls of TzHaar
	// to emulate the style from 2008 HD rework
	private static final float[] gradientBaseColor = vec(3, 4, 26);
	private static final float[] gradientDarkColor = vec(3, 4, 10);
	private static final int gradientBottom = 200;
	private static final int gradientTop = -200;

	public static int[] recolorTzHaar(
		ModelOverride modelOverride,
		Model model,
		int face,
		int color1,
		int color2,
		int color3
	) {
		int[] hsl1 = ColorUtils.unpackRawHsl(color1);
		int[] hsl2 = ColorUtils.unpackRawHsl(color2);
		int[] hsl3 = ColorUtils.unpackRawHsl(color3);

		// shift model hues from red->yellow
		int hue = 7;
		hsl1[0] = hsl2[0] = hsl3[0] = hue;

		if (modelOverride.tzHaarRecolorType == TzHaarRecolorType.GRADIENT) {
			final int triA = model.getFaceIndices1()[face];
			final int triB = model.getFaceIndices2()[face];
			final int triC = model.getFaceIndices3()[face];
			final float[] yVertices = model.getVerticesY();
			float height = (yVertices[triA] + yVertices[triB] + yVertices[triC]) / 3;
			float pos = clamp((height - gradientTop) / (float) gradientBottom, 0.0f, 1.0f);

			// apply coloring to the rocky walls
			if (hsl1[2] < 20 || hsl2[2] < 20 || hsl3[2] < 20) {
				round(hsl1, mix(gradientDarkColor, gradientBaseColor, pos));
				round(hsl2, mix(gradientDarkColor, gradientBaseColor, pos));
				round(hsl3, mix(gradientDarkColor, gradientBaseColor, pos));
			}
		}
		else if (modelOverride.tzHaarRecolorType == TzHaarRecolorType.HUE_SHIFT)
		{
			// objects around the entrance to The Inferno only need a hue-shift
			// and very slight lightening to match the lightened terrain
			hsl1[2] += 1;
			hsl2[2] += 1;
			hsl3[2] += 1;
		}

		tzHaarRecolored[0] = ColorUtils.packRawHsl(hsl1);
		tzHaarRecolored[1] = ColorUtils.packRawHsl(hsl2);
		tzHaarRecolored[2] = ColorUtils.packRawHsl(hsl3);

		return tzHaarRecolored;
	}
}
