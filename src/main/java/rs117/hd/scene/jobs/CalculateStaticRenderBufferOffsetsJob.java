package rs117.hd.scene.jobs;

import java.util.HashSet;
import rs117.hd.data.StaticTileData;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Job;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;

public class CalculateStaticRenderBufferOffsetsJob extends Job {

	public SceneContext sceneContext;

	public int renderBufferOffset;
	public int renderableCount;

	public BuildVisibleTileListJob visibleTileListJob;

	private final HashSet<Integer> processedRenderables = new HashSet<>();

	public void setup(SceneContext inSceneContext, BuildVisibleTileListJob inVisibleTileListJob, int inRenderBufferOffset) {
		complete();
		sceneContext = inSceneContext;
		visibleTileListJob = inVisibleTileListJob;
		renderBufferOffset = inRenderBufferOffset;
	}

	@Override
	protected void doWork() {
		final int MAX_TILE_COUNT = MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE;
		for(int tileIdx = 0; tileIdx < MAX_TILE_COUNT; tileIdx++) {
			sceneContext.staticTileData[tileIdx].scenePaint_RenderBufferOffset = -1;
			sceneContext.staticTileData[tileIdx].tileModel_RenderBufferOffset = -1;
		}

		for(StaticTileData.StaticRenderable renderable : sceneContext.staticRenderableData) {
			renderable.renderBufferOffset = -1;
		}

		processedRenderables.clear();

		visibleTileListJob.wait(true);

		BuildVisibleTileListJob.VisibleTiles visibleTiles = visibleTileListJob.getResultUnsafe();
		for(int i = 0; i < visibleTiles.count; i++) {
			final int tileIdx = visibleTiles.indices[i];
			final StaticTileData tileData = sceneContext.staticTileData[tileIdx];

			if(tileData.scenePaint_VertexCount > 0) {
				tileData.scenePaint_RenderBufferOffset = renderBufferOffset;
				renderBufferOffset += tileData.scenePaint_VertexCount;
			}

			if(tileData.tileModel_VertexCount > 0) {
				int vertexCount = tileData.tileModel_VertexCount >> 1;
				tileData.tileModel_RenderBufferOffset = renderBufferOffset;
				renderBufferOffset += vertexCount;
			}

			for(int renderableIdx : tileData.renderables) {
				if(!processedRenderables.add(renderableIdx)) {
					continue;
				}

				final StaticTileData.StaticRenderable renderable = sceneContext.staticRenderableData.get(renderableIdx);
				renderable.renderBufferOffset = renderBufferOffset;
				renderBufferOffset += renderable.faceCount * 3;
				renderableCount++;
			}
		}
	}
}
