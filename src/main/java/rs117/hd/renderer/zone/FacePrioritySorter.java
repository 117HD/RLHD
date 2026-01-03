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
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static rs117.hd.renderer.zone.Zone.VERT_SIZE;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
final class FacePrioritySorter {
	private static final int MAX_VERTEX_COUNT = 6500;
	private static final int MAX_DIAMETER = 6000;
	private static final int ZSORT_GROUP_SIZE = 1024;
	private static final int MAX_FACES_PER_PRIORITY = 4000;
	private static final int PRIORITY_COUNT = 12;

	private static final int FACES_PER_DISTANCE = ZSORT_GROUP_SIZE;

	private final int[] distances = new int[MAX_VERTEX_COUNT];
	private final char[] distanceFaceCount = new char[MAX_DIAMETER];
	private final int[] distanceToFaces = new int[MAX_DIAMETER * FACES_PER_DISTANCE];
	private final int[] numOfPriority = new int[PRIORITY_COUNT];
	private final int[] orderedFaces = new int[PRIORITY_COUNT * MAX_FACES_PER_PRIORITY];
	private final float[] modelProjectedX = new float[MAX_VERTEX_COUNT];
	private final float[] modelProjectedY = new float[MAX_VERTEX_COUNT];
	private final int[] eq10 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] eq11 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] lt10 = new int[PRIORITY_COUNT];

	private final long[] distanceStamp = new long[MAX_DIAMETER];
	private final ArrayDeque<SortingSlice> sortingSlicePool = new ArrayDeque<>();
	private long globalStamp = 1;
	private int[] sortingData = new int[16];
	private int sortingDataWritten = 0;

	public SortingSlice obtainSortingSlice() {
		SortingSlice result = sortingSlicePool.isEmpty() ? new SortingSlice() : sortingSlicePool.pop();
		result.head = result.length = 0;
		return result;
	}

	public void reset() {
		sortingDataWritten = 0;
	}

	private long nextStamp() {
		if (++globalStamp == Long.MAX_VALUE) {
			Arrays.fill(distanceStamp, 0);
			globalStamp = 1;
		}
		return globalStamp;
	}

	boolean sortModelFaces(
		SortingSlice sortedFaces,
		SortingSlice unsortedFaces,
		Projection proj,
		Model model,
		int orientation,
		int x,
		int y,
		int z
	) {
		model.calculateBoundsCylinder();
		final int diameter = model.getDiameter();
		if (diameter >= MAX_DIAMETER)
			return false;

		final int radius = model.getRadius();
		final int vertexCount = model.getVerticesCount();
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

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

			vertexX += x;
			vertexY += y;
			vertexZ += z;

			p = proj.project(vertexX, vertexY, vertexZ);
			if (p[2] < 50)
				return false;

			modelProjectedX[v] = p[0] / p[2];
			modelProjectedY[v] = p[1] / p[2];
			distances[v] = (int) p[2] - zero;
		}

		final long stamp = nextStamp();

		final int faceCount = model.getFaceCount();
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] faceColors3 = model.getFaceColors3();
		final byte[] faceRenderPriorities = model.getFaceRenderPriorities();

		unsortedFaces.start();
		int minFz = diameter, maxFz = 0;
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
				unsortedFaces.putFace(i);
				continue;
			}

			final int fz = radius + (distances[v1] + distances[v2] + distances[v3]) / 3;
			final int base = fz * FACES_PER_DISTANCE;

			if (distanceStamp[fz] != stamp) {
				distanceStamp[fz] = stamp;
				distanceFaceCount[fz] = 1;
				distanceToFaces[base] = i;

				minFz = Math.min(minFz, fz);
				maxFz = Math.max(maxFz, fz);
			} else {
				distanceToFaces[base + distanceFaceCount[fz]++] = i;
			}
		}
		unsortedFaces.end();

		sortedFaces.start();
		if (faceRenderPriorities == null) {
			for (int i = maxFz; i >= minFz; --i) {
				if (distanceStamp[i] != stamp)
					continue;

				int cnt = distanceFaceCount[i];
				int base = i * FACES_PER_DISTANCE;
				sortedFaces.putFaces(distanceToFaces, base, cnt);
			}
		} else {
			Arrays.fill(numOfPriority, 0);
			Arrays.fill(lt10, 0);

			for (int i = maxFz; i >= minFz; --i) {
				if (distanceStamp[i] != stamp)
					continue;

				int cnt = distanceFaceCount[i];
				int base = i * FACES_PER_DISTANCE;

				for (int f = 0; f < cnt; ++f) {
					int face = distanceToFaces[base + f];
					byte pri = faceRenderPriorities[face];
					int idx = numOfPriority[pri]++;

					orderedFaces[pri * MAX_FACES_PER_PRIORITY + idx] = face;

					if (pri < 10)
						lt10[pri] += i;
					else if (pri == 10)
						eq10[idx] = i;
					else
						eq11[idx] = i;
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
			int dynPri = 10;
			int numDynFaces = numOfPriority[10];
			int dynBase = 10 * MAX_FACES_PER_PRIORITY;
			int[] dynDist = eq10;

			if (drawnFaces == numDynFaces) {
				dynPri = 11;
				numDynFaces = numOfPriority[11];
				dynBase = 11 * MAX_FACES_PER_PRIORITY;
				dynDist = eq11;
			}

			int currFaceDistance =
				drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;

			for (int pri = 0; pri < 10; ++pri) {
				while (pri == 0 && currFaceDistance > avg12) {
					sortedFaces.putFace(orderedFaces[dynBase + drawnFaces++]);

					if (drawnFaces == numDynFaces && dynPri == 10) {
						dynPri = 11;
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynBase = 11 * MAX_FACES_PER_PRIORITY;
						dynDist = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;
				}

				while (pri == 3 && currFaceDistance > avg34) {
					sortedFaces.putFace(orderedFaces[dynBase + drawnFaces++]);

					if (drawnFaces == numDynFaces && dynPri == 10) {
						dynPri = 11;
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynBase = 11 * MAX_FACES_PER_PRIORITY;
						dynDist = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;
				}

				while (pri == 5 && currFaceDistance > avg68) {
					sortedFaces.putFace(orderedFaces[dynBase + drawnFaces++]);

					if (drawnFaces == numDynFaces && dynPri == 10) {
						dynPri = 11;
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynBase = 11 * MAX_FACES_PER_PRIORITY;
						dynDist = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;
				}

				int cnt = numOfPriority[pri];
				int base = pri * MAX_FACES_PER_PRIORITY;
				sortedFaces.putFaces(orderedFaces, base, cnt);
			}

			while (currFaceDistance != -1000) {
				sortedFaces.putFace(orderedFaces[dynBase + drawnFaces++]);

				if (drawnFaces == numDynFaces && dynPri == 10) {
					dynPri = 11;
					drawnFaces = 0;
					numDynFaces = numOfPriority[11];
					dynBase = 11 * MAX_FACES_PER_PRIORITY;
					dynDist = eq11;
				}

				currFaceDistance = drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;
			}
		}
		sortedFaces.end();

		return true;
	}

	boolean sortStaticModelFaces(
		SortingSlice sortedFaces,
		Zone.AlphaModel m,
		int yawCos, int yawSin,
		int pitchCos, int pitchSin
	) {
		final int radius = m.radius;
		final int diameter = 1 + radius * 2;
		if (diameter >= MAX_DIAMETER)
			return false;

		final long stamp = nextStamp();
		int minFz = diameter, maxFz = 0;
		for (int i = 0; i < m.packedFaces.length; ++i) {
			final int packed = m.packedFaces[i];
			final int x = packed >> 21;
			final int y = (packed << 11) >> 22;
			final int z = (packed << 21) >> 21;

			int fz = ((z * yawCos - x * yawSin) >> 16);
			fz = ((y * pitchSin + fz * pitchCos) >> 16) + radius;

			final int base = fz * FACES_PER_DISTANCE;
			if (distanceStamp[fz] != stamp) {
				distanceStamp[fz] = stamp;
				distanceFaceCount[fz] = 1;
				distanceToFaces[base] = i;

				minFz = min(minFz, fz);
				maxFz = max(maxFz, fz);
			} else {
				distanceToFaces[base + distanceFaceCount[fz]++] = i;
			}
		}

		sortedFaces.start();
		final int start = m.startpos / (VERT_SIZE >> 2); // ints to verts
		final byte[] pri = m.renderPriorities;
		if (pri == null || m.modelOverride.disablePrioritySorting) {
			for (int i = maxFz; i >= minFz; --i) {
				if (distanceStamp[i] != stamp)
					continue;

				final int cnt = distanceFaceCount[i];
				final int base = i * FACES_PER_DISTANCE;

				for (int faceIdx = 0; faceIdx < cnt; ++faceIdx) {
					final int idx = distanceToFaces[base + faceIdx] * 3 + start;
					sortedFaces.putIndices(idx, idx + 1, idx + 2);
				}
			}
		} else {
			Arrays.fill(numOfPriority, 0);
			int minPr = PRIORITY_COUNT, maxPr = 0;
			for (int i = maxFz; i >= minFz; --i) {
				if (distanceStamp[i] != stamp)
					continue;

				final int cnt = distanceFaceCount[i];
				final int base = i * FACES_PER_DISTANCE;
				for (int f = 0; f < cnt; ++f) {
					final int face = distanceToFaces[base + f];
					final byte p = pri[face];

					minPr = min(minPr, p);
					maxPr = max(maxPr, p);

					orderedFaces[p * MAX_FACES_PER_PRIORITY + numOfPriority[p]++] = face;
				}
			}

			for (int pIdx = minPr; pIdx <= maxPr; ++pIdx) {
				final int cnt = numOfPriority[pIdx];
				final int base = pIdx * MAX_FACES_PER_PRIORITY;

				for (int faceIdx = 0; faceIdx < cnt; ++faceIdx) {
					final int idx = orderedFaces[base + faceIdx] * 3 + start;
					sortedFaces.putIndices(idx, idx + 1, idx + 2);
				}
			}
		}
		sortedFaces.end();

		return true;
	}

	public final class SortingSlice {
		private int head;
		private int length;

		public int length() {
			return length;
		}

		public int get(int idx) {
			return sortingData[head + idx];
		}

		public void copy(GpuIntBuffer buffer) {
			if(length <= 0)
				return;

			buffer.ensureCapacity(length);
			buffer.getBuffer().put(sortingData, head, length);
		}

		public void free() {
			head = length = 0;
			sortingSlicePool.add(this);
		}

		private void start() {
			head = sortingDataWritten;
			length = 0;
		}

		private void end() {
			length = sortingDataWritten - head;
		}

		private void ensureCapacity(int count) {
			if (sortingDataWritten + count <= sortingData.length)
				return;
			sortingData = Arrays.copyOf(sortingData, (int) HDUtils.ceilPow2(sortingDataWritten + count));
		}

		private void putFace(int f) {
			ensureCapacity(1);
			sortingData[sortingDataWritten++] = f;
		}

		private void putFaces(int[] faces, int offset, int count) {
			ensureCapacity(count);
			System.arraycopy(faces, offset, sortingData, sortingDataWritten, count);
			sortingDataWritten += count;
		}

		private void putIndices(int a, int b, int c) {
			ensureCapacity(3);
			sortingData[sortingDataWritten++] = a;
			sortingData[sortingDataWritten++] = b;
			sortingData[sortingDataWritten++] = c;
		}
	}
}
