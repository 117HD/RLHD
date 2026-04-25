package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.JobGroup;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.DynamicModelVAO.METADATA_SIZE;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
import static rs117.hd.renderer.zone.ZoneRenderer.FRAMES_IN_FLIGHT;

@Slf4j
public class WorldViewContext {
	public static final int VAO_OPAQUE = 0;
	public static final int VAO_ALPHA = 1;
	public static final int VAO_PLAYER = 2;
	public static final int VAO_SHADOW = 3;
	public static final int VAO_COUNT = 4;

	public static final ConcurrentPool<DynamicModelVAO> DYNAMIC_MODEL_VAO_STAGING_POOL =
		new ConcurrentPool<>(() -> new DynamicModelVAO("DynamicModelVAO::Staging", true));
	public static final ConcurrentPool<DynamicModelVAO> DYNAMIC_MODEL_VAO_POOL =
		new ConcurrentPool<>(() -> new DynamicModelVAO("DynamicModelVAO", false));

	@Inject
	private Injector injector;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private SceneManager sceneManager;

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
	final DynamicModelVAO[][] dynamicModelVaos = new DynamicModelVAO[FRAMES_IN_FLIGHT][VAO_COUNT];

	public long loadTime;
	public long uploadTime;
	public long sceneSwapTime;

	final JobGroup<ZoneUploadJob> sceneLoadGroup = new JobGroup<>(true, true);
	final JobGroup<ZoneUploadJob> streamingGroup = new JobGroup<>(false, false);
	final JobGroup<ZoneUploadJob> invalidationGroup = new JobGroup<>(true, false);

	WorldViewContext(
		@Nullable WorldView worldView,
		@Nullable ZoneSceneContext sceneContext,
		UBOWorldViews uboWorldViews
	) {
		this.worldViewId = worldView == null ? WorldView.TOPLEVEL : worldView.getId();
		this.sceneContext = sceneContext;
		this.sizeX = worldView == null ? NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? NUM_ZONES : worldView.getSizeY() >> 3;
		if (worldView != null)
			uboWorldViewStruct = uboWorldViews.acquire(worldView);
		zones = new Zone[sizeX][sizeZ];
	}

	public void initialize(RenderState renderState, Injector injector) {
		injector.injectMembers(this);

		vaoSceneCmd = new CommandBuffer("WorldViewScene", renderState);
		vaoDirectionalCmd = new CommandBuffer("WorldViewDirectional", renderState);

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
			IntBuffer buf = stack.mallocInt(3);
			buf.put(uboWorldViewStruct == null ? 0 : uboWorldViewStruct.worldViewIdx + 1);
			buf.put(0).put(0);
			buf.flip();
			vboM.upload(buf);
		}

