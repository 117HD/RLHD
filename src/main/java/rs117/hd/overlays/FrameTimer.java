package rs117.hd.overlays;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Stack;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.*;

@Slf4j
@Singleton
public class FrameTimer {
	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	private final Timer[] timers = Timer.values();
	private final int numTimers = timers.length;
	private final int numGpuTimers = (int) Arrays.stream(timers).filter(t -> t.isGpuTimer).count();
	private final boolean[] activeTimers = new boolean[numTimers];
	private final long[] timings = new long[numTimers];
	private final int[] gpuQueries = new int[numTimers * 2];
	private final ArrayDeque<Listener> listeners = new ArrayDeque<>();
	private final Stack<Integer> glDebugGroupStack = new Stack<Integer>();

	private boolean isInactive = true;

	public long cumulativeError;
	public long errorCompensation;

	private void initialize() {
		clientThread.invokeLater(() -> {
			int[] queryNames = new int[numGpuTimers * 2];
			glGenQueries(queryNames);
			int queryIndex = 0;
			for (var timer : timers)
				if (timer.isGpuTimer)
					for (int j = 0; j < 2; ++j)
						gpuQueries[timer.ordinal() * 2 + j] = queryNames[queryIndex++];

			isInactive = false;
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
			isInactive = true;
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
		if (log.isDebugEnabled() && timer.isGpuTimer && timer.gpuDebugGroup && plugin.glCaps.OpenGL43) {
			glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, timer.ordinal(), timer.name);
			glDebugGroupStack.push(timer.ordinal());
			plugin.checkGLErrors();
		}

		if (isInactive)
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
		if (log.isDebugEnabled() && timer.isGpuTimer && timer.gpuDebugGroup && plugin.glCaps.OpenGL43) {
			if(!glDebugGroupStack.isEmpty() && glDebugGroupStack.peek() == timer.ordinal()) {
				glPopDebugGroup();
				glDebugGroupStack.pop();
			} else {
				log.warn("GPU Timer {} was popped out of order", timer.name);
			}
			plugin.checkGLErrors();
		}

		if (isInactive || !activeTimers[timer.ordinal()])
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
		if (isInactive)
			return;
		timings[timer.ordinal()] += time;
	}

	public void endFrameAndReset() {
		if (log.isDebugEnabled() &&  plugin.glCaps.OpenGL43) {
			for (int i = 0; i < glDebugGroupStack.size(); i++) {
				log.warn("GPU Group {} was never popped", timers[glDebugGroupStack.pop()].name);
				glPopDebugGroup();
			}
		}

		if (isInactive)
			return;

		long frameEnd = System.nanoTime();

		int[] available = { 0 };
		for (var timer : timers) {
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
					timings[i] += frameEnd;
				}
			}
		}

		var frameTimings = new FrameTimings(frameEnd, timings);
		for (var listener : listeners)
			listener.onFrameCompletion(frameTimings);

		reset();
	}
}
