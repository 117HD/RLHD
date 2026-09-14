package rs117.hd.profiling;

import java.util.Arrays;

public class ProfileSample {

	public final long frameTimestamp;
	public final long[] timers;
	public final long[] allocations;
	public final Event[] events;
	public final long totalAsyncTime;
	public final float cpuLoad;
	public final long heapUsageKB;
	public final long freeSystemMemoryKB;
	public final long gpuUsageKB;

	public ProfileSample(long frameTimestamp, long[] timers, long[] allocations, Event[] events, int eventCount, float cpuLoad, long heapUsageKB, long freeSystemMemoryKB, long gpuUsageKB) {
		this.frameTimestamp = frameTimestamp;
		this.timers = Arrays.copyOf(timers, timers.length);
		this.allocations = Arrays.copyOf(allocations, allocations.length);
		this.events = eventCount > 0 ? Arrays.copyOf(events, eventCount) : null;
		this.totalAsyncTime = calculateTotalAsyncTime(timers);
		this.cpuLoad = cpuLoad;
		this.heapUsageKB = heapUsageKB;
		this.freeSystemMemoryKB = freeSystemMemoryKB;
		this.gpuUsageKB = gpuUsageKB;
	}

	private static long calculateTotalAsyncTime(long[] timers) {
		long asyncTime = 0;
		for(int i = 0; i < timers.length; i++)
			if(Timer.TIMERS[i].isAsyncCpuTimer())
				asyncTime += timers[i];
		return asyncTime;
	}
}
