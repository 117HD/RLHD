package rs117.hd.utils;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
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
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import rs117.hd.HdPlugin;
import rs117.hd.utils.collections.PooledArrayType;

import static com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION;
import static rs117.hd.utils.HDUtils.drawStringShadowed;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class GCMonitor extends Overlay implements NotificationListener {
	private static final Runtime RUNTIME = Runtime.getRuntime();

	private static final long RECOMMENDED_HEAP_AVAIL = (long) 2e+8; // 200 MB
	private static final long MIN_HEAP_AVAIL = (long) 1e+8; // 100 MB

	private static final Color[] COLORS = { Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN };

	@Getter
	private int gcCount = 0;

	@Getter
	private long gcDurationMs = 0;

	private long nextHeapLogTime = 0;
	private final long[] GC_AVAIL = new long[32];

	private final Dimension bounds = new Dimension(428, 32);

	@Inject
	private HdPlugin plugin;

	@Inject
	private OverlayManager overlayManager;

	private final List<NotificationEmitter> emitters = new ArrayList<>();

	public void startup() {
		for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gcBean instanceof NotificationEmitter) {
				NotificationEmitter emitter = (NotificationEmitter) gcBean;
				emitter.addNotificationListener(this, null, null);
				emitters.add(emitter);
			}
		}

		overlayManager.add(this);

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
		overlayManager.remove(this);
	}

	private long getAvgAvailHeap() {
		final int gcAvailCount = min(GC_AVAIL.length, gcCount);
		long gcAvailAccum = 0;
		for (int i = 0; i < gcAvailCount; i++)
			gcAvailAccum += GC_AVAIL[i];
		return gcAvailAccum / gcAvailCount;
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
			long averageGCAvail = getAvgAvailHeap();
			if(averageGCAvail < MIN_HEAP_AVAIL) {
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

	@Override
	public Dimension render(Graphics2D g) {
		final long averageGCAvail = getAvgAvailHeap();
		if(averageGCAvail > RECOMMENDED_HEAP_AVAIL)
			return null;

		final float percentTillShutdown = saturate((float) (averageGCAvail - MIN_HEAP_AVAIL) / MIN_HEAP_AVAIL);

		g.setColor(COLORS[(int) (percentTillShutdown * COLORS.length)]);
		drawStringShadowed(g, "Client is running low on memory, memory left: " + round(2, percentTillShutdown) + "% till 117HD will shutdown", 8, 12);
		drawStringShadowed(g, "Please either increase memory or reduce active plugins", 8, 28);
		return bounds;
	}
}
