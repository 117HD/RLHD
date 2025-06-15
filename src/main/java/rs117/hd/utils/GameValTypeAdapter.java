package rs117.hd.utils;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.HashSet;
import rs117.hd.data.GameVals;

@Slf4j
@Singleton
public abstract class GameValTypeAdapter extends TypeAdapter<HashSet<Integer>> {

	protected final String typeKey;

	public GameValTypeAdapter(String typeKey) {
		this.typeKey = typeKey;
	}

	@Override
	public void write(JsonWriter out, HashSet<Integer> src) throws IOException {
		out.beginArray();

		for (Integer id : src) {
			String name = GameVals.INSTANCE.get(typeKey,id);
			if (name == null) throw new JsonParseException("Unknown id for " + typeKey + ": " + id);
			out.value(name);
		}
		out.endArray();
	}

	@Override
	public HashSet<Integer> read(JsonReader in) throws IOException {
		HashSet<Integer> result = new HashSet<>();

		in.beginArray();
		while (in.hasNext()) {
			String name = in.nextString();
			Integer id = GameVals.INSTANCE.get(typeKey, name);
			if (id == null) {
				log.error("GameVal Conversion not found for type '{}' and name '{}'", typeKey, name);
			} else {
				result.add(id);
			}
		}
		in.endArray();

		return result;
	}
}