package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
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
import rs117.hd.utils.Mat4;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.jobs.JobGroup;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.DynamicModelVAO.METADATA_SIZE;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
import static rs117.hd.renderer.zone.ZoneRenderer.FRAMES_IN_FLIGHT;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class WorldViewContext {
	public static final int VAO_OPAQUE = 0;
	public static final int VAO_ALPHA = 1;
	public static final int VAO_PLAYER = 2;
	public static final int VAO_SHADOW = 3;
	public static final int VAO_PRESCENE = 4;
	public static final int VAO_COUNT = 5;

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

	public ZoneSceneContext sceneContext;
	public boolean isLoading = true;
	public boolean isSquashed;
	@Nullable
	public float[] projection;

	final int worldViewId;
	final int sizeX, sizeZ;
	@Nullable
	WorldViewStruct uboWorldViewStruct;

	private final float[] lastProjection = Mat4.zero();
	private final int[] lastOrigin = new int[3];
	private int lastOrientation;

	Zone[][] zones;
	GLBuffer vboM;

	int minLevel, level, maxLevel;
	Set<Integer> hideRoofIds;

	final EboAlphaWriterJob sortedAlphaFacesUpload = new EboAlphaWriterJob(this);
	final StaticAlphaSortingJob alphaSortingJob = new StaticAlphaSortingJob();
	int[] eboAlphaIndices;
	// Number of valid indices in eboAlphaIndices this frame (the packed blocks for this world view).
	int eboAlphaCount;
	// Absolute EBO offset the whole packed block array is uploaded to, resolved once per frame on first use, or -1.
	int eboAlphaBaseOffset = -1;

	// Interned StaticAlphaModels so identical static models can be shared across zones in this world view.
	// Guarded by synchronizing on the map itself.
	private final Map<StaticAlphaModel, StaticAlphaModel> staticAlphaModels = new HashMap<>();

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
		if (sceneContext != null)
			sceneContext.worldViewContext = this;
		this.sizeX = worldView == null ? NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? NUM_ZONES : worldView.getSizeY() >> 3;
		if (worldView != null)
			uboWorldViewStruct = uboWorldViews.acquire();
		zones = new Zone[sizeX][sizeZ];
	}

	public void initialize(Injector injector) {
		injector.injectMembers(this);

		vaoSceneCmd = new CommandBuffer("WorldViewScene");
		vaoDirectionalCmd = new CommandBuffer("WorldViewDirectional");

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = injector.getInstance(Zone.class);
	}

	void updateProjection(WorldView topLevel) {
		// Update WorldView projection, or estimate its trajectory when off-screen, if possible
		if (worldViewId == WorldView.TOPLEVEL)
			return;

		var worldEntity = topLevel.worldEntities().byIndex(worldViewId);
		isSquashed = worldEntity != null && worldEntity.isHiddenForOverlap();
		if (worldEntity == null) {
			projection = null;
			return;
		}

		var worldView = worldEntity.getWorldView();
		if (worldView.getMainWorldProjection() instanceof FloatProjection) {
			if (projection == null)
				projection = new float[16];
			copyTo(projection, ((FloatProjection) worldView.getMainWorldProjection()).getProjection());
			copyTo(lastProjection, projection);
			var origin = worldEntity.getLocalLocation();
			lastOrigin[0] = origin.getX();
			lastOrigin[1] = topLevel.getTileHeight(origin.getX(), origin.getY(), topLevel.getPlane());
			lastOrigin[2] = origin.getY();
			lastOrientation = worldEntity.getOrientation();
		} else if (projection != null) {
			// Approximate the WorldView's projection based on the WorldEntity's position & orientation
			var origin = worldEntity.getLocalLocation();
			float angle = (worldEntity.getOrientation() - lastOrientation) * JAU_TO_RAD;
			float sin = sin(angle);
			float cos = cos(angle);
			copyTo(projection, lastProjection);
			for (int i = 0; i < 16; i += 4) {
				float x = lastProjection[i];
				float z = lastProjection[i + 2];
				if (i == 12) {
					x -= lastOrigin[0];
					z -= lastOrigin[2];
				}
				projection[i] = cos * x + sin * z;
				projection[i + 2] = -sin * x + cos * z;
			}
			projection[12] += origin.getX();
			projection[13] += topLevel.getTileHeight(origin.getX(), origin.getY(), topLevel.getPlane()) - lastOrigin[1];
			projection[14] += origin.getY();
		}

		if (uboWorldViewStruct != null) {
			if (projection != null)
				uboWorldViewStruct.projection.set(projection);

			Scene scene = worldView.getScene();
			if (scene != null) {
				uboWorldViewStruct.tint.set(
					scene.getOverrideHue(), scene.getOverrideSaturation(),
					scene.getOverrideLuminance(), scene.getOverrideAmount()
				);
			} else {
				uboWorldViewStruct.tint.set(0, 0, 0, 0);
			}
		}
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

	/**
	 * Deduplicates an already constructed static alpha model against the models already interned for this world view.
	 * If an equal model exists, it is returned and {@code model} can be discarded; otherwise {@code model} is interned
	 * and returned. The returned model's {@code refCount} must eventually be balanced with
	 * {@link #releaseStaticAlphaModel(StaticAlphaModel)}.
	 */
	StaticAlphaModel dedupeStaticAlphaModel(StaticAlphaModel model) {
		synchronized (staticAlphaModels) {
			final StaticAlphaModel existing = staticAlphaModels.get(model);
			if (existing != null) {
				existing.refCount++;
				return existing;
			}
			model.refCount = 1;
			staticAlphaModels.put(model, model);
			return model;
		}
	}

	void releaseStaticAlphaModel(StaticAlphaModel model) {
		if (model == null)
			return;
		synchronized (staticAlphaModels) {
			if (--model.refCount <= 0)
				staticAlphaModels.remove(model);
		}
	}

	void sortStaticAlphaModels(Camera camera) {
		// One job per world view, so each distinct StaticAlphaModel is sorted and assigned an index block exactly
		// once, no matter how many zones reference it. Instance visibility is resolved per zone here, and only models
		// which have at least one visible instance are queued for sorting.
		alphaSortingJob.reset();

		final int offset = sceneContext.sceneOffset >> 3;
		final boolean frustumCull = worldViewId == WorldView.TOPLEVEL;

		for (int zx = 0; zx < sizeX; zx++) {
			for (int zz = 0; zz < sizeZ; zz++) {
				final Zone z = zones[zx][zz];
				if (z.alphaModels.isEmpty())
					continue;

				z.determineAlphaModelVisibility(camera, zx - offset, zz - offset, frustumCull);

				for (int i = 0; i < z.alphaModels.size(); i++) {
					final AlphaModelInstance m = z.alphaModels.get(i);
					if ((m.flags & AlphaModelInstance.SKIP) != 0 || m.isTemp() || !m.isVisible())
						continue;
					alphaSortingJob.addStaticAlphaModel(m.staticModel);
				}
			}
		}

		final int alphaFaceCount = alphaSortingJob.getAssignedIndexCount();

		// The packed block array is uploaded to the EBO as one contiguous copy, so the destination of any block is
		// just eboAlphaBaseOffset + its offset within eboAlphaIndices, resolved once on first use per frame.
		eboAlphaCount = alphaFaceCount;
		eboAlphaBaseOffset = -1;

		if (alphaFaceCount > 0) {
			ZoneRenderer.eboAlpha.ensureCapacity((long) alphaFaceCount * Integer.BYTES);
			eboAlphaIndices = PooledArrayType.INT.ensureCapacity(eboAlphaIndices, alphaFaceCount);
			alphaSortingJob.queue(camera, eboAlphaIndices);
		} else {
			if (eboAlphaIndices != null)
				PooledArrayType.INT.release(eboAlphaIndices);
			eboAlphaIndices = null;
		}
	}

	// Synchronize creation of delayed upload jobs with zone swaps and invalidation
	synchronized void handleZoneSwap(int zx, int zz, boolean queue) {
		Zone curZone = zones[zx][zz];
		ZoneUploadJob uploadTask = curZone.uploadJob;
		if (uploadTask == null)
			return;

		if (!uploadTask.isQueued()) {
			if (queue && uploadTask.revealAfterTimestampMs < System.currentTimeMillis()) {
				log.trace("queueing zone({}): [{}-{},{}]", uploadTask.zoneBeingUploaded.hashCode(), worldViewId, zx, zz);
				uploadTask.revealAfterTimestampMs = 0;
				uploadTask.queue(streamingGroup, sceneManager.getGenerateSceneDataTask());
			}
			return;
		}

		if (uploadTask.isDone()) {
			curZone.uploadJob = null;
			if (uploadTask.ranToCompletion() && !uploadTask.wasCancelled()) {
				Zone uploadedZone = uploadTask.zoneBeingUploaded;
				if (uploadedZone == null) {
					log.error(
						"Completed zone upload job [{}] has already been released: [{}-{},{}]",
						uploadTask.hashCode(),
						worldViewId,
						zx,
						zz
					);
					curZone.rebuild = true;
					return;
				}
				log.trace("swapping zone({}): [{}-{},{}]", uploadedZone.hashCode(), worldViewId, zx, zz);

				Zone prevZone = curZone;
				// Swap the zone out with the one we just uploaded
				zones[zx][zz] = curZone = uploadedZone;
				clientThread.invoke(() -> uploadedZone.unmap(this));

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
		sortedAlphaFacesUpload.cancel();
		alphaSortingJob.destroy();

		if (sceneContext != null) {
			sceneContext.worldViewContext = null;
			sceneContext.destroy();
		}
		sceneContext = null;

		if (uboWorldViewStruct != null)
			uboWorldViewStruct.free();
		uboWorldViewStruct = null;
		projection = null;
		isSquashed = false;

		if(eboAlphaIndices != null)
			PooledArrayType.INT.release(eboAlphaIndices);
		eboAlphaIndices = null;

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

		synchronized (staticAlphaModels) {
			staticAlphaModels.clear();
		}

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

	// Synchronize creation of delayed upload jobs with zone swaps and invalidation
	synchronized void invalidateZone(int zx, int zz) {
		Zone curZone = zones[zx][zz];
		long revealAfterTimestampMs = 0;
		ZoneUploadJob previousUpload = curZone.uploadJob;
		if (previousUpload != null) {
			curZone.uploadJob = null;
			Zone pendingZone = previousUpload.zoneBeingUploaded;
			log.trace(
				"Invalidate Zone({}) - Cancelled upload task: [{}-{},{}] task zone({})",
				curZone.hashCode(),
				worldViewId,
				zx,
				zz,
				pendingZone.hashCode()
			);
			revealAfterTimestampMs = previousUpload.revealAfterTimestampMs;
			previousUpload.cancel();
			previousUpload.release();

			if (pendingZone != curZone)
				DestructibleHandler.destroy(pendingZone);
		}

		Zone newZone = injector.getInstance(Zone.class);
		newZone.dirty = zones[zx][zz].dirty;

		curZone.setUploadJob(ZoneUploadJob.build(this, sceneContext, newZone, false, zx, zz));
		curZone.uploadJob.revealAfterTimestampMs = revealAfterTimestampMs;

		// Queue right away, so we can wait for it while in the POH in order to hide building mode placeholders
		if (sceneContext.isInHouse || revealAfterTimestampMs <= 0)
			curZone.uploadJob.queue(invalidationGroup, sceneManager.getGenerateSceneDataTask());
	}
}
