package rs117.hd.renderer.zone;

import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.jobs.Job;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;

@Slf4j
public final class ZoneUploadJob extends Job {
	private static final ConcurrentLinkedDeque<ZoneUploadJob> POOL = new ConcurrentLinkedDeque<>();
	private static final ThreadLocal<ZoneUploader> THREAD_LOCAL_SCENE_UPLOADER =
		ThreadLocal.withInitial(() -> getInjector().getInstance(ZoneUploader.class));

	private static class ZoneUploader extends SceneUploader {
		ZoneUploadJob job;

		@SneakyThrows
		@Override
		protected void onBeforeProcessTile(Tile t, boolean isEstimate) {
			job.workerHandleCancel();
		}
	}

	private WorldViewContext viewContext;
	private ZoneSceneContext sceneContext;

	Zone zone;
	int x, z;
	float delay;
	long frameNumber;
	boolean shouldUnmap;

	@Override
	protected void onRun() throws InterruptedException {
		final ZoneUploader sceneUploader = THREAD_LOCAL_SCENE_UPLOADER.get();
		try {
			workerHandleCancel();

			sceneUploader.job = this;
			sceneUploader.setScene(sceneContext.scene);
			sceneUploader.estimateZoneSize(sceneContext, zone, x, z);

			if (zone.sizeO > 0 || zone.sizeA > 0) {
				workerHandleCancel();

				invokeClientCallback(this::mapZoneVertexBuffers);
				workerHandleCancel();

				sceneUploader.uploadZone(sceneContext, zone, x, z);
				workerHandleCancel();

				if (shouldUnmap)
					invokeClientCallback(zone::unmap);
			}
			zone.initialized = true;
		} finally {
			sceneUploader.clear();
		}
	}

	private void mapZoneVertexBuffers() {
		try {
			VBO o = null, a = null;
			int sz = zone.sizeO * Zone.VERT_SIZE * 3;
			if (sz > 0) {
				o = new VBO(sz);
				o.initialize(GL_STATIC_DRAW);
				o.map();
			}

			sz = zone.sizeA * Zone.VERT_SIZE * 3;
			if (sz > 0) {
				a = new VBO(sz);
				a.initialize(GL_STATIC_DRAW);
				a.map();
			}

			zone.initialize(o, a, eboAlpha);
			zone.setMetadata(viewContext, sceneContext, x, z);
		} catch (Throwable ex) {
			log.warn(
				"Caught exception whilst processing zone [{}, {}] worldId [{}] group priority [{}] cancelling...\n",
				x,
				z,
				viewContext.worldViewId,
				isHighPriority(),
				ex
			);
			cancel();
		}
	}

	@Override
	protected void onCancel() {
		if (viewContext.zones[x][z] != zone)
			viewContext.pendingCull.add(zone);

		// Avoid holding a reference to the context after the job is done
		viewContext = null;
		sceneContext = null;
	}

	@Override
	protected void onReleased() {
		viewContext = null;
		sceneContext = null;
		frameNumber = 0;
		zone = null;
		delay = -1.0f;
		assert !POOL.contains(this) : "Task is already in pool";
		POOL.add(this);
	}

	public static ZoneUploadJob build(
		WorldViewContext viewContext,
		ZoneSceneContext sceneContext,
		Zone zone,
		boolean shouldUnmap,
		int x,
		int z
	) {
		assert viewContext != null : "WorldViewContext cant be null";
		assert sceneContext != null : "ZoneSceneContext cant be null";
		assert zone != null : "Zone cant be null";
		assert !zone.initialized : "Zone is already initialized";

		ZoneUploadJob newTask = POOL.poll();
		if (newTask == null)
			newTask = new ZoneUploadJob();
		newTask.viewContext = viewContext;
		newTask.sceneContext = sceneContext;
		newTask.zone = zone;
		newTask.frameNumber = viewContext.sceneManager.getFrameNumber();
		newTask.shouldUnmap = shouldUnmap;
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
