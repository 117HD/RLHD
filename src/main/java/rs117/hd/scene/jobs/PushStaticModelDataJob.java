package rs117.hd.scene.jobs;

import java.util.HashSet;
import rs117.hd.data.StaticTileData;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Job;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

public class PushStaticModelDataJob extends Job {

	public SceneContext sceneContext;

	public GpuIntBuffer modelPassthroughBuffer;

	public int numPassthroughModels;

	public IGetRenderableBuffer getRenderableBuffer;

	private BuildVisibleTileListJob visibleTileListJob;

	private final int[] eightIntWrite = new int[8];

	private final HashSet<Integer> processedRenderables = new HashSet<>();

	public void setup(SceneContext inSceneContext, GpuIntBuffer inModelPassthroughBuffer, int inNumPassthroughModels, BuildVisibleTileListJob inVisibleTileListJob, IGetRenderableBuffer inGetRenderableBuffer) {
		complete();

		sceneContext = inSceneContext;
		numPassthroughModels = inNumPassthroughModels;
		modelPassthroughBuffer = inModelPassthroughBuffer;
		visibleTileListJob = inVisibleTileListJob;
		getRenderableBuffer = inGetRenderableBuffer;
	}

	@Override
	protected void doWork() {
		processedRenderables.clear();
		BuildVisibleTileListJob.VisibleTiles visibleTiles = visibleTileListJob.getResultUnsafe();
		for(int i = 0; i < visibleTiles.count; i++) {
			final int tileIdx = visibleTiles.indices[i];
			final StaticTileData tileData = sceneContext.staticTileData[tileIdx];

			int tileX = (tileData.tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
			int tileY = (tileData.tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;

			if(tileData.scenePaint_VertexCount > 0) {
				eightIntWrite[0] = tileData.scenePaint_VertexOffset;
				eightIntWrite[1] = tileData.scenePaint_UVOffset;
				eightIntWrite[2] = tileData.scenePaint_VertexCount / 3;
				eightIntWrite[3] = tileData.scenePaint_RenderBufferOffset;
				eightIntWrite[4] = 0;
				eightIntWrite[5] = tileX;
				eightIntWrite[6] = 0;
				eightIntWrite[7] = tileY;

				modelPassthroughBuffer.put(eightIntWrite);
				numPassthroughModels++;
			}

			if(tileData.tileModel_VertexCount > 0) {
				int vertexCount = tileData.tileModel_VertexCount >> 1;

				eightIntWrite[0] = tileData.tileModel_VertexOffset;
				eightIntWrite[1] = tileData.tileModel_UVOffset;
				eightIntWrite[2] = vertexCount / 3;
				eightIntWrite[3] = tileData.tileModel_RenderBufferOffset;
				eightIntWrite[4] = 0;
				eightIntWrite[5] = tileX;
				eightIntWrite[6] = 0;
				eightIntWrite[7] = tileY;

				modelPassthroughBuffer.put(eightIntWrite);
				numPassthroughModels++;
			}

			for(int renderableIdx : tileData.renderables) {
				if(!processedRenderables.add(renderableIdx)) {
					continue;
				}

				final StaticTileData.StaticRenderable renderable = sceneContext.staticRenderableData.get(renderableIdx);
				eightIntWrite[0] = renderable.vertexOffset;
				eightIntWrite[1] = renderable.uvOffset;
				eightIntWrite[2] = renderable.faceCount;
				eightIntWrite[3] = renderable.renderBufferOffset;
				eightIntWrite[4] = renderable.orientation | (renderable.hillskew ? 1 : 0) << 26 | tileData.plane << 24;
				eightIntWrite[5] = renderable.x;
				eightIntWrite[6] = renderable.z << 16 | renderable.height & 0xFFFF;
				eightIntWrite[7] = renderable.y;

				getRenderableBuffer.get(renderable.faceCount).put(eightIntWrite);
			}
		}
	}

	public interface IGetRenderableBuffer {
		public GpuIntBuffer get(int faces);
	}
}
