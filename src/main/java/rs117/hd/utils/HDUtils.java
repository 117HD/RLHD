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
package rs117.hd.utils;

import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.data.ObjectType;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.ProceduralGenerator.VERTICES_PER_FACE;
import static rs117.hd.scene.ProceduralGenerator.faceLocalVertices;
import static rs117.hd.scene.ProceduralGenerator.isOverlayFace;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class HDUtils {
	public static final int HIDDEN_HSL = 12345678;

	public static int vertexHash(int[] vPos) {
		// simple custom hashing function for vertex position data
		StringBuilder s = new StringBuilder();
		for (int part : vPos)
			s.append(part).append(",");
		return s.toString().hashCode();
	}

	public static float[] calculateSurfaceNormals(float[] a, float[] b, float[] c) {
		subtract(b, a, b);
		subtract(c, a, c);
		return cross(b, c);
	}

	public static long ceilPow2(long l) {
		assert l >= 0;
		l--; // Reduce by 1 in case it's already a power of 2
		// Fill in all bits below the highest active bit
		for (int i = 1; i <= 32; i *= 2)
			l |= l >> i;
		return l + 1; // Bump it up to the next power of 2
	}

	public static float[] sunAngles(float altitude, float azimuth) {
		return multiply(vec(altitude, azimuth), DEG_TO_RAD);
	}

	public static float[] ensureArrayLength(float[] array, int targetLength) {
		return array.length == targetLength ? array : slice(array, 0, targetLength);
	}

	public static int convertWallObjectOrientation(int orientation) {
		// Note: this is still imperfect, since the model rotation of a wall object depends on more than just the config orientation,
		// 		 i.e. extra rotation depending on wall type whatever. I'm not sure.
		// Derived from config orientation {@link HDUtils#getBakedOrientation}
		switch (orientation) {
			case 1:
				return 512; // west
			case 2:
				return 1024; // north
			case 4:
				return 1536; // east
			case 8:
			default:
				return 0; // south
			case 16:
				return 768; // north-west
			case 32:
				return 1280; // north-east
			case 64:
				return 1792; // south-east
			case 128:
				return 256; // south-west
		}
	}

	// (gameObject.getConfig() >> 6) & 3, // 2-bit orientation
	// (gameObject.getConfig() >> 8) & 1, // 1-bit interactType != 0 (supports items)
	// (gameObject.getConfig() >> 9) // should always be zero

	/**
	 * Computes the orientation used when uploading the model.
	 * This does not include the extra 45-degree rotation of diagonal models.
	 */
	public static int getModelPreOrientation(int config) {
		var objectType = ObjectType.fromConfig(config);
		int orientation = 1024 + 512 * (config >>> 6 & 3);
		switch (objectType) {
			case WallDiagonalCorner:
			case WallSquareCorner:
			case WallDecorDiagonalOffset:
			case WallDecorDiagonalBoth:
				orientation += 1024;
		}
		return orientation % 2048;
	}

	/**
	 * Computes the complete model orientation, including the pre-orientation when uploading,
	 * and the extra 45-degree rotation of diagonal models.
	 */
	public static int getModelOrientation(int config) {
		int orientation = getModelPreOrientation(config);
		var objectType = ObjectType.fromConfig(config);
		switch (objectType) {
			case WallDecorDiagonalNoOffset:
			case CentrepieceDiagonal:
				orientation += 256;
		}
		return orientation % 2048;
	}

	/**
	 * Returns the south-west coordinate of the scene in world coordinates, after resolving instance template
	 * chunks to their original world coordinates. If the scene is instanced, the base coordinates are computed from
	 * the center chunk instead, or any valid chunk if the center chunk is invalid.
	 *
	 * @param scene to get the south-west coordinate for
	 * @param plane to use when resolving instance template chunks
	 * @return the south-western coordinate of the scene in world space
	 */
	public static int[] getSceneBaseBestGuess(Scene scene, int plane) {
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		if (scene.isInstance()) {
			// Assume the player is loaded into the center chunk, and calculate the world space position of the lower
			// left corner of the scene, assuming well-behaved template chunks are used to create the instance.
			int chunkX = 6, chunkY = 6;
			int[][] chunks = scene.getInstanceTemplateChunks()[plane];
			int chunk = chunks[chunkX][chunkY];
			if (chunk == -1) {
				// If the center chunk is invalid, pick any valid chunk and hope for the best
				outer:
				for (chunkX = 0; chunkX < chunks.length; chunkX++) {
					for (chunkY = 0; chunkY < chunks[chunkX].length; chunkY++) {
						chunk = chunks[chunkX][chunkY];
						if (chunk != -1)
							break outer;
					}
				}
			}

			if (chunk != -1) {
				// Extract chunk coordinates
				baseX = chunk >> 14 & 0x3FF;
				baseY = chunk >> 3 & 0x7FF;
				// Shift to what would be the lower left corner chunk if the template chunks were contiguous on the map
				baseX -= chunkX;
				baseY -= chunkY;
				// Transform to world coordinates
				baseX <<= 3;
				baseY <<= 3;
			}
		}

		return ivec(baseX, baseY, 0);
	}

	/**
	 * The returned plane may be different
	 */
	public static int[] localToWorld(Scene scene, int localX, int localY, int plane) {
		return sceneToWorld(scene, localX >> LOCAL_COORD_BITS, localY >> LOCAL_COORD_BITS, plane);
	}

	/**
	 * The returned plane may be different
	 */
	public static int[] sceneToWorld(Scene scene, int sceneX, int sceneY, int plane) {
		if (scene.isInstance()) {
			if (sceneX >= 0 && sceneY >= 0 && sceneX < SCENE_SIZE && sceneY < SCENE_SIZE) {
				int chunkX = sceneX / CHUNK_SIZE;
				int chunkY = sceneY / CHUNK_SIZE;
				int templateChunk = scene.getInstanceTemplateChunks()[plane][chunkX][chunkY];
				if (templateChunk != -1) {
					int rotation = 4 - (templateChunk >> 1 & 3);
					int templateChunkY = (templateChunk >> 3 & 2047) * 8;
					int templateChunkX = (templateChunk >> 14 & 1023) * 8;
					int templateChunkPlane = templateChunk >> 24 & 3;
					int worldX = templateChunkX + (sceneX & 7);
					int worldY = templateChunkY + (sceneY & 7);

					int[] pos = { worldX, worldY, templateChunkPlane };

					chunkX = pos[0] & -8;
					chunkY = pos[1] & -8;
					int x = pos[0] & 7;
					int y = pos[1] & 7;
					switch (rotation) {
						case 1:
							pos[0] = chunkX + y;
							pos[1] = chunkY + (7 - x);
							break;
						case 2:
							pos[0] = chunkX + (7 - x);
							pos[1] = chunkY + (7 - y);
							break;
						case 3:
							pos[0] = chunkX + (7 - y);
							pos[1] = chunkY + x;
							break;
					}

					return pos;
				}
			}
			return ivec(-1, -1, 0);
		}

		return ivec(scene.getBaseX() + sceneX, scene.getBaseY() + sceneY, plane);
	}

	public static int worldToRegionID(int[] worldPoint) {
		return worldToRegionID(worldPoint[0], worldPoint[1]);
	}

	public static int worldToRegionID(int worldX, int worldY) {
		return worldX >> 6 << 8 | worldY >> 6;
	}

	public static boolean is32Bit() {
		return System.getProperty("sun.arch.data.model", "Unknown").equals("32");
	}

	public static boolean sceneIntersects(Scene scene, int numChunksExtended, Area area) {
		return sceneIntersects(scene, numChunksExtended, area.aabbs);
	}

	public static boolean sceneIntersects(Scene scene, int numChunksExtended, AABB... aabbs) {
		if (scene.isInstance()) {
			var templateChunks = scene.getInstanceTemplateChunks();
			for (var plane : templateChunks) {
				for (var column : plane) {
					for (int chunk : column) {
						if (chunk == -1)
							continue;

						int chunkX = chunk >> 14 & 1023;
						int chunkY = chunk >> 3 & 2047;
						int minX = chunkX * CHUNK_SIZE;
						int minY = chunkY * CHUNK_SIZE;
						int maxX = (chunkX + 1) * CHUNK_SIZE - 1;
						int maxY = (chunkY + 1) * CHUNK_SIZE - 1;

						for (var aabb : aabbs)
							if (aabb.intersects(minX, minY, maxX, maxY))
								return true;
					}
				}
			}

			return false;
		}

		return getNonInstancedSceneBounds(scene, numChunksExtended).intersects(aabbs);
	}

	public static AABB getNonInstancedSceneBounds(Scene scene, int numChunksExtended) {
		assert !scene.isInstance();
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();
		int extended = numChunksExtended * CHUNK_SIZE;
		return new AABB(
			baseX - extended,
			baseY - extended,
			baseX + SCENE_SIZE + extended - 1,
			baseY + SCENE_SIZE + extended - 1
		);
	}

	public static int getSouthWesternMostTileColor(int[] out, Tile tile) {
		var paint = tile.getSceneTilePaint();
		var model = tile.getSceneTileModel();
		int hsl = 0;
		if (paint != null) {
			hsl = paint.getSwColor();
			ColorUtils.unpackRawHsl(out, hsl);
		} else if (model != null) {
			int faceCount = tile.getSceneTileModel().getFaceX().length;
			final int[] faceColorsA = model.getTriangleColorA();
			final int[] faceColorsB = model.getTriangleColorB();
			final int[] faceColorsC = model.getTriangleColorC();

			outer:
			for (int face = 0; face < faceCount; face++) {
				if (isOverlayFace(tile, face))
					continue;

				int[][] vertices = faceLocalVertices(tile, face);
				int[] faceColors = new int[] { faceColorsA[face], faceColorsB[face], faceColorsC[face] };

				for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
					hsl = faceColors[vertex];
					if (vertices[vertex][0] != LOCAL_TILE_SIZE && vertices[vertex][1] != LOCAL_TILE_SIZE)
						break outer;
				}
			}

			ColorUtils.unpackRawHsl(out, hsl);
		}
		return hsl;
	}
}
