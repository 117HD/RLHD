package rs117.hd.utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.HashSet;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GsonUtils {
    @VisibleForTesting
    public static boolean THROW_WHEN_PARSING_FAILS = false;

	public static HashSet<Integer> parseIDArray(JsonReader in) throws IOException {
		HashSet<Integer> ids = new HashSet<>();
		in.beginArray();
		while (in.hasNext()) {
			if (in.peek() == JsonToken.NUMBER) {
				try {
					ids.add(in.nextInt());
				} catch (NumberFormatException ex) {
					String message = "Failed to parse int";
					if (THROW_WHEN_PARSING_FAILS)
						throw new RuntimeException(message, ex);
					log.error(message, ex);
				}
			} else {
				throw new RuntimeException("Unable to parse ID: " + in.peek());
			}
		}
        in.endArray();
        return ids;
    }

	public static void writeIDArray(JsonWriter out, HashSet<Integer> listToWrite) throws IOException {
		if (listToWrite.size() == 0) {
			out.nullValue();
			return;
		}

		out.beginArray();
		for (int id : listToWrite)
			out.value(id);
		out.endArray();
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
}
