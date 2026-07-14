package rs117.hd.utils;

import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import rs117.hd.scene.GamevalManager;
import rs117.hd.utils.collections.Int2ObjectHashMap;
import rs117.hd.utils.collections.IntHashSet;

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

	private final Int2ObjectHashMap<Entry> cache = new Int2ObjectHashMap<>(MAX_SIZE);
	private final IntHashSet ANIM_ID_IGNORE_LIST = new IntHashSet();

	public void initialize() {
		try (var gamevals = gamevalManager.obtainHandle()) {
			for (var substringToIgnore : ANIM_IGNORE_LIST)
				for (var entry : gamevals.getAnims().entrySet())
					if (entry.getKey().contains(substringToIgnore))
						ANIM_ID_IGNORE_LIST.add(entry.getValue());
			ANIM_ID_IGNORE_LIST.trimToSize();
		}
	}

	public void destroy() {
		ANIM_ID_IGNORE_LIST.clear();
		ANIM_ID_IGNORE_LIST.trimToSize();
		cache.clear();
	}

	public int size() {
		return cache.size();
	}

	public synchronized void clear() {
		cache.clear();
	}

	public synchronized Entry get(NPC npc) {
		int npcId = npc.getId();
		var entry = cache.get(npcId);

		if (entry == null) {
			if (cache.size() >= NpcDisplacementCache.MAX_SIZE) {
				long oldestMs = Long.MAX_VALUE;
				int oldestNpcId = -1;
				for (var e : cache) {
					if (e.getValue().lastAccessMs < oldestMs) {
						oldestNpcId = e.getKey();
						entry = e.getValue();
					}
				}

				if(entry != null) {
					cache.remove(oldestNpcId);
					entry.reset();
				}
			}

			if(entry == null)
				entry = new NpcDisplacementCache.Entry();
			cache.put(npcId, entry);

			// Check if NPC is allowed to displace
			int animId = npc.getWalkAnimation();
			entry.canDisplace = animId == -1 || !ANIM_ID_IGNORE_LIST.contains(animId);
		}

		entry.lastAccessMs = System.currentTimeMillis();
		return entry;
	}
}
