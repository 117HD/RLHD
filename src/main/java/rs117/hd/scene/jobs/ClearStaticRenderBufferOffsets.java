package rs117.hd.scene.jobs;

import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Job;

import static net.runelite.api.Constants.*;

public class ClearStaticRenderBufferOffsets extends Job {

	public SceneContext sceneContext;

	@Override
	protected void doWork() {
		final int MAX_TILE_COUNT = MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE;
		for (int tileIdx = 0; tileIdx < MAX_TILE_COUNT; tileIdx++) {
			sceneContext.staticTileData[tileIdx].reset();
		}
	}
}
