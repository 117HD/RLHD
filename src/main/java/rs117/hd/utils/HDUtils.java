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

import java.util.Random;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.ProceduralGenerator.VERTICES_PER_FACE;
import static rs117.hd.scene.ProceduralGenerator.faceLocalVertices;
import static rs117.hd.scene.ProceduralGenerator.isOverlayFace;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

@Slf4j
@Singleton
public class HDUtils {
	public static final long KiB = 1024;
	public static final long MiB = KiB * KiB;
	public static final long GiB = MiB * KiB;
	public static final Random rand = new Random();

	// The epsilon for floating point values used by jogl
	public static final float EPSILON = 1.1920929E-7f;

	public static final float PI = (float) Math.PI;
	public static final float TWO_PI = PI * 2;
	public static final float HALF_PI = PI / 2;
	public static final float QUARTER_PI = PI / 2;

	public static final float MAX_FLOAT_WITH_128TH_PRECISION = 1 << 16;

	public static final int MAX_SNOW_LIGHTNESS = 70;
	public static final int HIDDEN_HSL = 12345678;

	// directional vectors approximately opposite of the directional light used by the client
	private static final float[] LIGHT_DIR_TILE = new float[] { 0.70710678f, 0.70710678f, 0f };

	public static float min(float... v) {
		float min = v[0];
		for (int i = 1; i < v.length; i++)
			if (v[i] < min)
				min = v[i];
		return min;
	}

	public static float max(float... v) {
		float max = v[0];
		for (int i = 1; i < v.length; i++)
			if (v[i] > max)
				max = v[i];
		return max;
	}

	public static float sum(float... v) {
		float sum = 0;
		for (float x : v)
			sum += x;
		return sum;
	}

	public static float avg(float... v) {
		return sum(v) / v.length;
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static float[] lerp(float[] vecA, float[] vecB, float t) {
		float[] out = new float[Math.min(vecA.length, vecB.length)];
		for (int i = 0; i < out.length; i++)
			out[i] = lerp(vecA[i], vecB[i], t);
		return out;
	}

	static int[] lerp(int[] vecA, int[] vecB, float t) {
		int[] out = new int[Math.min(vecA.length, vecB.length)];
		for (int i = 0; i < out.length; i++)
			out[i] = (int) lerp(vecA[i], vecB[i], t);
		return out;
	}

	public static float hermite(float from, float to, float t) {
		float t2 = t * t;
		float t3 = t2 * t;
		return
			from * (1 - 3 * t2 + 2 * t3) +
			to * (3 * t2 - 2 * t3);
	}

	public static float[] hermite(float[] from, float[] to, float t) {
		float[] result = new float[from.length];
		for (int i = 0; i < result.length; i++)
			result[i] = hermite(from[i], to[i], t);
		return result;
	}

	public static double fract(double x) {
		return mod(x, 1);
	}

	public static float fract(float x) {
		return mod(x, 1);
	}

	/**
	 * Modulo that returns the answer with the same sign as the modulus.
	 */
	public static double mod(double x, double modulus) {
		return (x - Math.floor(x / modulus) * modulus);
	}

	/**
	 * Modulo that returns the answer with the same sign as the modulus.
	 */
	public static float mod(float x, float modulus) {
		return (float) (x - Math.floor(x / modulus) * modulus);
	}

	/**
	 * Modulo that returns the answer with the same sign as the modulus.
	 */
	public static int mod(int x, int modulus) {
		return ((x % modulus) + modulus) % modulus;
	}

	public static float clamp(float value, float min, float max) {
		return Math.min(Math.max(value, min), max);
	}

	public static int clamp(int value, int min, int max) {
		return Math.min(Math.max(value, min), max);
	}

	public static long clamp(long value, long min, long max) {
		return Math.min(Math.max(value, min), max);
	}

	public static double log2(double x) {
		return Math.log(x) / Math.log(2);
	}

	public static int vertexHash(int[] vPos) {
		// simple custom hashing function for vertex position data
		StringBuilder s = new StringBuilder();
		for (int part : vPos)
			s.append(part).append(",");
		return s.toString().hashCode();
	}

	public static float[] calculateSurfaceNormals(float[] a, float[] b, float[] c) {
		Vector.subtract(b, a, b);
		Vector.subtract(c, a, c);
		float[] n = new float[3];
		return Vector.cross(n, b, c);
	}

	public static float dotLightDirectionTile(float x, float y, float z) {
		// Tile normal vectors need to be normalized
		float length = x * x + y * y + z * z;
		if (length < EPSILON)
			return 0;
		return (x * LIGHT_DIR_TILE[0] + y * LIGHT_DIR_TILE[1]) / (float) Math.sqrt(length);
	}

	public static long ceilPow2(long x) {
		return (long) Math.pow(2, Math.ceil(Math.log(x) / Math.log(2)));
	}

	public static float[] sunAngles(float altitude, float azimuth) {
		return new float[] { (float) Math.toRadians(altitude), (float) Math.toRadians(azimuth) };
	}

	public static int convertWallObjectOrientation(int orientation) {
		// Note: this is still imperfect, since the model rotation of a wall object depends on more than just the config orientation,
		// 		 i.e. extra rotation depending on wall type whatever. I'm not sure.
		// Derived from config orientation {@link HDUtils#getBakedOrientation}
		switch (orientation) {
			case 1: // east (config orientation = 0)
				return 512;
			case 2: // south (config orientation = 1)
				return 1024;
			case 4: // west (config orientation = 2)
				return 1536;
			case 8: // north (config orientation = 3)
			default:
				return 0;
			case 16: // south-east (config orientation = 0)
				return 768;
			case 32: // south-west (config orientation = 1)
				return 1280;
			case 64: // north-west (config orientation = 2)
				return 1792;
			case 128: // north-east (config orientation = 3)
				return 256;
		}
	}

	// (gameObject.getConfig() >> 6) & 3, // 2-bit orientation
	// (gameObject.getConfig() >> 8) & 1, // 1-bit interactType != 0 (supports items)
	// (gameObject.getConfig() & 0x3F), // 6-bit object type? (10 seems to mean movement blocker)
	// (gameObject.getConfig() >> 9) // should always be zero
	public static int getBakedOrientation(int config) {
		switch (config >> 6 & 3) {
			case 0: // Rotated 180 degrees
				return 1024;
			case 1: // Rotated 90 degrees counter-clockwise
				return 1536;
			case 2: // Not rotated
			default:
				return 0;
			case 3: // Rotated 90 degrees clockwise
				return 512;
		}
	}

	public static String getObjectType(int config) {
		int type = config & 0x3F;
		final String[] OBJECT_TYPES = {
			"StraightWalls",
			"DiagWallsConn",
			"EntireWallsCorners",
			"StraightWallsConn",
			"StraightInDeco",
			"StraightOutDeco",
			"DiagOutDeco",
			"DiagInDeco",
			"DiagInWallDeco",
			"DiagWalls",
			"Objects",
			"GroundObjects",
			"StraightSlopeRoofs",
			"DiagSlopeRoofs",
			"DiagSlopeConnRoofs",
			"StraightSlopeConnRoofs",
			"StraightSlopeCorners",
			"FlatTopRoofs",
			"BottomEdgeRoofs",
			"DiagBottomEdgeConn",
			"StraightBottomEdgeConn",
			"StraightBottomEdgeConnCorners",
			"GroundDecoMapSigns"
		};
		String name = "Unknown";
		if (type < OBJECT_TYPES.length)
			name = OBJECT_TYPES[type];
		return String.format("(%d) %s", type, name);
	}

	public static AABB getSceneBounds(Scene scene) {
		if (!scene.isInstance()) {
			int x = scene.getBaseX() - SCENE_OFFSET;
			int y = scene.getBaseY() - SCENE_OFFSET;
			return new AABB(x, y, x + EXTENDED_SCENE_SIZE, y + EXTENDED_SCENE_SIZE);
		}

		// Assume instances are assembled from approximately adjacent chunks on the map
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;

		int[][][] chunks = scene.getInstanceTemplateChunks();
		for (int[][] plane : chunks) {
			for (int[] column : plane) {
				for (int chunk : column) {
					if (chunk == -1)
						continue;

					// Extract chunk coordinates
					int x = chunk >> 14 & 0x3FF;
					int y = chunk >> 3 & 0x7FF;
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x + 1);
					maxY = Math.max(maxY, y + 1);
				}
			}
		}

		// Return an AABB representing no match, if there are no chunks
		if (maxX < minX || maxY < minY)
			return new AABB(-1, -1);

		// Transform from chunk to world coordinates
		return new AABB(minX << 3, minY << 3, maxX << 3, maxY << 3);
	}

