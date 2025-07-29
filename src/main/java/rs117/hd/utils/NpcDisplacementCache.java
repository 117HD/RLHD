package rs117.hd.utils;

import java.util.HashMap;
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
			var anim = gamevalManager.getAnimName(npc.getWalkAnimation());
			entry.canDisplace = anim == null || !ANIM_IGNORE_LIST.contains(anim);
		}

		entry.lastAccessMs = System.currentTimeMillis();
		return entry;
	}
}
