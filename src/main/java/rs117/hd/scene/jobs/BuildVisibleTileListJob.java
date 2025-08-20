package rs117.hd.scene.jobs;

import rs117.hd.scene.SceneCullingManager;
import rs117.hd.utils.Job;

import static net.runelite.api.Constants.*;
import static rs117.hd.scene.SceneCullingManager.VISIBILITY_HIDDEN;

public class BuildVisibleTileListJob extends Job {

	private final VisibleTiles visibleTiles = new VisibleTiles();

	private SceneCullingManager.CullingResults cullingResults;

	@Override
	protected void doWork() {
		final int maxTileCount = MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE;
		visibleTiles.count = 0;
		for (int tileIdx = 0; tileIdx < maxTileCount; tileIdx++) {
			if(cullingResults.getTileResult(tileIdx) > VISIBILITY_HIDDEN) {
				visibleTiles.indices[visibleTiles.count++] = tileIdx;
			}
		}
	}

	public void setCullingResults(SceneCullingManager.CullingResults inCullingResults) {
		complete();
		cullingResults = inCullingResults;
	}

	public VisibleTiles getResultUnsafe() {
		return visibleTiles;
	}

	public VisibleTiles getResult() {
		complete();
		return visibleTiles;
	}

	public static class VisibleTiles {
		public final int[] indices = new int[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
		public int count;
	}
}
