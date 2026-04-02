package rs117.hd.renderer.zone;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.Job;

@Slf4j
public final class ZoneUploadJob extends Job {
	public static final ConcurrentPool<ZoneUploadJob> POOL = new ConcurrentPool<>(ZoneUploadJob::new);

	private WorldViewContext viewContext;
	private ZoneSceneContext sceneContext;
	private GpuIntBuffer opaqueStaging;
	private GpuIntBuffer alphaStaging;
	private GpuIntBuffer tboStaging;

	@Getter
	private Zone zone;
	@Getter
	private int x, z;
	@Getter
	private long revealAfterTimestampMs;

	@Override
	protected void onRun() throws InterruptedException {
		try (SceneUploader sceneUploader = SceneUploader.POOL.acquire()) {
			workerHandleCancel();

			opaqueStaging = GpuIntBuffer.POOL.acquire();
			alphaStaging = GpuIntBuffer.POOL.acquire();
			tboStaging = GpuIntBuffer.POOL.acquire();

			sceneUploader.onBeforeProcessTile = this::onBeforeProcessTile;
			sceneUploader.setScene(sceneContext.scene);
			sceneUploader.uploadZone(sceneContext, zone, x, z, opaqueStaging, alphaStaging, tboStaging);
		}
	}

	private void onBeforeProcessTile(Tile t) throws InterruptedException {
		workerHandleCancel();
	}

	@Override
	protected void onCompletion() {
		if(opaqueStaging == null || alphaStaging == null || tboStaging == null)
			return;

		zone.sizeIntsOpaque = opaqueStaging.position();
		zone.sizeIntsAlpha = alphaStaging.position();
		zone.sizeIntsFace = tboStaging.position();

		if(zone.sizeIntsOpaque <= 0 && zone.sizeIntsAlpha <= 0 && zone.sizeIntsFace <= 0)
			return;

		opaqueStaging.flip();
		alphaStaging.flip();
		tboStaging.flip();

		zone.initialize(viewContext, sceneContext, x, z, opaqueStaging, alphaStaging, tboStaging);
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
		if(opaqueStaging != null)
			GpuIntBuffer.POOL.recycle(opaqueStaging.clear());
		opaqueStaging = null;

		if(alphaStaging != null)
			GpuIntBuffer.POOL.recycle(alphaStaging.clear());
		alphaStaging = null;

		if(tboStaging != null)
			GpuIntBuffer.POOL.recycle(tboStaging.clear());
		tboStaging = null;

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
		return build(viewContext, sceneContext, zone, x, z, 0);
	}

	public static ZoneUploadJob build(
		WorldViewContext viewContext,
		ZoneSceneContext sceneContext,
		Zone zone,
		int x,
		int z,
		long revealAfterTimestampMs
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
		newTask.revealAfterTimestampMs = revealAfterTimestampMs;
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
