package rs117.hd.overlays;

import java.util.Arrays;

public class FrameTimings {
	public final long frameTimestamp;
	public final long[] timers;

	public FrameTimings(long frameTimestamp, long[] timers) {
		this.frameTimestamp = frameTimestamp;
		this.timers = Arrays.copyOf(timers, timers.length);
	}
}
