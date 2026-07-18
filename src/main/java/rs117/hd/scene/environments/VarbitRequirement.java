package rs117.hd.scene.environments;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import rs117.hd.scene.GamevalManager;
import rs117.hd.utils.GsonUtils;

public class VarbitRequirement {
	private static final GamevalManager.VarbitAdapter VARBITS = new GamevalManager.VarbitAdapter();

	public int id = -1;
	public int state = 1;

	public VarbitRequirement() {}

	public VarbitRequirement(int id) {
		this.id = id;
	}

	public VarbitRequirement(int id, int state) {
		this.id = id;
		this.state = state;
	}

	public boolean isSatisfied(int currentValue) {
		if (state == -1)
			return currentValue != 0;
		return currentValue == state;
	}

	public static class Adapter extends TypeAdapter<VarbitRequirement[]> {
		@Override
		public VarbitRequirement[] read(JsonReader in) throws IOException {
			var token = in.peek();
			if (token == JsonToken.NULL) {
				in.skipValue();
				return new VarbitRequirement[0];
			}
			if (token == JsonToken.NUMBER || token == JsonToken.STRING)
				return new VarbitRequirement[] { new VarbitRequirement(readVarbitId(in)) };
			if (token == JsonToken.BEGIN_OBJECT)
				return new VarbitRequirement[] { readObject(in) };
			if (token != JsonToken.BEGIN_ARRAY)
				throw new IOException("Unexpected type for requiredVarbit: " + token + " at " + GsonUtils.location(in));

			var list = new ArrayList<VarbitRequirement>();
			in.beginArray();
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
				if (in.peek() == JsonToken.NULL) {
					in.skipValue();
					continue;
				}
				if (in.peek() == JsonToken.NUMBER || in.peek() == JsonToken.STRING) {
					list.add(new VarbitRequirement(readVarbitId(in)));
					continue;
				}
				if (in.peek() == JsonToken.BEGIN_OBJECT) {
					list.add(readObject(in));
					continue;
				}
				throw new IOException("Unexpected type in requiredVarbit list: " + in.peek() + " at " + GsonUtils.location(in));
			}
			in.endArray();
			return list.toArray(VarbitRequirement[]::new);
		}

		private static int readVarbitId(JsonReader in) throws IOException {
			var el = JsonParser.parseReader(in);
			var arr = new JsonArray();
			arr.add(el);
			HashSet<Integer> ids = VARBITS.fromJsonTree(arr);
			return ids == null || ids.isEmpty() ? -1 : ids.iterator().next();
		}

		private static VarbitRequirement readObject(JsonReader in) throws IOException {
			var req = new VarbitRequirement();
			in.beginObject();
			while (in.hasNext()) {
				switch (in.nextName()) {
					case "id":
						req.id = readVarbitId(in);
						break;
					case "state":
						req.state = in.nextInt();
						break;
					default:
						in.skipValue();
						break;
				}
			}
			in.endObject();
			if (req.id < 0)
				throw new IOException("requiredVarbit object missing id at " + GsonUtils.location(in));
			return req;
		}

		@Override
		public void write(JsonWriter out, VarbitRequirement[] value) throws IOException {
			if (value == null || value.length == 0) {
				out.nullValue();
				return;
			}
			out.beginArray();
			for (var req : value) {
				if (req.state == 1) {
					writeVarbitId(out, req.id);
				} else {
					out.beginObject();
					out.name("id");
					writeVarbitId(out, req.id);
					out.name("state").value(req.state);
					out.endObject();
				}
			}
			out.endArray();
		}

		private static void writeVarbitId(JsonWriter out, int id) throws IOException {
			var ids = new HashSet<Integer>();
			ids.add(id);
			var el = VARBITS.toJsonTree(ids).getAsJsonArray().get(0).getAsJsonPrimitive();
			if (el.isString())
				out.value(el.getAsString());
			else
				out.value(el.getAsInt());
		}
	}
}
