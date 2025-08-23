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

	public static float signedTriangleArea(float[] a, float[] b, float[] c) {
		return (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1])) * 0.5f;
	}

	public static float triangleArea(float[] a, float[] b, float[] c) {
		return Math.abs(signedTriangleArea(a, b, c));
	}

	public static float barycentricDepth(float[] p, float[] a, float[] b, float[] c,
		float depthA, float depthB, float depthC) {

		// Calculate the determinant of the triangle (twice the area)
		float det = (b[1] - c[1]) * (a[0] - c[0]) +
					(c[0] - b[0]) * (a[1] - c[1]);

		// Avoid division by near-zero determinant (degenerate triangle)
		if (Math.abs(det) < 1e-8f) return depthA;

		// Compute barycentric coordinates (lambdas) for point p
		float lambda1 = ((b[1] - c[1]) * (p[0] - c[0]) +
						 (c[0] - b[0]) * (p[1] - c[1])) / det;

		float lambda2 = ((c[1] - a[1]) * (p[0] - c[0]) +
						 (a[0] - c[0]) * (p[1] - c[1])) / det;

		float lambda3 = 1.0f - lambda1 - lambda2;

		// Interpolate depth using the barycentric coordinates
		return lambda1 * depthA + lambda2 * depthB + lambda3 * depthC;
	}

	public static boolean pointInTriangle(float[] p, float[] a, float[] b, float[] c) {
		// Compute vectors
		float[] edge0 = new float[2]; // c - a
		float[] edge1 = new float[2]; // b - a
		float[] pointVec = new float[2]; // p - a

		subtract(edge0, c, a);
		subtract(edge1, b, a);
		subtract(pointVec, p, a);

		// Compute dot products
		float dot00 = dot(edge0, edge0);
		float dot01 = dot(edge0, edge1);
		float dot02 = dot(edge0, pointVec);
		float dot11 = dot(edge1, edge1);
		float dot12 = dot(edge1, pointVec);

		// Compute barycentric coordinates
		float denominator = dot00 * dot11 - dot01 * dot01;
		if (denominator == 0.0f) return false; // Degenerate triangle

		float invDenom = 1.0f / denominator;
		float u = (dot11 * dot02 - dot01 * dot12) * invDenom;
		float v = (dot00 * dot12 - dot01 * dot02) * invDenom;

		// Check if point is inside the triangle
		return (u >= 0) && (v >= 0) && (u + v <= 1.0f);
	}

	public static boolean isSphereIntersectingFrustum(float x, float y, float z, float radius, float[][] cullingPlanes) {
		for (float[] plane : cullingPlanes) {
			if (distanceToPlane(plane, x, y, z) < -radius) {
				return false;
			}
		}

		return true;
	}

	public static boolean isSphereIntersectingAABB(
		float centerX, float centerY, float centerZ,
		float radius,
		float minX, float minY, float minZ,
		float maxX, float maxY, float maxZ
	) {
		float d = 0;

		// X axis
		if (centerX < minX) {
			d += (centerX - minX) * (centerX - minX);
		} else if (centerX > maxX) {
			d += (centerX - maxX) * (centerX - maxX);
		}

		// Y axis
		if (centerY < minY) {
			d += (centerY - minY) * (centerY - minY);
		} else if (centerY > maxY) {
			d += (centerY - maxY) * (centerY - maxY);
		}

		// Z axis
		if (centerZ < minZ) {
			d += (centerZ - minZ) * (centerZ - minZ);
		} else if (centerZ > maxZ) {
			d += (centerZ - maxZ) * (centerZ - maxZ);
		}

		return d <= radius * radius;
	}

	public static boolean sphereIntersectsOOBB(
		float[] sphereCenterWorld, float radius,
		float[] viewMatrix,
		float[] oobbMin, float[] oobbMax
	) {
		final float[] localCenter = new float[3];
		Mat4.transformVecAffine(localCenter, viewMatrix, sphereCenterWorld);

		float[] closest = clamp(localCenter, oobbMin, oobbMax);
		float distSq = distanceSquared(localCenter, closest);

		return distSq <= radius * radius;
	}

	public static boolean aabbIntersectsOOBB(
		float minWorldX, float minWorldY, float minWorldZ,
		float maxWorldX, float maxWorldY, float maxWorldZ,
		float[] viewMatrix,
		float[] oobbMin, // in view (light) space
		float[] oobbMax  // in view (light) space
	) {
		final float[] cornerWS = new float[3];
		final float[] cornerLS = new float[3];

		// Generate all 8 corners of the world-space AABB
		for (int x = 0; x <= 1; x++) {
			for (int y = 0; y <= 1; y++) {
				for (int z = 0; z <= 1; z++) {
					cornerWS[0] = (x == 0) ? minWorldX : maxWorldX;
					cornerWS[1] = (y == 0) ? minWorldY : maxWorldY;
					cornerWS[2] = (z == 0) ? minWorldZ : maxWorldZ;

					// Transform world-space corner to view (light) space
					Mat4.transformVecAffine(cornerLS, viewMatrix, cornerWS);

					if (pointInsideAABB(cornerLS, oobbMin, oobbMax)) {
						return true;
					}
				}
			}
		}

		return false;
	}

	public static boolean pointInsideAABB(float[] point, float[] min, float[] max) {
		return point[0] >= min[0] && point[0] <= max[0] &&
			   point[1] >= min[1] && point[1] <= max[1] &&
			   point[2] >= min[2] && point[2] <= max[2];
	}

	public static boolean isAABBIntersectingAABB(
		float minA_X, float minA_Y, float minA_Z,
		float maxA_X, float maxA_Y, float maxA_Z,
		float minB_X, float minB_Y, float minB_Z,
		float maxB_X, float maxB_Y, float maxB_Z
	) {
		return (
			minA_X <= maxB_X && maxA_X >= minB_X &&
			minA_Y <= maxB_Y && maxA_Y >= minB_Y &&
			minA_Z <= maxB_Z && maxA_Z >= minB_Z
		);
	}


	public static boolean isAABBIntersectingFrustum(
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		float[][] cullingPlanes,
		float padding
	) {
		for (float[] plane : cullingPlanes) {
			if (
				distanceToPlane(plane, minX, minY, minZ) < padding &&
				distanceToPlane(plane, maxX, minY, minZ) < padding &&
				distanceToPlane(plane, minX, maxY, minZ) < padding &&
				distanceToPlane(plane, maxX, maxY, minZ) < padding &&
				distanceToPlane(plane, minX, minY, maxZ) < padding &&
				distanceToPlane(plane, maxX, minY, maxZ) < padding &&
				distanceToPlane(plane, minX, maxY, maxZ) < padding &&
				distanceToPlane(plane, maxX, maxY, maxZ) < padding) {
				// Not visible - all returned negative
				return false;
			}
		}

		// Potentially visible
		return true;
	}

	public static boolean isCylinderIntersectingFrustrum(int x, int y, int z, int height, int radius, float[][] cullingPlanes) {
		final int SAMPLES = 8; // Number of points to test around the circle
		final float TWO_PI = (float) (2 * Math.PI);
		final float ANGLE_STEP = TWO_PI / SAMPLES;

		float topY = y + height;

		// Also check cylinder center top and bottom
		if (isPointInsideFrustum(x, y, z, cullingPlanes) ||
			isPointInsideFrustum(x, topY, z, cullingPlanes)) {
			return true;
		}

		// Check top and bottom circle edge points
		for (int i = 0; i < SAMPLES; i++) {
			float angle = i * ANGLE_STEP;
			float offsetX = (float) Math.cos(angle) * radius;
			float offsetZ = (float) Math.sin(angle) * radius;

			float px = x + offsetX;
			float pz = z + offsetZ;

			// Check if this point on top or bottom circle is inside the frustum
			if (isPointInsideFrustum(px, y, pz, cullingPlanes) ||
				isPointInsideFrustum(px, topY, pz, cullingPlanes)) {
				return true;
			}
		}

		return false; // All tested points are outside
	}

	public static boolean IsTileVisible(int x, int z, int h0, int h1, int h2, int h3, float[][] cullingPlanes) {
		return IsTileVisible(x, z, h0, h1, h2, h3, cullingPlanes, 0);
	}

	public static boolean IsTileVisible(int x, int z, int h0, int h1, int h2, int h3, float[][] cullingPlanes, int padding) {
		int x1 = x + LOCAL_TILE_SIZE;
		int z1 = z + LOCAL_TILE_SIZE;
		for (float[] plane : cullingPlanes) {
			if (distanceToPlane(plane, x, h0, z) >= padding ||
				distanceToPlane(plane, x1, h1, z) >= padding ||
				distanceToPlane(plane, x, h2, z1) >= padding ||
				distanceToPlane(plane, x1, h3, z1) >= padding) {
				// At least one point is inside this plane; continue testing other planes
				continue;
			}
			return false; // All points outside this plane
		}
		return true;
	}

	public static boolean isPointInsideFrustum(float x, float y, float z, float[][] cullingPlanes) {
		for (float[] plane : cullingPlanes) {
			if (distanceToPlane(plane, x, y, z) < 0) {
				return false; // Point is outside this plane
			}
		}
		return true;
	}

	public enum Halfspace {
		NEGATIVE,
		ON_PLANE,
		POSITIVE,
	}

	public static Halfspace classifyPoint(float[] plane, float x, float y, float z) {
		float d = distanceToPlane(plane, x, y, z);
		if (d < 0) return Halfspace.NEGATIVE;
		if (d > 0) return Halfspace.POSITIVE;
		return Halfspace.ON_PLANE;
	}

	public static int tileCoordinateToIndex(int plane, int tileExX, int tileExY) {
		return (plane * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE) + (tileExX * EXTENDED_SCENE_SIZE) + tileExY;
	}

	public static void clipFrustumToDistance(float[][] frustumCorners, float maxDistance) {
		if (frustumCorners.length != 8) {
			return;
		}

		// Clip Far Plane Corners
		for (int i = 4; i < frustumCorners.length; i++) {
			float[] nearCorner = frustumCorners[i - 4];
			float[] farCorner = frustumCorners[i];
			float[] nearToFarVec = subtract(nearCorner, farCorner);
			float len = length(nearToFarVec);

			if (len > 1e-5f && len > maxDistance) {
				normalize(nearToFarVec, nearToFarVec);
				float[] clipped = multiply(nearToFarVec, maxDistance);
				frustumCorners[i] = add(clipped, nearCorner);
			}
		}
	}
}