	/**
	 * Returns the south-west coordinate of the extended scene in world coordinates, after resolving instance template
	 * chunks to their original world coordinates. If the scene is instanced, the base coordinates are computed from
	 * the center chunk instead, or any valid chunk if the center chunk is invalid.
	 *
	 * @param scene to get the south-west coordinate for
	 * @param plane to use when resolving instance template chunks
	 * @return the south-western coordinate of the scene in world space
	 */
	public static int[] getSceneBaseExtended(Scene scene, int plane)
	{
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		if (scene.isInstance())
		{
			// Assume the player is loaded into the center chunk, and calculate the world space position of the lower
			// left corner of the scene, assuming well-behaved template chunks are used to create the instance.
			int chunkX = 6, chunkY = 6;
			int chunk = scene.getInstanceTemplateChunks()[plane][chunkX][chunkY];
			if (chunk == -1)
			{
				// If the center chunk is invalid, pick any valid chunk and hope for the best
				int[][] chunks = scene.getInstanceTemplateChunks()[plane];
				outer:
				for (chunkX = 0; chunkX < chunks.length; chunkX++)
				{
					for (chunkY = 0; chunkY < chunks[chunkX].length; chunkY++)
					{
						chunk = chunks[chunkX][chunkY];
						if (chunk != -1)
						{
							break outer;
						}
					}
				}
			}

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

		return new int[] { baseX - SCENE_OFFSET, baseY - SCENE_OFFSET };
	}

	/**
	 * The returned plane may be different, so it's not safe to use for indexing into overlay IDs for instance
	 */
	public static int[] localToWorld(Scene scene, int localX, int localY, int plane) {
		int sceneX = localX >> LOCAL_COORD_BITS;
		int sceneY = localY >> LOCAL_COORD_BITS;

		if (scene.isInstance() && sceneX >= 0 && sceneY >= 0 && sceneX < SCENE_SIZE && sceneY < SCENE_SIZE) {
			int chunkX = sceneX / 8;
			int chunkY = sceneY / 8;
			int templateChunk = scene.getInstanceTemplateChunks()[plane][chunkX][chunkY];
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

		return new int[] { scene.getBaseX() + sceneX, scene.getBaseY() + sceneY, plane };
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
