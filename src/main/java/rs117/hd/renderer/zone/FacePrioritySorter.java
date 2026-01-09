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

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;

import static rs117.hd.renderer.zone.WorldViewContext.ALPHA_ZSORT_SQ;
import static rs117.hd.renderer.zone.Zone.VERT_SIZE;
import static rs117.hd.utils.HDUtils.ceilPow2;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
final class FacePrioritySorter {
	private static final int MAX_VERTEX_COUNT = 6500;
	private static final int MAX_DIAMETER = 6000;
	private static final int ZSORT_GROUP_SIZE = 1024;
	private static final int MAX_FACES_PER_PRIORITY = 4000;
	private static final int PRIORITY_COUNT = 12;

	private static final int FACES_PER_DISTANCE = ZSORT_GROUP_SIZE;

	private static final int[] BASE_DISTANCE_LUT = new int[MAX_DIAMETER];
	private static final int[] BASE_PRIORITY_LUT = new int[PRIORITY_COUNT];

	static {
		for(int i = 0; i < MAX_DIAMETER; i++)
			BASE_DISTANCE_LUT[i] = i * FACES_PER_DISTANCE;

		for(int i = 0; i < PRIORITY_COUNT; i++)
			BASE_PRIORITY_LUT[i] = i * MAX_FACES_PER_PRIORITY;
	}

	public final float[] modelProjected = new float[MAX_VERTEX_COUNT * 3];

