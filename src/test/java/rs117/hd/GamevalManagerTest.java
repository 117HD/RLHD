package rs117.hd;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import junit.framework.TestCase;
import rs117.hd.scene.GamevalManager;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import java.util.*;

public class GamevalManagerTest extends TestCase {
	private static final String KEY = "objects";
	private static final ResourcePath GAMEVAL_PATH = Props.getPathOrDefault(
		"rlhd.gameval-path",
		() -> ResourcePath.path(GamevalManager.class, "gamevals.json")
	);

	private static final Map<String, Map<String, Integer>> gamevals = new HashMap<>();
	private static final Map<String, Map<Integer, String>> reverseGamevals = new HashMap<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();

		if (!gamevals.isEmpty()) {
			// Already loaded
			return;
		}

		Map<String, Map<String, Integer>> loaded = GAMEVAL_PATH.loadJson(
			new Gson(),
			new TypeToken<Map<String, Map<String, Integer>>>() {}.getType()
		);

		gamevals.putAll(loaded);

		// Build reverse lookup map
		for (var entry : gamevals.entrySet()) {
			Map<Integer, String> reverse = new HashMap<>();
			for (var e : entry.getValue().entrySet()) {
				reverse.put(e.getValue(), e.getKey());
			}
			reverseGamevals.put(entry.getKey(), reverse);
		}

		assertFalse("gamevals should not be empty after loading", gamevals.isEmpty());
		assertNotNull("gamevals should contain key '" + KEY + "'", gamevals.get(KEY));
	}

	private static String getName(String key, int id) {
		Map<String, Integer> map = gamevals.getOrDefault(key, Map.of());
		for (Map.Entry<String, Integer> entry : map.entrySet()) {
			if (entry.getValue() == id) {
				return entry.getKey();
			}
		}
		return null;
	}

	private static String getNameFast(String key, int id) {
		return reverseGamevals.getOrDefault(key, Map.of()).get(id);
	}

	public void testCorrectness() {
		Map<String, Integer> entries = gamevals.get(KEY);
		assertNotNull(entries);

		for (Map.Entry<String, Integer> entry : entries.entrySet()) {
			String expected = entry.getKey();
			int id = entry.getValue();

			assertEquals(expected, getName(KEY, id));
			assertEquals(expected, getNameFast(KEY, id));
		}
	}

	public void testPerformanceComparison() {
		Map<String, Integer> entries = gamevals.get(KEY);
		assertNotNull(entries);

		int[] ids = entries.values().stream()
			.limit(100)
			.mapToInt(Integer::intValue)
			.toArray();

		int repetitions = 100_000;

		long startLinear = System.nanoTime();
		for (int i = 0; i < repetitions; i++) {
			for (int id : ids) {
				getName(KEY, id);
			}
		}
		long endLinear = System.nanoTime();

		long startMap = System.nanoTime();
		for (int i = 0; i < repetitions; i++) {
			for (int id : ids) {
				getNameFast(KEY, id);
			}
		}
		long endMap = System.nanoTime();

		double linearMs = (endLinear - startLinear) / 1_000_000.0;
		double mapMs = (endMap - startMap) / 1_000_000.0;
		double diffMs = linearMs - mapMs;

		System.out.println("====== Large Test (100000 iterations) ======");
		System.out.printf("getName (linear)   took: %.2f ms%n", linearMs);
		System.out.printf("getNameFast (map)  took: %.2f ms%n", mapMs);
		System.out.printf("Time difference (linear - map): %.2f ms%n", diffMs);

		assertTrue("Linear search should be slower than map lookup", linearMs > mapMs);
	}

	public void testSmallPerformanceComparison() {
		Map<String, Integer> entries = gamevals.get(KEY);
		assertNotNull(entries);

		int[] ids = entries.values().stream()
			.limit(10)
			.mapToInt(Integer::intValue)
			.toArray();

		int repetitions = 100;

		long startLinear = System.nanoTime();
		for (int i = 0; i < repetitions; i++) {
			for (int id : ids) {
				String name = getName(KEY, id);
				assertNotNull(name);
			}
		}
		long endLinear = System.nanoTime();

		long startMap = System.nanoTime();
		for (int i = 0; i < repetitions; i++) {
			for (int id : ids) {
				String name = getNameFast(KEY, id);
				assertNotNull(name);
			}
		}
		long endMap = System.nanoTime();

		double linearMs = (endLinear - startLinear) / 1_000_000.0;
		double mapMs = (endMap - startMap) / 1_000_000.0;
		double diffMs = linearMs - mapMs;

		System.out.println("====== Small Test (100 iterations) ======");
		System.out.printf("getName (linear)   took: %.2f ms%n", linearMs);
		System.out.printf("getNameFast (map)  took: %.2f ms%n", mapMs);
		System.out.printf("Time difference (linear - map): %.2f ms%n", diffMs);

		// Check correctness for these 10 entries
		for (int id : ids) {
			assertEquals(getName(KEY, id), getNameFast(KEY, id));
		}

		assertTrue("Linear search should be slower than map lookup", linearMs > mapMs);
	}
}
