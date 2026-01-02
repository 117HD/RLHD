/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
package rs117.hd.renderer.zone;

import java.util.ArrayDeque;
import java.util.Arrays;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
class FacePrioritySorter {
	static final int[] distances;
	static final char[] distanceFaceCount;
	static final char[][] distanceToFaces;

	private static final float[] modelProjectedX;
	private static final float[] modelProjectedY;

	static final int[] numOfPriority;
	private static final int[] eq10;
	private static final int[] eq11;
	private static final int[] lt10;
	static final int[][] orderedFaces;

	private static final int MAX_VERTEX_COUNT = 6500;
	private static final int MAX_DIAMETER = 6000;
	private static final int ZSORT_GROUP_SIZE = 1024; // was 512
	private static final int MAX_FACES_PER_PRIORITY = 4000; // was 2500

	static {
		distances = new int[MAX_VERTEX_COUNT];
		distanceFaceCount = new char[MAX_DIAMETER];
		distanceToFaces = new char[MAX_DIAMETER][ZSORT_GROUP_SIZE];

		modelProjectedX = new float[MAX_VERTEX_COUNT];
		modelProjectedY = new float[MAX_VERTEX_COUNT];

		numOfPriority = new int[12];
		eq10 = new int[MAX_FACES_PER_PRIORITY];
		eq11 = new int[MAX_FACES_PER_PRIORITY];
		lt10 = new int[12];
		orderedFaces = new int[12][MAX_FACES_PER_PRIORITY];
	}

	public final class SortedModel {
		private int[] sorted = new int[16];
		private int sortedFaceCount;

		private int[] unsorted = new int[16];
		private int unsortedFaceCount;

		private void pushSortedFace(int face) {
			if(sortedFaceCount >= sorted.length)
				sorted = Arrays.copyOf(sorted, sorted.length * 2);
			sorted[sortedFaceCount++] = face;
		}

		private void pushUnsortedFace(int face) {
			if(unsortedFaceCount >= unsorted.length)
				unsorted = Arrays.copyOf(unsorted, unsorted.length * 2);
			unsorted[unsortedFaceCount++] = face;
		}

		public int getFaceCount() { return sortedFaceCount + unsortedFaceCount; }

		public int getFace(int idx) { return idx < sortedFaceCount ? sorted[idx] : unsorted[idx - sortedFaceCount]; }

		public void free() {
			sortedFaceCount = 0;
			unsortedFaceCount = 0;
			sortedModelPool.add(this);
		}
	}

	private final ArrayDeque<SortedModel> sortedModelPool = new ArrayDeque<>();

	SortedModel sortModelFaces(
		Projection proj,
		Model model,
		int orientation,
		int x,
		int y,
		int z
	) {
		final int vertexCount = model.getVerticesCount();
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final int faceCount = model.getFaceCount();
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] faceColors3 = model.getFaceColors3();
		final byte[] faceRenderPriorities = model.getFaceRenderPriorities();

		orientation = mod(orientation, 2048);
		float orientSinf = SINE[orientation] / 65536f;
		float orientCosf = COSINE[orientation] / 65536f;

		float[] p = proj.project(x, y, z);
		int zero = (int) p[2];

		for (int v = 0; v < vertexCount; ++v) {
			float vertexX = verticesX[v];
			float vertexY = verticesY[v];
			float vertexZ = verticesZ[v];

			if (orientation != 0) {
				float x0 = vertexX;
				vertexX = vertexZ * orientSinf + x0 * orientCosf;
				vertexZ = vertexZ * orientCosf - x0 * orientSinf;
			}

			// move to local position
			vertexX += x;
			vertexY += y;
			vertexZ += z;

			p = proj.project(vertexX, vertexY, vertexZ);
			if (p[2] < 50) {
				return null;
			}

			modelProjectedX[v] = p[0] / p[2];
			modelProjectedY[v] = p[1] / p[2];
			distances[v] = (int) p[2] - zero;
		}

		model.calculateBoundsCylinder();
		final int diameter = model.getDiameter();
		final int radius = model.getRadius();
		if (diameter >= 6000) {
			return null;
		}

		Arrays.fill(distanceFaceCount, 0, diameter, (char) 0);

