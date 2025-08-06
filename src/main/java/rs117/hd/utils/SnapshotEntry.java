package rs117.hd.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import rs117.hd.overlays.Timer;

public class SnapshotEntry
{
	public long elapsed;
	public long currentTime;
	public long drawnTiles;
	public long drawnStatic;
	public long drawnDynamic;
	public long npcCacheSize;
	public long[] timings;
	public String bottleneck;
	public Double estimatedFps;
	public long cpuTime;
	public long gpuTime;
	public Map<String, Long> timingMap;
	public long memoryUsed;
	public long memoryTotal;
	public long memoryFree;
	public long memoryMax;

	public SnapshotEntry() {}

	public SnapshotEntry(
		long timestamp, long currentTime,
		long drawnTiles, long drawnStatic, long drawnDynamic,
		long npcCacheSize, long[] timings
	)
	{
		this.elapsed = timestamp;
		this.currentTime = currentTime;
		this.drawnTiles = drawnTiles;
		this.drawnStatic = drawnStatic;
		this.drawnDynamic = drawnDynamic;
		this.npcCacheSize = npcCacheSize;
		this.timings = timings.clone();
		this.cpuTime = timings[Timer.DRAW_FRAME.ordinal()];
		this.gpuTime = timings[Timer.RENDER_FRAME.ordinal()];

		this.estimatedFps = 1e9 / Math.max(cpuTime, gpuTime);
		this.bottleneck = cpuTime > gpuTime ? "CPU" : "GPU";

		this.timingMap = new LinkedHashMap<>();
		for (Timer t : Timer.values()) {
			this.timingMap.put(t.name(), this.timings[t.ordinal()]);
		}

		Runtime rt = Runtime.getRuntime();
		this.memoryTotal = rt.totalMemory();
		this.memoryFree  = rt.freeMemory();
		this.memoryMax   = rt.maxMemory();
		this.memoryUsed  = memoryTotal - memoryFree;

	}
}