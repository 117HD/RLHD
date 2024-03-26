package rs117.hd.scene.lights;

import javax.annotation.Nullable;
import net.runelite.api.*;
import net.runelite.api.coords.*;

public class TileObjectImpostorTracker {
	public TileObject tileObject;
	public final long tileObjectHash;
	public boolean justSpawned = true;
	public boolean spawnedAnyLights;
	public int[] impostorIds;
	public int impostorVarbit = -1;
	public int impostorVarp = -1;
	public int impostorId = -1;

	public TileObjectImpostorTracker(TileObject tileObject) {
		this.tileObject = tileObject;
		this.tileObjectHash = tileObjectHash(tileObject);
	}

	public long lightHash(int impostorId) {
		long hash = this.tileObjectHash;
		hash = hash * 31 + impostorId;
		return hash;
	}

	private static long tileObjectHash(@Nullable TileObject tileObject) {
		if (tileObject == null)
			return 0;

		LocalPoint lp = tileObject.getLocalLocation();
		long hash = lp.getX();
		hash = hash * 31 + lp.getY();
		hash = hash * 31 + tileObject.getPlane();
		hash = hash * 31 + tileObject.getId();
		return hash;
	}
}
