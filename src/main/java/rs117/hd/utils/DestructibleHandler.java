package rs117.hd.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class DestructibleHandler {
	private static final ConcurrentLinkedQueue<Destructible> PENDING_DESTRUCTION = new ConcurrentLinkedQueue<>();
	private static final ConcurrentLinkedQueue<Destructible> LEAKED_DESTRUCTIBLE = new ConcurrentLinkedQueue<>();
	private static final StringBuilder sb = new StringBuilder();
	private static final HashMap<Class<?>, Integer> totalLeakedCount = new HashMap<>();

	@Getter
	private static boolean isShuttingDown = false;

	public static void queueDestruction(Destructible destructible) {
		PENDING_DESTRUCTION.add(destructible);
	}

	public static void destroy(Destructible destructible) {
		PENDING_DESTRUCTION.remove(destructible);
		LEAKED_DESTRUCTIBLE.remove(destructible);

		destructible.destroy();
	}

	public static void queueLeakedDestruction(Destructible destructible) {
		LEAKED_DESTRUCTIBLE.add(destructible);
	}

	public static void flushPendingDestruction() {
		flushPendingDestruction(false);
	}

	public static void flushPendingDestruction(boolean isShutdown) {
		try {
			isShuttingDown = isShutdown;

			Destructible destructable;
			while ((destructable = PENDING_DESTRUCTION.poll()) != null)
				destructable.destroy();

			if (isShutdown) {
				// Perform GC + finalization to ensure we pick up on any leaks during shutdown
				for (int i = 0; i < 5; i++) {
					System.gc();
					System.runFinalization();
				}
			}

			if (LEAKED_DESTRUCTIBLE.isEmpty())
				return;

			sb.setLength(0);
			sb.append("Leaked Destructible detected, count: ").append(LEAKED_DESTRUCTIBLE.size()).append("\n");
			while ((destructable = LEAKED_DESTRUCTIBLE.poll()) != null) {
				sb.append(" * ").append(destructable.getClass().getSimpleName()).append(" | ").append(destructable).append("\n");
				destructable.destroy();

				// Track the amount that type has leaked over the course of the session
				int typeLeakCount = totalLeakedCount.getOrDefault(destructable.getClass(), 0);
				totalLeakedCount.put(destructable.getClass(), typeLeakCount + 1);
			}

			sb.append("Total leaked by Type:");
			for (Map.Entry<Class<?>, Integer> entry : totalLeakedCount.entrySet())
				sb.append("\n * ").append(entry.getKey()).append(": ").append(entry.getValue());

			log.warn(sb.toString());
		} finally {
			isShuttingDown = false;
		}
	}
}
