package rs117.hd.data;

import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class GameVals {

	private static final ResourcePath GAME_VAL_PATH = Props.getPathOrDefault(
		"rlhd.gameval-path",
		() -> path(HdPlugin.class, "gamevals.json")
	);


	public static final String NPC_KEY = "npcs";
	public static final String OBJECT_KEY = "objects";
	public static final String ANIM_KEY = "anims";
	public static final String SPOTANIM_KEY = "spotanim";

	@Inject
	private HdPlugin plugin;

	private final Map<String, Map<String, Integer>> values = new HashMap<>();

	public void startUp() throws IOException {
		long startTime = System.nanoTime();

		Type type = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();

		try (InputStream is = GAME_VAL_PATH.toInputStream()) {
			if (is == null) {
				log.warn("Could not find resource: {}", GAME_VAL_PATH.path);
				return;
			}

			try (InputStreamReader reader = new InputStreamReader(is)) {
				Map<String, Map<String, Integer>> loaded = plugin.getGson().fromJson(reader, type);
				loaded.forEach((key, map) -> values.put(key, Collections.unmodifiableMap(map)));
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to load gamevals JSON from: " + GAME_VAL_PATH.path, e);
		}

		long durationMs = (System.nanoTime() - startTime) / 1_000_000;
		log.info("Loaded gamevals.json in {} ms", durationMs);
	}

	public Map<String, Integer> getAll(String typeKey) {
		return values.getOrDefault(typeKey, Collections.emptyMap());
	}

	public Integer get(String typeKey, String name) {
		return Objects.equals(name, "-1") ? -1 : getAll(typeKey).get(name);
	}

	public String getName(String typeKey, Integer id) {
		return getAll(typeKey)
			.entrySet()
			.stream()
			.filter(entry -> entry.getValue().equals(id))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(null);
	}

	public Integer getNpc(String name) {
		return get(NPC_KEY, name);
	}

	public Integer getObject(String name) {
		return get(OBJECT_KEY, name);
	}

	public Integer getAnim(String name) {
		return get(ANIM_KEY, name);
	}

	public Integer getSpotanim(String name) {
		return get(SPOTANIM_KEY, name);
	}

	public String getNpcConfigName(int id) {
		return getName(NPC_KEY, id);
	}

}