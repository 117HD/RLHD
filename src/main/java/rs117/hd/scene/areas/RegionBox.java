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

@RequiredArgsConstructor
public class RegionBox {
	public final int from, to;

	public AABB onPlane(int plane) {
		AABB aabb = toAabb();
		return aabb.onPlane(plane);
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
		return new AABB((x1) << 6, (y1) << 6, ((x2) + 1 << 6) - 1, ((y2) + 1 << 6) - 1);
	}

	@Slf4j
	public static class JsonAdapter extends TypeAdapter<RegionBox[]> {
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
				int[] ints = new int[2];
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

				list.add(new RegionBox(ints[0], ints[1]));
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
				out.jsonValue("[ " + box.from + ", " + box.to + " ]");
			}
			out.endArray();
		}
	}
}