		SortedModel sorted = !sortedModelPool.isEmpty() ? sortedModelPool.poll() : new SortedModel();
		for (char i = 0; i < faceCount; ++i) {
			if (faceColors3[i] == -2)
				continue;

			final int v1 = indices1[i];
			final int v2 = indices2[i];
			final int v3 = indices3[i];

			final float
				aX = modelProjectedX[v1],
				aY = modelProjectedY[v1],
				bX = modelProjectedX[v2],
				bY = modelProjectedY[v2],
				cX = modelProjectedX[v3],
				cY = modelProjectedY[v3];

			// Back-face culling
			if ((aX - bX) * (cY - bY) - (cX - bX) * (aY - bY) <= 0) {
				sorted.pushUnsortedFace(i);
				continue;
			}

			int distance = radius + (distances[v1] + distances[v2] + distances[v3]) / 3;
			assert distance >= 0 && distance < diameter;
			distanceToFaces[distance][distanceFaceCount[distance]++] = i;
		}

		if (faceRenderPriorities == null) {
			for (int i = diameter - 1; i >= 0; --i) {
				final int cnt = distanceFaceCount[i];
				if (cnt > 0) {
					final char[] faces = distanceToFaces[i];
					for (int faceIdx = 0; faceIdx < cnt; ++faceIdx)
						sorted.pushSortedFace(faces[faceIdx]);
				}
			}
		} else {
			Arrays.fill(numOfPriority, 0);
			Arrays.fill(lt10, 0);

			for (int i = diameter - 1; i >= 0; --i) {
				final int cnt = distanceFaceCount[i];
				if (cnt > 0) {
					final char[] faces = distanceToFaces[i];
					for (int faceIdx = 0; faceIdx < cnt; ++faceIdx) {
						final int face = faces[faceIdx];
						final byte pri = faceRenderPriorities[face];
						final int distIdx = numOfPriority[pri]++;

						orderedFaces[pri][distIdx] = face;
						if (pri < 10) {
							lt10[pri] += i;
						} else if (pri == 10) {
							eq10[distIdx] = i;
						} else {
							eq11[distIdx] = i;
						}
					}
				}
			}

			int avg12 = 0;
			if (numOfPriority[1] > 0 || numOfPriority[2] > 0)
				avg12 = (lt10[1] + lt10[2]) / (numOfPriority[1] + numOfPriority[2]);

			int avg34 = 0;
			if (numOfPriority[3] > 0 || numOfPriority[4] > 0)
				avg34 = (lt10[3] + lt10[4]) / (numOfPriority[3] + numOfPriority[4]);

			int avg68 = 0;
			if (numOfPriority[6] > 0 || numOfPriority[8] > 0)
				avg68 = (lt10[8] + lt10[6]) / (numOfPriority[8] + numOfPriority[6]);

			int drawnFaces = 0;
			int numDynFaces = numOfPriority[10];
			int[] dynFaces = orderedFaces[10];
			int[] dynFaceDistances = eq10;
			if (drawnFaces == numDynFaces) {
				numDynFaces = numOfPriority[11];
				dynFaces = orderedFaces[11];
				dynFaceDistances = eq11;
			}

			int currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;

			for (int pri = 0; pri < 10; ++pri) {
				while (pri == 0 && currFaceDistance > avg12) {
					sorted.pushSortedFace(dynFaces[drawnFaces++]);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
				}

				while (pri == 3 && currFaceDistance > avg34) {
					sorted.pushSortedFace(dynFaces[drawnFaces++]);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
				}

				while (pri == 5 && currFaceDistance > avg68) {
					sorted.pushSortedFace(dynFaces[drawnFaces++]);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
				}

				final int priNum = numOfPriority[pri];
				final int[] priFaces = orderedFaces[pri];

				for (int faceIdx = 0; faceIdx < priNum; ++faceIdx)
					sorted.pushSortedFace(priFaces[faceIdx]);
			}

			while (currFaceDistance != -1000) {
				sorted.pushSortedFace(dynFaces[drawnFaces++]);

				if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
					drawnFaces = 0;
					dynFaces = orderedFaces[11];
					numDynFaces = numOfPriority[11];
					dynFaceDistances = eq11;
				}

				currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
			}
		}

		return sorted;
	}
}
