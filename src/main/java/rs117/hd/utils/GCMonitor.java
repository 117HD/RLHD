package rs117.hd.utils;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Singleton;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.utils.collections.PooledArrayType;

import static com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class GCMonitor {
	private static final Runtime RUNTIME = Runtime.getRuntime();

	// This is the required available memory needed at all times otherwise the client will OOM when loading another scene
	private static final long REQUIRED_HEAP_AVAIL = (long) 1.8e+8; // 180 MB

	private static int GC_COUNT = 0;
	private static long GC_TIME_MS = 0;

	private static long nextHeapLogTime = 0;
	private static final long[] GC_AVAIL = new long[32];

	public static int getGCCount() { return GC_COUNT; }

	public static long getGCTimeMS() { return GC_TIME_MS; }

	private final List<NotificationEmitter> emitters = new ArrayList<>();

	public void startup() {
		for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gcBean instanceof NotificationEmitter) {
				NotificationEmitter emitter = (NotificationEmitter) gcBean;
				emitter.addNotificationListener(GCMonitor::onGcNotification, null, null);
				emitters.add(emitter);
			}
		}

		Arrays.fill(GC_AVAIL, 0);
		GC_COUNT = 0;
		GC_TIME_MS = 0;
		nextHeapLogTime = 0;

		log.info("Registered {} GC notification listeners", emitters.size());
	}

	public void shutdown() {
		for (NotificationEmitter emitter : emitters) {
			try {
				emitter.removeNotificationListener(GCMonitor::onGcNotification);
			} catch (ListenerNotFoundException e) {
				log.debug("GC listener already removed", e);
			}
		}
		emitters.clear();
	}

	private static void onGcNotification(Notification notification, Object handback) {
		if(!HdPlugin.isActive() || HdPlugin.isPluginStopPending())
			return;

		if (!GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType()))
			return;

		final GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());

		final long usedHeap = RUNTIME.totalMemory() - RUNTIME.freeMemory();
		final long freeHeap = RUNTIME.maxMemory() - usedHeap;

		// Store available heap space after GC
		GC_AVAIL[GC_COUNT % GC_AVAIL.length] = freeHeap;
		GC_TIME_MS = info.getGcInfo().getDuration();
		GC_COUNT++;

		// Calculate average available heap space after GC & determine if theres enough space
		if(GC_COUNT > 4) {
			final int gcAvailCount = min(GC_AVAIL.length, GC_COUNT);
			long averageGCAvail = 0;
			for (int i = 0; i < gcAvailCount; i++)
				averageGCAvail += GC_AVAIL[i];
			averageGCAvail /= gcAvailCount;

			if(averageGCAvail < REQUIRED_HEAP_AVAIL) {
				log.warn("Detected Average Avail Heap after GC: {}", formatBytes(averageGCAvail));
				log.warn("Shutting down plugin due to lack of heap space, increase max memory or decrease required heap space");
				HdPlugin.requestPluginStop();
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
