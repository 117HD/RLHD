/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * Copyright (c) 2022, Hooder <ahooder@protonmail.com>
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

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.coords.*;
import rs117.hd.data.environments.Area;

import static net.runelite.api.Constants.*;

public class AABB {
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;

	public AABB(int regionId) {
		minX = (regionId >>> 8) << 6;
		minY = (regionId & 0xFF) << 6;
		maxX = minX + REGION_SIZE - 1;
		maxY = minY + REGION_SIZE - 1;
		minZ = Integer.MIN_VALUE;
		maxZ = Integer.MAX_VALUE;
	}

	public AABB(int x, int y) {
		minX = maxX = x;
		minY = maxY = y;
		minZ = Integer.MIN_VALUE;
		maxZ = Integer.MAX_VALUE;
	}

	public AABB(int x, int y, int z) {
		minX = maxX = x;
		minY = maxY = y;
		minZ = maxZ = z;
	}

	public AABB(int x1, int y1, int x2, int y2) {
		minX = Math.min(x1, x2);
		minY = Math.min(y1, y2);
		minZ = Integer.MIN_VALUE;
		maxX = Math.max(x1, x2);
		maxY = Math.max(y1, y2);
		maxZ = Integer.MAX_VALUE;
	}

	public AABB(int x1, int y1, int x2, int y2, int z1) {
		minX = Math.min(x1, x2);
		minY = Math.min(y1, y2);
		maxX = Math.max(x1, x2);
		maxY = Math.max(y1, y2);
		minZ = maxZ = z1;
	}

	public AABB(int x1, int y1, int z1, int x2, int y2, int z2) {
		minX = Math.min(x1, x2);
		minY = Math.min(y1, y2);
		minZ = Math.min(z1, z2);
		maxX = Math.max(x1, x2);
		maxY = Math.max(y1, y2);
		maxZ = Math.max(z1, z2);
	}

	public static AABB[] regions(int... regionIds) {
		return Arrays.stream(regionIds)
			.mapToObj(AABB::new)
			.toArray(AABB[]::new);
	}

	public static AABB regionBox(int fromRegionId, int toRegionId) {
		int x1 = fromRegionId >>> 8;
		int y1 = fromRegionId & 0xFF;
		int x2 = toRegionId >>> 8;
		int y2 = toRegionId & 0xFF;
		if (x1 > x2) {
			int temp = x1;
			x1 = x2;
			x2 = temp;
		}
		if (y1 > y2) {
			int temp = y1;
			y1 = y2;
			y2 = temp;
		}
		return new AABB((x1) << 6, (y1) << 6, ((x2) + 1 << 6) - 1, ((y2) + 1 << 6) - 1);
	}

	public AABB onPlane(int plane) {
		return new AABB(minX, minY, plane, maxX, maxY, plane);
	}

	public boolean hasZ() {
		return minZ != Integer.MIN_VALUE || maxZ != Integer.MAX_VALUE;
	}

	public boolean isPoint() {
		return
			minX == maxX &&
			minY == maxY &&
			(!hasZ() || minZ == maxZ);
	}

	public boolean isVolume() {
		return !isPoint();
	}

	public boolean contains(int... worldXYZ) {
		return
			minX <= worldXYZ[0] && worldXYZ[0] <= maxX &&
			minY <= worldXYZ[1] && worldXYZ[1] <= maxY &&
			(worldXYZ.length < 3 || minZ <= worldXYZ[2] && worldXYZ[2] <= maxZ);
	}

	public boolean contains(WorldPoint location) {
		return contains(location.getX(), location.getY(), location.getPlane());
	}

	public boolean contains(AABB other) {
		return
			contains(other.minX, other.minY, other.minZ) &&
			contains(other.maxX, other.maxY, other.maxZ);
	}

	public boolean intersects(int minX, int minY, int maxX, int maxY) {
		return
			minX < this.maxX && maxX > this.minX &&
			minY < this.maxY && maxY > this.minY;
	}

	public boolean intersects(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		return
			minX < this.maxX && maxX > this.minX &&
			minY < this.maxY && maxY > this.minY &&
			minZ < this.maxZ && maxZ > this.minZ;
	}

	public boolean intersects(AABB other) {
		return intersects(
			other.minX, other.maxX,
			other.minY, other.maxY,
			other.minZ, other.maxZ
		);
	}

	@Override
	public String toString() {
		if (hasZ())
			return String.format("AABB{min=(%d,%d,%d), max=(%d,%d,%d)}", minX, minY, minZ, maxX, maxY, maxZ);
		return String.format("AABB{min=(%d,%d), max=(%d,%d)}", minX, minY, maxX, maxY);
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof AABB))
			return false;

		AABB other = (AABB) obj;
		return
			other.minX == minX && other.maxX == maxX &&
			other.minY == minY && other.maxY == maxY &&
			other.minZ == minZ && other.maxZ == maxZ;
	}

	public static class JsonAdapter extends TypeAdapter<AABB[]> {
		private final Area.JsonAdapter areaAdapter = new Area.JsonAdapter();

		@Override
		public AABB[] read(JsonReader in) throws IOException {
			in.beginArray();
			ArrayList<AABB> list = new ArrayList<>();
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
				if (in.peek() == JsonToken.NULL) {
					in.skipValue();
					continue;
				}

				// Parse numbers as region IDs
				if (in.peek() == JsonToken.NUMBER) {
					list.add(new AABB(in.nextInt()));
					continue;
				}

				if (in.peek() == JsonToken.STRING) {
					var area = areaAdapter.read(in);
					Collections.addAll(list, area.aabbs);
					continue;
				}

				in.beginArray();
				int[] ints = new int[6];
				int i = 0;
				while (in.hasNext()) {
					switch (in.peek()) {
						case NUMBER:
							if (i >= ints.length)
								throw new IOException(
									"Too many numbers in AABB entry (> " + ints.length + ") at " + GsonUtils.location(in));
							ints[i++] = in.nextInt();
						case END_ARRAY:
							break;
						case NULL:
							in.skipValue();
							continue;
						default:
							throw new IOException("Malformed AABB entry. Unexpected token: " + in.peek() + " at " + GsonUtils.location(in));
					}
				}
				in.endArray();

				switch (i) {
					case 1:
						list.add(new AABB(ints[0]));
						break;
					case 2:
						list.add(new AABB(ints[0], ints[1]));
						break;
					case 3:
						list.add(new AABB(ints[0], ints[1], ints[2]));
						break;
					case 4:
						list.add(new AABB(ints[0], ints[1], ints[2], ints[3]));
						break;
					case 5:
						list.add(new AABB(ints[0], ints[2], ints[1], ints[3], ints[4]));
						break;
					case 6:
						list.add(new AABB(ints[0], ints[1], ints[2], ints[3], ints[4], ints[5]));
						break;
				}
			}
			in.endArray();
			return list.toArray(new AABB[0]);
		}

		@Override
		public void write(JsonWriter out, AABB[] aabbs) throws IOException {
			if (aabbs == null || aabbs.length == 0) {
				out.nullValue();
				return;
			}

			out.beginArray();
			for (AABB aabb : aabbs) {
				out.beginArray();
				out.value(aabb.minX);
				out.value(aabb.minY);
				if (aabb.hasZ())
					out.value(aabb.minZ);
				if (aabb.isVolume()) {
					out.value(aabb.maxX);
					out.value(aabb.maxY);
					if (aabb.hasZ())
						out.value(aabb.maxZ);
				}
				out.endArray();
			}
			out.endArray();
		}
	}
}