	private final byte[] distanceFaceCount = new byte[MAX_DIAMETER];
	private final int[] distanceToFaces = new int[MAX_DIAMETER * FACES_PER_DISTANCE];
	private final int[] numOfPriority = new int[PRIORITY_COUNT];
	private final int[] orderedFaces = new int[PRIORITY_COUNT * MAX_FACES_PER_PRIORITY];
	private final int[] eq10 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] eq11 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] lt10 = new int[PRIORITY_COUNT];

	private final long[] distanceStamp = new long[MAX_DIAMETER];
	private long globalStamp = 1;

	private long nextStamp() {
		if (++globalStamp == Long.MAX_VALUE) {
			Arrays.fill(distanceStamp, 0);
			globalStamp = 1;
		}
		return globalStamp;
	}

	void sortModelFaces(
		SortedFaces sortedFaces,
		SortedFaces unsortedFaces,
		Model model
	) {
		final int diameter = model.getDiameter();
		if (diameter >= MAX_DIAMETER)
			return;

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int radius = model.getRadius();
		final int faceCount = model.getFaceCount();
		final int[] faceColors3 = model.getFaceColors3();

		final long stamp = nextStamp();

		unsortedFaces.ensureCapacity(faceCount);
		int minFz = diameter, maxFz = 0;
		for (int i = 0; i < faceCount; ++i) {
			if (faceColors3[i] == -2)
				continue;

			int offset = indices1[i] * 3;
			final float aX = modelProjected[offset];
			final float aY = modelProjected[offset + 1];
			final float aD = modelProjected[offset + 2];

			offset = indices2[i] * 3;
			final float bX = modelProjected[offset];
			final float bY = modelProjected[offset + 1];
			final float bD = modelProjected[offset + 2];

			offset = indices3[i] * 3;
			final float cX = modelProjected[offset];
			final float cY = modelProjected[offset + 1];
			final float cD = modelProjected[offset + 2];

			// Back-face culling
			if ((aX - bX) * (cY - bY) - (cX - bX) * (aY - bY) <= 0) {
				unsortedFaces.putFace(i);
				continue;
			}

			final int fz = radius + (int)((aD + bD + cD) / 3.0f);
			final int base = BASE_DISTANCE_LUT[fz];

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

		sortedFaces.ensureCapacity(faceCount - unsortedFaces.length);
		final byte[] faceRenderPriorities = model.getFaceRenderPriorities();
		if (faceRenderPriorities == null) {
			for (int i = maxFz; i >= minFz; --i) {
				if (distanceStamp[i] != stamp)
					continue;

				int cnt = distanceFaceCount[i];
				int base = BASE_DISTANCE_LUT[i];
				sortedFaces.putFaces(distanceToFaces, base, cnt);
			}
		} else {
			for(int i = 0; i < PRIORITY_COUNT; i++)
				numOfPriority[i] = lt10[i] = 0;

			for (int i = maxFz; i >= minFz; --i) {
				if (distanceStamp[i] != stamp)
					continue;

				final int cnt = distanceFaceCount[i];
				final int base = BASE_DISTANCE_LUT[i];

				for (int f = 0; f < cnt; ++f) {
					final int face = distanceToFaces[base + f];
					final int pri = faceRenderPriorities[face];
					final int idx = numOfPriority[pri]++;

					orderedFaces[BASE_PRIORITY_LUT[pri] + idx] = face;

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

				sortedFaces.putFaces(orderedFaces, BASE_PRIORITY_LUT[pri], numOfPriority[pri]);
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
	}

	void sortStaticModelFacesWithPriority(
		Zone.AlphaModel m,
		int yawCos, int yawSin,
		int pitchCos, int pitchSin
	) {
		final int radius = m.radius;
		final int diameter = 1 + radius * 2;
		if (diameter >= MAX_DIAMETER)
			return;

		final long stamp = nextStamp();
		final int faceCount = m.packedFaces.length;
		int minFz = diameter, maxFz = 0;
		for (int i = 0; i < faceCount; ++i) {
			final int packed = m.packedFaces[i];
			final int x = packed >> 21;
			final int y = (packed << 11) >> 22;
			final int z = (packed << 21) >> 21;

			int fz = ((z * yawCos - x * yawSin) >> 16);
			fz = ((y * pitchSin + fz * pitchCos) >> 16) + radius;

			final int base = BASE_DISTANCE_LUT[fz];
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

		final int start = m.startpos / (VERT_SIZE >> 2); // ints to verts
		final byte[] pri = m.renderPriorities;

		Arrays.fill(numOfPriority, 0);
		int minPri = PRIORITY_COUNT, maxPri = 0;
		for (int i = maxFz; i >= minFz; --i) {
			if (distanceStamp[i] != stamp)
				continue;

			final int cnt = distanceFaceCount[i];
			final int base = BASE_DISTANCE_LUT[i];
			for (int f = 0; f < cnt; ++f) {
				final int face = distanceToFaces[base + f];
				final byte p = pri[face];
				final int offset = numOfPriority[p]++;
				if(offset == 0) {
					minPri = min(minPri, p);
					maxPri = max(maxPri, p);
					orderedFaces[BASE_PRIORITY_LUT[p]] = face;
				} else {
					orderedFaces[BASE_PRIORITY_LUT[p] + offset] = face;
				}
			}
		}

		for (int pIdx = minPri; pIdx <= maxPri; ++pIdx) {
			final int cnt = numOfPriority[pIdx];
			final int base = BASE_PRIORITY_LUT[pIdx];

			for (int faceIdx = 0; faceIdx < cnt; ++faceIdx)
				m.sortedFaces.putFaceIndices(orderedFaces[base + faceIdx] * 3 + start);
		}
	}

	void sortFarStaticModelFacesByDistance(
		Zone.AlphaModel m,
		int yawCos, int yawSin,
		int pitchCos, int pitchSin
	) {
		final int radius = m.radius;
		final int diameter = 1 + radius * 2;
		if (diameter >= MAX_DIAMETER)
			return;

		final int faceCount = m.packedFaces.length;
		final float distFrac = saturate(m.dist / (float)ALPHA_ZSORT_SQ);
		final int buckets = clamp(ceilPow2((int)((float)(distanceFaceCount.length / faceCount) * (1.0f - distFrac))), 8, diameter);
		final long stamp = nextStamp();

		int minBucket = buckets, maxBucket = 0;
		for (int i = 0; i < faceCount; ++i) {
			final int packed = m.packedFaces[i];
			final int x = packed >> 21;
			final int y = (packed << 11) >> 22;
			final int z = (packed << 21) >> 21;

			int fz = ((z * yawCos - x * yawSin) >> 16);
			fz = ((y * pitchSin + fz * pitchCos) >> 16) + radius;

			final int bucket = floor(saturate(fz / (float)diameter) * (float)buckets);
			final int base = bucket * faceCount;
			if (distanceStamp[bucket] != stamp) {
				distanceStamp[bucket] = stamp;
				distanceFaceCount[bucket] = 1;
				distanceToFaces[base] = i;

				minBucket = min(minBucket, bucket);
				maxBucket = max(maxBucket, bucket);
			} else {
				distanceToFaces[base + distanceFaceCount[bucket]++] = i;
			}
		}

		final int start = m.startpos / (VERT_SIZE >> 2); // ints to verts
		for (int b = maxBucket; b >= minBucket; --b) {
			if (distanceStamp[b] != stamp)
				continue;

			final int cnt = distanceFaceCount[b];
			final int base = b * faceCount;

			for (int faceIdx = 0; faceIdx < cnt; ++faceIdx)
				m.sortedFaces.putFaceIndices(distanceToFaces[base + faceIdx] * 3 + start);
		}
	}

	public static final class SortedFaces {
		public int[] facesIndices;
		public int length;

		private boolean fixedSize;

		public SortedFaces() {
			facesIndices = new int[16];
		}

		public SortedFaces(int capacity) {
			facesIndices = new int[capacity];
			fixedSize = true;
		}

		public SortedFaces reset() {
			length = 0;
			return this;
		}

		private SortedFaces ensureCapacity(int count) {
			if (length + count < facesIndices.length || fixedSize)
				return this;
			int newCapacity = ceilPow2(length + count);
			log.debug( "{} Resizing \t{}",
				this,
				String.format("%.2f MB -> %.2f MB", (facesIndices.length * Integer.BYTES) / 1e6, (newCapacity * Integer.BYTES) / 1e6)
			);
			facesIndices = Arrays.copyOf(facesIndices, newCapacity);
			return this;
		}

		private void putFace(int f) {
			if(length < facesIndices.length)
				facesIndices[length++] = f;
		}

		void putFaceIndices(int offset) {
			if(length >= facesIndices.length)
				return;
			facesIndices[length++] = offset;
			facesIndices[length++] = offset + 1;
			facesIndices[length++] = offset + 2;
		}

		private void putFaces(int[] indicies, int offset, int count) {
			ensureCapacity(count);
			System.arraycopy(indicies, offset, facesIndices, length, count);
			length += count;
		}
	}
}
