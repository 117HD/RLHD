package rs117.hd.tooling.overlays;

import java.util.Arrays;

public class FrameTimings {
	final long frameTimestamp;
	final long[] timers;

	public FrameTimings(long frameTimestamp, long[] timers) {
		this.frameTimestamp = frameTimestamp;
		this.timers = Arrays.copyOf(timers, timers.length);
	}
}
