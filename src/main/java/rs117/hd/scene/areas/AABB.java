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
package rs117.hd.scene.areas;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.*;
import rs117.hd.scene.AreaManager;
import rs117.hd.utils.GsonUtils;

import static net.runelite.api.Constants.*;
import static rs117.hd.utils.MathUtils.*;

public class AABB {
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;

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
		minX = min(x1, x2);
		minY = min(y1, y2);
		minZ = Integer.MIN_VALUE;
		maxX = max(x1, x2);
		maxY = max(y1, y2);
		maxZ = Integer.MAX_VALUE;
	}

	public AABB(int x1, int y1, int x2, int y2, int z1) {
		minX = min(x1, x2);
		minY = min(y1, y2);
		maxX = max(x1, x2);
		maxY = max(y1, y2);
		minZ = maxZ = z1;
	}

	public AABB(int x1, int y1, int z1, int x2, int y2, int z2) {
		minX = min(x1, x2);
		minY = min(y1, y2);
		minZ = min(z1, z2);
		maxX = max(x1, x2);
		maxY = max(y1, y2);
		maxZ = max(z1, z2);
	}

	public AABB(int[] point) {
		this(point[0], point[1], point[2]);
	}

	public AABB(int[] from, int[] to) {
		this(from[0], from[1], from[2], to[0], to[1], to[2]);
	}

	public static AABB fromRegionId(int regionId) {
		int minX = (regionId >>> 8) << 6;
		int minY = (regionId & 0xFF) << 6;
		int maxX = minX + REGION_SIZE - 1;
		int maxY = minY + REGION_SIZE - 1;
		return new AABB(minX, minY, maxX, maxY);
	}

	public AABB onPlane(int plane) {
		return new AABB(minX, minY, plane, maxX, maxY, plane);
	}

	public AABB expandTo(int[] point) {
		return new AABB(
			min(minX, point[0]),
			min(minY, point[1]),
			min(minZ, point[2]),
			max(maxX, point[0]),
			max(maxY, point[1]),
			max(maxZ, point[2])
		);
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

	public boolean contains(int... worldPos) {
		assert worldPos.length >= 2 : "Expected X, Y & possibly a plane, got: " + Arrays.toString(worldPos);
		return
			minX <= worldPos[0] && worldPos[0] <= maxX &&
			minY <= worldPos[1] && worldPos[1] <= maxY &&
			(worldPos.length < 3 || minZ <= worldPos[2] && worldPos[2] <= maxZ);
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
			minX <= this.maxX && maxX >= this.minX &&
			minY <= this.maxY && maxY >= this.minY;
	}

	public boolean intersects(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return
			minX <= this.maxX && maxX >= this.minX &&
			minY <= this.maxY && maxY >= this.minY &&
			minZ <= this.maxZ && maxZ >= this.minZ;
	}

	public boolean intersects(AABB other) {
		return intersects(
			other.minX,
			other.minY,
			other.minZ,
			other.maxX,
			other.maxY,
			other.maxZ
		);
	}

	public boolean intersects(AABB... aabbs) {
		for (var aabb : aabbs)
			if (intersects(aabb))
				return true;
		return false;
	}

	public float[] getCenter() {
		return new float[] {
			(minX + maxX) / 2.f,
			(minY + maxY) / 2.f,
			(minZ + maxZ) / 2.f
		};
	}

	@Override
	public String toString() {
		if (hasZ())
			return String.format("AABB{min=(%d,%d,%d), max=(%d,%d,%d)}", minX, minY, minZ, maxX, maxY, maxZ);
		return String.format("AABB{min=(%d,%d), max=(%d,%d)}", minX, minY, maxX, maxY);
	}

	public String toArgs() {
		if (hasZ()) {
			if (isPoint())
				return String.format("[ %d, %d, %d ]", minX, minY, minZ);
			if (minZ == maxZ)
				return String.format("[ %d, %d, %d, %d, %d ]", minX, minY, maxX, maxY, minZ);
			return String.format("[ %d, %d, %d, %d, %d, %d ]", minX, minY, minZ, maxX, maxY, maxZ);
		}
		if (isPoint())
			return String.format("[ %d, %d ]", minX, minY);
		return String.format("[ %d, %d, %d, %d ]", minX, minY, maxX, maxY);
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

	@Slf4j
	public static class ArrayAdapter extends TypeAdapter<AABB[]> {
		@Override
		public AABB[] read(JsonReader in) throws IOException {
			in.beginArray();
			ArrayList<AABB> list = new ArrayList<>();
			outer:
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
				if (in.peek() == JsonToken.NULL) {
					in.skipValue();
					continue;
				}

				if (in.peek() == JsonToken.NUMBER) {
					log.warn("AABBs are specified by two or more numbers. Did you forget to add an array at {}?", GsonUtils.location(in));
					continue;
				}

				if (in.peek() == JsonToken.STRING) {
					String name = in.nextString();
					for (var area : AreaManager.AREAS) {
						if (name.equals(area.name)) {
							Collections.addAll(list, area.aabbs);
							continue outer;
						}
					}

					log.warn("No area exists with the name '{}' at {}", name, GsonUtils.location(in), new Throwable());
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
						log.warn("AABBs are specified by two or more numbers, only one was provided at {}", GsonUtils.location(in));
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
						list.add(new AABB(ints[0], ints[1], ints[2], ints[3], ints[4]));
						break;
					case 6:
						list.add(new AABB(ints[0], ints[1], ints[2], ints[3], ints[4], ints[5]));
						break;
				}
			}
			in.endArray();
			return list.toArray(AABB[]::new);
		}

		@Override
		public void write(JsonWriter out, AABB[] aabbs) throws IOException {
			if (aabbs == null || aabbs.length == 0) {
				out.nullValue();
				return;
			}

			out.beginArray();
			for (AABB aabb : aabbs) {
				// Compact JSON array
				StringBuilder sb = new StringBuilder();
				sb.append("[ ").append(aabb.minX);
				sb.append(", ").append(aabb.minY);
				if (aabb.hasZ())
					sb.append(", ").append(aabb.minZ);
				if (aabb.isVolume()) {
					sb.append(", ").append(aabb.maxX);
					sb.append(", ").append(aabb.maxY);
					if (aabb.hasZ())
						sb.append(", ").append(aabb.maxZ);
				}
				sb.append(" ]");
				out.jsonValue(sb.toString());
			}
			out.endArray();
		}
	}
}
