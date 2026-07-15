package rs117.hd.profiling;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX;
import static rs117.hd.HdPlugin.GL_CAPS;

@Slf4j
@Singleton
public class Profiler {
	public static final int CPU_TIMER = 0;
	public static final int ASYNC_CPU_TIMER = 1;
	public static final int GPU_TIMER = 2;
	public static final int ASYNC_GPU_TIMER = 3;

	private static final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	private static final int NUM_TIMERS = Timer.TIMERS.length;
	private static final int NUM_GPU_TIMERS = (int) Arrays.stream(Timer.TIMERS).filter(Timer::isGpuTimer).count();
	private static final int NUM_GPU_DEBUG_GROUPS = (int) Arrays.stream(Timer.TIMERS).filter(Timer::hasGpuDebugGroup).count();

	private static final boolean TRACK_SYSTEM_MEMORY = HDUtils.getFreeSystemMemory() != Long.MAX_VALUE;

	private final AutoTimer[] autoTimers = new AutoTimer[NUM_TIMERS];
	private final boolean[] activeTimers = new boolean[NUM_TIMERS];
	private final long[] timings = new long[NUM_TIMERS];
	private final long[] heap = new long[NUM_TIMERS];
	private final long[] allocations = new long[NUM_TIMERS];
	private final int[] gpuQueries = new int[NUM_TIMERS * 2];
	private final ArrayDeque<Timer> glDebugGroupStack = new ArrayDeque<>(NUM_GPU_DEBUG_GROUPS);
	private final ArrayDeque<Listener> listeners = new ArrayDeque<>();
	private long[] lastGCTimes;

	@RequiredArgsConstructor
	public class AutoTimer implements AutoCloseable {
		private final Timer timer;

		@Override
		public void close() {
			end(timer);
		}
	}

	@SuppressWarnings("resource")
	public Profiler() {
		for (int i = 0; i < NUM_TIMERS; i++)
			autoTimers[i] = new AutoTimer(Timer.TIMERS[i]);
	}

	@Getter
	private boolean isActive = false;

	public long cumulativeError;
	public long errorCompensation;

	private void initialize() {
		clientThread.invoke(() -> {
			int[] queryNames = new int[NUM_GPU_TIMERS * 2];
			glGenQueries(queryNames);
			int queryIndex = 0;
			for (var timer : Timer.TIMERS)
				if (timer.isGpuTimer())
					for (int j = 0; j < 2; ++j)
						gpuQueries[timer.ordinal() * 2 + j] = queryNames[queryIndex++];

			isActive = true;
			plugin.setupSyncMode();
			plugin.enableDetailedTimers = true;

			// Estimate the timer's own runtime, with a warm-up run first
			final int iterations = 100000;
			final int compensation = 1950000; // additional manual correction
			for (int i = 0; i < 2; i++) {
				errorCompensation = 0;
				for (int j = 0; j < iterations; j++) {
					begin(Timer.DRAW_FRAME);
					end(Timer.DRAW_FRAME);
				}
				errorCompensation = (timings[Timer.DRAW_FRAME.ordinal()] + compensation) / iterations;
				timings[Timer.DRAW_FRAME.ordinal()] = 0;
			}
			log.debug("Estimated the overhead of timers to be around {} ns", errorCompensation);
		});
	}

	private void destroy() {
		clientThread.invoke(() -> {
			if (!isActive)
				return;

			isActive = false;
			plugin.setupSyncMode();
			plugin.enableDetailedTimers = false;

			glDeleteQueries(gpuQueries);
			Arrays.fill(gpuQueries, 0);
			reset();
		});
	}

	@FunctionalInterface
	public interface Listener {
		void onFrameCompletion(ProfileSample timings);
	}

	public void addTimingsListener(Listener listener) {
		if (listeners.isEmpty())
			initialize();
		listeners.add(listener);
	}

	public void removeTimingsListener(Listener listener) {
		listeners.remove(listener);
		if (listeners.isEmpty())
			destroy();
	}

	public void removeAllListeners() {
		listeners.clear();
		destroy();
	}

	public void reset() {
		Arrays.fill(timings, 0);
		Arrays.fill(allocations, 0);
		Arrays.fill(activeTimers, false);
		cumulativeError = 0;
	}

	public long getTimeStamp() { return isActive ? System.nanoTime() : 0; }

	public long getUsedMemory() { return isActive ? HDUtils.getUsedMemory(true) : 0; }

	public AutoTimer begin(Timer timer) {
		int index = timer.ordinal();
		if (log.isDebugEnabled() && timer.hasGpuDebugGroup() && HdPlugin.GL_CAPS.OpenGL43) {
			if (glDebugGroupStack.contains(timer)) {
				log.warn("The debug group {} is already on the stack", timer.name());
			} else {
				glDebugGroupStack.push(timer);
				GL43C.glPushDebugGroup(GL43C.GL_DEBUG_SOURCE_APPLICATION, index, timer.name);
			}
		}

		if (!isActive)
			return null;

		if (timer.isGpuTimer()) {
			if (activeTimers[index])
				throw new UnsupportedOperationException("Cumulative GPU timing isn't supported");
			glQueryCounter(gpuQueries[index * 2], GL_TIMESTAMP);
		} else if (!activeTimers[index]) {
			cumulativeError += errorCompensation + 1 >> 1;
			timings[index] -= System.nanoTime() - cumulativeError;
			heap[index] = HDUtils.getUsedMemory(true);
		}
		activeTimers[index] = true;

		return autoTimers[index];
	}

