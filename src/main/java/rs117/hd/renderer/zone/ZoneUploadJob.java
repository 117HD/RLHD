package rs117.hd.renderer.zone;

import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.jobs.Job;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;

@Slf4j
public final class ZoneUploadJob extends Job {
	private static final ConcurrentLinkedQueue<ZoneUploadJob> POOL = new ConcurrentLinkedQueue<>();

	private WorldViewContext viewContext;
	private ZoneSceneContext sceneContext;

	Zone zone;
	int x, z;
	float delay;
	boolean shouldUnmap;

	@Override
	protected void onRun() throws InterruptedException {
		try (SceneUploader sceneUploader = SceneUploader.POOL.acquire()) {
			workerHandleCancel();

			sceneUploader.onBeforeProcessTile = this::onBeforeProcessTile;
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
		}
	}

	private void onBeforeProcessTile(Tile t, boolean isEstimate) throws InterruptedException {
		workerHandleCancel();
	}

	private void mapZoneVertexBuffers() {
		try {
			GLBuffer o = null, a = null;
			int sz = zone.sizeO * Zone.VERT_SIZE * 3;
			if (sz > 0) {
				o = new GLBuffer("Zone::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
				o.initialize(sz);
				o.map(MAP_WRITE);
			}

			sz = zone.sizeA * Zone.VERT_SIZE * 3;
			if (sz > 0) {
				a = new GLBuffer("Zone::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
				a.initialize(sz);
				a.map(MAP_WRITE);
			}

			GLTextureBuffer f = null;
			sz = zone.sizeF * Zone.TEXTURE_SIZE;
			if (sz > 0) {
				f = new GLTextureBuffer("Textured Faces", GL_STATIC_DRAW);
				f.initialize(sz);
				f.map(MAP_WRITE);
			}

			zone.initialize(o, a, f, eboAlpha.id);
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
		zone.uploadJob = null;
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
