package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
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
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.opengl.uniforms.UBOWorldViews.WorldViewStruct;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.jobs.JobGroup;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
import static rs117.hd.renderer.zone.VAO.METADATA_SIZE;

@Slf4j
public class WorldViewContext {
	private static final int VAO_BUFFER_COUNT = 3;

	public static final int VAO_OPAQUE = 0;
	public static final int VAO_ALPHA = 1;
	public static final int VAO_PLAYER = 2;
	public static final int VAO_SHADOW = 3;
	public static final int VAO_COUNT = 4;

	public static final int ALPHA_ZSORT = 8192;
	public static final int ALPHA_ZSORT_SQ = ALPHA_ZSORT * ALPHA_ZSORT;

	@Inject
	private Injector injector;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Inject
	private SceneManager sceneManager;

	private static final ArrayDeque<VAO> VAO_STAGING_POOL = new ArrayDeque<>();
	private static final ArrayDeque<VAO> VAO_POOL = new ArrayDeque<>();

	final int worldViewId;
	final int sizeX, sizeZ;
	@Nullable
	WorldViewStruct uboWorldViewStruct;
	ZoneSceneContext sceneContext;
	Zone[][] zones;
	GLBuffer vboM;
	boolean isLoading = true;

	int minLevel, level, maxLevel;
	Set<Integer> hideRoofIds;

	private final Comparator<Zone> alphaSortComparator = Comparator.comparingInt((Zone z) -> z.dist).reversed();
	private final List<Zone> alphaZones = new ArrayList<>();

	CommandBuffer vaoSceneCmd;
	CommandBuffer vaoDirectionalCmd;
	final VAO[][] vaos = new VAO[VAO_BUFFER_COUNT][VAO_COUNT];

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

	public void initialize(RenderState renderState, Injector injector) {
		injector.injectMembers(this);

		vaoSceneCmd = new CommandBuffer(renderState);
		vaoDirectionalCmd = new CommandBuffer(renderState);

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = injector.getInstance(Zone.class);
	}

	void initBuffers() {
		if (vboM != null)
			return;

		vboM = new GLBuffer("WorldViewMetadata", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW, 0);
		vboM.initialize(METADATA_SIZE);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer buf = stack.callocInt(3);
			buf.put(uboWorldViewStruct == null ? 0 : uboWorldViewStruct.worldViewIdx + 1);
			buf.put(0).put(0);
			buf.flip();
			vboM.upload(buf);
		}

		long start = System.nanoTime();
		for(int i = 0; i < VAO_COUNT; i ++) {
			final boolean needsStaging = i == VAO_OPAQUE || i == VAO_SHADOW;
			final ArrayDeque<VAO> POOL = needsStaging ? VAO_STAGING_POOL : VAO_POOL;
			for(int k = 0; k < VAO_BUFFER_COUNT; k++) {
				VAO vao = vaos[k][i] = POOL.poll();
				if(vao == null) {
					vao = vaos[k][i] = new VAO(Integer.toString(i), needsStaging);
					vao.initialize();
				}
				vao.bindMetadataVAO(vboM);
			}
		}
		log.debug("WorldViewContext - WorldViewid: {} initBuffers took {}ms", worldViewId, (System.nanoTime() - start) / 1000000);
	}

	void map() {
		for(int i = 0; i < VAO_COUNT; i ++)
			vaos[plugin.frame % VAO_BUFFER_COUNT][i].map();
	}

	VAO.VAOView beginDraw(int type, int faces) {
		return vaos[plugin.frame % VAO_BUFFER_COUNT][type].beginDraw(faces);
	}

	void drawAll(int type, CommandBuffer cmd) {
		vaos[plugin.frame % VAO_BUFFER_COUNT][type].draw(cmd);
	}

	void unmap() {
		for(int i = 0; i < VAO_COUNT; i ++) {
			final boolean shouldCoalesce = i == VAO_OPAQUE || i == VAO_SHADOW;
			vaos[plugin.frame % VAO_BUFFER_COUNT][i].unmap(shouldCoalesce);
		}
	}

	void sortStaticAlphaModels(Camera camera) {
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
			for (Zone z : alphaZones)
				z.alphaStaticModelSort(camera);
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

				if (PrevZone != curZone) {
					curZone.inSceneFrustum  = PrevZone.inSceneFrustum;
					curZone.inShadowFrustum = PrevZone.inShadowFrustum;
					pendingCull.add(PrevZone);
				}
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

	void free(boolean isShutdown) {
		sceneLoadGroup.cancel();
		streamingGroup.cancel();

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = null;

		if (uboWorldViewStruct != null)
			uboWorldViewStruct.free();
		uboWorldViewStruct = null;

		for(int i = 0; i < VAO_COUNT; i ++) {
			for (int k = 0; k < VAO_BUFFER_COUNT; k++) {
				final ArrayDeque<VAO> POOL = vaos[k][i].hasStagingBuffer() ? VAO_STAGING_POOL : VAO_POOL;
				if(isShutdown || POOL.size() > 24) {
					vaos[k][i].destroy();
				} else {
					(vaos[k][i].hasStagingBuffer() ? VAO_STAGING_POOL : VAO_POOL).add(vaos[k][i]);
				}
			}
		}

		if(isShutdown) {
			VAO v;
			while ((v = VAO_STAGING_POOL.poll()) != null)
				v.destroy();
			while ((v = VAO_POOL.poll()) != null)
				v.destroy();
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
