package rs117.hd.overlays;

import java.util.Arrays;

public class FrameTimings {
	public final long frameTimestamp;
	public final long[] timers;
	public final float cpuLoad;

	public FrameTimings(long frameTimestamp, long[] timers, float cpuLoad) {
		this.frameTimestamp = frameTimestamp;
		this.timers = Arrays.copyOf(timers, timers.length);
		this.cpuLoad = cpuLoad;
	}
}
