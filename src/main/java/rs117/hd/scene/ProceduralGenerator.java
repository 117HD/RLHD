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
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.Int2IntHashMap;
import rs117.hd.utils.collections.Int2ObjectHashMap;
import rs117.hd.utils.collections.IntHashSet;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.TILE_OVERRIDE_COUNT;
import static rs117.hd.scene.SceneContext.TILE_OVERRIDE_MAIN;
import static rs117.hd.scene.SceneContext.TILE_OVERRIDE_OVERLAY;
import static rs117.hd.scene.SceneContext.TILE_OVERRIDE_UNDERLAY;
import static rs117.hd.scene.SceneContext.TILE_SKIP_FLAG;
import static rs117.hd.scene.SceneContext.TILE_WATER_FLAG;
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

	private final ConcurrentPool<GeneratorContext> GENERATOR_POOL = new ConcurrentPool<>(GeneratorContext::new);

	final class GeneratorContext implements AutoCloseable {
		final MainTileOverridesGenerator mainTileOverridesGenerator = new MainTileOverridesGenerator();
		final TerrainDataGenerator terrainDataGenerator = new TerrainDataGenerator();
		final UnderwaterTerrainGenerator underwaterTerrainGenerator = new UnderwaterTerrainGenerator();
		final TerrainNormalGenerator terrainNormalGenerator = new TerrainNormalGenerator();

		@Override
		public void close() {
			GENERATOR_POOL.recycle(this);
		}
	}

	/**
	 * Gets the vertex keys of a Tile Paint tile for use in retrieving data from hashmaps.
	 * Writes the vertex keys in following order: SW, SE, NW, NE
	 *
	 * @param ctx that the tile is from
	 * @param tile to get the vertex keys of
	 */
	public static void tileVertexKeys(SceneContext ctx, Tile tile, int[][] tileVertices, int[] vertexHashes) {
		tileVertices(ctx, tile, tileVertices);
		for (int vertex = 0; vertex < 4; ++vertex)
			vertexHashes[vertex] = fastVertexHash(tileVertices[vertex]);
	}

	public void clearSceneData(SceneContext sceneContext) {
		sceneContext.tileFlags = null;
		sceneContext.vertexIsWater = null;
		sceneContext.vertexIsLand = null;
		sceneContext.vertexIsOverlay = null;
		sceneContext.vertexIsUnderlay = null;
		sceneContext.vertexUnderwaterDepth = null;
		if (!(sceneContext instanceof LegacySceneContext))
			sceneContext.underwaterDepthLevels = null;
	}

	public static void faceVertexKeys(Tile tile, int face, int[][] vertices, int[] vertexHashes) {
		faceVertices(tile, face, vertices);
		for (int vertex = 0; vertex < 3; ++vertex)
			vertexHashes[vertex] = fastVertexHash(vertices[vertex]);
	}

	public static int[] faceVertexKeys(Tile tile, int face) {
		int[][] vertices = new int[3][3];
		int[] vertexHashes = new int[3];
		faceVertexKeys(tile, face, vertices, vertexHashes);
		return vertexHashes;
	}

	public void generateSceneData(SceneContext sceneCtx, SceneContext prevSceneCtx) {
		try (GeneratorContext ctx = GENERATOR_POOL.acquire()) {
			long timerTotal = System.currentTimeMillis();
			long timerCalculateMainOverrides, timerCalculateTerrainNormals, timerGenerateTerrainData, timerGenerateUnderwaterTerrain;

			long startTime = System.currentTimeMillis();
			ctx.mainTileOverridesGenerator.generate(sceneCtx, prevSceneCtx);
			timerCalculateMainOverrides = (int) (System.currentTimeMillis() - startTime);
			startTime = System.currentTimeMillis();
			ctx.underwaterTerrainGenerator.generate(sceneCtx, prevSceneCtx);
			timerGenerateUnderwaterTerrain = (int) (System.currentTimeMillis() - startTime);
			startTime = System.currentTimeMillis();
			ctx.terrainNormalGenerator.generate(sceneCtx, prevSceneCtx);
			timerCalculateTerrainNormals = (int) (System.currentTimeMillis() - startTime);
			startTime = System.currentTimeMillis();
			ctx.terrainDataGenerator.generate(sceneCtx, prevSceneCtx);
			timerGenerateTerrainData = (int) (System.currentTimeMillis() - startTime);

			log.debug("procedural data generation took {}ms to complete", (System.currentTimeMillis() - timerTotal));
			log.debug("-- calculateMainTileOverrides: {}ms", timerCalculateMainOverrides);
			log.debug("-- calculateTerrainNormals: {}ms", timerCalculateTerrainNormals);
			log.debug("-- generateTerrainData: {}ms", timerGenerateTerrainData);
			log.debug("-- generateUnderwaterTerrain: {}ms", timerGenerateUnderwaterTerrain);
		}
	}

	static class TerrainNormalGenerator {
		private final int[][] vertices = new int[4][3];
		private final int[] hashes = new int[4];

		private final int[] vertexHeights = new int[4];
		private final int[] surfaceNormal = new int[3];
		private final int[] normalA = new int[3];
		private final int[] normalB = new int[3];
		private final int[] normalC = new int[3];
		private final float[] avgNormal = new float[3];

		private int[][][] faceVertices = new int[2][VERTICES_PER_FACE][3];
		private int[][] faceVertexKeys = new int[VERTICES_PER_FACE][3];

		/**
		 * Iterates through all Tiles in a given Scene, calculating vertex normals
		 * for each one, then stores resulting normal data in a HashMap.
		 */
		private void generate(SceneContext sceneContext, SceneContext prevSceneContext) {
			sceneContext.vertexTerrainNormals = new Int2ObjectHashMap<>(prevSceneContext != null && prevSceneContext.vertexTerrainNormals != null ? prevSceneContext.vertexTerrainNormals.capacity() : 0);

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
				final int[] vertexNormal = entry.getValue();
				normalize(avgNormal, vec3(avgNormal, vertexNormal[0], vertexNormal[1], vertexNormal[2]));
				for (int i = 0; i < 3; i++)
					vertexNormal[i] = normShort(avgNormal[i]);
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
		private void calculateNormalsForTile(SceneContext sceneContext, Tile tile, boolean isBridge) {
			int faceCount = 2;
			if (tile.getSceneTileModel() != null) {
				// Tile model
				SceneTileModel tileModel = tile.getSceneTileModel();
				faceCount = tileModel.getFaceX().length;
				if (faceVertices.length < faceCount) {
					faceVertices = new int[faceCount][VERTICES_PER_FACE][3];
					faceVertexKeys = new int[faceCount][VERTICES_PER_FACE];
				}

				for (int face = 0; face < faceCount; face++) {
					faceVertexKeys(tile, face, vertices, hashes);

					ivec3(faceVertices[face][0], vertices[0][0], vertices[0][1], vertices[0][2]);
					ivec3(faceVertices[face][2], vertices[1][0], vertices[1][1], vertices[1][2]);
					ivec3(faceVertices[face][1], vertices[2][0], vertices[2][1], vertices[2][2]);

					ivec3(faceVertexKeys[face], hashes[0], hashes[1], hashes[2]);
				}
			} else {
				tileVertexKeys(sceneContext, tile, vertices, hashes);

				ivec3(faceVertices[0][0], vertices[3][0], vertices[3][1], vertices[3][2]);
				ivec3(faceVertices[0][1], vertices[1][0], vertices[1][1], vertices[1][2]);
				ivec3(faceVertices[0][2], vertices[2][0], vertices[2][1], vertices[2][2]);

				ivec3(faceVertices[1][0], vertices[0][0], vertices[0][1], vertices[0][2]);
				ivec3(faceVertices[1][1], vertices[2][0], vertices[2][1], vertices[2][2]);
				ivec3(faceVertices[1][2], vertices[1][0], vertices[1][1], vertices[1][2]);

				ivec3(faceVertexKeys[0], hashes[3], hashes[1], hashes[2]);
				ivec3(faceVertexKeys[1], hashes[0], hashes[2], hashes[1]);
			}

			// Loop through tris to calculate and accumulate normals
			for (int face = 0; face < faceCount; face++) {
				// XYZ
				ivec3(vertexHeights, faceVertices[face][0][2], faceVertices[face][1][2], faceVertices[face][2][2]);
				if (!isBridge) {
					vertexHeights[0] += sceneContext.vertexUnderwaterDepth.getOrDefault(faceVertexKeys[face][0], 0);
					vertexHeights[1] += sceneContext.vertexUnderwaterDepth.getOrDefault(faceVertexKeys[face][1], 0);
					vertexHeights[2] += sceneContext.vertexUnderwaterDepth.getOrDefault(faceVertexKeys[face][2], 0);
				}

				ivec3(
					normalA,
					faceVertices[face][0][0],
					faceVertices[face][0][1],
					vertexHeights[0]
				);

				ivec3(
					normalB,
					faceVertices[face][1][0],
					faceVertices[face][1][1],
					vertexHeights[1]
				);

				ivec3(
					normalC,
					faceVertices[face][2][0],
					faceVertices[face][2][1],
					vertexHeights[2]
				);

				calculateSurfaceNormals(surfaceNormal, normalA, normalB, normalC);

				for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
					final int vertexKey = faceVertexKeys[face][vertex];
					final int[] terrainNormal = sceneContext.vertexTerrainNormals.getOrDefault(vertexKey, null);
					if (terrainNormal != null) {
						add(terrainNormal, terrainNormal, surfaceNormal);
					} else {
						sceneContext.vertexTerrainNormals.put(vertexKey, copy(surfaceNormal));
					}
				}
			}
		}
	}

	public boolean useDefaultColor(Tile tile, TileOverride override) {
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

	public WaterType seasonalWaterType(TileOverride override, int textureId) {
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

	private static boolean[] getTileOverlayTris(int tileShapeIndex) {
		if (tileShapeIndex >= TILE_OVERLAY_TRIS.length) {
			log.debug("getTileOverlayTris(): unknown tileShapeIndex ({})", tileShapeIndex);
			return new boolean[10]; // false
		}

		return TILE_OVERLAY_TRIS[tileShapeIndex];
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

	private static void faceVertices(Tile tile, int face, int[][] vertices) {
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

	private static int[][] faceVertices(Tile tile, int face) {
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

	public static void tileVertexKeys(SceneContext ctx, Tile tile, int[] vertexHashes) {
		int[][] vertices = new int[4][3];
		tileVertexKeys(ctx, tile, vertices, vertexHashes);
	}

	public static int[] tileVertexKeys(SceneContext ctx, Tile tile) {
		int[] vertexHashes = new int[4];
		tileVertexKeys(ctx, tile, vertexHashes);
		return vertexHashes;
	}

	final class MainTileOverridesGenerator {
		private final TileOverride[] overrides = new TileOverride[TILE_OVERRIDE_COUNT];
		private final int[] worldPos = new int[3];
		private final int[] ids = new int[2];

		private void generate(SceneContext sceneContext, SceneContext preSceneCtx) {
			final Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
			final short[][][] overlayIds = sceneContext.scene.getOverlayIds();
			final short[][][] underlayIds = sceneContext.scene.getUnderlayIds();
			final int sizeX = sceneContext.sizeX;
			final int sizeY = sceneContext.sizeZ;

			sceneContext.tileOverrideIndices = new int[MAX_Z][sizeX][sizeY];
			sceneContext.tileOverrides.ensureCapacity(preSceneCtx != null ? preSceneCtx.tileOverrides.size() : 0);

			final boolean canReuse = preSceneCtx != null && sceneContext.scene.isInstance() == preSceneCtx.scene.isInstance() && sceneContext.currentArea == preSceneCtx.currentArea && !preSceneCtx.tileOverrides.isEmpty();
			final int dX = canReuse ? (sceneContext.scene.getBaseX() - preSceneCtx.scene.getBaseX() >> 3) << 3 : 0;
			final int dY = canReuse ? (sceneContext.scene.getBaseY() - preSceneCtx.scene.getBaseY() >> 3) << 3 : 0;

			for (int z = 0; z < MAX_Z; ++z) {
				for (int x = 0; x < sizeX; ++x) {
					for (int y = 0; y < sizeY; ++y) {
						final Tile tile = tiles[z][x][y];
						if (tile == null)
							continue;

						final int oX = x + dX;
						final int oY = y + dY;
						final boolean canReuseTile = canReuse && oX >= 8 && oX < sizeX - 8 && oY >= 8 && oY < sizeY - 8;

						int tileZ = tile.getRenderLevel();
						ids[0] = OVERLAY_FLAG | overlayIds[tileZ][x][y];
						ids[1] = underlayIds[tileZ][x][y];
						calculateTileOverride(sceneContext, canReuseTile ? preSceneCtx : null, tile, tileZ, x, y, oX, oY);

						final Tile bridge = tile.getBridge();
						if(bridge != null) {
							tileZ = bridge.getRenderLevel();
							ids[0] = OVERLAY_FLAG | overlayIds[tileZ][x][y];
							ids[1] = underlayIds[tileZ][x][y];
							calculateTileOverride(sceneContext, canReuseTile ? preSceneCtx : null, bridge, tileZ, x, y, oX, oY);
						}
					}
				}
			}
		}

		private void calculateTileOverride(SceneContext sceneContext, SceneContext prevSceneContext, Tile tile, int tileZ, int tileExX, int tileExY, int prevTileExX, int prevTileExY) {
			if(prevSceneContext != null && prevSceneContext.getTileOverrides(tileZ, prevTileExX, prevTileExY, overrides)) {
				sceneContext.setTileOverride(tileZ, tileExX, tileExY, overrides[0], overrides[1], overrides[2]);
				return;
			}

			sceneContext.extendedSceneToWorld(tileExX, tileExY, tileZ, worldPos);

			final var mainOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, ids);
			final var underlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, ids[1]);
			final var overlayOverride = tileOverrideManager.getOverride(sceneContext, tile, worldPos, ids[0]);

			sceneContext.setTileOverride(tileZ, tileExX, tileExY, mainOverride, underlayOverride, overlayOverride);
		}
	}

	final class TerrainDataGenerator {
		private final int[][] vertices = new int[4][3];
		private final int[] hashes = new int[4];
		private final int[] worldPos = new int[3];

		private int[] vertexHashes;
		private int[] vertexColors;
		private TileOverride[] vertexOverrides;
		private boolean[] vertexIsOverlay;
		private boolean[] vertexDefaultColor;

		/**
		 * Iterates through all Tiles in a given Scene, producing color and
		 * material data for each vertex of each Tile. Then adds the resulting
		 * data to appropriate HashMaps.
		 */
		private void generate(SceneContext sceneContext, SceneContext prevSceneCtx) {
			sceneContext.vertexTerrainColor = new Int2IntHashMap(prevSceneCtx != null && prevSceneCtx.vertexTerrainColor != null ? prevSceneCtx.vertexTerrainColor.capacity() : 0);
			// used for overriding potentially undesirable vertex colors
			// for example, colors that aren't supposed to be visible
			sceneContext.highPriorityColor = new IntHashSet(prevSceneCtx != null && prevSceneCtx.highPriorityColor != null ? prevSceneCtx.highPriorityColor.capacity() : 0);
			sceneContext.vertexTerrainTexture = new Int2ObjectHashMap<>(prevSceneCtx != null && prevSceneCtx.vertexTerrainTexture != null ? prevSceneCtx.vertexTerrainTexture.capacity() : 0);
			// for faces without an overlay is set to true
			sceneContext.vertexIsUnderlay = new IntHashSet(prevSceneCtx != null && prevSceneCtx.vertexIsUnderlay != null ? prevSceneCtx.vertexIsUnderlay.capacity() : 0);
			// for faces with an overlay is set to true
			// the result of these maps can be used to determine the vertices
			// between underlays and overlays for custom blending
			sceneContext.vertexIsOverlay = new IntHashSet(prevSceneCtx != null && prevSceneCtx.vertexIsOverlay != null ? prevSceneCtx.vertexIsOverlay.capacity() : 0);

			Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
			int sizeX = sceneContext.sizeX;
			int sizeY = sceneContext.sizeZ;
			for (int z = 0; z < MAX_Z; ++z) {
				for (int x = 0; x < sizeX; ++x) {
					for (int y = 0; y < sizeY; ++y) {
						final var tile = tiles[z][x][y];
						if(tile == null)
							continue;

						generateDataForTile(sceneContext, tiles[z][x][y], x, y, z);

						if(tile.getBridge() != null)
							generateDataForTile(sceneContext, tile.getBridge(), x, y, z);
					}
				}
			}
		}

		/**
		 * Produces color and material data for the vertices of the provided Tile.
		 * Then adds the resulting data to appropriate HashMaps.
		 *
		 * @param sceneContext that the tile is associated with
		 * @param tile         to generate terrain data for
		 */
		private void generateDataForTile(SceneContext sceneContext, Tile tile, int tileExX, int tileExY, int plane) {
			int faceCount;
			if (tile.getSceneTilePaint() != null) {
				faceCount = 2;
			} else if (tile.getSceneTileModel() != null) {
				faceCount = tile.getSceneTileModel().getFaceX().length;
			} else {
				return;
			}

			int tileX = tileExX - sceneContext.sceneOffset;
			int tileY = tileExY - sceneContext.sceneOffset;
			int tileZ = tile.getRenderLevel();
			sceneContext.sceneToWorld(tileX, tileY, tileZ, worldPos);

			vertexHashes = ensureCapacity(vertexHashes, faceCount * VERTICES_PER_FACE);
			vertexColors = ensureCapacity(vertexColors, faceCount * VERTICES_PER_FACE);
			vertexOverrides = ensureCapacity(vertexOverrides, faceCount * VERTICES_PER_FACE, TileOverride[]::new);
			vertexIsOverlay = ensureCapacity(vertexIsOverlay, faceCount * VERTICES_PER_FACE);
			vertexDefaultColor = ensureCapacity(vertexDefaultColor, faceCount * VERTICES_PER_FACE);

			Arrays.fill(vertexHashes, 0, faceCount * VERTICES_PER_FACE, 0);
			Arrays.fill(vertexColors, 0, faceCount * VERTICES_PER_FACE, 0);
			Arrays.fill(vertexOverrides, 0, faceCount * VERTICES_PER_FACE, null);
			Arrays.fill(vertexIsOverlay, 0, faceCount * VERTICES_PER_FACE, false);
			Arrays.fill(vertexDefaultColor, 0, faceCount * VERTICES_PER_FACE, false);

			if (tile.getSceneTilePaint() != null) {
				// tile paint

				var override = sceneContext.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_MAIN);
				if (override.waterType != WaterType.NONE) {
					// skip water tiles
					return;
				}

				int swColor = tile.getSceneTilePaint().getSwColor();
				int seColor = tile.getSceneTilePaint().getSeColor();
				int nwColor = tile.getSceneTilePaint().getNwColor();
				int neColor = tile.getSceneTilePaint().getNeColor();

				tileVertexKeys(sceneContext, tile, vertices, vertexHashes);

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
			} else if (tile.getSceneTileModel() != null) {
				// tile model

				SceneTileModel sceneTileModel = tile.getSceneTileModel();

				final int[] faceColorsA = sceneTileModel.getTriangleColorA();
				final int[] faceColorsB = sceneTileModel.getTriangleColorB();
				final int[] faceColorsC = sceneTileModel.getTriangleColorC();

				var overlayOverride = sceneContext.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_OVERLAY);
				var underlayOverride = sceneContext.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_UNDERLAY);

				for (int face = 0; face < faceCount; face++) {
					faceVertexKeys(tile, face, vertices, hashes);

					for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
						boolean isOverlay = isOverlayFace(tile, face);
						var override = isOverlay ? overlayOverride : underlayOverride;
						if (override.waterType != WaterType.NONE)
							continue; // skip water faces

						vertexHashes[face * VERTICES_PER_FACE + vertex] = hashes[vertex];

						int color = (vertex == 0 ? faceColorsA : vertex == 1 ? faceColorsB : faceColorsC)[face];
						vertexColors[face * VERTICES_PER_FACE + vertex] = color;

						vertexOverrides[face * VERTICES_PER_FACE + vertex] = override;
						vertexIsOverlay[face * VERTICES_PER_FACE + vertex] = isOverlay;

						if (isOverlay && useDefaultColor(tile, override))
							vertexDefaultColor[face * VERTICES_PER_FACE + vertex] = true;
					}
				}
			}

			int vertexCount = faceCount * VERTICES_PER_FACE;
			for (int vertex = 0; vertex < vertexCount; vertex++) {
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

				final int key = vertexHashes[vertex];
				int[] vNormals = sceneContext.vertexTerrainNormals.get(key);
				if (vNormals == null)
					vNormals = new int[] { 0, 0, 0 };

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
				if (isOverlay) {
					sceneContext.vertexIsOverlay.add(key);
				} else {
					sceneContext.vertexIsUnderlay.add(key);
				}

				// add color and texture to hashmap
				if ((!lowPriorityColor || !sceneContext.highPriorityColor.contains(key)) && !vertexDefaultColor[vertex]) {
					boolean shouldWrite = isOverlay || !sceneContext.vertexTerrainColor.containsKey(key);

					if (shouldWrite) {
						sceneContext.vertexTerrainColor.put(key, vertexColors[vertex]);
						sceneContext.vertexTerrainTexture.put(key, material);
					} else {
						sceneContext.vertexTerrainColor.putIfAbsent(key, vertexColors[vertex]);
						sceneContext.vertexTerrainTexture.putIfAbsent(key, material);
					}

					if (!lowPriorityColor)
						sceneContext.highPriorityColor.add(key);
				}
			}
		}
	}



	final class UnderwaterTerrainGenerator {
		private final int[][][] underwaterDepths = new int[MAX_Z][EXTENDED_SCENE_SIZE + 1][EXTENDED_SCENE_SIZE + 1];
		private final int[][] vertices = new int[4][3];
		private final int[] hashes = new int[4];

		// Defines ranges of water tiles
		private final int[] minX = new int[MAX_Z];
		private final int[] maxX = new int[MAX_Z];
		private final int[] minY = new int[MAX_Z];
		private final int[] maxY = new int[MAX_Z];

		/**
		 * Generates underwater terrain data by iterating through all Tiles in a given
		 * Scene, increasing the depth of each tile based on its distance from the shore.
		 * Then stores the resulting data in a HashMap.
		 */
		private void generate(SceneContext sceneContext, SceneContext prevSceneCtx) {
			int sizeX = sceneContext.sizeX;
			int sizeY = sceneContext.sizeZ;
			// bit 1 set if a tile contains at least 1 face which qualifies as water
			// bit 2 set if a tile will be skipped when the scene is drawn, this is due to certain edge cases with water on the same X/Y on different planes
			sceneContext.tileFlags = new byte[MAX_Z][sizeX][sizeY];
			// true if a vertex is part of a face which qualifies as water; non-existent if not
			sceneContext.vertexIsWater = new IntHashSet(prevSceneCtx != null && prevSceneCtx.vertexIsWater != null ? prevSceneCtx.vertexIsWater.capacity() : 0);
			// true if a vertex is part of a face which qualifies as land; non-existent if not
			// tiles along the shoreline will be true for both vertexIsWater and vertexIsLand
			sceneContext.vertexIsLand = new IntHashSet(prevSceneCtx != null && prevSceneCtx.vertexIsLand != null ? prevSceneCtx.vertexIsLand.capacity() : 0);
			// the height adjustment for each vertex, to be applied to the vertex'
			// real height to create the underwater terrain
			sceneContext.vertexUnderwaterDepth = new Int2IntHashMap(prevSceneCtx != null && prevSceneCtx.vertexUnderwaterDepth != null ? prevSceneCtx.vertexUnderwaterDepth.capacity() : 0);
			// the basic 'levels' of underwater terrain, used to sink terrain based on its distance
			// from the shore, then used to produce the world-space height offset
			// 0 = land
			sceneContext.underwaterDepthLevels = new int[MAX_Z][sizeX + 1][sizeY + 1];
			// the world-space height offsets of each vertex on the tile grid
			// these offsets are interpolated to calculate offsets for vertices not on the grid (tilemodels)

			for (int z = 0; z < MAX_Z; ++z) {
				for (int x = 0; x < sizeX; ++x) {
					// set the array to 1 initially
					// this assumes that all vertices are water;
					// we will set non-water vertices to 0 in the next loop
					Arrays.fill(sceneContext.underwaterDepthLevels[z][x], 1);
				}
			}

			Scene scene = sceneContext.scene;
			Tile[][][] tiles = scene.getExtendedTiles();

			int minZ = MAX_Z, maxZ = 0;
			Arrays.fill(minX, sizeX);
			Arrays.fill(minY, sizeY);
			Arrays.fill(maxX, 0);
			Arrays.fill(maxY, 0);

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
						if (tile.getBridge() != null)
							tile = tile.getBridge();

						int tileZ = tile.getRenderLevel();
						if (tile.getSceneTilePaint() != null) {
							tileVertexKeys(sceneContext, tile, vertices, hashes);

							var override = sceneContext.getTileOverride(tileZ, x, y, TILE_OVERRIDE_MAIN);
							if (seasonalWaterType(override, tile.getSceneTilePaint().getTexture()) == WaterType.NONE) {
								for (int i = 0; i < hashes.length; i++)
									if (tile.getSceneTilePaint().getNeColor() != HIDDEN_HSL || override.forced)
										sceneContext.vertexIsLand.add(hashes[i]);

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
										if (sceneContext.isTileFlagSet(checkZ, x, y, TILE_WATER_FLAG)) {
											sceneContext.underwaterDepthLevels[z][x][y] = 0;
											sceneContext.underwaterDepthLevels[z][x + 1][y] = 0;
											sceneContext.underwaterDepthLevels[z][x][y + 1] = 0;
											sceneContext.underwaterDepthLevels[z][x + 1][y + 1] = 0;

											sceneContext.setTileFlag(z, x, y, TILE_SKIP_FLAG);

											continueLoop = true;

											break;
										}
									}

									if (continueLoop)
										continue;
								}

								sceneContext.setTileFlag(z, x, y, TILE_WATER_FLAG);
								maxZ = max(maxZ, z);
								minZ = min(minZ, z);
								minX[z] = min(minX[z], x);
								maxX[z] = max(maxX[z], x);
								minY[z] = min(minY[z], y);
								maxY[z] = max(maxY[z], y);

								for (int i = 0; i < hashes.length; i++)
									sceneContext.vertexIsWater.add(hashes[i]);
							}
						} else if (tile.getSceneTileModel() != null) {
							SceneTileModel model = tile.getSceneTileModel();

							int faceCount = model.getFaceX().length;

							var overlayOverride = sceneContext.getTileOverride(tileZ, x, y, TILE_OVERRIDE_OVERLAY);
							var underlayOverride = sceneContext.getTileOverride(tileZ, x, y, TILE_OVERRIDE_UNDERLAY);

							// Stop tiles on the same X,Y coordinates on different planes from
							// each generating water. Prevents undesirable results in certain places.
							if (z > 0) {
								boolean tileIncludesWater = false;
								for (int face = 0; face < faceCount; face++) {
									var override = ProceduralGenerator.isOverlayFace(tile, face) ? overlayOverride : underlayOverride;
									int textureId = model.getTriangleTextureId() == null ? -1 :
										model.getTriangleTextureId()[face];
									if (seasonalWaterType(override, textureId) != WaterType.NONE) {
										tileIncludesWater = true;
										break;
									}
								}

								if (tileIncludesWater) {
									boolean continueLoop = false;

									for (int checkZ = 0; checkZ < z; ++checkZ) {
										if (sceneContext.isTileFlagSet(checkZ, x, y, TILE_WATER_FLAG)) {
											sceneContext.underwaterDepthLevels[z][x][y] = 0;
											sceneContext.underwaterDepthLevels[z][x + 1][y] = 0;
											sceneContext.underwaterDepthLevels[z][x][y + 1] = 0;
											sceneContext.underwaterDepthLevels[z][x + 1][y + 1] = 0;

											sceneContext.setTileFlag(z, x, y, TILE_SKIP_FLAG);

											continueLoop = true;

											break;
										}
									}

									if (continueLoop)
										continue;
								}
							}

							for (int face = 0; face < faceCount; face++) {
								faceVertexKeys(tile, face, vertices, hashes);

								var override = ProceduralGenerator.isOverlayFace(tile, face) ? overlayOverride : underlayOverride;
								int textureId = model.getTriangleTextureId() == null ? -1 :
									model.getTriangleTextureId()[face];
								if (seasonalWaterType(override, textureId) == WaterType.NONE) {
									for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
										if (model.getTriangleColorA()[face] != HIDDEN_HSL || override.forced)
											sceneContext.vertexIsLand.add(hashes[vertex]);

										if (vertices[vertex][0] % LOCAL_TILE_SIZE == 0 &&
										    vertices[vertex][1] % LOCAL_TILE_SIZE == 0
										) {
											int vX = (vertices[vertex][0] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
											int vY = (vertices[vertex][1] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;

											sceneContext.underwaterDepthLevels[z][vX][vY] = 0;
										}
									}
								} else {
									sceneContext.setTileFlag(z, x, y, TILE_WATER_FLAG);
									minZ = min(minZ, z);
									maxZ = max(maxZ, z);
									minX[z] = min(minX[z], x);
									maxX[z] = max(maxX[z], x);
									minY[z] = min(minY[z], y);
									maxY[z] = max(maxY[z], y);

									for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
										sceneContext.vertexIsWater.add(hashes[vertex]);
								}
							}
						} else {
							sceneContext.underwaterDepthLevels[z][x][y] = 0;
							sceneContext.underwaterDepthLevels[z][x + 1][y] = 0;
							sceneContext.underwaterDepthLevels[z][x][y + 1] = 0;
							sceneContext.underwaterDepthLevels[z][x + 1][y + 1] = 0;
						}
					}
				}
			}

			// Sink terrain further from shore by desired levels.
			for (int level = 0; level < DEPTH_LEVEL_SLOPE.length - 1; level++) {
				for (int z = minZ; z <= maxZ; ++z) {
					for (int x = minX[z]; x <= maxX[z]; x++) {
						for (int y = minY[z]; y <= maxY[z]; y++) {
							if (sceneContext.underwaterDepthLevels[z][x][y] == 0) {
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
							if (sceneContext.underwaterDepthLevels[z][x - 1][y] < tileHeight) {
								// West
								continue;
							}
							if (x < sceneContext.underwaterDepthLevels[z].length - 1
							    && sceneContext.underwaterDepthLevels[z][x + 1][y] < tileHeight) {
								// East
								continue;
							}
							if (sceneContext.underwaterDepthLevels[z][x][y - 1] < tileHeight) {
								// South
								continue;
							}
							if (y < sceneContext.underwaterDepthLevels[z].length - 1
							    && sceneContext.underwaterDepthLevels[z][x][y + 1] < tileHeight) {
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
			for (int z = minZ; z <= maxZ; ++z) {
				for (int x = minX[z]; x <= maxX[z]; x++) {
					for (int y = minY[z]; y <= maxY[z]; y++) {
						if (sceneContext.underwaterDepthLevels[z][x][y] == 0) {
							underwaterDepths[z][x][y] = 0;
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
			for (int z = minZ; z <= maxZ; ++z) {
				for (int x = minX[z]; x <= maxX[z]; x++) {
					for (int y = minY[z]; y <= maxY[z]; y++) {
						if (!sceneContext.isTileFlagSet(z, x, y, TILE_WATER_FLAG))
							continue;

						Tile tile = tiles[z][x][y];
						if (tile == null)
							continue;

						if (tile.getBridge() != null)
							tile = tile.getBridge();

						if (tile.getSceneTilePaint() != null) {
							tileVertexKeys(sceneContext, tile, vertices, hashes);

							sceneContext.vertexUnderwaterDepth.put(hashes[0], underwaterDepths[z][x][y]); //swVertex
							sceneContext.vertexUnderwaterDepth.put(hashes[1], underwaterDepths[z][x + 1][y]); //seVertex
							sceneContext.vertexUnderwaterDepth.put(hashes[2], underwaterDepths[z][x][y + 1]); //nwVertex
							sceneContext.vertexUnderwaterDepth.put(hashes[3], underwaterDepths[z][x + 1][y + 1]); //neVertex
						} else if (tile.getSceneTileModel() != null) {
							SceneTileModel sceneTileModel = tile.getSceneTileModel();

							int faceCount = sceneTileModel.getFaceX().length;

							for (int face = 0; face < faceCount; face++) {
								faceVertexKeys(tile, face, vertices, hashes);

								for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
									if (vertices[vertex][0] % LOCAL_TILE_SIZE == 0 &&
									    vertices[vertex][1] % LOCAL_TILE_SIZE == 0
									) {
										// The vertex is at the corner of the tile;
										// simply use the offset in the tile grid array.

										int vX = (vertices[vertex][0] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;
										int vY = (vertices[vertex][1] >> LOCAL_COORD_BITS) + sceneContext.sceneOffset;

										sceneContext.vertexUnderwaterDepth.put(hashes[vertex], underwaterDepths[z][vX][vY]);
									} else {
										// If the tile is a tile model and this vertex is shared only by faces that are water,
										// interpolate between the height offsets at each corner to get the height offset
										// of the vertex.

										float lerpX = fract(vertices[vertex][0] / (float) LOCAL_TILE_SIZE);
										float lerpY = fract(vertices[vertex][1] / (float) LOCAL_TILE_SIZE);
										float northHeightOffset = mix(
											underwaterDepths[z][x][y + 1],
											underwaterDepths[z][x + 1][y + 1],
											lerpX
										);
										float southHeightOffset = mix(underwaterDepths[z][x][y], underwaterDepths[z][x + 1][y], lerpX);
										int heightOffset = (int) mix(southHeightOffset, northHeightOffset, lerpY);

										if (!sceneContext.vertexIsLand.contains(hashes[vertex]))
											sceneContext.vertexUnderwaterDepth.put(hashes[vertex], heightOffset);
									}
								}
							}
						}
					}
				}
			}
		}
	}

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
		int color3,
		int[] out
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
		} else if (modelOverride.tzHaarRecolorType == TzHaarRecolorType.HUE_SHIFT) {
			// objects around the entrance to The Inferno only need a hue-shift
			// and very slight lightening to match the lightened terrain
			hsl1[2] += 1;
			hsl2[2] += 1;
			hsl3[2] += 1;
		}

		out[0] = ColorUtils.packRawHsl(hsl1);
		out[1] = ColorUtils.packRawHsl(hsl2);
		out[2] = ColorUtils.packRawHsl(hsl3);

		return out;
	}
}
