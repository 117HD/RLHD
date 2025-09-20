package rs117.hd.scene.jobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import rs117.hd.data.DynamicRenderableInstance;
import rs117.hd.data.StaticRenderableInstance;
import rs117.hd.data.StaticTileData;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneCullingManager;
import rs117.hd.utils.Job;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

public class PushStaticModelDataJob extends Job {

	public SceneContext sceneContext;

	public GpuIntBuffer modelPassthroughBuffer;

	public IGetRenderableBuffer getRenderableBuffer;

	private SceneCullingManager.CullingResults cullingResults;

	private final List<DynamicRenderableInstance> dynamicInstances = new ArrayList<>();

	private final boolean isStaticPass;
	private final int[] eightIntWrite = new int[8];

	public PushStaticModelDataJob(boolean staticPass, IGetRenderableBuffer inGetRenderableBuffer) {
		isStaticPass = staticPass;
		getRenderableBuffer = inGetRenderableBuffer;
	}

	public void setup(
		SceneContext inSceneContext,
		GpuIntBuffer inModelPassthroughBuffer,
		SceneCullingManager.CullingResults inCullingResults
	) {
		complete();

		sceneContext = inSceneContext;
		modelPassthroughBuffer = inModelPassthroughBuffer;
		cullingResults = inCullingResults;

		dynamicInstances.clear();
	}

	public void clearDynamicInstances(ArrayDeque<DynamicRenderableInstance> inDynamicInstancesPool) {
		for (int i = 0; i < dynamicInstances.size(); i++) {
			var instance = dynamicInstances.get(i);
			instance.renderableBuffer.reset();
			inDynamicInstancesPool.add(instance);
		}
		dynamicInstances.clear();
	}

	public boolean hasDynamicInstancesToPush() {
		return !dynamicInstances.isEmpty();
	}

	public void appendDynamicInstance(DynamicRenderableInstance instance) {
		dynamicInstances.add(instance);
	}

	@SneakyThrows
	@Override
	protected void doWork() {
		if(isStaticPass) {
			for (int i = 0; i < cullingResults.getNumVisibleTiles(); i++) {
				final int tileIdx = cullingResults.getVisibleTile(i);
				final StaticTileData tileData = sceneContext.staticTileData[tileIdx];

				eightIntWrite[4] = 0;
				eightIntWrite[5] = (tileData.tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
				eightIntWrite[6] = 0;
				eightIntWrite[7] = (tileData.tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;

				if (tileData.paintBuffer != null) {
					if (tileData.paintBuffer.push(eightIntWrite)) {
						modelPassthroughBuffer.put(eightIntWrite);
					}
				}

				if (tileData.modelBuffer != null) {
					if (tileData.modelBuffer.push(eightIntWrite)) {
						modelPassthroughBuffer.put(eightIntWrite);
					}
				}

				if (tileData.underwaterBuffer != null) {
					if (tileData.underwaterBuffer.push(eightIntWrite)) {
						modelPassthroughBuffer.put(eightIntWrite);
					}
				}

				for (StaticRenderableInstance staticRenderableInstance : tileData.renderables) {
					if (staticRenderableInstance.renderableBuffer.push(eightIntWrite)) {
						eightIntWrite[4] =
							staticRenderableInstance.orientation |
							(staticRenderableInstance.renderable.hillskew ? 1 : 0) << 26 |
							tileData.plane << 24;
						eightIntWrite[5] = staticRenderableInstance.x;
						eightIntWrite[6] = staticRenderableInstance.y << 16 |
										   staticRenderableInstance.renderable.height & 0xFFFF;
						eightIntWrite[7] = staticRenderableInstance.z;

						getRenderableBuffer.get(staticRenderableInstance.renderable.faceCount).put(eightIntWrite);
					}
				}
			}
		} else {
			for (int i = 0; i < dynamicInstances.size(); i++) {
				var dynamicInstance = dynamicInstances.get(i);
				if (dynamicInstance.renderableBuffer.push(eightIntWrite)) {
					eightIntWrite[4] = dynamicInstance.orientation;
					eightIntWrite[5] = dynamicInstance.x;
					eightIntWrite[6] = dynamicInstance.y << 16 |
									   dynamicInstance.height & 0xFFFF;
					eightIntWrite[7] = dynamicInstance.z;

					getRenderableBuffer.get(dynamicInstance.renderableBuffer.vertexCount / 3).put(eightIntWrite);
				}
			}
		}
	}

	public interface IGetRenderableBuffer {
		GpuIntBuffer get(int faces);
	}
}
