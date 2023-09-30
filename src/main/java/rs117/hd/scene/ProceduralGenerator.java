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
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.data.WaterType;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.Overlay;
import rs117.hd.data.materials.Underlay;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.scene.model_overrides.TzHaarRecolorType;
import rs117.hd.utils.HDUtils;

import static net.runelite.api.Constants.*;

@Slf4j
@Singleton
public class ProceduralGenerator {
	public static final int[] DEPTH_LEVEL_SLOPE = new int[] { 150, 300, 470, 610, 700, 750, 820, 920, 1080, 1300, 1350, 1380 };

	private static final int VERTICES_PER_FACE = 3;
	private static final boolean[][] TILE_OVERLAY_TRIS = new boolean[][]
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
	private HdPlugin plugin;

	public void generateSceneData(SceneContext sceneContext)
	{
		long timerTotal = System.currentTimeMillis();
		long timerCalculateTerrainNormals, timerGenerateTerrainData, timerGenerateUnderwaterTerrain;

		long startTime = System.currentTimeMillis();
		generateUnderwaterTerrain(sceneContext);
		timerGenerateUnderwaterTerrain = (int)(System.currentTimeMillis() - startTime);
		startTime = System.currentTimeMillis();
		calculateTerrainNormals(sceneContext);
		timerCalculateTerrainNormals = (int)(System.currentTimeMillis() - startTime);
		startTime = System.currentTimeMillis();
		generateTerrainData(sceneContext);
		timerGenerateTerrainData = (int)(System.currentTimeMillis() - startTime);

		log.debug("procedural data generation took {}ms to complete", (System.currentTimeMillis() - timerTotal));
		log.debug("-- calculateTerrainNormals: {}ms", timerCalculateTerrainNormals);
		log.debug("-- generateTerrainData: {}ms", timerGenerateTerrainData);
		log.debug("-- generateUnderwaterTerrain: {}ms", timerGenerateUnderwaterTerrain);
	}

