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

import java.util.HashSet;
import java.util.Random;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;

import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;

@Slf4j
@Singleton
public class HDUtils {
	public static final long KiB = 1024;
	public static final long MiB = KiB * KiB;
	public static final long GiB = MiB * KiB;
	public static final Random rand = new Random();

	// directional vectors approximately opposite of the directional light used by the client
	private static final float[] LIGHT_DIR_TILE = new float[] { 0.70710678f, 0.70710678f, 0f };
	public static final float[] LIGHT_DIR_MODEL = new float[] { 0.57735026f, 0.57735026f, 0.57735026f };

	// The epsilon for floating point values used by jogl
	public static final float EPSILON = 1.1920929E-7f;

	/**
	 * Computes a + b, storing it in the out array
	 */
	public static float[] add(float[] out, float[] a, float[] b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i] + b[i];
		return out;
	}

	/**
	 * Computes a - b, storing it in the out array
	 */
	public static float[] subtract(float[] out, float[] a, float[] b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i] - b[i];
		return out;
	}

	public static float[] cross(float[] out, float[] a, float[] b) {
		out[0] = a[1] * b[2] - a[2] * b[1];
		out[1] = a[2] * b[0] - a[0] * b[2];
		out[2] = a[0] * b[1] - a[1] * b[0];
		return out;
	}

	public static float[] abs(float[] out, float[] v) {
		for (int i = 0; i < out.length; i++)
			out[i] = Math.abs(v[i]);
		return out;
	}

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

	public static int clamp(int value, int min, int max) {
		return Math.min(max, Math.max(min, value));
	}

	public static float clamp(float value, float min, float max) {
		return Math.min(max, Math.max(min, value));
	}

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
		float[] n = new float[3];
		return cross(n, b, c);
	}

	public static int[] colorIntToHSL(int colorInt) {
		int[] outHSL = new int[3];
		outHSL[0] = colorInt >> 10 & 0x3F;
		outHSL[1] = colorInt >> 7 & 0x7;
		outHSL[2] = colorInt & 0x7F;
		return outHSL;
	}

	public static int colorHSLToInt(int[] colorHSL) {
		return (colorHSL[0] << 3 | colorHSL[1]) << 7 | colorHSL[2];
	}

	public static float dotLightDirectionModel(float x, float y, float z) {
		// Model normal vectors need to be normalized
		float length = x * x + y * y + z * z;
		if (length < EPSILON)
			return 0;
		return (x * LIGHT_DIR_MODEL[0] + y * LIGHT_DIR_MODEL[1] + z * LIGHT_DIR_MODEL[2]) / (float) Math.sqrt(length);
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

	public static HashSet<Integer> getSceneRegionIds(Scene scene) {
		HashSet<Integer> regionIds = new HashSet<>();

		if (scene.isInstance()) {
			// If the center chunk is invalid, pick any valid chunk and hope for the best
			int[][][] chunks = scene.getInstanceTemplateChunks();
			for (int[][] plane : chunks) {
				for (int[] column : plane) {
					for (int chunk : column) {
						if (chunk == -1)
							continue;

						// Extract chunk coordinates
						int x = chunk >> 14 & 0x3FF;
						int y = chunk >> 3 & 0x7FF;
						regionIds.add((x >> 3) << 8 | y >> 3);
					}
				}
			}
		}
		else
		{
			int baseX = scene.getBaseX();
			int baseY = scene.getBaseY();
			for (int x = 0; x < SCENE_SIZE; x += REGION_SIZE)
				for (int y = 0; y < SCENE_SIZE; y += REGION_SIZE)
					regionIds.add((baseX + x >> 6) << 8 | baseY + y >> 6);
		}

		return regionIds;
	}

	/**
	 * Returns the south-west coordinate of the scene in world space, after resolving instance template chunks to their
	 * original world coordinates. If the scene is instanced, the base coordinates are computed from the center chunk.
	 *
	 * @param scene to get the south-west coordinate for
	 * @param plane to use when resolving instance template chunks
	 * @return the south-western coordinate of the scene in world space
	 */
	public static WorldPoint getSceneBase(Scene scene, int plane)
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

		return new WorldPoint(baseX, baseY, plane);
	}

	public static int[] cameraSpaceToWorldPoint(Client client, int relativeX, int relativeZ) {
		int localX = client.getCameraX2() + relativeX;
		int localY = client.getCameraZ2() + relativeZ;
		int plane = client.getPlane();
		return localToWorld(client.getScene(), localX, localY, plane);
	}

	public static int[] localToWorld(Scene scene, int localX, int localY, int plane) {
		int sceneX = localX / LOCAL_TILE_SIZE;
		int sceneY = localY / LOCAL_TILE_SIZE;

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

	public static boolean sceneIsTheGauntlet(Scene scene) {
		if (!scene.isInstance())
			return false;

		var templateChunks = scene.getInstanceTemplateChunks();
		for (var plane : templateChunks) {
			for (var column : plane) {
				for (int chunk : column) {
					if (chunk == -1)
						continue;

					int worldX = (chunk >> 14 & 1023) * 8;
					int worldY = (chunk >> 3 & 2047) * 8;
					int regionId = HDUtils.worldToRegionID(worldX, worldY);

					// The Gauntlet should only ever consist of chunks from these regions
					return regionId == 7512 || regionId == 7768;
				}
			}
		}

		return false;
	}
}
