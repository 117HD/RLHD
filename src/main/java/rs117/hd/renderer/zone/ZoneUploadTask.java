package rs117.hd.renderer.zone;

import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.jobs.JobWork;

import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;

@Slf4j
public final class ZoneUploadTask extends JobWork {
	private static final ConcurrentLinkedDeque<ZoneUploadTask> POOL = new ConcurrentLinkedDeque<>();

	private static final ThreadLocal<SceneUploader> SCENE_UPLOADER_THREAD_LOCAL = ThreadLocal.withInitial(() -> getInjector().getInstance(SceneUploader.class));

	WorldViewContext viewContext;
	ZoneSceneContext sceneContext;
	Zone zone;
	int x, z;
	float delay;

	@Override
	protected void onRun() throws InterruptedException {
		final SceneUploader sceneUploader = SCENE_UPLOADER_THREAD_LOCAL.get();
		workerHandleCancel();

		sceneUploader.setScene(sceneContext.scene);
		sceneUploader.setCurrentWork(this);
		sceneUploader.estimateZoneSize(sceneContext, zone, x, z);

		if(zone.sizeO > 0 || zone.sizeA > 0) {
			workerHandleCancel();

			queueClientCallback(isHighPriority(), this::mapZoneVertexBuffers);
			workerHandleCancel();

			sceneUploader.uploadZone(sceneContext, zone, x, z);
			workerHandleCancel();

			queueClientCallback(isHighPriority(), this::unmapZoneVertexBuffers);
		} else {
			// The zone should not be left uninitialized, as this will prevent drawing anything within it
			zone.initialized = true;
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

	private void unmapZoneVertexBuffers() {
		zone.unmap();
		zone.initialized = true;
	}

	@Override
	protected void onCancel() {
		if(viewContext.zones[x][z] != zone)
			viewContext.pendingCull.add(zone);
	}

	@Override
	protected void onReleased() {
		viewContext = null;
		sceneContext = null;
		zone = null;
		delay = -1.0f;
		assert !POOL.contains(this) : "Task is already in pool";
		POOL.add(this);
	}

	public static ZoneUploadTask build(WorldViewContext viewContext, ZoneSceneContext sceneContext, Zone zone, int x, int z) {
		assert viewContext != null : "WorldViewContext cant be null";
		assert sceneContext != null : "ZoneSceneContext cant be null";
		assert zone != null : "Zone cant be null";
		assert !zone.initialized : "Zone is already initialized";

		ZoneUploadTask newTask = POOL.poll();
		if (newTask == null)
			newTask = new ZoneUploadTask();
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
		return super.toString() + " worldViewId: [" + (viewContext != null ? viewContext.worldViewId : "null") + "] X: [" + x + "] Z: [" + z + "]";
	}
}