	/**
	 * Iterates through all Tiles in a given Scene, producing color and
	 * material data for each vertex of each Tile. Then adds the resulting
	 * data to appropriate HashMaps.
	 */
	private void generateTerrainData(SceneContext sceneContext)
	{
		sceneContext.vertexTerrainColor = new HashMap<>();
		// used for overriding potentially undesirable vertex colors
		// for example, colors that aren't supposed to be visible
		sceneContext.highPriorityColor = new HashMap<>();
		sceneContext.vertexTerrainTexture = new HashMap<>();
		// for faces without an overlay is set to true
		sceneContext.vertexIsUnderlay = new HashMap<>();
		// for faces with an overlay is set to true
		// the result of these maps can be used to determine the vertices
		// between underlays and overlays for custom blending
		sceneContext.vertexIsOverlay = new HashMap<>();

		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x)
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y)
					if (tiles[z][x][y] != null)
						generateDataForTile(sceneContext, tiles[z][x][y]);

			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x)
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y)
					if (tiles[z][x][y] != null && tiles[z][x][y].getBridge() != null)
						generateDataForTile(sceneContext, tiles[z][x][y].getBridge());
		}
	}

	/**
	 * Produces color and material data for the vertices of the provided Tile.
	 * Then adds the resulting data to appropriate HashMaps.
	 *
	 * @param sceneContext that the tile is associated with
	 * @param tile         to generate terrain data for
	 */
	private void generateDataForTile(SceneContext sceneContext, Tile tile)
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
		Overlay[] vertexOverlays = new Overlay[faceCount * VERTICES_PER_FACE];
		Underlay[] vertexUnderlays = new Underlay[faceCount * VERTICES_PER_FACE];
		boolean[] vertexDefaultColor = new boolean[faceCount * VERTICES_PER_FACE];

		int tileExX = tile.getSceneLocation().getX() + SceneUploader.SCENE_OFFSET;
		int tileExY = tile.getSceneLocation().getY() + SceneUploader.SCENE_OFFSET;
		WorldPoint worldPos = sceneContext.localToWorld(tile.getLocalLocation(), tile.getRenderLevel());

		Scene scene = sceneContext.scene;
		if (tile.getSceneTilePaint() != null) {
			// tile paint

			Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
			Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);

			if (overlay.waterType != WaterType.NONE || underlay.waterType != WaterType.NONE) {
				// skip water tiles
				return;
			}

			int swColor = tile.getSceneTilePaint().getSwColor();
			int seColor = tile.getSceneTilePaint().getSeColor();
			int nwColor = tile.getSceneTilePaint().getNwColor();
			int neColor = tile.getSceneTilePaint().getNeColor();

			vertexHashes = tileVertexKeys(scene, tile);

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

			vertexOverlays[0] = vertexOverlays[1] = vertexOverlays[2] = vertexOverlays[3] = overlay;
			vertexUnderlays[0] = vertexUnderlays[1] = vertexUnderlays[2] = vertexUnderlays[3] = underlay;
			if (useDefaultColor(scene, tile))
			{
				vertexDefaultColor[0] = vertexDefaultColor[1] = vertexDefaultColor[2] = vertexDefaultColor[3] = true;
			}
		}
		else if (tile.getSceneTileModel() != null)
		{
			// tile model

			SceneTileModel sceneTileModel = tile.getSceneTileModel();

			final int[] faceColorsA = sceneTileModel.getTriangleColorA();
			final int[] faceColorsB = sceneTileModel.getTriangleColorB();
			final int[] faceColorsC = sceneTileModel.getTriangleColorC();

			for (int face = 0; face < faceCount; face++)
			{
				int[] faceColors = new int[]{faceColorsA[face], faceColorsB[face], faceColorsC[face]};

				int[] vertexKeys = faceVertexKeys(tile, face);

				for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
				{
					boolean isOverlay = isOverlayFace(tile, face);
					Overlay overlay = Overlay.NONE;
					if (isOverlay)
					{
						overlay = Overlay.getOverlay(scene, tile, plugin);
					}
					Underlay underlay =  Underlay.getUnderlay(scene, tile, plugin);

					if (overlay.waterType != WaterType.NONE || underlay.waterType != WaterType.NONE)
					{
						// skip water faces
						continue;
					}

					vertexHashes[face * VERTICES_PER_FACE + vertex] = vertexKeys[vertex];

					int color = faceColors[vertex];
					vertexColors[face * VERTICES_PER_FACE + vertex] = color;

					vertexOverlays[face * VERTICES_PER_FACE + vertex] = overlay;
					vertexUnderlays[face * VERTICES_PER_FACE + vertex] = underlay;

					if (isOverlay && useDefaultColor(scene, tile))
					{
						vertexDefaultColor[face * VERTICES_PER_FACE + vertex] = true;
					}
				}
			}
		}

		for (int vertex = 0; vertex < vertexHashes.length; vertex++)
		{
			if (vertexHashes[vertex] == 0)
			{
				continue;
			}
			if (vertexColors[vertex] < 0 || vertexColors[vertex] > 65535)
			{
				// skip invalid tile color
				continue;
			}
			// if this vertex already has a 'high priority' color assigned,
			// skip assigning a 'low priority' color unless there is no color assigned.
			// Near-solid-black tiles that are used in some places under wall objects
			boolean lowPriorityColor = vertexColors[vertex] <= 2;

			int[] colorHSL = HDUtils.colorIntToHSL(vertexColors[vertex]);

			float lightenMultiplier = 1.5f;
			int lightenBase = 15;
			int lightenAdd = 3;
			float darkenMultiplier = 0.5f;
			int darkenBase = 0;
			int darkenAdd = 0;

			float[] vNormals = sceneContext.vertexTerrainNormals.getOrDefault(vertexHashes[vertex], new float[] { 0, 0, 0 });

			float dot = HDUtils.dotLightDirectionTile(vNormals[0], vNormals[1], vNormals[2]);
			int lighten = (int) (Math.max((colorHSL[2] - lightenAdd), 0) * lightenMultiplier) + lightenBase;
			colorHSL[2] = (int) HDUtils.lerp(colorHSL[2], lighten, Math.max(dot, 0));
			int darken = (int) (Math.max((colorHSL[2] - darkenAdd), 0) * darkenMultiplier) + darkenBase;
			colorHSL[2] = (int) HDUtils.lerp(colorHSL[2], darken, Math.abs(Math.min(dot, 0)));
			colorHSL[2] *= 1.25f;

			boolean isOverlay = false;
			Material material = Material.DIRT_1;
			Overlay overlay = vertexOverlays[vertex];
			if (overlay != Overlay.NONE) {
				material = overlay.groundMaterial.getRandomMaterial(worldPos.getPlane(), worldPos.getX(), worldPos.getY());
				isOverlay = !overlay.blendedAsUnderlay;
				overlay.modifyColor(colorHSL);
			} else if (vertexUnderlays[vertex] != Underlay.NONE) {
				Underlay underlay = vertexUnderlays[vertex];
				material = underlay.groundMaterial.getRandomMaterial(worldPos.getPlane(), worldPos.getX(), worldPos.getY());
				isOverlay = underlay.blendedAsOverlay;
				underlay.modifyColor(colorHSL);
			}

			final int maxBrightness = 55; // reduces overexposure
			colorHSL[2] = HDUtils.clamp(colorHSL[2], 0, maxBrightness);
			vertexColors[vertex] = HDUtils.colorHSLToInt(colorHSL);

			// mark the vertex as either an overlay or underlay.
			// this is used to determine how to blend between vertex colors
			if (isOverlay)
			{
				sceneContext.vertexIsOverlay.put(vertexHashes[vertex], true);
			}
			else
			{
				sceneContext.vertexIsUnderlay.put(vertexHashes[vertex], true);
			}

			// add color and texture to hashmap
			if ((!lowPriorityColor || !sceneContext.highPriorityColor.containsKey(vertexHashes[vertex])) && !vertexDefaultColor[vertex])
			{
				if (vertexOverlays[vertex] != Overlay.NONE ||
					!sceneContext.vertexTerrainColor.containsKey(vertexHashes[vertex]) ||
					!sceneContext.highPriorityColor.containsKey(vertexHashes[vertex]))
				{
					sceneContext.vertexTerrainColor.put(vertexHashes[vertex], vertexColors[vertex]);
				}

				if (vertexOverlays[vertex] != Overlay.NONE ||
					!sceneContext.vertexTerrainTexture.containsKey(vertexHashes[vertex]) ||
					!sceneContext.highPriorityColor.containsKey(vertexHashes[vertex]))
				{
					sceneContext.vertexTerrainTexture.put(vertexHashes[vertex], material);
				}

				if (!lowPriorityColor)
				{
					sceneContext.highPriorityColor.put(vertexHashes[vertex], true);
				}
			}
		}
	}

	/**
	 * Generates underwater terrain data by iterating through all Tiles in a given
	 * Scene, increasing the depth of each tile based on its distance from the shore.
	 * Then stores the resulting data in a HashMap.
	 */
	private void generateUnderwaterTerrain(SceneContext sceneContext)
	{
		// true if a tile contains at least 1 face which qualifies as water
		sceneContext.tileIsWater = new boolean[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE];
		// true if a vertex is part of a face which qualifies as water; non-existent if not
		sceneContext.vertexIsWater = new HashMap<>();
		// true if a vertex is part of a face which qualifies as land; non-existent if not
		// tiles along the shoreline will be true for both vertexIsWater and vertexIsLand
		sceneContext.vertexIsLand = new HashMap<>();
		// if true, the tile will be skipped when the scene is drawn
		// this is due to certain edge cases with water on the same X/Y on different planes
		sceneContext.skipTile = new boolean[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE];
		// the height adjustment for each vertex, to be applied to the vertex'
		// real height to create the underwater terrain
		sceneContext.vertexUnderwaterDepth = new HashMap<>();
		// the basic 'levels' of underwater terrain, used to sink terrain based on its distance
		// from the shore, then used to produce the world-space height offset
		// 0 = land
		sceneContext.underwaterDepthLevels = new int[MAX_Z][EXTENDED_SCENE_SIZE + 1][EXTENDED_SCENE_SIZE + 1];
		// the world-space height offsets of each vertex on the tile grid
		// these offsets are interpolated to calculate offsets for vertices not on the grid (tilemodels)
		final int[][][] underwaterDepths = new int[MAX_Z][EXTENDED_SCENE_SIZE + 1][EXTENDED_SCENE_SIZE + 1];

		for (int z = 0; z < MAX_Z; ++z)
		{
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				// set the array to 1 initially
				// this assumes that all vertices are water;
				// we will set non-water vertices to 0 in the next loop
				Arrays.fill(sceneContext.underwaterDepthLevels[z][x], 1);
			}
		}

		Scene scene = sceneContext.scene;
		Tile[][][] tiles = scene.getExtendedTiles();

		// figure out which vertices are water and assign some data
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
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
						int[] vertexKeys = tileVertexKeys(scene, tile);

						if (tileWaterType(scene, tile, tile.getSceneTilePaint()) == WaterType.NONE) {
							for (int vertexKey : vertexKeys) {
								if (tile.getSceneTilePaint().getNeColor() != 12345678) {
									sceneContext.vertexIsLand.put(vertexKey, true);
								}
							}

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
								sceneContext.vertexIsWater.put(vertexKey, true);
							}
						}
					}
					else if (tile.getSceneTileModel() != null)
					{
						SceneTileModel sceneTileModel = tile.getSceneTileModel();

						int faceCount = sceneTileModel.getFaceX().length;

						// Stop tiles on the same X,Y coordinates on different planes from
						// each generating water. Prevents undesirable results in certain places.
						if (z > 0)
						{
							boolean tileIncludesWater = false;

							for (int face = 0; face < faceCount; face++)
							{
								if (faceWaterType(scene, tile, face, sceneTileModel) != WaterType.NONE)
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
										sceneContext.underwaterDepthLevels[z][x+1][y] = 0;
										sceneContext.underwaterDepthLevels[z][x][y+1] = 0;
										sceneContext.underwaterDepthLevels[z][x+1][y+1] = 0;

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
							int[][] vertices = faceVertices(tile, face);
							int[] vertexKeys = faceVertexKeys(tile, face);

							if (faceWaterType(scene, tile, face, sceneTileModel) == WaterType.NONE)
							{
								for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
								{
									if (sceneTileModel.getTriangleColorA()[face] != 12345678)
									{sceneContext.
										vertexIsLand.put(vertexKeys[vertex], true);
									}

									if (vertices[vertex][0] % Perspective.LOCAL_TILE_SIZE == 0 &&
										vertices[vertex][1] % Perspective.LOCAL_TILE_SIZE == 0
									) {
										int vX = vertices[vertex][0] / Perspective.LOCAL_TILE_SIZE + SceneUploader.SCENE_OFFSET;
										int vY = vertices[vertex][1] / Perspective.LOCAL_TILE_SIZE + SceneUploader.SCENE_OFFSET;

										sceneContext.underwaterDepthLevels[z][vX][vY] = 0;
									}
								}
							}
							else
							{
								sceneContext.tileIsWater[z][x][y] = true;

								for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
								{
									sceneContext.vertexIsWater.put(vertexKeys[vertex], true);
								}
							}
						}
					}
					else
					{
						sceneContext.underwaterDepthLevels[z][x][y] = 0;
						sceneContext.underwaterDepthLevels[z][x+1][y] = 0;
						sceneContext.underwaterDepthLevels[z][x][y+1] = 0;
						sceneContext.underwaterDepthLevels[z][x+1][y+1] = 0;
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
					int maxRange = DEPTH_LEVEL_SLOPE[sceneContext.underwaterDepthLevels[z][x][y] - 1];
					int minRange = (int) (DEPTH_LEVEL_SLOPE[sceneContext.underwaterDepthLevels[z][x][y] - 1] * 0.1f);
					// Range from noise-generated terrain is 10-60.
					// Translate the result from range 0-1.
//					float noiseOffset = (HeightCalc.calculate(baseX + x + 0xe3b7b, baseY + y + 0x87cce) - 10) / 50f;
					float noiseOffset = 0.5f;
					// limit range of variation
					float minOffset = 0.25f;
					float maxOffset = 0.75f;
					noiseOffset = HDUtils.lerp(minOffset, maxOffset, noiseOffset);
					// apply offset to vertex height range
					int heightOffset = (int) HDUtils.lerp(minRange, maxRange, noiseOffset);
					underwaterDepths[z][x][y] = heightOffset;
				}
			}
		}

		// Store the height offsets in a hashmap and calculate interpolated
		// height offsets for non-corner vertices.
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
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
						int[] vertexKeys = tileVertexKeys(scene, tile);

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
							int[][] vertices = faceVertices(tile, face);
							int[] vertexKeys = faceVertexKeys(tile, face);

							for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
							{
								if (vertices[vertex][0] % Perspective.LOCAL_TILE_SIZE == 0 &&
									vertices[vertex][1] % Perspective.LOCAL_TILE_SIZE == 0
								) {
									// The vertex is at the corner of the tile;
									// simply use the offset in the tile grid array.

									int vX = vertices[vertex][0] / Perspective.LOCAL_TILE_SIZE + SceneUploader.SCENE_OFFSET;
									int vY = vertices[vertex][1] / Perspective.LOCAL_TILE_SIZE + SceneUploader.SCENE_OFFSET;

									sceneContext.vertexUnderwaterDepth.put(vertexKeys[vertex], underwaterDepths[z][vX][vY]);
								}
								else
								{
									// If the tile is a tile model and this vertex is shared only by faces that are water,
									// interpolate between the height offsets at each corner to get the height offset
									// of the vertex.

									int tileX = x - SceneUploader.SCENE_OFFSET;
									int tileY = y - SceneUploader.SCENE_OFFSET;
									int localVertexX = vertices[vertex][0] - (tileX * Perspective.LOCAL_TILE_SIZE);
									int localVertexY = vertices[vertex][1] - (tileY * Perspective.LOCAL_TILE_SIZE);
									float lerpX = (float) localVertexX / (float) Perspective.LOCAL_TILE_SIZE;
									float lerpY = (float) localVertexY / (float) Perspective.LOCAL_TILE_SIZE;
									float northHeightOffset = HDUtils.lerp(
										underwaterDepths[z][x][y + 1],
										underwaterDepths[z][x + 1][y + 1],
										lerpX
									);
									float southHeightOffset = HDUtils.lerp(underwaterDepths[z][x][y], underwaterDepths[z][x + 1][y], lerpX);
									int heightOffset = (int) HDUtils.lerp(southHeightOffset, northHeightOffset, lerpY);

									if (!sceneContext.vertexIsLand.containsKey(vertexKeys[vertex])) {
										sceneContext.vertexUnderwaterDepth.put(vertexKeys[vertex], heightOffset);
									}
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
	private void calculateTerrainNormals(SceneContext sceneContext)
	{
		sceneContext.vertexTerrainNormals = new HashMap<>();

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

			for (int face = 0; face < tileModel.getFaceX().length; face++)
			{
				int[][] vertices = faceVertices(tile, face);

				faceVertices[face][0] = new int[]{vertices[0][0], vertices[0][1], vertices[0][2]};
				faceVertices[face][2] = new int[]{vertices[1][0], vertices[1][1], vertices[1][2]};
				faceVertices[face][1] = new int[]{vertices[2][0], vertices[2][1], vertices[2][2]};

				int[] vertexKeys = faceVertexKeys(tile, face);
				faceVertexKeys[face][0] = vertexKeys[0];
				faceVertexKeys[face][2] = vertexKeys[1];
				faceVertexKeys[face][1] = vertexKeys[2];
			}
		}
		else
		{
			faceVertices = new int[2][VERTICES_PER_FACE][3];
			faceVertexKeys = new int[VERTICES_PER_FACE][3];
			int[][] vertices = tileVertices(sceneContext.scene, tile);
			faceVertices[0] = new int[][]{vertices[3], vertices[1], vertices[2]};
			faceVertices[1] = new int[][]{vertices[0], vertices[2], vertices[1]};

			int[] vertexKeys = tileVertexKeys(sceneContext.scene, tile);
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

			float[] vertexNormals = HDUtils.calculateSurfaceNormals(
				new float[] {
					faceVertices[face][0][0],
					faceVertices[face][0][1],
					vertexHeights[0]
				},
				new float[] {
					faceVertices[face][1][0],
					faceVertices[face][1][1],
					vertexHeights[1]
				},
				new float[] {
					faceVertices[face][2][0],
					faceVertices[face][2][1],
					vertexHeights[2]
				}
			);

			for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
			{
				int vertexKey = faceVertexKeys[face][vertex];
				// accumulate normals to hashmap
				sceneContext.vertexTerrainNormals.merge(vertexKey, vertexNormals, (a, b) -> HDUtils.add(a, a, b));
			}
		}
	}

	boolean useDefaultColor(Scene scene, Tile tile)
	{
		if ((tile.getSceneTilePaint() != null && tile.getSceneTilePaint().getTexture() >= 0) ||
			(tile.getSceneTileModel() != null && tile.getSceneTileModel().getTriangleTextureId() != null))
		{
			// skip tiles with textures provided by default
			return true;
		}

		Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
		if (overlay != Overlay.NONE)
		{
			return !overlay.blended;
		}
		Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
		if (underlay != Underlay.NONE)
		{
			return !underlay.blended;
		}
		return false;
	}

	private WaterType getSeasonalWaterType(WaterType waterType)
	{
		return plugin.configWinterTheme && waterType == WaterType.WATER ? WaterType.ICE : waterType;
	}

	/**
	 * Returns the WaterType of the provided SceneTilePaint Tile.
	 *
	 * @param scene that the tile is from
	 * @param tile  to determine the WaterType of
	 * @return the WaterType of the specified Tile
	 */
	WaterType tileWaterType(Scene scene, Tile tile, SceneTilePaint sceneTilePaint)
	{
		if (sceneTilePaint == null)
			return WaterType.NONE;

		WaterType waterType;
		Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
		if (overlay != Overlay.NONE) {
			waterType = overlay.waterType;
		} else {
			Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
			waterType = underlay.waterType;
		}

		return getSeasonalWaterType(waterType);
	}

	/**
	 * Returns the WaterType of the provided SceneTileModel Tile's specified face.
	 *
	 * @param scene that the tile is from
	 * @param tile  that the tile model is for
	 * @param face  the index of the specified face
	 * @return the WaterType of the specified face on the tile model
	 */
	WaterType faceWaterType(Scene scene, Tile tile, int face, SceneTileModel sceneTileModel)
	{
		WaterType waterType = WaterType.NONE;

		if (sceneTileModel != null)
		{
			Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
			if (isOverlayFace(tile, face) && overlay != Overlay.NONE)
			{
				waterType = overlay.waterType;
			}
			else
			{
				Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
				waterType = underlay.waterType;
			}
		}

		waterType = getSeasonalWaterType(waterType);

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

	private static int[][] tileVertices(Scene scene, Tile tile) {
		int tileX = tile.getSceneLocation().getX();
		int tileY = tile.getSceneLocation().getY();
		int tileExX = tileX + SceneUploader.SCENE_OFFSET;
		int tileExY = tileY + SceneUploader.SCENE_OFFSET;
		int tileZ = tile.getRenderLevel();
		int[][][] tileHeights = scene.getTileHeights();

		int[] swVertex = new int[] {
			tileX * Perspective.LOCAL_TILE_SIZE,
			tileY * Perspective.LOCAL_TILE_SIZE,
			tileHeights[tileZ][tileExX][tileExY]
		};
		int[] seVertex = new int[] {
			(tileX + 1) * Perspective.LOCAL_TILE_SIZE,
			tileY * Perspective.LOCAL_TILE_SIZE,
			tileHeights[tileZ][tileExX + 1][tileExY]
		};
		int[] nwVertex = new int[] {
			tileX * Perspective.LOCAL_TILE_SIZE,
			(tileY + 1) * Perspective.LOCAL_TILE_SIZE,
			tileHeights[tileZ][tileExX][tileExY + 1]
		};
		int[] neVertex = new int[] {
			(tileX + 1) * Perspective.LOCAL_TILE_SIZE,
			(tileY + 1) * Perspective.LOCAL_TILE_SIZE,
			tileHeights[tileZ][tileExX + 1][tileExY + 1]
		};

		return new int[][] { swVertex, seVertex, nwVertex, neVertex };
	}

	private static int[][] faceVertices(Tile tile, int face)
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
		int sceneVertexXA = vertexX[vertexFacesA];
		int sceneVertexXB = vertexX[vertexFacesB];
		int sceneVertexXC = vertexX[vertexFacesC];
		// scene Y
		int sceneVertexZA = vertexZ[vertexFacesA];
		int sceneVertexZB = vertexZ[vertexFacesB];
		int sceneVertexZC = vertexZ[vertexFacesC];
		// scene Z - heights
		int sceneVertexYA = vertexY[vertexFacesA];
		int sceneVertexYB = vertexY[vertexFacesB];
		int sceneVertexYC = vertexY[vertexFacesC];

		int[] vertexA = new int[] { sceneVertexXA, sceneVertexZA, sceneVertexYA };
		int[] vertexB = new int[] { sceneVertexXB, sceneVertexZB, sceneVertexYB };
		int[] vertexC = new int[] { sceneVertexXC, sceneVertexZC, sceneVertexYC };

		return new int[][] { vertexA, vertexB, vertexC };
	}

	public static int[][] faceLocalVertices(Tile tile, int face) {
		if (tile.getSceneTileModel() == null)
			return new int[0][0];

		int x = tile.getSceneLocation().getX();
		int y = tile.getSceneLocation().getY();
		int baseX = x * Perspective.LOCAL_TILE_SIZE;
		int baseY = y * Perspective.LOCAL_TILE_SIZE;

		int[][] vertices = faceVertices(tile, face);
		for (int[] vertex : vertices) {
			vertex[0] -= baseX;
			vertex[1] -= baseY;
		}
		return vertices;
	}

	/**
	 * Gets the vertex keys of a Tile Paint tile for use in retrieving data from hashmaps.
	 *
	 * @param scene that the tile is from
	 * @param tile  to get the vertex keys of
	 * @return Vertex keys in following order: SW, SE, NW, NE
	 */
	public static int[] tileVertexKeys(Scene scene, Tile tile)
	{
		int[][] tileVertices = tileVertices(scene, tile);
		int[] vertexHashes = new int[tileVertices.length];

		for (int vertex = 0; vertex < tileVertices.length; ++vertex)
			vertexHashes[vertex] = HDUtils.vertexHash(tileVertices[vertex]);

		return vertexHashes;
	}

	public static int[] faceVertexKeys(Tile tile, int face)
	{
		int[][] faceVertices = faceVertices(tile, face);
		int[] vertexHashes = new int[faceVertices.length];

		for (int vertex = 0; vertex < faceVertices.length; ++vertex)
			vertexHashes[vertex] = HDUtils.vertexHash(faceVertices[vertex]);

		return vertexHashes;
	}

	private static final int[][] tzHaarRecolored = new int[4][3];
	// used when calculating the gradient to apply to the walls of TzHaar
	// to emulate the style from 2008 HD rework
	private static final int[] gradientBaseColor = new int[]{3, 4, 26};
	private static final int[] gradientDarkColor = new int[]{3, 4, 10};
	private static final int gradientBottom = 200;
	private static final int gradientTop = -200;

	public static int[][] recolorTzHaar(
		ModelOverride modelOverride,
		Model model,
		int face,
		int packedAlphaPriority,
		ObjectType objectType,
		int color1S,
		int color1L,
		int color2S,
		int color2L,
		int color3S,
		int color3L
	) {
		// recolor tzhaar to look like the 2008+ HD version
		if (objectType == ObjectType.GROUND_OBJECT) {
			// remove the black parts of floor objects to allow the ground to show
			// so we can apply textures, ground blending, etc. to it
			if (color1S <= 1)
				packedAlphaPriority = 0xFF << 24;
		}

		// shift model hues from red->yellow
		int hue = 7;
		int color1H = hue;
		int color2H = hue;
		int color3H = hue;

		if (modelOverride.tzHaarRecolorType == TzHaarRecolorType.GRADIENT) {
			final int triA = model.getFaceIndices1()[face];
			final int triB = model.getFaceIndices2()[face];
			final int triC = model.getFaceIndices3()[face];
			final int[] yVertices = model.getVerticesY();
			int heightA = yVertices[triA];
			int heightB = yVertices[triB];
			int heightC = yVertices[triC];

			// apply coloring to the rocky walls
			if (color1L < 20) {
				float pos = HDUtils.clamp((float) (heightA - gradientTop) / (float) gradientBottom, 0.0f, 1.0f);
				color1H = (int) HDUtils.lerp(gradientDarkColor[0], gradientBaseColor[0], pos);
				color1S = (int) HDUtils.lerp(gradientDarkColor[1], gradientBaseColor[1], pos);
				color1L = (int) HDUtils.lerp(gradientDarkColor[2], gradientBaseColor[2], pos);
			}

			if (color2L < 20)
			{
				float pos = HDUtils.clamp((float) (heightB - gradientTop) / (float) gradientBottom, 0.0f, 1.0f);
				color2H = (int)HDUtils.lerp(gradientDarkColor[0], gradientBaseColor[0], pos);
				color2S = (int)HDUtils.lerp(gradientDarkColor[1], gradientBaseColor[1], pos);
				color2L = (int)HDUtils.lerp(gradientDarkColor[2], gradientBaseColor[2], pos);
			}

			if (color3L < 20)
			{
				float pos = HDUtils.clamp((float) (heightC - gradientTop) / (float) gradientBottom, 0.0f, 1.0f);
				color3H = (int)HDUtils.lerp(gradientDarkColor[0], gradientBaseColor[0], pos);
				color3S = (int)HDUtils.lerp(gradientDarkColor[1], gradientBaseColor[1], pos);
				color3L = (int)HDUtils.lerp(gradientDarkColor[2], gradientBaseColor[2], pos);
			}
		}
		else if (modelOverride.tzHaarRecolorType == TzHaarRecolorType.HUE_SHIFT)
		{
			// objects around the entrance to The Inferno only need a hue-shift
			// and very slight lightening to match the lightened terrain
			color1L += 1;
			color2L += 1;
			color3L += 1;
		}

		tzHaarRecolored[0][0] = color1H;
		tzHaarRecolored[0][1] = color1S;
		tzHaarRecolored[0][2] = color1L;
		tzHaarRecolored[1][0] = color2H;
		tzHaarRecolored[1][1] = color2S;
		tzHaarRecolored[1][2] = color2L;
		tzHaarRecolored[2][0] = color3H;
		tzHaarRecolored[2][1] = color3S;
		tzHaarRecolored[2][2] = color3L;
		tzHaarRecolored[3][0] = packedAlphaPriority;

		return tzHaarRecolored;
	}
}
