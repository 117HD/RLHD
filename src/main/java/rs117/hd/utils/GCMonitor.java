package rs117.hd.utils;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.utils.collections.PooledArrayType;

import static com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class GCMonitor implements NotificationListener {
	private static final Runtime RUNTIME = Runtime.getRuntime();

	// This is the required available memory needed at all times otherwise the client will OOM when loading another scene
	private static final long REQUIRED_HEAP_AVAIL = (long) 1.8e+8; // 180 MB

	@Getter
	private int gcCount = 0;

	@Getter
	private long gcDurationMs = 0;

	private long nextHeapLogTime = 0;
	private final long[] GC_AVAIL = new long[32];

	@Inject
	private HdPlugin plugin;

	private final List<NotificationEmitter> emitters = new ArrayList<>();

	public void startup() {
		for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gcBean instanceof NotificationEmitter) {
				NotificationEmitter emitter = (NotificationEmitter) gcBean;
				emitter.addNotificationListener(this, null, null);
				emitters.add(emitter);
			}
		}

		Arrays.fill(GC_AVAIL, 0);
		gcCount = 0;
		gcDurationMs = 0;
		nextHeapLogTime = 0;

		log.info("Registered {} GC notification listeners", emitters.size());
	}

	public void shutdown() {
		for (NotificationEmitter emitter : emitters) {
			try {
				emitter.removeNotificationListener(this);
			} catch (ListenerNotFoundException e) {
				log.debug("GC listener already removed", e);
			}
		}
		emitters.clear();
	}

	@Override
	public void handleNotification(Notification notification, Object handback) {
		if(!plugin.isActive() || plugin.isPluginStopPending())
			return;

		if (!GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType()))
			return;

		final GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());

		final long usedHeap = RUNTIME.totalMemory() - RUNTIME.freeMemory();
		final long freeHeap = RUNTIME.maxMemory() - usedHeap;

		// Store available heap space after GC
		GC_AVAIL[gcCount % GC_AVAIL.length] = freeHeap;
		gcDurationMs = info.getGcInfo().getDuration();
		gcCount++;

		// Calculate average available heap space after GC & determine if theres enough space
		if(gcCount >= 8) {
			final int gcAvailCount = min(GC_AVAIL.length, gcCount);
			long averageGCAvail = 0;
			for (int i = 0; i < gcAvailCount; i++)
				averageGCAvail += GC_AVAIL[i];
			averageGCAvail /= gcAvailCount;

			if(averageGCAvail < REQUIRED_HEAP_AVAIL) {
				log.warn("Detected Average Avail Heap after GC: {}", formatBytes(averageGCAvail));
				plugin.requestPluginStop("117HD has turned off due to lack of memory, increase max memory or reduce enabled plugins");
				return;
			}

			if(nextHeapLogTime <= System.currentTimeMillis()) {
				nextHeapLogTime = System.currentTimeMillis() + 30000;
				log.info("Average Avail Heap after GC: {}", formatBytes(averageGCAvail));
			}
		}

		final String action = info.getGcAction().toLowerCase();
		if (!action.contains("major") && !action.contains("full") && !action.contains("old"))
			return;

		// Full GC has occurred, cleanup pooled arrays to free up memory
		PooledArrayType.forceCleanup(true);
	}
}
