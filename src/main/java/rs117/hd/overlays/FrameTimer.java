package rs117.hd.overlays;

import java.util.ArrayDeque;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
@Singleton
public class FrameTimer {
	@Inject
	private ClientThread clientThread;

	private final int numTimers = Timer.values().length;
	private final int numGpuTimers = (int) Arrays.stream(Timer.values()).filter(t -> t.isGpuTimer).count();
	private final boolean[] activeTimers = new boolean[numTimers];
	private final long[] timings = new long[numTimers];
	private final int[] gpuQueries = new int[numTimers * 2];
	private final ArrayDeque<Listener> listeners = new ArrayDeque<>();

	private boolean isInactive = true;
	private long cumulativeError = 0;

	private void initialize() {
		clientThread.invokeLater(() -> {
			int[] queryNames = new int[numGpuTimers * 2];
			glGenQueries(queryNames);
			int queryIndex = 0;
			for (var timer : Timer.values())
				if (timer.isGpuTimer)
					for (int j = 0; j < 2; ++j)
						gpuQueries[timer.ordinal() * 2 + j] = queryNames[queryIndex++];
			isInactive = false;
		});
	}

	private void destroy() {
		isInactive = true;
		clientThread.invokeLater(() -> {
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
		if (listeners.size() == 0)
			initialize();
		listeners.add(listener);
	}

	public void removeTimingsListener(Listener listener) {
		listeners.remove(listener);
		if (listeners.size() == 0)
			destroy();
	}

	public void removeAllListeners() {
		listeners.clear();
		destroy();
	}

	private void reset() {
		Arrays.fill(timings, 0);
		Arrays.fill(activeTimers, false);
	}

	public void begin(Timer timer) {
		if (isInactive)
			return;

		if (timer.isGpuTimer) {
			if (activeTimers[timer.ordinal()])
				throw new UnsupportedOperationException("Cumulative GPU timing isn't supported");
			glQueryCounter(gpuQueries[timer.ordinal() * 2], GL_TIMESTAMP);
		} else if (!activeTimers[timer.ordinal()]) {
			timings[timer.ordinal()] -= System.nanoTime() - cumulativeError;
		}
		activeTimers[timer.ordinal()] = true;
	}

	public void end(Timer timer) {
		if (isInactive || !activeTimers[timer.ordinal()])
			return;

		if (timer.isGpuTimer) {
			glQueryCounter(gpuQueries[timer.ordinal() * 2 + 1], GL_TIMESTAMP);
			// leave the GPU timer active, since it needs to be gathered at a later point
		} else {
			cumulativeError += 17; // compensate slightly for the timer's own overhead
			timings[timer.ordinal()] += System.nanoTime() - cumulativeError;
			activeTimers[timer.ordinal()] = false;
		}
	}

	public void endFrameAndReset() {
		if (isInactive)
			return;

		long frameEnd = System.nanoTime();

		int[] available = { 0 };
		for (var timer : Timer.values()) {
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