	public void end(Timer timer) {
		if (log.isDebugEnabled() && timer.hasGpuDebugGroup() && HdPlugin.GL_CAPS.OpenGL43) {
			if (glDebugGroupStack.peek() != timer) {
				if (glDebugGroupStack.contains(timer))
					log.warn("The debug group {} was popped out of order", timer.name());
			} else {
				glDebugGroupStack.pop();
				GL43C.glPopDebugGroup();
			}
		}

		int index = timer.ordinal();
		if (!isActive || !activeTimers[index])
			return;

		if (timer.isGpuTimer()) {
			glQueryCounter(gpuQueries[index * 2 + 1], GL_TIMESTAMP);
			// leave the GPU timer active, since it needs to be gathered at a later point
		} else {
			final long originalHeap = heap[index];
			final long newHeap = HDUtils.getUsedMemory(true);
			final long allocated = newHeap - originalHeap;

			cumulativeError += errorCompensation >> 1;
			timings[index] += System.nanoTime() - cumulativeError;
			allocations[index] += allocated > 0 ? allocated : 0;
			activeTimers[index] = false;
			heap[index] = 0;
		}
	}

	public void addDuration(Timer timer, long nanos) {
		if (isActive)
			timings[timer.ordinal()] += nanos;
	}

	public void add(Timer timer, long startNanos) {
		if (isActive)
			timings[timer.ordinal()] += System.nanoTime() - startNanos;
	}

	public void add(Timer timer, long startNanos, long startMemory) {
		if (isActive) {
			long allocation = HDUtils.getUsedMemory(true) - startMemory;
			timings[timer.ordinal()] += System.nanoTime() - startNanos;
			allocations[timer.ordinal()] += allocation > 0 ? allocation : 0;
		}
	}

	public void add(Timer timer, long duration, TimeUnit unit) {
		if (isActive)
			timings[timer.ordinal()] += TimeUnit.NANOSECONDS.convert(duration, unit);
	}

	public void endFrameAndReset() {
		if (HdPlugin.GL_CAPS.OpenGL43) {
			while (!glDebugGroupStack.isEmpty()) {
				log.warn("The debug group {} was never popped", glDebugGroupStack.pop().name());
				GL43C.glPopDebugGroup();
			}
		}

		if (!isActive)
			return;

		long frameEndNanos = System.nanoTime();
		long frameEndTimestamp = System.currentTimeMillis();

		trackGarbageCollection();

		int[] available = { 0 };
		for (var timer : Timer.TIMERS) {
			int i = timer.ordinal();
			if (timer.isGpuTimer()) {
				if (!activeTimers[i])
					continue;

				for (int j = 0; j < 2; j++) {
					while (available[0] == 0)
						glGetQueryObjectiv(gpuQueries[i * 2 + j], GL_QUERY_RESULT_AVAILABLE, available);
					timings[i] += (j * 2L - 1) * glGetQueryObjectui64(gpuQueries[i * 2 + j], GL_QUERY_RESULT);
				}
			} else {
				if (activeTimers[i]) {
					// End the CPU timer automatically, but warn about it
					log.warn("Timer {} was never ended", timer);
					timings[i] += frameEndNanos;
				}
			}
		}

		final float cpuLoad = (float) osBean.getSystemLoadAverage() / osBean.getAvailableProcessors();
		final long heapUsageKB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L;
		final long freeSystemMemory = TRACK_SYSTEM_MEMORY ? HDUtils.getFreeSystemMemory() / 1024L : 0;

		final long gpuUsageKB;
		if (GL_CAPS.GL_NVX_gpu_memory_info) {
			int totalKB = glGetInteger(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX);
			int availableKB = glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
			gpuUsageKB = totalKB - availableKB;
		} else {
			gpuUsageKB = -1;
		}

		var frameTimings = new ProfileSample(frameEndTimestamp, timings, allocations, cpuLoad, heapUsageKB, freeSystemMemory, gpuUsageKB);
		for (var listener : listeners)
			listener.onFrameCompletion(frameTimings);

		reset();
	}

	private void trackGarbageCollection() {
		List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
		if (lastGCTimes == null || lastGCTimes.length != garbageCollectors.size())
			lastGCTimes = new long[garbageCollectors.size()];

		plugin.garbageCollectionCount = 0;
		long elapsedDuration = 0;
		for (int i = 0; i < garbageCollectors.size(); i++) {
			var gc = garbageCollectors.get(i);
			long time = gc.getCollectionTime();
			if (time > 0 && time != lastGCTimes[i]) {
				long duration = time - lastGCTimes[i];
				lastGCTimes[i] = time;
				elapsedDuration += duration;
			}
			plugin.garbageCollectionCount += gc.getCollectionCount();
		}

		addDuration(Timer.GARBAGE_COLLECTION, elapsedDuration * 1_000_000L);
	}
}
