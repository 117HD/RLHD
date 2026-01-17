package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.opengl.uniforms.UBOWorldViews.WorldViewStruct;
import rs117.hd.renderer.zone.VAO.VAOList;
import rs117.hd.utils.Camera;
import rs117.hd.utils.jobs.JobGroup;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;

@Slf4j
public class WorldViewContext {
	public static final int VAO_OPAQUE = 0;
	public static final int VAO_ALPHA = 1;
	public static final int VAO_PLAYER = 3;
	public static final int VAO_SHADOW = 4;
	public static final int VAO_COUNT = 5;

	public static final int ALPHA_ZSORT = 8192;
	public static final int ALPHA_ZSORT_SQ = ALPHA_ZSORT * ALPHA_ZSORT;

	@Inject
	private Injector injector;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private SceneManager sceneManager;

	final int worldViewId;
	final int sizeX, sizeZ;
	@Nullable
	WorldViewStruct uboWorldViewStruct;
	ZoneSceneContext sceneContext;
	Zone[][] zones;
	VBO vboM;
	boolean isLoading = true;

	int minLevel, level, maxLevel;
	Set<Integer> hideRoofIds;

	private final Comparator<Zone> alphaSortComparator = Comparator.comparingInt((Zone z) -> z.dist).reversed();
	private final List<Zone> alphaZones = new ArrayList<>();

	StaticAlphaSortingJob staticAlphaSortingJob;

	final VAOList[] vaoLists = new VAOList[VAO_COUNT];

	public long loadTime;
	public long uploadTime;
	public long sceneSwapTime;

	final LinkedBlockingDeque<Zone> pendingCull = new LinkedBlockingDeque<>();
	final JobGroup<ZoneUploadJob> sceneLoadGroup = new JobGroup<>(true, true);
	final JobGroup<ZoneUploadJob> streamingGroup = new JobGroup<>(false, false);
	final JobGroup<ZoneUploadJob> invalidationGroup = new JobGroup<>(true, false);

	WorldViewContext(
		@Nullable WorldView worldView,
		@Nullable ZoneSceneContext sceneContext,
		UBOWorldViews uboWorldViews
	) {
		this.worldViewId = worldView == null ? -1 : worldView.getId();
		this.sceneContext = sceneContext;
		this.sizeX = worldView == null ? NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? NUM_ZONES : worldView.getSizeY() >> 3;
		if (worldView != null)
			uboWorldViewStruct = uboWorldViews.acquire(worldView);
		zones = new Zone[sizeX][sizeZ];
	}

	public void initialize(Injector injector) {
		injector.injectMembers(this);

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = injector.getInstance(Zone.class);
	}

	void initBuffers() { initBuffers(-1); }

	void initBuffers(int vaoPreAllocate) {
		if (vboM != null)
			return;

		vboM = new VBO(VAO.METADATA_SIZE);
		vboM.initialize(GL_STATIC_DRAW);
		vboM.map();
		vboM.vb
			.put(uboWorldViewStruct == null ? 0 : uboWorldViewStruct.worldViewIdx + 1)
			.put(0).put(0); // dummy scene offset for macOS
		vboM.unmap();

		for(int i = 0; i < VAO_COUNT; i++) {
			vaoLists[i] = new VAOList(vboM, eboAlpha, client);
			if(vaoPreAllocate > 0)
				vaoLists[i].preAllocate(vaoPreAllocate);
		}
	}

	VAOList getVaoList(int vaoType) {
		assert vaoType >= 0 && vaoType < VAO_COUNT : "Invalid VAO type: " + vaoType;
		return vaoLists[vaoType];
	}

	VAO getVao(int vaoType, int size) {
		assert vaoType >= 0 && vaoType < VAO_COUNT : "Invalid VAO type: " + vaoType;
		return vaoLists[vaoType].get(size);
	}

	void map() {
		for(int i = 0; i < VAO_COUNT; i++) {
			if(vaoLists[i] != null)
				vaoLists[i].map();
		}
	}

	void addRange() {
		for(int i = 0; i < VAO_COUNT; i++) {
			if(vaoLists[i] != null)
				vaoLists[i].addRange();
		}
	}

	void unmap() {
		for(int i = 0; i < VAO_COUNT; i++) {
			if(vaoLists[i] != null)
				vaoLists[i].unmap();
		}
	}

	void sortStaticAlphaModels(FacePrioritySorter facePrioritySorter, Camera camera) {
		if(staticAlphaSortingJob == null)
			staticAlphaSortingJob = new StaticAlphaSortingJob(facePrioritySorter);

		staticAlphaSortingJob.waitForCompletion();
		alphaZones.clear();

		final int offset = sceneContext.sceneOffset >> 3;
		final int camPosX = (int) camera.getPositionX();
		final int camPosZ = (int) camera.getPositionZ();
		for(int zx = 0; zx < sizeX; zx++) {
			for(int zz = 0; zz < sizeZ; zz++) {
				final Zone z = zones[zx][zz];
				if(z.alphaModels.isEmpty() || (worldViewId == -1 && !z.inSceneFrustum))
					continue;

				final int dx = camPosX - ((zx - offset) << 10);
				final int dz = camPosZ - ((zz - offset) << 10);
				z.dist = dx * dx + dz * dz;
				alphaZones.add(z);
			}
		}

		if(!alphaZones.isEmpty()) {
			alphaZones.sort(alphaSortComparator);
			staticAlphaSortingJob.reset();
			for (Zone z : alphaZones)
				z.alphaStaticModelSort(staticAlphaSortingJob);
			staticAlphaSortingJob.queue(camera);
		}
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
				clientThread.invoke(curZone::unmap);

				if (PrevZone != curZone)
					pendingCull.add(PrevZone);
			} else if (uploadTask.wasCancelled() && !curZone.cull) {
				boolean shouldRetry = uploadTask.encounteredError() && curZone.isFirstLoadingAttempt;
				if (shouldRetry) {
					// Cache if the previous upload task encountered an error,
					// if it encounters another one the zone will be dropped to avoid constantly rebuilding
					curZone.rebuild = true;
					curZone.isFirstLoadingAttempt = false;
				} else if (uploadTask.encounteredError()) {
					log.debug(
						"Zone({}) [{}-{},{}] was cancelled due to an error, dropping to avoid constantly rebuilding",
						curZone.hashCode(),
						worldViewId,
						zx,
						zz
					);
				}
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

	int getSortedAlphaCount() {
		int count = 0;

		for (int x = 0; x < sizeX; x++)
			for (int z = 0; z < sizeZ; z++)
				count += zones[x][z].sortedFacesLen;

		return count;
	}

	void completeInvalidation() {
		if (isLoading)
			return;

		invalidationGroup.complete();

		for (int x = 0; x < sizeX; x++)
			for (int z = 0; z < sizeZ; z++)
				handleZoneSwap(-1.0f, x, z);
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

		for(int i = 0; i < VAO_COUNT; i++) {
			if(vaoLists[i] != null)
				vaoLists[i].free();
		}

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z].free();

		Zone cullZone;
		while ((cullZone = pendingCull.poll()) != null)
			cullZone.free();

		if (vboM != null)
			vboM.destroy();
		vboM = null;

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

		Zone newZone = injector.getInstance(Zone.class);
		newZone.dirty = zones[zx][zz].dirty;

		curZone.uploadJob = ZoneUploadJob.build(this, sceneContext, newZone, false, zx, zz);
		curZone.uploadJob.delay = prevUploadDelay;
		if (curZone.uploadJob.delay < 0.0f)
			curZone.uploadJob.queue(invalidationGroup, sceneManager.getGenerateSceneDataTask());
	}
}
