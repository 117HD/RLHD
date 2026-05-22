package rs117.hd.utils;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Singleton;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.collections.PooledArrayType;

import static com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class GCMonitor {

	private static final Runtime RUNTIME = Runtime.getRuntime();

	private static final double MIN_FREE_HEAP_RATIO = 0.10;
	private static final long MIN_FREE_BYTES = 128L * 1024L * 1024L;

	private static long NEXT_LOG_TIME_MS = 0;
	private static long LAST_FULL_GC_TIME_MS = 0;

	private static int FULL_GC_COUNT = 0;

	private final List<NotificationEmitter> emitters = new ArrayList<>();

	public void startup() {
		for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gcBean instanceof NotificationEmitter) {
				NotificationEmitter emitter = (NotificationEmitter) gcBean;
				emitter.addNotificationListener(GCMonitor::onGcNotification, null, null);
				emitters.add(emitter);
			}
		}

		log.info("[GCMonitor] Registered {} GC notification listeners", emitters.size());
	}

	public void shutdown() {
		for (NotificationEmitter emitter : emitters) {
			try {
				emitter.removeNotificationListener(GCMonitor::onGcNotification);
			} catch (ListenerNotFoundException e) {
				log.debug("[GCMonitor] GC listener already removed", e);
			}
		}
		emitters.clear();
	}

	private static void onGcNotification(Notification notification, Object handback) {
		if (!GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType()))
			return;

		final GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
		final String action = info.getGcAction().toLowerCase();
		if (!action.contains("major") && !action.contains("full") && !action.contains("old"))
			return;

		final long currentTime = System.currentTimeMillis();
		if (LAST_FULL_GC_TIME_MS > 0 && currentTime - LAST_FULL_GC_TIME_MS < 1000) {
			FULL_GC_COUNT++;
			if(currentTime > NEXT_LOG_TIME_MS) {
				log.warn("[GCMonitor] Multiple Full GCs ({}) within a second have occurred, increasing max memory might help", FULL_GC_COUNT);
				NEXT_LOG_TIME_MS = currentTime + 10000;
			}
		}
		LAST_FULL_GC_TIME_MS = currentTime;

		final long usedHeap = RUNTIME.totalMemory() - RUNTIME.freeMemory();
		final long maxHeap = RUNTIME.maxMemory();
		final long freeHeap = maxHeap - usedHeap;

		final double freeRatio = (double) freeHeap / (double) maxHeap;

		if (freeRatio > MIN_FREE_HEAP_RATIO && freeHeap > MIN_FREE_BYTES)
			return;

		final long poolSize = PooledArrayType.getCurrentTotalCacheSize();
		if (poolSize <= 0)
			return;

		log.warn(
			"[GCMonitor] Memory pressure detected after GC. HeapAvailable={} HeapUsed={} Pool={}",
			formatBytes(freeHeap), formatBytes(usedHeap), formatBytes(poolSize)
		);

		PooledArrayType.forceCleanup(true);
	}
}
