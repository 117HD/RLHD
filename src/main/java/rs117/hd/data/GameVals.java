package rs117.hd.data;

import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.util.Objects;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;

import javax.inject.Inject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Repository responsible for loading and providing access to
 * various game value mappings (Jagex Internal config Names) categorized by {@link GameValType}.
 */

@Slf4j
@Singleton
public class GameVals {

	@Inject
	private HdPlugin plugin;

	private final Map<GameValType, Map<String, Integer>> values = new EnumMap<>(GameValType.class);

	public void startUp() throws IOException {
		long startTime = System.nanoTime();

		Type mapType = new TypeToken<Map<String, Integer>>() {}.getType();

		for (GameValType type : GameValType.values()) {
			String path = "/rs117/hd/gamevals/" + type.getFileName();

			try (InputStream is = getClass().getResourceAsStream(path)) {
				if (is == null) {
					log.warn("Could not find resource: {}", path);
					values.put(type, Collections.emptyMap());
					continue;
				}

				try (InputStreamReader reader = new InputStreamReader(is)) {
					Map<String, Integer> map = plugin.getGson().fromJson(reader, mapType);
					values.put(type, Collections.unmodifiableMap(map));
				}
			} catch (Exception e) {
				throw new RuntimeException("Failed to load JSON for " + type + ": " + path, e);
			}
		}

		long endTime = System.nanoTime();
		long durationMs = (endTime - startTime) / 1_000_000;

		log.info("Loaded all GameValType data in {} ms", durationMs);
	}

	public Map<String, Integer> get(GameValType type) {
		return values.getOrDefault(type, Collections.emptyMap());
	}

	public Integer get(GameValType type, String name) {
		if (Objects.equals(name, "-1")) { //Not Ideal but we use it as a hack for anims
			return -1;
		}
		return get(type).get(name);
	}

	public String lookupNameById(GameValType type, Integer id) {
		Map<String, Integer> map = values.get(type);
		if (map == null) return null;

		for (Map.Entry<String, Integer> entry : map.entrySet()) {
			if (entry.getValue().equals(id)) {
				return entry.getKey();
			}
		}
		return null;
	}
}