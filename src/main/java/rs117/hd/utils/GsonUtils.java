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
