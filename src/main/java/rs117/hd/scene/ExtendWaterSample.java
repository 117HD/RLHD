package rs117.hd.scene;

import java.util.HashMap;
import rs117.hd.scene.water_types.WaterType;

public class ExtendWaterSample {
	public final WaterType waterType;
	public final int plane;
	public final int flatHeight;
	public final int flatUnderwaterHeight;
	public final HashMap<Long, Integer> boundarySurfaceHeights;
	public final HashMap<Long, Integer> boundaryUnderwaterHeights;

	public ExtendWaterSample(
		WaterType waterType,
		int plane,
		int flatHeight,
		int flatUnderwaterHeight,
		HashMap<Long, Integer> boundarySurfaceHeights,
		HashMap<Long, Integer> boundaryUnderwaterHeights
	) {
		this.waterType = waterType;
		this.plane = plane;
		this.flatHeight = flatHeight;
		this.flatUnderwaterHeight = flatUnderwaterHeight;
		this.boundarySurfaceHeights = boundarySurfaceHeights;
		this.boundaryUnderwaterHeights = boundaryUnderwaterHeights;
	}

	public static long packWorldVertex(int worldX, int worldY) {
		return ((long) worldX << 32) | (worldY & 0xffffffffL);
	}
}
