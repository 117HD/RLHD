package rs117.hd.renderer.zone;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.Job;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public final class ZoneUploadJob extends Job {
	public static final ConcurrentPool<ZoneUploadJob> POOL = new ConcurrentPool<>(ZoneUploadJob::new);

	private WorldViewContext viewContext;
	private ZoneSceneContext sceneContext;

	Zone zone;
	int x, z;
	long revealAfterTimestampMs;

	@Override
	protected void onRun() throws InterruptedException {
		try (SceneUploader sceneUploader = SceneUploader.POOL.acquire()) {
			workerHandleCancel();

			sceneUploader.onBeforeProcessTile = this::onBeforeProcessTile;
			sceneUploader.setScene(sceneContext.scene);

			try (
				GpuIntBuffer opaqueStaging = GpuIntBuffer.POOL.acquire();
				GpuIntBuffer alphaStaging = GpuIntBuffer.POOL.acquire();
				GpuIntBuffer tboStaging = GpuIntBuffer.POOL.acquire()
			) {
				sceneUploader.uploadZone(sceneContext, zone, x, z, opaqueStaging, alphaStaging, tboStaging);

				zone.sizeIntsOpaque = opaqueStaging.position();
				zone.sizeIntsAlpha = alphaStaging.position();
				zone.sizeIntsFace = tboStaging.position();

				opaqueStaging.flip();
				alphaStaging.flip();
				tboStaging.flip();

				workerHandleCancel();

				if(zone.sizeIntsOpaque > 0 || zone.sizeIntsAlpha > 0) {
					invokeClientCallback(() -> {
						long start = System.nanoTime();
						GLBuffer o = null, a = null;
						if (zone.sizeIntsOpaque > 0) {
							o = new GLBuffer("Zone::VBO::Opaque", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
							o.initialize(opaqueStaging.getBuffer());
						}

						if (zone.sizeIntsAlpha > 0) {
							a = new GLBuffer("Zone::VBO::Alpha", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
							a.initialize(alphaStaging.getBuffer());
						}

						GLTextureBuffer f = null;
						if (zone.sizeIntsFace > 0) {
							f = new GLTextureBuffer("Zone::TBO", GL_STATIC_DRAW);
							f.initialize(tboStaging.getBuffer());
						}

						zone.initialize(o, a, f);
						zone.setMetadata(viewContext, sceneContext, x, z);
						viewContext.bufferInit += System.nanoTime() - start;
					});
				}
			}

			for(int i = 0; i < zone.alphaModels.size(); i++) {
				final Zone.AlphaModel m = zone.alphaModels.get(i);
				m.vao = zone.glVaoA;
				m.tboF = zone.tboF.getTexId();
			}
			zone.initialized = true;
		}
	}

	private void onBeforeProcessTile(Tile t) throws InterruptedException {
		workerHandleCancel();
	}

	@Override
	protected void onCancel() {
		if (viewContext.zones[x][z] != zone)
			DestructibleHandler.queueDestruction(zone);

		// Avoid holding a reference to the context after the job is done
		viewContext = null;
		sceneContext = null;
	}

	@Override
	protected void onReleased() {
		viewContext = null;
		sceneContext = null;
		zone.uploadJob = null;
		zone = null;
		revealAfterTimestampMs = 0;
		POOL.recycle(this);
	}

	public static ZoneUploadJob build(
		WorldViewContext viewContext,
		ZoneSceneContext sceneContext,
		Zone zone,
		int x,
		int z
	) {
		assert viewContext != null : "WorldViewContext cant be null";
		assert sceneContext != null : "ZoneSceneContext cant be null";
		assert zone != null : "Zone cant be null";
		assert !zone.initialized : "Zone is already initialized";

		ZoneUploadJob newTask = POOL.acquire();
		newTask.viewContext = viewContext;
		newTask.sceneContext = sceneContext;
		newTask.zone = zone;
		newTask.x = x;
		newTask.z = z;
		newTask.isReleased = false;

		return newTask;
	}

	@Override
	public String toString() {
		return String.format(
			"%s: worldViewId=%s, x=%d, z=%d",
			super.toString(),
			viewContext != null ? viewContext.worldViewId : "null",
			x, z
		);
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		log.debug("ZoneUploadJob finalized, it should have been pooled? - {}", this);
	}
}
