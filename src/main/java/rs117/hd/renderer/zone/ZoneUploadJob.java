package rs117.hd.renderer.zone;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.profiling.Profiler;
import rs117.hd.profiling.Timer;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.Job;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;

@Slf4j
public final class ZoneUploadJob extends Job {
	private static final ConcurrentPool<ZoneUploadJob> POOL = new ConcurrentPool<>(ZoneUploadJob::new);

	private WorldViewContext viewContext;
	private ZoneSceneContext sceneContext;

	Zone zoneToBeReplaced;
	Zone zoneBeingUploaded;
	int x, z;
	long revealAfterTimestampMs;
	boolean shouldUnmap;

	@Override
	protected void onRun() throws InterruptedException {
		final long start = System.nanoTime();
		try (SceneUploader sceneUploader = SceneUploader.POOL.acquire()) {
			workerHandleCancel();

			sceneUploader.onBeforeProcessTile = this::onBeforeProcessTile;
			sceneUploader.setScene(sceneContext.scene);
			sceneUploader.estimateZoneSize(sceneContext, zoneBeingUploaded, x, z);

			if (zoneBeingUploaded.sizeO > 0 || zoneBeingUploaded.sizeA > 0) {
				workerHandleCancel();

				invokeClientCallback(this::mapZoneVertexBuffers);
				workerHandleCancel();

				sceneUploader.uploadZone(sceneContext, zoneBeingUploaded, x, z);
				workerHandleCancel();

				if (shouldUnmap)
					invokeClientCallback(zoneBeingUploaded::unmap);
			}
			zoneBeingUploaded.initialized = true;
		} finally {
			if(Profiler.isActive())
				Profiler.getInstance().add(Timer.ZONE_UPLOAD, start);
		}
	}

	private void onBeforeProcessTile(Tile t, boolean isEstimate) throws InterruptedException {
		workerHandleCancel();
	}

	private void mapZoneVertexBuffers() {
		try {
			GLBuffer o = null, a = null;
			int sz = zoneBeingUploaded.sizeO * Zone.VERT_SIZE * 3;
			if (sz > 0) {
				o = new GLBuffer("Zone::VBO::Opaque", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
				o.initialize(sz);
				o.map(MAP_WRITE);
			}

			sz = zoneBeingUploaded.sizeA * Zone.VERT_SIZE * 3;
			if (sz > 0) {
				a = new GLBuffer("Zone::VBO::Alpha", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
				a.initialize(sz);
				a.map(MAP_WRITE);
			}

			GLTextureBuffer f = null;
			sz = zoneBeingUploaded.sizeF * Zone.TEXTURE_SIZE;
			if (sz > 0) {
				f = new GLTextureBuffer("Zone::TBO", GL_STATIC_DRAW);
				f.initialize(sz);
				f.map(MAP_WRITE);
			}

			zoneBeingUploaded.initialize(o, a, f);
			zoneBeingUploaded.setMetadata(viewContext, sceneContext, x, z);
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
		if (viewContext.zones[x][z] != zoneBeingUploaded)
			DestructibleHandler.queueDestruction(zoneBeingUploaded);

		// Avoid holding a reference to the context after the job is done
		viewContext = null;
		sceneContext = null;
	}

	@Override
	protected void onReleased() {
		viewContext = null;
		sceneContext = null;
		if (zoneToBeReplaced != null && zoneToBeReplaced.uploadJob == this)
			zoneToBeReplaced.uploadJob = null;
		zoneToBeReplaced = null;
		if (zoneBeingUploaded != null && zoneBeingUploaded.uploadJob == this)
			zoneBeingUploaded.uploadJob = null;
		zoneBeingUploaded = null;
		revealAfterTimestampMs = 0;
		POOL.recycle(this);
	}

	public static ZoneUploadJob build(
		WorldViewContext viewContext,
		ZoneSceneContext sceneContext,
		Zone uploadZone,
		boolean shouldUnmap,
		int x,
		int z
	) {
		assert viewContext != null : "WorldViewContext cant be null";
		assert sceneContext != null : "ZoneSceneContext cant be null";
		assert uploadZone != null : "Zone cant be null";
		assert !uploadZone.initialized : "Zone is already initialized";

		ZoneUploadJob newTask = POOL.acquire();
		newTask.viewContext = viewContext;
		newTask.sceneContext = sceneContext;
		newTask.zoneBeingUploaded = uploadZone;
		newTask.shouldUnmap = shouldUnmap;
		newTask.x = x;
		newTask.z = z;
		newTask.zoneToBeReplaced = null;
		newTask.resetReleased();

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
		log.trace("ZoneUploadJob finalized, it should have been pooled? - {}", this);
	}
}
