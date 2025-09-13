package rs117.hd.scene;

import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class GamevalManager {
	private static final ResourcePath GAMEVAL_PATH = Props
		.getFile("rlhd.gameval-path", () -> path(GamevalManager.class, "gamevals.json"));

	private static final String NPC_KEY = "npcs";
	private static final String OBJECT_KEY = "objects";
	private static final String ANIM_KEY = "anims";
	private static final String SPOTANIM_KEY = "spotanims";

	@Inject
	private HdPlugin plugin;

	private FileWatcher.UnregisterCallback fileWatcher;

	private static final Map<String, Map<String, Integer>> GAMEVALS = new HashMap<>();

	static {
		clearGamevals();
	}

	private static void clearGamevals() {
		GAMEVALS.put(NPC_KEY, Collections.emptyMap());
		GAMEVALS.put(OBJECT_KEY, Collections.emptyMap());
		GAMEVALS.put(ANIM_KEY, Collections.emptyMap());
		GAMEVALS.put(SPOTANIM_KEY, Collections.emptyMap());
	}

	public void startUp() throws IOException {
		fileWatcher = GAMEVAL_PATH.watch((path, first) -> {
			try {
				Map<String, Map<String, Integer>> gamevals = plugin.getGson()
					.fromJson(path.toReader(), new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
				GAMEVALS.replaceAll((k, v) -> gamevals.getOrDefault(k, Collections.emptyMap()));
				log.debug("Loaded gameval mappings");
			} catch (IOException ex) {
				log.error("Failed to load gamevals:", ex);
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		clearGamevals();
	}

	private String getName(String key, int id) {
		return GAMEVALS
			.get(key)
			.entrySet()
			.stream()
			.filter(e -> e.getValue() == id)
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(null);
	}

	public Map<String, Integer> getNpcs() {
		return GAMEVALS.get(NPC_KEY);
	}

	public Map<String, Integer> getObjects() {
		return GAMEVALS.get(OBJECT_KEY);
	}

	public Map<String, Integer> getAnims() {
		return GAMEVALS.get(ANIM_KEY);
	}

	public Map<String, Integer> getSpotanims() {
		return GAMEVALS.get(SPOTANIM_KEY);
	}

	public int getNpcId(String name) {
		return getNpcs().get(name);
	}

	public int getObjectId(String name) {
		return getObjects().get(name);
	}

	public int getAnimId(String name) {
		return getAnims().get(name);
	}

	public int getSpotanimId(String name) {
		return getSpotanims().get(name);
	}

	public String getNpcName(int id) {
		return getName(NPC_KEY, id);
	}

	public String getObjectName(int id) {
		return getName(OBJECT_KEY, id);
	}

	public String getAnimName(int id) {
		return getName(ANIM_KEY, id);
	}

	public String getSpotanimName(int id) {
		return getName(SPOTANIM_KEY, id);
	}

	@Slf4j
	@RequiredArgsConstructor
	private abstract static class GamevalAdapter extends TypeAdapter<HashSet<Integer>> {
		private final String key;

		@Override
		public HashSet<Integer> read(JsonReader in) throws IOException {
			var map = GAMEVALS.get(key);
			HashSet<Integer> result = new HashSet<>();

			in.beginArray();
			while (in.hasNext()) {
				var type = in.peek();
				switch (type) {
					case NUMBER: {
						int id = in.nextInt();
						if (id != -1)
							log.debug("Adding raw {} ID: {} at {}. Should be replaced with a gameval.", key, id, GsonUtils.location(in));
						result.add(id);
						break;
					}
					case STRING:
						String name = in.nextString();
						Integer id = map.get(name);
						if (id == null) {
							String suggestion = "";
							for (var gamevalMapEntry : GAMEVALS.entrySet()) {
								if (gamevalMapEntry.getValue().get(name) != null) {
									suggestion = String.format(", did you mean to match %s?", gamevalMapEntry.getKey());
									break;
								}
							}
							log.error("Missing {} gameval: {}{} at {}", key, name, suggestion, GsonUtils.location(in), new Throwable());
						} else {
							result.add(id);
						}
						break;
					default:
						log.error("Unexpected {} gameval type: {} at {}", key, type, GsonUtils.location(in), new Throwable());
						break;
				}
			}
			in.endArray();

			return result;
		}

		@Override
		public void write(JsonWriter out, HashSet<Integer> ids) throws IOException {
			var remainingIds = new ArrayList<>(ids);
			var map = GAMEVALS.get(key);
			var names = map.entrySet().stream()
				.filter(e -> remainingIds.remove(e.getValue()))
				.map(Map.Entry::getKey)
				.sorted()
				.toArray(String[]::new);

			if (!remainingIds.isEmpty()) {
				remainingIds.sort(Integer::compareTo);
				log.warn(
					"Exporting IDs with no corresponding gamevals: {}", remainingIds.stream()
						.filter(i -> i != -1)
						.map(Object::toString)
						.collect(Collectors.joining(", "))
				);
			}

			out.beginArray();
			for (var id : remainingIds)
				out.value(id);
			for (var name : names)
				out.value(name);
			out.endArray();
		}
	}

	public static class NpcAdapter extends GamevalAdapter {
		public NpcAdapter() {
			super(NPC_KEY);
		}
	}

	public static class ObjectAdapter extends GamevalAdapter {
		public ObjectAdapter() {
			super(OBJECT_KEY);
		}
	}

	public static class AnimationAdapter extends GamevalAdapter {
		public AnimationAdapter() {
			super(ANIM_KEY);
		}
	}

	public static class SpotanimAdapter extends GamevalAdapter {
		public SpotanimAdapter() {
			super(SPOTANIM_KEY);
		}
	}
}
