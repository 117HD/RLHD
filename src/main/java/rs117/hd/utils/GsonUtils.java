package rs117.hd.utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GsonUtils {
    @VisibleForTesting
    public static boolean THROW_WHEN_PARSING_FAILS = false;

	public static String location(JsonReader in) {
		var str = in.toString();
		int i = str.indexOf(" at ");
		if (i != -1)
			str = str.substring(i + 4);
		return str;
	}

	public static HashSet<String> parseStringArray(JsonReader in) throws IOException {
		HashSet<String> ids = new HashSet<>();
		in.beginArray();
		while (in.hasNext()) {
			if (in.peek() == JsonToken.STRING) {
				try {
					ids.add(in.nextString());
				} catch (NumberFormatException ex) {
					String message = "Failed to parse string at " + location(in);
					if (THROW_WHEN_PARSING_FAILS)
						throw new RuntimeException(message, ex);
					log.error(message, ex);
				}
			} else {
				throw new RuntimeException("Unable to parse string: " + in.peek() + " at " + location(in));
			}
		}
		in.endArray();
		return ids;
	}


	public static HashSet<Integer> parseIDArray(JsonReader in) throws IOException {
		HashSet<Integer> ids = new HashSet<>();
		in.beginArray();
		while (in.hasNext()) {
			if (in.peek() == JsonToken.NUMBER) {
				try {
					ids.add(in.nextInt());
				} catch (NumberFormatException ex) {
					String message = "Failed to parse int at " + location(in);
					if (THROW_WHEN_PARSING_FAILS)
						throw new RuntimeException(message, ex);
					log.error(message, ex);
				}
			} else {
				throw new RuntimeException("Unable to parse ID: " + in.peek() + " at " + location(in));
			}
		}
        in.endArray();
        return ids;
    }

	public static void writeStringArray(JsonWriter out, HashSet<String> listToWrite) throws IOException {
		if (listToWrite.isEmpty()) {
			out.nullValue();
			return;
		}

		out.beginArray();
		for (String id : listToWrite)
			out.value(id);
		out.endArray();
	}

	public static void writeIDArray(JsonWriter out, HashSet<Integer> listToWrite) throws IOException {
		if (listToWrite.isEmpty()) {
			out.nullValue();
			return;
		}

		out.beginArray();
		for (int id : listToWrite)
			out.value(id);
		out.endArray();
	}

	public static class StringSetAdapter extends TypeAdapter<HashSet<String>> {
		@Override
		public HashSet<String> read(JsonReader in) throws IOException {
			return parseStringArray(in);
		}

		@Override
		public void write(JsonWriter out, HashSet<String> value) throws IOException {
			writeStringArray(out, value);
		}
	}

	public static class IntegerSetAdapter extends TypeAdapter<HashSet<Integer>> {
		@Override
		public HashSet<Integer> read(JsonReader in) throws IOException {
			return parseIDArray(in);
		}

		@Override
		public void write(JsonWriter out, HashSet<Integer> value) throws IOException {
			writeIDArray(out, value);
		}
	}

	@Slf4j
	public static class DegreesToRadians extends TypeAdapter<Object> {
		@Override
		public Object read(JsonReader in) throws IOException {
			var token = in.peek();
			if (token == JsonToken.NULL)
				return null;

			if (token == JsonToken.NUMBER) {
				float angle = (float) in.nextDouble();
				return (float) Math.toRadians(angle);
			}

			if (token == JsonToken.BEGIN_ARRAY) {
				ArrayList<Float> list = new ArrayList<>();
				in.beginArray();
				while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
					if (in.peek() == JsonToken.BEGIN_ARRAY)
						throw new IOException("Expected an array of numbers. Got nested arrays.");
					list.add((float) read(in));
				}
				in.endArray();

				float[] result = new float[list.size()];
				for (int i = 0; i < list.size(); i++)
					result[i] = list.get(i);
				return result;
			}

			throw new IOException("Expected a number or array of numbers. Got " + token);
		}

		@Override
		public void write(JsonWriter out, Object src) throws IOException {
			if (src == null) {
				out.nullValue();
				return;
			}

			if (src instanceof float[]) {
				out.beginArray();
				for (float f : (float[]) src)
					out.value(Math.toDegrees(f));
				out.endArray();
				return;
			}

			if (src instanceof Float) {
				out.value(Math.toDegrees((float) src));
			}

			throw new IOException("Expected a float or float array. Got " + src);
		}
	}
}
