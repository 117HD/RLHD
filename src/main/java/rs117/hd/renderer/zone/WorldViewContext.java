package rs117.hd.renderer.zone;

import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.opengl.buffer.uniforms.UBOWorldViews;
import rs117.hd.utils.jobs.JobGroup;

import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
@Slf4j
public class WorldViewContext {
	final int worldViewId;
	final int sizeX, sizeZ;
	@Nullable
	UBOWorldViews.WorldViewStruct uboWorldViewStruct;
	SceneManager sceneManager;
	ZoneSceneContext sceneContext;
	Zone[][] zones;
	boolean isLoading = true;

	int minLevel, level, maxLevel;
	Set<Integer> hideRoofIds;

	public long loadTime;
	public long uploadTime;
	public long sceneSwapTime;

	final LinkedBlockingDeque<Zone> pendingCull = new LinkedBlockingDeque<>();
	final JobGroup<ZoneUploadJob> sceneLoadGroup = new JobGroup<>(true, false);
	final JobGroup<ZoneUploadJob> streamingGroup = new JobGroup<>(false, false);

	WorldViewContext(
		SceneManager sceneManager,
		@Nullable WorldView worldView,
		@Nullable ZoneSceneContext sceneContext,
		UBOWorldViews uboWorldViews
	) {
		this.sceneManager = sceneManager;
		this.worldViewId = worldView == null ? -1 : worldView.getId();
		this.sceneContext = sceneContext;
		this.sizeX = worldView == null ? NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? NUM_ZONES : worldView.getSizeY() >> 3;
		if (worldView != null)
			uboWorldViewStruct = uboWorldViews.acquire(worldView);
		zones = new Zone[sizeX][sizeZ];
		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = new Zone();
	}

	void handleZoneSwap(float deltaTime, int zx, int zz) {
		Zone curZone = zones[zx][zz];
		ZoneUploadJob uploadTask = curZone.uploadJob;
		if (uploadTask == null)
			return;

		if (!uploadTask.isQueued()) {
			if (deltaTime > 0.0f && uploadTask.delay >= 0.0f) {
				uploadTask.delay -= deltaTime;
				if (uploadTask.delay <= 0.0f) {
					log.trace("queueing zone({}): [{}-{},{}]", uploadTask.zone.hashCode(), worldViewId, zx, zz);
					uploadTask.delay = -1.0f;
					uploadTask.queue(streamingGroup, sceneManager.getGenerateSceneDataTask());
				}
			}
			return;
		}

		if (uploadTask.isDone()) {
			curZone.uploadJob = null;
			if (uploadTask.ranToCompletion() && !uploadTask.wasCancelled()) {
				log.trace("swapping zone({}): [{}-{},{}]", uploadTask.zone.hashCode(), worldViewId, zx, zz);

				Zone PrevZone = curZone;
				// Swap the zone out with the one we just uploaded
				zones[zx][zz] = curZone = uploadTask.zone;

				if (PrevZone != curZone)
					pendingCull.add(PrevZone);
			} else if (uploadTask.wasCancelled() && !curZone.cull) {
				curZone.rebuild = true;
			}
			uploadTask.release();
		}
	}

	boolean update(float deltaTime) {
		if (isLoading)
			return false;

		Zone cullZone;
		while ((cullZone = pendingCull.poll()) != null) {
			log.trace("Culling zone({})", cullZone.hashCode());
			cullZone.free();
		}

		boolean queuedWork = false;
		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				handleZoneSwap(deltaTime, x, z);

				if (zones[x][z].rebuild) {
					zones[x][z].rebuild = false;
					invalidateZone(x, z);
					queuedWork = true;
				}
			}
		}

		return queuedWork;
	}

	void free() {
		sceneLoadGroup.cancel();
		streamingGroup.cancel();

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = null;

		if (uboWorldViewStruct != null)
			uboWorldViewStruct.free();
		uboWorldViewStruct = null;

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z].free();

		Zone cullZone;
		while ((cullZone = pendingCull.poll()) != null)
			cullZone.free();

		isLoading = true;
	}

	void invalidate() {
		log.debug("invalidate all zones for worldViewId: [{}]", worldViewId);
		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				invalidateZone(x, z);
	}

	void invalidateZone(int zx, int zz) {
		Zone curZone = zones[zx][zz];
		float prevUploadDelay = -1.0f;
		if (curZone.uploadJob != null) {
			log.trace(
				"Invalidate Zone({}) - Cancelled upload task: [{}-{},{}] task zone({})",
				curZone.hashCode(),
				worldViewId,
				zx,
				zz,
				curZone.uploadJob.zone.hashCode()
			);
			prevUploadDelay = curZone.uploadJob.delay;
			curZone.uploadJob.cancel();
			curZone.uploadJob.release();
		}

		Zone newZone = new Zone();
		newZone.dirty = zones[zx][zz].dirty;

		curZone.uploadJob = ZoneUploadJob.build(sceneManager.ssboModelData, this, sceneContext, newZone, zx, zz);
		curZone.uploadJob.delay = prevUploadDelay;
		if (curZone.uploadJob.delay < 0.0f)
			curZone.uploadJob.queue(streamingGroup, sceneManager.getGenerateSceneDataTask());
	}
}
