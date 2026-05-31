package rs117.hd.utils;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
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

import static net.runelite.client.ui.overlay.OverlayPosition.ABOVE_CHATBOX_RIGHT;
import static rs117.hd.HdPlugin.MAX_EXPANDED_CHUNKS;
import static rs117.hd.utils.HDUtils.drawStringShadowed;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class GCMonitor extends Overlay implements NotificationListener {
	private static final Runtime RUNTIME = Runtime.getRuntime();

	private static final long RECOMMENDED_HEAP_AVAIL = 200 * MB;
	private static final long MINIMUM_HEAP_AVAIL = 170 * MB;

	private static final long GC_SAMPLE_WINDOW_MS = 60_000;

	private static final long WARNING_DELAY_MS = 10_000;
	private static final float CRITICAL_THRESHOLD = 0.5f;
	private static final long FADE_DURATION_MS = 1_000;

	private static final Color[] WARNING_COLORS = {
		Color.RED,
		Color.ORANGE,
		Color.YELLOW,
		Color.GREEN
	};

	private static final int GC_WEIGHT_FULL = 4;
	private static final int GC_WEIGHT_MINOR = 1;

	private final GCSample[] gcSamples = new GCSample[16];
	private final Dimension bounds = new Dimension(500, 32);
	private final List<NotificationEmitter> emitters = new ArrayList<>();

	@Getter
	private int gcCount = 0;
	private long gcDurationMs = 0;
	private long nextHeapLogTime = 0;
	private float warningAlpha = 0f;
	private long warningStartTime = -1;
	private final List<SuspendHandle> suspendHandles = new ArrayList<>();

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	public GCMonitor() {
		for (int i = 0; i < gcSamples.length; i++)
			gcSamples[i] = new GCSample();
	}

	public void startup() {
		for (var gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gcBean instanceof NotificationEmitter) {
				NotificationEmitter emitter = (NotificationEmitter) gcBean;
				emitter.addNotificationListener(this, null, null);
				emitters.add(emitter);
			}
		}

		overlayManager.add(this);

		setPriority(PRIORITY_HIGHEST);
		setPosition(ABOVE_CHATBOX_RIGHT);

		gcCount = 0;
		gcDurationMs = 0;
		nextHeapLogTime = 0;

		for (GCSample sample : gcSamples) {
			sample.timestamp = 0;
			sample.availableHeap = 0;
			sample.weight = 0;
		}

		log.debug("Registered {} GC notification listeners", emitters.size());
	}

	public SuspendHandle acquireSuspendHandle() {
		synchronized (suspendHandles) {
			SuspendHandle suspendHandle = new SuspendHandle();
			suspendHandles.add(suspendHandle);
			return suspendHandle;
		}
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

		synchronized (suspendHandles) {
			suspendHandles.clear();
		}

		emitters.clear();
		overlayManager.remove(this);
	}

	private double calculateExpandedMapLoadingFrac() {
		return mix(0.6f, 1.0f, (float) plugin.configExpandedMapLoadingChunks / MAX_EXPANDED_CHUNKS);
	}

	private long calculateRecommendedHeapSize() {
		return (long) (RECOMMENDED_HEAP_AVAIL * calculateExpandedMapLoadingFrac());
	}

	private long calculateMinimalHeapSize() {
		return (long) (MINIMUM_HEAP_AVAIL * calculateExpandedMapLoadingFrac());
	}

	private int calculateRecommendedMemoryIncreaseMB(long averageAvailHeap) {
		final long deficit = calculateRecommendedHeapSize() - averageAvailHeap;
		if (deficit <= 0)
			return 0;

		return ceil(deficit * 1.25f / MB);
	}

	private int calculateRecommendedExpandedMapLoading(long averageAvailHeap) {
		for (int chunks = MAX_EXPANDED_CHUNKS; chunks >= 0; chunks--) {
			final float frac = mix(0.6f, 1.0f, (float) chunks / MAX_EXPANDED_CHUNKS);
			final long requiredHeap = (long) (RECOMMENDED_HEAP_AVAIL * frac);
			if (averageAvailHeap >= requiredHeap)
				return chunks;
		}

		return 0;
	}

	public boolean isCloseToRunningOutOfMemory() {
		return getAvgAvailHeap() < calculateRecommendedHeapSize();
	}

	private int getRecentSampleWeight() {
		final long now = System.currentTimeMillis();

		int totalWeight = 0;
		for (int i = 0; i < gcSamples.length; i++) {
			final GCSample sample = gcSamples[i];
			if (sample.timestamp == 0 || now - sample.timestamp > GC_SAMPLE_WINDOW_MS)
				continue;

			totalWeight += sample.weight;
		}
		return totalWeight;
	}

	private long getAvgAvailHeap() {
		final long now = System.currentTimeMillis();

		long accum = 0;
		int totalWeight = 0;

		for (int i = 0; i < gcSamples.length; i++) {
			final GCSample sample = gcSamples[i];
			if (sample.timestamp == 0 || now - sample.timestamp > GC_SAMPLE_WINDOW_MS)
				continue;

			accum += sample.availableHeap * sample.weight;
			totalWeight += sample.weight;
		}

		return totalWeight > 0 ? accum / totalWeight : RUNTIME.maxMemory();
	}

	@Override
	public void handleNotification(Notification notification, Object handback) {
		if (!plugin.isActive() || plugin.isPluginStopPending())
			return;

		final String action;
		try {
			if (!com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType()))
				return;

			final var info = com.sun.management.GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
			gcDurationMs = info.getGcInfo().getDuration();
			gcCount++;

			action = info.getGcAction().toLowerCase();
		} catch (Throwable ex) {
			log.error("Unsupported JVM:", ex);
			shutdown();
			return;
		}

		final boolean isFullGC = action.contains("major") || action.contains("full") || action.contains("old");
		final int gcWeight = isFullGC ? GC_WEIGHT_FULL : GC_WEIGHT_MINOR;

		PooledArrayType.forceCleanup(isFullGC);

		final long usedHeap = RUNTIME.totalMemory() - RUNTIME.freeMemory();
		final long availableHeap = RUNTIME.maxMemory() - usedHeap;
		final long now = System.currentTimeMillis();

		if (suspendHandles.isEmpty()) {
			final GCSample sample = gcSamples[gcCount % gcSamples.length];
			sample.timestamp = now;
			sample.availableHeap = availableHeap;
			sample.weight = gcWeight;
		}

		final long averageGCAvail = getAvgAvailHeap();
		if (nextHeapLogTime <= now) {
			nextHeapLogTime = now + 30_000;

			log.info(
				"Average Avail Heap after GC: {}",
				formatBytes(averageGCAvail)
			);
		}

		if (plugin.configMemoryMonitoring) {
			final int recentSampleWeight = getRecentSampleWeight();
			if (recentSampleWeight >= 8 && averageGCAvail < calculateMinimalHeapSize()) {
				log.warn("Detected Average Avail Heap after GC: {}", formatBytes(averageGCAvail));

				final int recommendedIncreaseMB = calculateRecommendedMemoryIncreaseMB(averageGCAvail);
				final int recommendedEML = calculateRecommendedExpandedMapLoading(averageGCAvail);

				plugin.requestPluginStop(
					"117HD has turned off due to avoid running out of memory, try:\n" +
					(recommendedIncreaseMB > 0 ? " * Increase Client Memory by at least " + recommendedIncreaseMB + " MB\n" : "") +
					" * Reduce Extended Map Loading to " + recommendedEML + "\n" +
					" * Reduce enabled plugins\n" +
					"If the issue persists even after all of the above, please join our discord server:\n" +
					HdPlugin.DISCORD_URL
				);
			}
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		if (!plugin.configMemoryMonitoring)
			return null;

		final long averageGCAvail = getAvgAvailHeap();
		final long minHeapSize = calculateMinimalHeapSize();
		final float shutdownFrac = saturate((float) (averageGCAvail - minHeapSize) / minHeapSize);

		final long now = System.currentTimeMillis();
		final float fadeSpeed = plugin.deltaTime / (FADE_DURATION_MS / 1000f);

		if (averageGCAvail <= calculateRecommendedHeapSize()) {
			if (warningStartTime == -1)
				warningStartTime = now;
		} else {
			warningStartTime = -1;
		}

		boolean shouldFadeIn = warningStartTime != -1 && now - warningStartTime >= WARNING_DELAY_MS;
		if (!shouldFadeIn && shutdownFrac <= CRITICAL_THRESHOLD) {
			warningStartTime = now - WARNING_DELAY_MS;
			shouldFadeIn = true;
		}

		if (shouldFadeIn) {
			warningAlpha = min(1f, warningAlpha + fadeSpeed);
		} else {
			warningAlpha = max(0f, warningAlpha - fadeSpeed);
		}

		if (warningAlpha <= 0f)
			return null;

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
			"Please either reduce active plugins/extended map loading or increase client memory",
			8,
			28,
			fadedShadowColor
		);

		return bounds;
	}

	private static final class GCSample {
		long timestamp;
		long availableHeap;
		int weight;
	}

	public class SuspendHandle implements AutoCloseable {
		@Override
		public void close() {
			synchronized (suspendHandles) {
				suspendHandles.remove(this);
			}
		}
	}
}
