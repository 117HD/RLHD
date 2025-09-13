package rs117.hd.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import rs117.hd.scene.GamevalManager;

@Singleton
public class NpcDisplacementCache {
	private static final int MAX_SIZE = 100;
	private static final Set<String> ANIM_IGNORE_LIST = Set.of("HOVER", "FLY", "IMPLING", "SWAN", "DUCK", "SWIM");

	@Inject
	private GamevalManager gamevalManager;

	public static class Entry {
		public boolean canDisplace;
		public int idleRadius;
		public long lastAccessMs;

		{
			reset();
		}

		public Entry reset() {
			canDisplace = true;
			idleRadius = -1;
			lastAccessMs = 0;
			return this;
		}
	}

	private final HashMap<Integer, Entry> cache = new HashMap<>(MAX_SIZE);
	private Set<Integer> ANIM_ID_IGNORE_LIST = Collections.emptySet();

	public void initialize() {
		HashSet<Integer> idsToIgnore = new HashSet<>();
		for (var substringToIgnore : ANIM_IGNORE_LIST)
			for (var entry : gamevalManager.getAnims().entrySet())
				if (entry.getKey().contains(substringToIgnore))
					idsToIgnore.add(entry.getValue());
		ANIM_ID_IGNORE_LIST = Set.copyOf(idsToIgnore);
	}

	public void destroy() {
		ANIM_ID_IGNORE_LIST = Collections.emptySet();
		cache.clear();
	}

	public int size() {
		return cache.size();
	}

	public void clear() {
		cache.clear();
	}

	public Entry get(NPC npc) {
		int npcId = npc.getId();
		var entry = cache.get(npcId);

		if (entry == null) {
			if (cache.size() >= NpcDisplacementCache.MAX_SIZE) {
				long oldestMs = Long.MAX_VALUE;
				int oldestNpcId = -1;
				for (var e : cache.entrySet())
					if (e.getValue().lastAccessMs < oldestMs)
						oldestNpcId = e.getKey();
				entry = cache.remove(oldestNpcId).reset();
			} else {
				entry = new NpcDisplacementCache.Entry();
			}
			cache.put(npcId, entry);

			// Check if NPC is allowed to displace
			int animId = npc.getWalkAnimation();
			entry.canDisplace = animId == -1 || !ANIM_ID_IGNORE_LIST.contains(animId);
		}

		entry.lastAccessMs = System.currentTimeMillis();
		return entry;
	}
}
