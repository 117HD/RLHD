package rs117.hd.utils;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
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

	private static final int MAX_EXPANDED_CHUNKS = 5;
	private static final long RECOMMENDED_HEAP_AVAIL = (long) 2e+8; // 200 MB
	private static final long MIN_HEAP_AVAIL = (long) 1e+8; // 100 MB

	private static final long GC_SAMPLE_WINDOW_MS = 60_000;

	private static final long FADE_DURATION_MS = 1_000;

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
	private long gcDurationMs = 0;
	private long nextHeapLogTime = 0;
	private float warningAlpha = 0f;
	private long lastRenderTime = System.currentTimeMillis();

	@Inject
	private HdPlugin plugin;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FrameTimer frameTimer;

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

	public void update() {
		frameTimer.add(Timer.GARBAGE_COLLECTION, gcDurationMs, TimeUnit.MILLISECONDS);
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

	public long calculateRecommendedHeapSize() {
		return (long) (RECOMMENDED_HEAP_AVAIL * (plugin.configExpandedMapLoadingChunks + 1) / (MAX_EXPANDED_CHUNKS + 1.0));
	}

	public long calculateMinimalHeapSize() {
		return (long) (MIN_HEAP_AVAIL * (plugin.configExpandedMapLoadingChunks + 1) / (MAX_EXPANDED_CHUNKS + 1.0));
	}

	public boolean isCloseToRunningOutOfMemory() {
		return getAvgAvailHeap() < calculateRecommendedHeapSize();
	}

	private int getRecentSampleCount() {
		final long now = System.currentTimeMillis();

		int count = 0;
		for (int i = 0; i < gcSamples.length; i++) {
			final GCSample sample = gcSamples[i];
			if (sample.timestamp == 0 || now - sample.timestamp > GC_SAMPLE_WINDOW_MS)
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
			if (sample.timestamp == 0 || now - sample.timestamp > GC_SAMPLE_WINDOW_MS)
				continue;

			accum += sample.availableHeap;
			count++;
		}

		return count > 0 ? accum / count : RUNTIME.maxMemory();
	}

	@Override
	public void handleNotification(Notification notification, Object handback) {
		if (!plugin.isActive() || plugin.isPluginStopPending())
			return;

		if (!GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType()))
			return;

		final GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());

		final long usedHeap = RUNTIME.totalMemory() - RUNTIME.freeMemory();
		final long availableHeap = RUNTIME.maxMemory() - usedHeap;

		final long now = System.currentTimeMillis();

		GCSample sample = gcSamples[gcCount % gcSamples.length];
		sample.timestamp = now;
		sample.availableHeap = availableHeap;

		gcDurationMs = info.getGcInfo().getDuration();
		gcCount++;

		final long averageGCAvail = getAvgAvailHeap();
		final int recentSampleCount = getRecentSampleCount();
		if (recentSampleCount >= 4 && averageGCAvail < calculateMinimalHeapSize()) {
			log.warn("Detected Average Avail Heap after GC: {}", formatBytes(averageGCAvail));

			plugin.requestPluginStop(
				"117HD has turned off due to lack of memory try:\n" +
				" * Reducing enabled plugins\n" +
				" * Reducing Extended Map Loading\n"+
				" * Increasing Client Memory"
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

		final long now = System.currentTimeMillis();
		final float deltaSeconds = (now - lastRenderTime) / 1000f;
		final float fadeSpeed = deltaSeconds / (FADE_DURATION_MS / 1000f);

		if (averageGCAvail <= calculateRecommendedHeapSize()) {
			warningAlpha = Math.min(1f, warningAlpha + fadeSpeed);
		} else {
			warningAlpha = Math.max(0f, warningAlpha - fadeSpeed);
		}
		lastRenderTime = now;

		if (warningAlpha <= 0f)
			return null;

		final long minHeapSize = calculateMinimalHeapSize();
		final float shutdownFrac = saturate((float) (averageGCAvail - minHeapSize) / minHeapSize);
		final int colorIndex = min(WARNING_COLORS.length - 1, (int) (shutdownFrac * WARNING_COLORS.length));

		final Color fadedShadowColor = new Color(0, 0, 0, (int) (warningAlpha * 255));
		final Color fadedBaseColor = new Color(
			WARNING_COLORS[colorIndex].getRed(),
			WARNING_COLORS[colorIndex].getGreen(),
			WARNING_COLORS[colorIndex].getBlue(),
			(int) (warningAlpha * 255)
		);

		g.setColor(fadedBaseColor);
		drawStringShadowed(
			g,
			"Client is running low on memory, memory left: "
			+ round(1, shutdownFrac * 100.0f)
			+ "% till 117HD will shutdown",
			8,
			12,
			fadedShadowColor
		);

		drawStringShadowed(
			g,
			"Please either reduce active plugins/extended loaded chunks or increase client memory",
			8,
			28,
			fadedShadowColor
		);

		return bounds;
	}

	private static final class GCSample {
		long timestamp;
		long availableHeap;
	}
}