package rs117.hd.renderer.zone.jobs;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Constants.*;

@Slf4j
public class RoofChangesJob extends Job {

	public Scene prev;
	public Scene scene;

	public final boolean[] mismatched = new boolean[EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
	public final Map<Integer, Integer> result = new HashMap<>();

	@Override
	protected void onRun() {
		// Calculate roof ids for the zone
		final int[][][] prids = prev.getRoofs();
		final int[][][] nrids = scene.getRoofs();

		final int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		final int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		result.clear();
		Arrays.fill(mismatched, false);
		for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
			for (int z = 0; z < EXTENDED_SCENE_SIZE; ++z) {
				int ox = x + (dx << 3);
				int oz = z + (dy << 3);

				for (int level = 0; level < 4; ++level) {
					// old zone still in scene?
					if (ox >= 0 && oz >= 0 && ox < EXTENDED_SCENE_SIZE && oz < EXTENDED_SCENE_SIZE) {
						int prid = prids[level][ox][oz];
						int nrid = nrids[level][x][z];
						if (prid > 0 && nrid > 0 && prid != nrid) {
							Integer old = result.putIfAbsent(prid, nrid);
							if (old == null) {
								log.trace("Roof change: {} -> {}", prid, nrid);
							} else if (old != nrid) {
								log.debug("Roof change mismatch: {} -> {} vs {}", prid, nrid, old);
								mismatched[x * EXTENDED_SCENE_SIZE + z] = true;
							}
						}
					}
				}
			}
		}
	}

	public boolean doesZoneHaveRoofMismatch(int x, int z) {
		return mismatched[x * EXTENDED_SCENE_SIZE + z];
	}
}
