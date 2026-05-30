package rs117.hd.overlays;

import java.util.Arrays;

public class FrameTimings {
	public final long frameTimestamp;
	public final long[] timers;
	public final long[] allocations;
	public final float cpuLoad;

	public FrameTimings(long frameTimestamp, long[] timers, long[] allocations, float cpuLoad) {
		this.frameTimestamp = frameTimestamp;
		this.timers = Arrays.copyOf(timers, timers.length);
		this.allocations = Arrays.copyOf(allocations, allocations.length);
		this.cpuLoad = cpuLoad;
	}
}
