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
import rs117.hd.utils.collections.PrimitiveIntArray;

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

	@Getter
	private static Profiler instance;

	@Getter
	private static boolean isActive = false;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	private static final int NUM_EVENTS = Event.EVENTS.length;
	private static final int NUM_TIMERS = Timer.TIMERS.length;
	private static final int NUM_STATS = Stat.STATS.length;
	private static final int NUM_GPU_TIMERS = (int) Arrays.stream(Timer.TIMERS).filter(Timer::isGpuTimer).count();
	private static final int NUM_GPU_DEBUG_GROUPS = (int) Arrays.stream(Timer.TIMERS).filter(Timer::hasGpuDebugGroup).count();

	private static final boolean TRACK_SYSTEM_MEMORY = HDUtils.getFreeSystemMemory() != Long.MAX_VALUE;
	private static final int GPU_QUERY_GROWTH = Math.max(NUM_GPU_TIMERS * 2, 32);

	private final TimerState[] timerStates = new TimerState[NUM_TIMERS];
	private final Event[] events = new Event[NUM_EVENTS];
	private final long[] stats = new long[NUM_STATS];

	private final int[] queryResult = { 0 };

	private final PrimitiveIntArray pendingElapsedResults = new PrimitiveIntArray();
	private final PrimitiveIntArray freeGpuQueries = new PrimitiveIntArray();
	private final PrimitiveIntArray allocatedGpuQueries = new PrimitiveIntArray();
	private final PrimitiveIntArray elapsedStack = new PrimitiveIntArray();

	private final ArrayDeque<Timer> glDebugGroupStack = new ArrayDeque<>(NUM_GPU_DEBUG_GROUPS);
	private final ArrayDeque<Listener> listeners = new ArrayDeque<>();
	private long[] lastGCTimes;

	private boolean useElapsedGpuQueries;
	private int activeElapsedQuery = -1;
	private int nextEventIndex = 0;

	@RequiredArgsConstructor
	public class AutoTimer implements AutoCloseable {
		private final Timer timer;

		@Override
		public void close() {
			end(timer);
		}
	}

	private final class TimerState {
		private final Timer timer;
		private final AutoTimer autoTimer;

		private boolean active;
		private long timing;
		private long heap;
		private long allocations;
		private int gpuDepth;

		private PrimitiveIntArray openTimestampSpans;
		private PrimitiveIntArray pendingTimestampSpans;

		private TimerState(Timer timer) {
			this.timer = timer;
			this.autoTimer = new AutoTimer(timer);

			if (timer.isGpuTimer()) {
				openTimestampSpans = new PrimitiveIntArray();
				pendingTimestampSpans = new PrimitiveIntArray();
			}
		}

		private void reset() {
			active = false;
			timing = 0;
			heap = 0;
			allocations = 0;
			gpuDepth = 0;
		}
	}

	@SuppressWarnings("resource")
	public Profiler() {
		for (int i = 0; i < NUM_TIMERS; i++)
			timerStates[i] = new TimerState(Timer.TIMERS[i]);
	}

	public long cumulativeError;
	public long errorCompensation;

	private void initialize() {
		clientThread.invoke(() -> {
			useElapsedGpuQueries = HdPlugin.APPLE || !HdPlugin.GL_CAPS.OpenGL33;

			instance = this;
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
				errorCompensation = (timerStates[Timer.DRAW_FRAME.ordinal()].timing + compensation) / iterations;
				timerStates[Timer.DRAW_FRAME.ordinal()].timing = 0;
			}
			log.debug("Estimated the overhead of timers to be around {} ns", errorCompensation);
		});
	}

	private void destroy() {
		clientThread.invoke(() -> {
			if (!isActive)
				return;

			isActive = false;
			instance = null;

			plugin.setupSyncMode();
			plugin.enableDetailedTimers = false;

			reset();
			if (allocatedGpuQueries.length > 0)
				glDeleteQueries(Arrays.copyOf(allocatedGpuQueries.array, allocatedGpuQueries.length));
			allocatedGpuQueries.reset();
			freeGpuQueries.reset();
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
		if (activeElapsedQuery != -1) {
			glEndQuery(GL_TIME_ELAPSED);
			releaseGpuQuery(activeElapsedQuery);
			activeElapsedQuery = -1;
		}
		elapsedStack.reset();
		releaseElapsedResults();
		for (int i = 0; i < NUM_TIMERS; i++) {
			final TimerState state = timerStates[i];
			state.reset();
			if (!state.timer.isGpuTimer())
				continue;
			releaseTimestampSpans(state.openTimestampSpans);
			releaseTimestampSpans(state.pendingTimestampSpans);
		}
		Arrays.fill(stats, 0);
		cumulativeError = 0;
		nextEventIndex = 0;
	}

	private int acquireGpuQuery() {
		if (freeGpuQueries.length == 0) {
			int[] names = new int[GPU_QUERY_GROWTH];
			glGenQueries(names);
			freeGpuQueries.put(names, 0, GPU_QUERY_GROWTH);
			allocatedGpuQueries.put(names, 0, GPU_QUERY_GROWTH);
		}
		return freeGpuQueries.array[--freeGpuQueries.length];
	}

	private void releaseGpuQuery(int id) {
		freeGpuQueries.ensureCapacity(1);
		freeGpuQueries.put(id);
	}

	private void finalizeActiveElapsedQuery() {
		glEndQuery(GL_TIME_ELAPSED);
		int id = activeElapsedQuery;
		activeElapsedQuery = -1;
		int depth = elapsedStack.length;
		pendingElapsedResults.ensureCapacity(depth + 2);
		pendingElapsedResults.put(id);
		pendingElapsedResults.put(depth);
		pendingElapsedResults.put(elapsedStack.array, 0, depth);
	}

	private void releaseElapsedResults() {
		int idx = 0;
		while (idx < pendingElapsedResults.length) {
			releaseGpuQuery(pendingElapsedResults.array[idx]);
			idx += 2 + pendingElapsedResults.array[idx + 1];
		}
		pendingElapsedResults.reset();
	}

	private void releaseTimestampSpans(PrimitiveIntArray spans) {
		for (int i = 0; i < spans.length; i++)
			releaseGpuQuery(spans.array[i]);
		spans.reset();
	}

	public long getTimeStamp() { return isActive ? System.nanoTime() : 0; }

	public long getUsedMemory() { return isActive ? HDUtils.getUsedMemory(true) : 0; }

	public AutoTimer begin(Timer timer) {
		final int index = timer.ordinal();
		final TimerState state = timerStates[index];

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
			if (useElapsedGpuQueries) {
				if (elapsedStack.length > 0)
					finalizeActiveElapsedQuery(); // pause the parent so this nested query can use the hardware's single elapsed-query slot
				activeElapsedQuery = acquireGpuQuery();
				glBeginQuery(GL_TIME_ELAPSED, activeElapsedQuery);
				elapsedStack.ensureCapacity(1);
				elapsedStack.put(index);
			} else {
				var spans = state.openTimestampSpans;
				spans.ensureCapacity(2);
				int startId = acquireGpuQuery();
				spans.put(startId);
				spans.put(acquireGpuQuery());
				glQueryCounter(startId, GL_TIMESTAMP);
			}
			state.gpuDepth++;
		} else if (!state.active) {
			cumulativeError += errorCompensation + 1 >> 1;
			subtractDuration(index, System.nanoTime() - cumulativeError);
			state.heap = HDUtils.getUsedMemory(true);
		}
		state.active = true;

		return state.autoTimer;
	}

	public boolean end(Timer timer) {
		if (log.isDebugEnabled() && timer.hasGpuDebugGroup() && HdPlugin.GL_CAPS.OpenGL43) {
			if (glDebugGroupStack.peek() != timer) {
				if (glDebugGroupStack.contains(timer))
					log.warn("The debug group {} was popped out of order", timer.name());
			} else {
				glDebugGroupStack.pop();
				GL43C.glPopDebugGroup();
			}
		}

		final int index = timer.ordinal();
		final TimerState state = timerStates[index];

		if (!isActive || !state.active)
			return false;

		if (timer.isGpuTimer()) {
			if (useElapsedGpuQueries) {
				if (elapsedStack.length > 0 && elapsedStack.array[elapsedStack.length - 1] == index) {
					finalizeActiveElapsedQuery();
					elapsedStack.length--;
					if (elapsedStack.length > 0) {
						// Resume the parent timer's query now that this one has finished
						activeElapsedQuery = acquireGpuQuery();
						glBeginQuery(GL_TIME_ELAPSED, activeElapsedQuery);
					}
				} else {
					log.warn("GPU timer {} was ended out of order", timer.name());
					for (int i = elapsedStack.length - 1; i >= 0; i--) {
						if (elapsedStack.array[i] == index) {
							elapsedStack.removeAt(i);
							break;
						}
					}
				}
			} else {
				var spans = state.openTimestampSpans;
				int endId = spans.array[--spans.length];
				int startId = spans.array[--spans.length];
				glQueryCounter(endId, GL_TIMESTAMP);

				var pending = state.pendingTimestampSpans;
				pending.ensureCapacity(2);
				pending.put(startId);
				pending.put(endId);
			}
			state.gpuDepth--;
			state.active = state.gpuDepth > 0;
			// leave the GPU timer's result to be gathered at a later point
		} else {
			final long originalHeap = state.heap;
			final long newHeap = HDUtils.getUsedMemory(true);
			final long allocated = newHeap - originalHeap;

			cumulativeError += errorCompensation >> 1;
			addDuration(index, System.nanoTime() - cumulativeError);
			if (allocated > 0)
				addAllocation(timer.ordinal(), allocated);
			state.active = false;
			state.heap = 0;
		}

		return true;
	}

	private synchronized void subtractDuration(int ordinal, long nanos) {
		timerStates[ordinal].timing -= nanos;
	}

	private synchronized void addDuration(int ordinal, long nanos) {
		timerStates[ordinal].timing += nanos;
	}

	private synchronized void addAllocation(int ordinal, long allocated) {
		timerStates[ordinal].allocations += allocated;
	}

	public void addDuration(Timer timer, long nanos) {
		if (isActive)
			addDuration(timer.ordinal(), nanos);
	}

	public void add(Timer timer, long startNanos) {
		if (isActive)
			addDuration(timer.ordinal(), System.nanoTime() - startNanos);
	}

	public void add(Timer timer, long startNanos, long startMemory) {
		if (isActive) {
			long allocated = HDUtils.getUsedMemory(true) - startMemory;
			addDuration(timer.ordinal(), System.nanoTime() - startNanos);
			if(allocated > 0)
				addAllocation(timer.ordinal(), allocated);
		}
	}

	public void add(Timer timer, long duration, TimeUnit unit) {
		if (isActive)
			addDuration(timer.ordinal(), TimeUnit.NANOSECONDS.convert(duration, unit));
	}

	public synchronized void pushEvent(Event event) {
		if(nextEventIndex < NUM_EVENTS)
			events[nextEventIndex++] = event;
	}

	public synchronized void setStat(Stat stat, long value) {
		stats[stat.ordinal()] = value;
	}

	public synchronized void setStat(Stat stat, int x, int y) {
		stats[stat.ordinal()] = (long)x | ((long)y << 32L);
	}

	public synchronized void addStat(Stat stat, long value) {
		stats[stat.ordinal()] += value;
	}

	public synchronized void incrementStat(Stat stat) {
		stats[stat.ordinal()]++;
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

		final long frameEndNanos = System.nanoTime();
		final long frameEndTimestamp = System.currentTimeMillis();

		trackGarbageCollection();

		long totalAsyncTime = 0;
		for (int i = 0; i < NUM_TIMERS; i++) {
			final TimerState state = timerStates[i];
			if (state.timer.isGpuTimer()) {
				if (state.gpuDepth > 0) {
					// End any dangling GPU timer spans automatically, but warn about it
					log.warn("Timer {} was never ended", state.timer);
					while (state.gpuDepth > 0)
						end(state.timer);
				}

				if (!useElapsedGpuQueries) {
					var spans = state.pendingTimestampSpans;
					for (int j = 0; j < spans.length; j += 2) {
						int startId = spans.array[j];
						int endId = spans.array[j + 1];
						queryResult[0] = 0;
						while (queryResult[0] == 0)
							glGetQueryObjectiv(endId, GL_QUERY_RESULT_AVAILABLE, queryResult);
						state.timing += glGetQueryObjectui64(endId, GL_QUERY_RESULT) - glGetQueryObjectui64(startId, GL_QUERY_RESULT);
						releaseGpuQuery(startId);
						releaseGpuQuery(endId);
					}
					spans.reset();
				}
			} else {
				if (state.active) {
					// End the CPU timer automatically, but warn about it
					log.warn("Timer {} was never ended", state.timer);
					state.timing += frameEndNanos;
				} else if(state.timer.isAsyncCpuTimer()) {
					totalAsyncTime += state.timing;
				}
			}
		}

		if (useElapsedGpuQueries) {
			int idx = 0;
			while (idx < pendingElapsedResults.length) {
				int id = pendingElapsedResults.array[idx++];
				int depth = pendingElapsedResults.array[idx++];

				queryResult[0] = 0;
				while (queryResult[0] == 0)
					glGetQueryObjectiv(id, GL_QUERY_RESULT_AVAILABLE, queryResult);
				long duration = glGetQueryObjectui64(id, GL_QUERY_RESULT);

				for (int k = 0; k < depth; k++)
					timerStates[pendingElapsedResults.array[idx + k]].timing += duration;
				idx += depth;

				releaseGpuQuery(id);
			}
			pendingElapsedResults.reset();
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

		var frameTimings = new ProfileSample(frameEndTimestamp, totalAsyncTime, stats, events, nextEventIndex, cpuLoad, heapUsageKB, freeSystemMemory, gpuUsageKB);

		for (int i = 0; i < NUM_TIMERS; i++) {
			final TimerState state = timerStates[i];
			frameTimings.timers[i] = state.timing;
			frameTimings.allocations[i] = state.allocations;
		}

		for (var listener : listeners)
			listener.onFrameCompletion(frameTimings);

		reset();
	}

	private long lastGarbageCollectionCount;

	private void trackGarbageCollection() {
		List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
		if (lastGCTimes == null || lastGCTimes.length != garbageCollectors.size())
			lastGCTimes = new long[garbageCollectors.size()];

		long garbageCollectionCount = 0;
		long elapsedDuration = 0;
		for (int i = 0; i < garbageCollectors.size(); i++) {
			var gc = garbageCollectors.get(i);
			long time = gc.getCollectionTime();
			if (time > 0 && time != lastGCTimes[i]) {
				long duration = time - lastGCTimes[i];
				lastGCTimes[i] = time;
				elapsedDuration += duration;
			}
			garbageCollectionCount += gc.getCollectionCount();
		}

		if(garbageCollectionCount != lastGarbageCollectionCount) {
			lastGarbageCollectionCount = garbageCollectionCount;
			pushEvent(Event.GC);
		}

		setStat(Stat.GARBAGE_COLLECTION_COUNT, garbageCollectionCount);
		addDuration(Timer.GARBAGE_COLLECTION, elapsedDuration * 1_000_000L);
	}
}
