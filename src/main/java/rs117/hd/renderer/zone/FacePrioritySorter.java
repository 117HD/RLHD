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
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static rs117.hd.renderer.zone.Zone.VERT_SIZE;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class FacePrioritySorter implements AutoCloseable {
	public static ConcurrentPool<FacePrioritySorter> POOL;

	public static final int MAX_FACE_COUNT = 8192;
	private static final int MAX_DIAMETER = 6000;
	private static final int MAX_FACES_PER_PRIORITY = 4000;
	private static final int PRIORITY_COUNT = 12;

	public final int[] faceDistances = new int[MAX_FACE_COUNT];

	private final int[] orderedFaces = new int[PRIORITY_COUNT * MAX_FACES_PER_PRIORITY];
	private final int[] numOfPriority = new int[PRIORITY_COUNT];
	private final int[] eq10 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] eq11 = new int[MAX_FACES_PER_PRIORITY];
	private final int[] lt10 = new int[PRIORITY_COUNT];

	private final int[] zsortHead = new int[MAX_DIAMETER];
	private final int[] zsortTail = new int[MAX_DIAMETER];
	private final int[] zsortNext = new int[MAX_FACE_COUNT];

	void sortModelFaces(PrimitiveIntArray visibleFaces, Model model) {
		final int diameter = model.getDiameter();
		if (diameter <= 0 || diameter >= MAX_DIAMETER)
			return;

		int minFz = diameter, maxFz = 0;
		Arrays.fill(zsortHead, 0, diameter + 1, -1);
		Arrays.fill(zsortTail, 0, diameter + 1, -1);

		// Build the z-sorted linked list of faces
		for (int i = 0; i < visibleFaces.length; ++i) {
			final int faceIdx = visibleFaces.faces[i];
			final int distance = clamp(faceDistances[faceIdx], 0, diameter);

			final int tailFaceIdx = zsortTail[distance];
			if (tailFaceIdx == -1) {
				zsortHead[distance] = zsortTail[distance] = faceIdx;
				zsortNext[faceIdx] = -1;

				minFz = min(minFz, distance);
				maxFz = max(maxFz, distance);
			} else {
				zsortNext[tailFaceIdx] = faceIdx;
				zsortNext[faceIdx] = -1;
				zsortTail[distance] = faceIdx;
			}
		}
		visibleFaces.reset();

		final byte[] priorities = model.getFaceRenderPriorities();
		if (priorities == null) {
			for (int i = maxFz; i >= minFz; --i) {
				for (int f = zsortHead[i]; f != -1; f = zsortNext[f])
					visibleFaces.putFace(f);
			}
			return;
		}

		Arrays.fill(numOfPriority, 0);
		Arrays.fill(lt10, 0);

		for (int i = maxFz; i >= minFz; --i) {
			for (int f = zsortHead[i]; f != -1; f = zsortNext[f]) {
				final int pri = priorities[f];
				final int idx = numOfPriority[pri]++;

				orderedFaces[pri * MAX_FACES_PER_PRIORITY + idx] = f;

				if (pri < 10)
					lt10[pri] += i;
				else if (pri == 10)
					eq10[idx] = i;
				else
					eq11[idx] = i;
			}
		}

		int avg12 = (numOfPriority[1] + numOfPriority[2]) > 0
			? (lt10[1] + lt10[2]) / (numOfPriority[1] + numOfPriority[2]) : 0;

		int avg34 = (numOfPriority[3] + numOfPriority[4]) > 0
			? (lt10[3] + lt10[4]) / (numOfPriority[3] + numOfPriority[4]) : 0;

		int avg68 = (numOfPriority[6] + numOfPriority[8]) > 0
			? (lt10[6] + lt10[8]) / (numOfPriority[6] + numOfPriority[8]) : 0;

		int drawnFaces = 0;
		int numDynFaces = numOfPriority[10];
		int dynBase = 10 * MAX_FACES_PER_PRIORITY;
		int[] dynDist = eq10;

		if (numDynFaces == 0) {
			numDynFaces = numOfPriority[11];
			dynBase = 11 * MAX_FACES_PER_PRIORITY;
			dynDist = eq11;
		}

		int currFaceDistance =
			drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;

		for (int pri = 0; pri < 10; ++pri) {
			while ((pri == 0 && currFaceDistance > avg12) ||
				   (pri == 3 && currFaceDistance > avg34) ||
				   (pri == 5 && currFaceDistance > avg68)) {
				visibleFaces.putFace(orderedFaces[dynBase + drawnFaces++]);

				if (drawnFaces == numDynFaces && dynBase == 10 * MAX_FACES_PER_PRIORITY) {
					drawnFaces = 0;
					numDynFaces = numOfPriority[11];
					dynBase = 11 * MAX_FACES_PER_PRIORITY;
					dynDist = eq11;
				}

				currFaceDistance =
					drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;
			}

			visibleFaces.putFaces(
				orderedFaces,
				pri * MAX_FACES_PER_PRIORITY,
				numOfPriority[pri]
			);
		}

		while (currFaceDistance != -1000) {
			visibleFaces.putFace(orderedFaces[dynBase + drawnFaces++]);

			if (drawnFaces == numDynFaces && dynBase == 10 * MAX_FACES_PER_PRIORITY) {
				drawnFaces = 0;
				numDynFaces = numOfPriority[11];
				dynBase = 11 * MAX_FACES_PER_PRIORITY;
				dynDist = eq11;
			}

			currFaceDistance =
				drawnFaces < numDynFaces ? dynDist[drawnFaces] : -1000;
		}
	}

	void sortStaticModelFacesByDistance(
		Zone.AlphaModel m,
		int yawCos, int yawSin,
		int pitchCos, int pitchSin
	) {
		final int radius = m.radius;
		final int diameter = 1 + radius * 2;
		if (diameter >= MAX_DIAMETER)
			return;

		final int faceCount = m.packedFaces.length;

		Arrays.fill(zsortHead, 0, diameter, -1);
		Arrays.fill(zsortTail, 0, diameter, -1);

		int minFz = diameter, maxFz = 0;
		for (int i = 0; i < faceCount; ++i) {
			final int packed = m.packedFaces[i];
			final int x = packed >> 21;
			final int y = (packed << 11) >> 22;
			final int z = (packed << 21) >> 21;

			int fz = ((z * yawCos - x * yawSin) >> 16);
			fz = ((y * pitchSin + fz * pitchCos) >> 16) + radius;

			if (zsortTail[fz] == -1) {
				zsortHead[fz] = zsortTail[fz] = i;
				zsortNext[i] = -1;

				minFz = min(minFz, fz);
				maxFz = max(maxFz, fz);
			} else {
				int lastFace = zsortTail[fz];
				zsortNext[lastFace] = i;
				zsortNext[i] = -1;
				zsortTail[fz] = i;
			}
		}

		final int start = m.startpos / (VERT_SIZE >> 2);
		for (int i = maxFz; i >= minFz; --i) {
			for (int f = zsortHead[i]; f != -1; f = zsortNext[f]) {
				if (m.sortedFacesLen >= m.sortedFaces.length)
					break;

				if (f >= faceCount)
					continue;

				final int sortedOffset = m.sortedFacesLen;
				final int faceStart = f * 3 + start;
				m.sortedFaces[sortedOffset] = faceStart;
				m.sortedFaces[sortedOffset + 1] = faceStart + 1;
				m.sortedFaces[sortedOffset + 2] = faceStart + 2;
				m.sortedFacesLen += 3;
			}
		}
	}

	@Override
	public void close() {
		POOL.recycle(this);
	}
}
