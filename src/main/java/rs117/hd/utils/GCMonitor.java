package rs117.hd.utils;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
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
import static net.runelite.client.ui.overlay.OverlayPosition.ABOVE_CHATBOX_RIGHT;
import static net.runelite.client.ui.overlay.OverlayPriority.HIGHEST;
import static rs117.hd.utils.HDUtils.drawStringShadowed;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class GCMonitor extends Overlay implements NotificationListener {
	private static final Runtime RUNTIME = Runtime.getRuntime();

	private static final long RECOMMENDED_HEAP_AVAIL = (long) 2e+8; // 200 MB
	private static final long MIN_HEAP_AVAIL = (long) 1e+8; // 100 MB
	private static final long GC_SAMPLE_WINDOW_MS = 60_000;
	private static final int MIN_RECENT_SAMPLES_FOR_SHUTDOWN = 4;

	private static final Color[] WARNING_COLORS = {
		Color.RED,
		Color.ORANGE,
		Color.YELLOW,
		Color.GREEN
	};

	private final GCSample[] gcSamples = new GCSample[16];
	private final Dimension bounds = new Dimension(500, 32);
	private final List<NotificationEmitter> emitters = new ArrayList<>();

	@Getter
	private int gcCount = 0;
	@Getter
	private long gcDurationMs = 0;
	private long nextHeapLogTime = 0;

	@Inject
	private HdPlugin plugin;

	@Inject
	private OverlayManager overlayManager;

	public GCMonitor() {
		for (int i = 0; i < gcSamples.length; i++)
			gcSamples[i] = new GCSample();
	}

	public void startup() {
		for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gcBean instanceof NotificationEmitter) {
				NotificationEmitter emitter = (NotificationEmitter) gcBean;
				emitter.addNotificationListener(this, null, null);
				emitters.add(emitter);
			}
		}

		overlayManager.add(this);

		setPriority(HIGHEST);
		setPosition(ABOVE_CHATBOX_RIGHT);

		gcCount = 0;
		gcDurationMs = 0;
		nextHeapLogTime = 0;

		for (GCSample sample : gcSamples) {
			sample.timestamp = 0;
			sample.availableHeap = 0;
		}

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

	public boolean isCloseToRunningOutOfMemory() {
		return getAvgAvailHeap() < RECOMMENDED_HEAP_AVAIL;
	}

	private int getRecentSampleCount() {
		final long now = System.currentTimeMillis();

		int count = 0;
		for (int i = 0; i < gcSamples.length; i++) {
			final GCSample sample = gcSamples[i];
			if (sample.timestamp == 0)
				continue;

			if (now - sample.timestamp > GC_SAMPLE_WINDOW_MS)
				continue;

			count++;
		}

		return count;
	}

	private long getAvgAvailHeap() {
		final long now = System.currentTimeMillis();

		long accum = 0;
		int count = 0;

		for (int i = 0; i < gcSamples.length; i++) {
			final GCSample sample = gcSamples[i];
			if (sample.timestamp == 0)
				continue;

			if (now - sample.timestamp > GC_SAMPLE_WINDOW_MS)
				continue;

			accum += sample.availableHeap;
			count++;
		}

		if (count == 0)
			return RUNTIME.maxMemory() - RUNTIME.freeMemory();

		return accum / count;
	}

	@Override
	public void handleNotification(Notification notification, Object handback) {
		if (!plugin.isActive() || plugin.isPluginStopPending())
			return;

		if (!GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType()))
			return;

		final GarbageCollectionNotificationInfo info =
			GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());

		final long usedHeap = RUNTIME.totalMemory() - RUNTIME.freeMemory();
		final long availableHeap = RUNTIME.maxMemory() - usedHeap;

		final long now = System.currentTimeMillis();

		GCSample sample = gcSamples[gcCount % gcSamples.length];
		sample.timestamp = now;
		sample.availableHeap = availableHeap;

		gcDurationMs = info.getGcInfo().getDuration();
		gcCount++;

		final int recentSamples = getRecentSampleCount();
		final long averageGCAvail = getAvgAvailHeap();

		if (recentSamples >= MIN_RECENT_SAMPLES_FOR_SHUTDOWN) {
			if (averageGCAvail < MIN_HEAP_AVAIL) {
				log.warn("Detected Average Avail Heap after GC: {}", formatBytes(averageGCAvail));

				plugin.requestPluginStop(
					"117HD has turned off due to lack of memory, " +
					"increase max memory or reduce enabled plugins"
				);

				return;
			}

			if (nextHeapLogTime <= now) {
				nextHeapLogTime = now + 30_000;

				log.info(
					"Average Avail Heap after GC: {}",
					formatBytes(averageGCAvail)
				);
			}
		}

		final String action = info.getGcAction().toLowerCase();

		if (!action.contains("major")
		    && !action.contains("full")
		    && !action.contains("old")) {
			return;
		}

		// Full GC has occurred, cleanup pooled arrays to free memory
		PooledArrayType.forceCleanup(true);
	}

	@Override
	public Dimension render(Graphics2D g) {
		final long averageGCAvail = getAvgAvailHeap();

		if (averageGCAvail > RECOMMENDED_HEAP_AVAIL)
			return null;

		final float percentTillShutdown = saturate(
			(float) (averageGCAvail - MIN_HEAP_AVAIL) / MIN_HEAP_AVAIL
		);

		final int colorIndex = Math.min(
			WARNING_COLORS.length - 1,
			(int) (percentTillShutdown * WARNING_COLORS.length)
		);

		g.setColor(WARNING_COLORS[colorIndex]);

		drawStringShadowed(
			g,
			"Client is running low on memory, memory left: "
			+ round(1, percentTillShutdown * 100.0f)
			+ "% till 117HD will shutdown",
			8,
			12
		);

		drawStringShadowed(
			g,
			"Please either increase memory or reduce active plugins",
			8,
			28
		);

		return bounds;
	}

	private static final class GCSample {
		long timestamp;
		long availableHeap;
	}
}