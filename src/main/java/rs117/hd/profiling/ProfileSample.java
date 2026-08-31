package rs117.hd.profiling;

import java.util.Arrays;

public class ProfileSample {

	public final long frameTimestamp;
	public final long[] timers;
	public final long[] allocations;
	public final Event[] events;
	public final float cpuLoad;
	public final long heapUsageKB;
	public final long freeSystemMemoryKB;
	public final long gpuUsageKB;

	public ProfileSample(long frameTimestamp, long[] timers, long[] allocations, Event[] events, int eventCount, float cpuLoad, long heapUsageKB, long freeSystemMemoryKB, long gpuUsageKB) {
		this.frameTimestamp = frameTimestamp;
		this.timers = Arrays.copyOf(timers, timers.length);
		this.allocations = Arrays.copyOf(allocations, allocations.length);
		this.events = eventCount > 0 ? Arrays.copyOf(events, eventCount) : null;
		this.cpuLoad = cpuLoad;
		this.heapUsageKB = heapUsageKB;
		this.freeSystemMemoryKB = freeSystemMemoryKB;
		this.gpuUsageKB = gpuUsageKB;
	}
}
