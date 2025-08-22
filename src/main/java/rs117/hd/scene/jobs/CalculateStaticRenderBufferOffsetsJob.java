package rs117.hd.scene.jobs;

import rs117.hd.data.StaticRenderableInstance;
import rs117.hd.data.StaticTileData;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneCullingManager;
import rs117.hd.utils.Job;

public class CalculateStaticRenderBufferOffsetsJob extends Job {

	public SceneContext sceneContext;

	public int renderBufferOffset;
	public int numPassthroughModels;
	public int numRenderables;

	public SceneCullingManager.CullingResults cullingResults;

	public void setup(
		SceneContext inSceneContext,
		SceneCullingManager.CullingResults inCullingResults,
		int inRenderBufferOffset
	) {
		complete();

		sceneContext = inSceneContext;
		cullingResults = inCullingResults;
		renderBufferOffset = inRenderBufferOffset;
	}

	@Override
	protected void doWork() {
		numPassthroughModels = 0;
		numRenderables = 0;

		for(int i = 0; i < cullingResults.getNumVisibleTiles(); i++) {
			final int tileIdx = cullingResults.getVisibleTile(i);
			final StaticTileData tileData = sceneContext.staticTileData[tileIdx];

			if (tileData.paintBuffer != null) {
				renderBufferOffset = tileData.paintBuffer.appendToRenderBuffer(renderBufferOffset);
				numPassthroughModels++;
			}

			if (tileData.modelBuffer != null) {
				renderBufferOffset = tileData.modelBuffer.appendToRenderBuffer(renderBufferOffset);
				numPassthroughModels++;
			}

			if (tileData.underwaterBuffer != null) {
				renderBufferOffset = tileData.underwaterBuffer.appendToRenderBuffer(renderBufferOffset);
				numPassthroughModels++;
			}

			for (StaticRenderableInstance instance : tileData.renderables) {
				if (instance.renderableBuffer == null || instance.renderableBuffer.renderBufferOffset >= 0) {
					continue;
				}

				renderBufferOffset = instance.renderableBuffer.appendToRenderBuffer(renderBufferOffset);
				numRenderables++;
			}
		}
	}
}
