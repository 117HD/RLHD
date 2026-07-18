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

public class VarRequirement {
	public int id = -1;
	public int state = 1;
	public Op op = Op.EQ;

	public enum Op {
		EQ,
		NEQ,
		GT,
		GTE,
		LT,
		LTE;

		static Op fromString(String value) throws IOException {
			switch (value.trim().toLowerCase()) {
				case "==":
				case "eq":
				case "equals":
					return EQ;
				case "!=":
				case "neq":
					return NEQ;
				case ">":
				case "gt":
					return GT;
				case ">=":
				case "gte":
					return GTE;
				case "<":
				case "lt":
					return LT;
				case "<=":
				case "lte":
					return LTE;
				default:
					throw new IOException("Unknown compare op: " + value);
			}
		}

		String toJson() {
			switch (this) {
				case NEQ: return "!=";
				case GT: return ">";
				case GTE: return ">=";
				case LT: return "<";
				case LTE: return "<=";
				default: return "==";
			}
		}
	}

	public VarRequirement() {}

	public VarRequirement(int id) {
		this.id = id;
	}

	public boolean isSatisfied(int currentValue) {
		if (state == -1 && op == Op.EQ)
			return currentValue != 0;
		switch (op) {
			case NEQ: return currentValue != state;
			case GT: return currentValue > state;
			case GTE: return currentValue >= state;
			case LT: return currentValue < state;
			case LTE: return currentValue <= state;
			default: return currentValue == state;
		}
	}

	public static class VarbitAdapter extends Adapter {
		public VarbitAdapter() {
			super(new GamevalManager.VarbitAdapter());
		}
	}

	public static class VarpAdapter extends Adapter {
		public VarpAdapter() {
			super(new GamevalManager.VarpAdapter());
		}
	}

	public static class Adapter extends TypeAdapter<VarRequirement[]> {
		private final TypeAdapter<HashSet<Integer>> idAdapter;

		Adapter(TypeAdapter<HashSet<Integer>> idAdapter) {
			this.idAdapter = idAdapter;
		}

		@Override
		public VarRequirement[] read(JsonReader in) throws IOException {
			var token = in.peek();
			if (token == JsonToken.NULL) {
				in.skipValue();
				return new VarRequirement[0];
			}
			if (token == JsonToken.NUMBER || token == JsonToken.STRING)
				return new VarRequirement[] { new VarRequirement(readId(in)) };
			if (token == JsonToken.BEGIN_OBJECT)
				return new VarRequirement[] { readObject(in) };
			if (token != JsonToken.BEGIN_ARRAY)
				throw new IOException("Unexpected type for var requirement: " + token + " at " + GsonUtils.location(in));

			var list = new ArrayList<VarRequirement>();
			in.beginArray();
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
				if (in.peek() == JsonToken.NULL) {
					in.skipValue();
					continue;
				}
				if (in.peek() == JsonToken.NUMBER || in.peek() == JsonToken.STRING) {
					list.add(new VarRequirement(readId(in)));
					continue;
				}
				if (in.peek() == JsonToken.BEGIN_OBJECT) {
					list.add(readObject(in));
					continue;
				}
				throw new IOException("Unexpected type in var requirement list: " + in.peek() + " at " + GsonUtils.location(in));
			}
			in.endArray();
			return list.toArray(VarRequirement[]::new);
		}

		private int readId(JsonReader in) {
			var el = new JsonParser().parse(in);
			var arr = new JsonArray();
			arr.add(el);
			HashSet<Integer> ids = idAdapter.fromJsonTree(arr);
			return ids == null || ids.isEmpty() ? -1 : ids.iterator().next();
		}

		private VarRequirement readObject(JsonReader in) throws IOException {
			var req = new VarRequirement();
			in.beginObject();
			while (in.hasNext()) {
				switch (in.nextName()) {
					case "id":
						req.id = readId(in);
						break;
					case "state":
						req.state = in.nextInt();
						break;
					case "op":
					case "compare":
						req.op = Op.fromString(in.nextString());
						break;
					default:
						in.skipValue();
						break;
				}
			}
			in.endObject();
			if (req.id < 0)
				throw new IOException("var requirement object missing id at " + GsonUtils.location(in));
			return req;
		}

		@Override
		public void write(JsonWriter out, VarRequirement[] value) throws IOException {
			if (value == null || value.length == 0) {
				out.nullValue();
				return;
			}
			out.beginArray();
			for (var req : value) {
				if (req.state == 1 && req.op == Op.EQ) {
					writeId(out, req.id);
				} else {
					out.beginObject();
					out.name("id");
					writeId(out, req.id);
					if (req.state != 1)
						out.name("state").value(req.state);
					if (req.op != Op.EQ)
						out.name("op").value(req.op.toJson());
					out.endObject();
				}
			}
			out.endArray();
		}

		private void writeId(JsonWriter out, int id) throws IOException {
			var ids = new HashSet<Integer>();
			ids.add(id);
			var el = idAdapter.toJsonTree(ids).getAsJsonArray().get(0).getAsJsonPrimitive();
			if (el.isString())
				out.value(el.getAsString());
			else
				out.value(el.getAsInt());
		}
	}
}
