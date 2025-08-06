package rs117.hd.scene.areas;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.GsonUtils;

import static net.runelite.api.Constants.*;

@RequiredArgsConstructor
public class RegionBox {
	public final int from, to;
	public final int fromPlane, toPlane;

	public RegionBox(int regionId) {
		this(regionId, regionId);
	}

	public RegionBox(int from, int to) {
		this(from, to, 0, MAX_Z - 1);
	}

	public RegionBox(int from, int to, int plane) {
		this(from, to, plane, plane);
	}

	public AABB toAabb() {
		int x1 = from >>> 8;
		int y1 = from & 0xFF;
		int x2 = to >>> 8;
		int y2 = to & 0xFF;
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
		return new AABB(
			(x1) << 6,
			(y1) << 6,
			fromPlane,
			((x2) + 1 << 6) - 1,
			((y2) + 1 << 6) - 1,
			toPlane
		);
	}

	@Slf4j
	public static class Adapter extends TypeAdapter<RegionBox[]> {
		@Override
		public RegionBox[] read(JsonReader in) throws IOException {
			in.beginArray();
			ArrayList<RegionBox> list = new ArrayList<>();
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
				if (in.peek() == JsonToken.NULL) {
					in.skipValue();
					continue;
				}

				in.beginArray();
				int[] ints = new int[4];
				int i = 0;
				while (in.hasNext()) {
					switch (in.peek()) {
						case NUMBER:
							if (i >= ints.length)
								throw new IOException(
									"Too many numbers in RegionBox entry (> " + ints.length + ") at " + GsonUtils.location(in));
							ints[i++] = in.nextInt();
						case END_ARRAY:
							break;
						case NULL:
							in.skipValue();
							continue;
						default:
							throw new IOException(
								"Malformed RegionBox entry. Unexpected token: " + in.peek() + " at " + GsonUtils.location(in));
					}
				}
				in.endArray();

				switch (i) {
					case 1:
						list.add(new RegionBox(ints[0]));
						break;
					case 2:
						list.add(new RegionBox(ints[0], ints[1]));
						break;
					case 3:
						list.add(new RegionBox(ints[0], ints[1], ints[2]));
						break;
					case 4:
						list.add(new RegionBox(ints[0], ints[1], ints[2], ints[3]));
						break;
				}
			}
			in.endArray();
			return list.toArray(new RegionBox[0]);
		}

		@Override
		public void write(JsonWriter out, RegionBox[] aabbs) throws IOException {
			if (aabbs == null || aabbs.length == 0) {
				out.nullValue();
				return;
			}

			out.beginArray();
			for (RegionBox box : aabbs) {
				// Compact JSON array
				if (box.fromPlane == 0 && box.toPlane == MAX_Z - 1) {
					if (box.from == box.to) {
						out.jsonValue(String.format("[ %d ]", box.from));
					} else {
						out.jsonValue(String.format("[ %d, %d ]", box.from, box.to));
					}
				} else if (box.fromPlane == box.toPlane) {
					out.jsonValue(String.format("[ %d, %d, %d ]", box.from, box.to, box.fromPlane));
				} else {
					out.jsonValue(String.format("[ %d, %d, %d, %d ]", box.from, box.to, box.fromPlane, box.toPlane));
				}
			}
			out.endArray();
		}
	}
}
