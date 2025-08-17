package rs117.hd.overlays;

import java.util.ArrayDeque;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
@Singleton
public class FrameTimer {
	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	private static final Timer[] TIMERS = Timer.values();
	private static final int NUM_TIMERS = TIMERS.length;
	private static final int NUM_GPU_TIMERS = (int) Arrays.stream(TIMERS).filter(t -> t.isGpuTimer).count();
	private static final int NUM_GPU_DEBUG_GROUPS = (int) Arrays.stream(TIMERS).filter(t -> t.gpuDebugGroup).count();

	private final boolean[] activeTimers = new boolean[NUM_TIMERS];
	private final long[] timings = new long[NUM_TIMERS];
	private final int[] gpuQueries = new int[NUM_TIMERS * 2];
	private final ArrayDeque<Timer> glDebugGroupStack = new ArrayDeque<>(NUM_GPU_DEBUG_GROUPS);
	private final ArrayDeque<Listener> listeners = new ArrayDeque<>();

	@Getter
	private boolean isActive = false;

	public long cumulativeError;
	public long errorCompensation;

	private void initialize() {
		clientThread.invokeLater(() -> {
			int[] queryNames = new int[NUM_GPU_TIMERS * 2];
			glGenQueries(queryNames);
			int queryIndex = 0;
			for (var timer : TIMERS)
				if (timer.isGpuTimer)
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
		clientThread.invokeLater(() -> {
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
		void onFrameCompletion(FrameTimings timings);
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
		Arrays.fill(activeTimers, false);
		cumulativeError = 0;
	}

	public void begin(Timer timer) {
		if (log.isDebugEnabled() && timer.gpuDebugGroup && HdPlugin.GL_CAPS.OpenGL43) {
			if (glDebugGroupStack.contains(timer)) {
				log.warn("The debug group {} is already on the stack", timer.name());
			} else {
				glDebugGroupStack.push(timer);
				GL43C.glPushDebugGroup(GL43C.GL_DEBUG_SOURCE_APPLICATION, timer.ordinal(), timer.name);
			}
		}

		if (!isActive)
			return;

		if (timer.isGpuTimer) {
			if (activeTimers[timer.ordinal()])
				throw new UnsupportedOperationException("Cumulative GPU timing isn't supported");
			glQueryCounter(gpuQueries[timer.ordinal() * 2], GL_TIMESTAMP);
		} else if (!activeTimers[timer.ordinal()]) {
			cumulativeError += errorCompensation + 1 >> 1;
			timings[timer.ordinal()] -= System.nanoTime() - cumulativeError;
		}
		activeTimers[timer.ordinal()] = true;
	}

	public void end(Timer timer) {
		if (log.isDebugEnabled() && timer.gpuDebugGroup && HdPlugin.GL_CAPS.OpenGL43) {
			if (glDebugGroupStack.peek() != timer) {
				log.warn("The debug group {} was popped out of order", timer.name());
			} else {
				glDebugGroupStack.pop();
				GL43C.glPopDebugGroup();
			}
		}

		if (!isActive || !activeTimers[timer.ordinal()])
			return;

		if (timer.isGpuTimer) {
			glQueryCounter(gpuQueries[timer.ordinal() * 2 + 1], GL_TIMESTAMP);
			// leave the GPU timer active, since it needs to be gathered at a later point
		} else {
			cumulativeError += errorCompensation >> 1;
			timings[timer.ordinal()] += System.nanoTime() - cumulativeError;
			activeTimers[timer.ordinal()] = false;
		}
	}

	public void add(Timer timer, long time) {
		if (isActive)
			timings[timer.ordinal()] += time;
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

		int[] available = { 0 };
		for (var timer : TIMERS) {
			int i = timer.ordinal();
			if (timer.isGpuTimer) {
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

		var frameTimings = new FrameTimings(frameEndTimestamp, timings);
		for (var listener : listeners)
			listener.onFrameCompletion(frameTimings);

		reset();
	}
}