		long start = System.nanoTime();
		for (int i = 0; i < VAO_COUNT; i++) {
			final boolean needsStaging = i == VAO_OPAQUE || i == VAO_PLAYER || i == VAO_SHADOW;
			final var POOL = needsStaging ? DYNAMIC_MODEL_VAO_STAGING_POOL : DYNAMIC_MODEL_VAO_POOL;
			for (int k = 0; k < FRAMES_IN_FLIGHT; k++) {
				DynamicModelVAO dynamicModelVao = dynamicModelVaos[k][i] = POOL.acquire();
				if (dynamicModelVao.getVao() == 0)
					dynamicModelVao.initialize();
				dynamicModelVao.bindMetadataVAO(vboM);
			}
		}
		log.trace("WorldViewContext - WorldViewId: {} initBuffers took {}ms", worldViewId, (System.nanoTime() - start) / 1000000);
	}

	void map() {
		for (int i = 0; i < VAO_COUNT; i++)
			dynamicModelVaos[plugin.frame % FRAMES_IN_FLIGHT][i].map();
	}

	DynamicModelVAO.View beginDraw(int type, int faces) {
		return dynamicModelVaos[plugin.frame % FRAMES_IN_FLIGHT][type].beginDraw(faces);
	}

	DynamicModelVAO.View beginDraw(int type, int playerDrawIndex, int faces) {
		return dynamicModelVaos[plugin.frame % FRAMES_IN_FLIGHT][type].beginDraw(playerDrawIndex, faces);
	}

	int obtainDrawIndex(int type) {
		return dynamicModelVaos[plugin.frame % FRAMES_IN_FLIGHT][type].obtainDrawIndex();
	}

	void drawAll(int type, CommandBuffer cmd) {
		dynamicModelVaos[plugin.frame % FRAMES_IN_FLIGHT][type].draw(cmd);
	}

	void unmap() {
		for (int i = 0; i < VAO_COUNT; i++) {
			final boolean shouldCoalesce = i == VAO_OPAQUE || i == VAO_PLAYER || i == VAO_SHADOW;
			dynamicModelVaos[plugin.frame % FRAMES_IN_FLIGHT][i].unmap(shouldCoalesce);
		}
	}

	void sortStaticAlphaModels(Camera camera) {
		alphaZones.clear();

		final int offset = sceneContext.sceneOffset >> 3;
		final int camPosX = (int) camera.getPositionX();
		final int camPosZ = (int) camera.getPositionZ();
		for (int zx = 0; zx < sizeX; zx++) {
			for (int zz = 0; zz < sizeZ; zz++) {
				final Zone z = zones[zx][zz];
				if (z.alphaModels.isEmpty() || (worldViewId == WorldView.TOPLEVEL && !z.inSceneFrustum))
					continue;

				final int dx = camPosX - ((zx - offset) << 10);
				final int dz = camPosZ - ((zz - offset) << 10);
				z.dist = dx * dx + dz * dz;
				alphaZones.add(z);
			}
		}

		if (!alphaZones.isEmpty()) {
			alphaZones.sort(alphaSortComparator);
			for (Zone z : alphaZones)
				z.alphaStaticModelSort(camera);
		}
	}

	void handleZoneSwap(int zx, int zz, boolean queue) {
		Zone curZone = zones[zx][zz];
		ZoneUploadJob uploadTask = curZone.uploadJob;
		if (uploadTask == null)
			return;

		if (!uploadTask.isQueued()) {
			if (queue && uploadTask.revealAfterTimestampMs < System.currentTimeMillis()) {
				log.trace("queueing zone({}): [{}-{},{}]", uploadTask.zone.hashCode(), worldViewId, zx, zz);
				uploadTask.revealAfterTimestampMs = 0;
				uploadTask.queue(streamingGroup, sceneManager.getGenerateSceneDataTask());
			}
			return;
		}

		if (uploadTask.isDone()) {
			curZone.uploadJob = null;
			if (uploadTask.ranToCompletion() && !uploadTask.wasCancelled()) {
				log.trace("swapping zone({}): [{}-{},{}]", uploadTask.zone.hashCode(), worldViewId, zx, zz);

				Zone prevZone = curZone;
				// Swap the zone out with the one we just uploaded
				zones[zx][zz] = curZone = uploadTask.zone;
				clientThread.invoke(curZone::unmap);

				if (prevZone != curZone) {
					curZone.inSceneFrustum = prevZone.inSceneFrustum;
					curZone.inShadowFrustum = prevZone.inShadowFrustum;
					DestructibleHandler.queueDestruction(prevZone);
				}

				sceneContext.animatedDynamicObjectIds.addAll(curZone.animatedDynamicObjectIds);
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

	void processZoneSwaps() {
		for (int x = 0; x < sizeX; x++)
			for (int z = 0; z < sizeZ; z++)
				handleZoneSwap(x, z, true);
	}

	void processZoneRebuilds() {
		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				if (zones[x][z].rebuild) {
					zones[x][z].rebuild = false;
					invalidateZone(x, z);
				}
			}
		}
	}

	void completeInvalidation() {
		if (invalidationGroup.getPendingCount() <= 0)
			return;

		invalidationGroup.complete();

		for (int x = 0; x < sizeX; x++)
			for (int z = 0; z < sizeZ; z++)
				handleZoneSwap(x, z, false);
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

		for (int i = 0; i < VAO_COUNT; i++) {
			for (int k = 0; k < FRAMES_IN_FLIGHT; k++) {
				if (dynamicModelVaos[k][i] == null)
					continue;
				final var POOL = dynamicModelVaos[k][i].hasStagingBuffer() ?
					DYNAMIC_MODEL_VAO_STAGING_POOL :
					DYNAMIC_MODEL_VAO_POOL;
				POOL.recycle(dynamicModelVaos[k][i]);
			}
		}

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z].destroy();

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
		long revealAfterTimestampMs = 0;
		if (curZone.uploadJob != null) {
			Zone pendingZone = curZone.uploadJob.zone;
			log.trace(
				"Invalidate Zone({}) - Cancelled upload task: [{}-{},{}] task zone({})",
				curZone.hashCode(),
				worldViewId,
				zx,
				zz,
				pendingZone.hashCode()
			);
			revealAfterTimestampMs = curZone.uploadJob.revealAfterTimestampMs;
			curZone.uploadJob.cancel();
			curZone.uploadJob.release();

			if (pendingZone != curZone)
				DestructibleHandler.destroy(pendingZone);
		}

		Zone newZone = injector.getInstance(Zone.class);
		newZone.dirty = zones[zx][zz].dirty;

		curZone.uploadJob = ZoneUploadJob.build(this, sceneContext, newZone, false, zx, zz);
		curZone.uploadJob.revealAfterTimestampMs = revealAfterTimestampMs;

		// Queue right away, so we can wait for it while in the POH in order to hide building mode placeholders
		if (sceneContext.isInHouse || revealAfterTimestampMs <= 0)
			curZone.uploadJob.queue(invalidationGroup, sceneManager.getGenerateSceneDataTask());
	}
}
