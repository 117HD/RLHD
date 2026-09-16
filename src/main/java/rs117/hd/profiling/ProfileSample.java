package rs117.hd.profiling;

import java.util.Arrays;

public class ProfileSample {
	public final long frameTimestamp;
	public final long[] timers = new long[Timer.TIMERS.length];
	public final long[] allocations = new long[Timer.TIMERS.length];
	public final Event[] events;
	public final long totalAsyncTime;
	public final float cpuLoad;
	public final long heapUsageKB;
	public final long freeSystemMemoryKB;
	public final long gpuUsageKB;

	public ProfileSample(long frameTimestamp, long totalAsyncTime, Event[] events, int eventCount, float cpuLoad, long heapUsageKB, long freeSystemMemoryKB, long gpuUsageKB) {
		this.frameTimestamp = frameTimestamp;
		this.totalAsyncTime = totalAsyncTime;
		this.events = eventCount > 0 ? Arrays.copyOf(events, eventCount) : null;
		this.cpuLoad = cpuLoad;
		this.heapUsageKB = heapUsageKB;
		this.freeSystemMemoryKB = freeSystemMemoryKB;
		this.gpuUsageKB = gpuUsageKB;
	}
}
