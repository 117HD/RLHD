package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.hooks.*;
import net.runelite.client.callback.RenderCallbackManager;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.PROCESSOR_COUNT;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_ALPHA;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PLAYER;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_SHADOW;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class ModelStreamingManager {
	private static final int RL_RENDER_THREADS = 2;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private Client client;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private ZoneRenderer renderer;

	private final int[] drawnDynamicRenderableCount = new int[RL_RENDER_THREADS + 1];
	private final int[][] asyncWorldPos = new int[RL_RENDER_THREADS + 1][3];
	private final float[][] asyncObjectProjectedPos = new float[RL_RENDER_THREADS + 1][4];

	private final ArrayList<AsyncCachedModel> pending = new ArrayList<>();
	private final PrimitiveIntArray clientVisibleFaces = new PrimitiveIntArray();
	private final PrimitiveIntArray clientCulledFaces = new PrimitiveIntArray();

	private boolean disabledRenderThreads;

	public int gpuFlags() {
		int flags = 0;
		if (config.multithreadedModelProcessing() && PROCESSOR_COUNT > 1) {
			// RENDER_THREADS will act as suppliers into the Job System, so this will be 2 + Client Suppliers
			flags |= DrawCallbacks.RENDER_THREADS(RL_RENDER_THREADS);
			initializeAsyncCachedModel();
		} else {
			AsyncCachedModel.POOL = null;
		}
		return flags;
	}

	public void initializeAsyncCachedModel() {
		if (!config.multithreadedModelProcessing())
			return;

		long maxModelSizeBytes = AsyncCachedModel.calculateMaxModelSizeBytes();
		long asyncModelCacheSizeBytes = config.asyncModelCacheSizeMiB() * MiB;
		int maxModelCount = (int) Math.ceil(asyncModelCacheSizeBytes / (double) maxModelSizeBytes);

		ensureAsyncUploadsComplete(null);

		AsyncCachedModel.POOL = new ConcurrentPool<>(plugin.getInjector(), AsyncCachedModel.class, maxModelCount);
		log.debug("Initialized Async Cached Model Pool with {} models", maxModelCount);
	}

	public void update() {
		if (AsyncCachedModel.POOL == null)
			return;

		if (plugin.isPowerSaving) {
			if (!disabledRenderThreads) {
				disabledRenderThreads = true;
				client.setGpuFlags(plugin.gpuFlags & ~DrawCallbacks.RENDER_THREADS(RL_RENDER_THREADS));
			}
		} else if (disabledRenderThreads) {
			disabledRenderThreads = false;
			client.setGpuFlags(plugin.gpuFlags);
		}

		Arrays.fill(drawnDynamicRenderableCount, 0);
	}

	public int getDrawnDynamicRenderableCount() {
		int count = 0;
		for (int i = 0; i < drawnDynamicRenderableCount.length; i++)
			count += drawnDynamicRenderableCount[i];
		return count;
	}

	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orientation, int x, int y, int z) {
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || (!sceneManager.isRoot(ctx) && ctx.isLoading) || !renderCallbackManager.drawObject(scene, gameObject))
			return;

		ctx.sceneContext.localToWorld(gameObject.getLocalLocation(), gameObject.getPlane(), asyncWorldPos[0]);
		// Hide everything outside the current area if area hiding is enabled
		if (ctx.sceneContext.currentArea != null && scene.getWorldViewId() == -1) {
			var base = ctx.sceneContext.sceneBase;
			assert base != null;
			boolean inArea = ctx.sceneContext.currentArea.containsPoint(
				base[0] + (x >> Perspective.LOCAL_COORD_BITS),
				base[1] + (z >> Perspective.LOCAL_COORD_BITS),
				base[2] + client.getTopLevelWorldView().getPlane()
			);
			if (!inArea)
				return;
		}
		Renderable renderable = gameObject.getRenderable();
		int uuid = ModelHash.generateUuid(client, gameObject.getHash(), renderable);

		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, asyncWorldPos[0]);
		if (modelOverride.hide)
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (gameObject.getX() >> 10) + offset;
		int zz = (gameObject.getY() >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		m.calculateBoundsCylinder();

		final float[] objectWorldPos = vec4(asyncObjectProjectedPos[0], x, y, z, 1.0f);
		if (ctx.uboWorldViewStruct != null)
			ctx.uboWorldViewStruct.project(objectWorldPos);

		final int modelClassification = renderer.sceneCamera.classifySphere(
			objectWorldPos[0], objectWorldPos[1], objectWorldPos[2], m.getRadius());
		boolean isOffScreen = modelClassification == -1;
		// Additional Culling checks to help reduce dynamic object perf impact when off-screen
		if (isOffScreen && (
			!modelOverride.castShadows ||
			!renderer.directionalShadowCasterVolume.intersectsPoint(
				(int) objectWorldPos[0],
				(int) objectWorldPos[1],
				(int) objectWorldPos[2]
			)
		)) {
			return;
		}
		plugin.drawnTempRenderableCount++;

		final boolean isModelPartiallyVisible = sceneManager.isRoot(ctx) && modelClassification == 0;
		final boolean hasAlpha = renderable instanceof Player || m.getFaceTransparencies() != null;
		final AsyncCachedModel asyncModelCache = obtainAvailableAsyncCachedModel(false);
		if (asyncModelCache != null) {
			asyncModelCache.queue(
				m, hasAlpha ? zone : null,
				(sceneUploader, facePrioritySorter, visibleFaces, culledFaces, cachedModel) -> {
					final long asyncStart = System.nanoTime();
					uploadTempModel(
						sceneUploader,
						facePrioritySorter,
						visibleFaces,
						culledFaces,
						worldProjection,
						ctx,
						gameObject,
						renderable,
						modelOverride,
						zone,
						cachedModel,
						isModelPartiallyVisible,
						hasAlpha,
						orientation, x, y, z
					);
					frameTimer.add(Timer.DRAW_TEMP_ASYNC, System.nanoTime() - asyncStart);
				}
			);
			return;
		}

		try (
			SceneUploader sceneUploader = SceneUploader.POOL.acquire();
			FacePrioritySorter facePrioritySorter = FacePrioritySorter.POOL.acquire()
		) {
			uploadTempModel(
				sceneUploader,
				facePrioritySorter,
				clientVisibleFaces,
				clientCulledFaces,
				worldProjection,
				ctx,
				gameObject,
				renderable,
				modelOverride,
				zone,
				m,
				isModelPartiallyVisible,
				hasAlpha,
				orientation, x, y, z
			);
		} catch (Exception e) {
			log.error("Error drawing temp object", e);
		}
	}

	private void uploadTempModel(
		SceneUploader sceneUploader,
		FacePrioritySorter facePrioritySorter,
		PrimitiveIntArray visibleFaces,
		PrimitiveIntArray culledFaces,
		Projection worldProjection,
		WorldViewContext ctx,
		GameObject gameObject,
		Renderable renderable,
		ModelOverride modelOverride,
		Zone zone,
		Model m,
		boolean isModelPartiallyVisible,
		boolean hasAlpha,
		int orientation, int x, int y, int z
	) {

		boolean shouldSort = hasAlpha && (!sceneManager.isRoot(ctx) || zone.inSceneFrustum);
		shouldSort &= sceneUploader.preprocessTempModel(
			worldProjection,
			plugin.cameraFrustum,
			shouldSort ? facePrioritySorter.faceDistances : null,
			visibleFaces,
			culledFaces,
			isModelPartiallyVisible,
			m,
			x,
			y,
			z,
			orientation
		);

		final int preOrientation = HDUtils.getModelPreOrientation(gameObject.getConfig());
		if (shouldSort)
			facePrioritySorter.sortModelFaces(visibleFaces, m);

		if (culledFaces.length > 0 && (!sceneManager.isRoot(ctx) || zone.inShadowFrustum)) {
			final VAO.VAOView shadowView = ctx.beginDraw(VAO_SHADOW, culledFaces.length);
			sceneUploader.uploadTempModel(
				culledFaces,
				m,
				modelOverride,
				preOrientation,
				orientation,
				true,
				shadowView,
				shadowView
			);
			shadowView.end();
		}


		if (visibleFaces.length > 0) {
			// opaque player faces have their own vao and are drawn in a separate pass from normal opaque faces
			// because they are not depth tested. transparent player faces don't need their own vao because normal
			// transparent faces are already not depth tested
			final VAO.VAOView opaqueView = ctx.beginDraw(renderable instanceof Player ? VAO_PLAYER : VAO_OPAQUE, visibleFaces.length);
			final VAO.VAOView alphaView = hasAlpha ? ctx.beginDraw(VAO_ALPHA, visibleFaces.length) : opaqueView;

			sceneUploader.uploadTempModel(
				visibleFaces,
				m,
				modelOverride,
				preOrientation,
				orientation,
				false,
				opaqueView,
				alphaView
			);

			// Fix rendering projectiles from boats with hide roofs enabled
			int plane = Math.min(ctx.maxLevel, gameObject.getPlane());

			opaqueView.end();
			if (renderable instanceof Player) {
				if (opaqueView.getEndOffset() > opaqueView.getStartOffset()) {
					zone.addPlayerModel(
						opaqueView,
						plane,
						x & 1023,
						y - renderable.getModelHeight() /* to render players over locs */,
						z & 1023
					);
				}
			}

			if (opaqueView != alphaView) {
				alphaView.end();
				if (alphaView.getEndOffset() > alphaView.getStartOffset()) {
					zone.addTempAlphaModel(
						modelOverride,
						alphaView,
						plane,
						x & 1023,
						y - renderable.getModelHeight() /* to render players over locs */,
						z & 1023
					);
				}
			}
		}
	}

	public void drawDynamic(
		int renderThreadId,
		Projection projection,
		Scene scene,
		TileObject tileObject,
		Renderable r,
		Model m,
		int orient,
		int x,
		int y,
		int z
	) {
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || (!sceneManager.isRoot(ctx) && ctx.isLoading) || !renderCallbackManager.drawObject(scene, tileObject))
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (x >> 10) + offset;
		int zz = (z >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		if (!zone.initialized)
			return;

		final float[] objectWorldPos = vec4(asyncObjectProjectedPos[renderThreadId + 1], x, y, z, 1.0f);
		if (ctx.uboWorldViewStruct != null)
			ctx.uboWorldViewStruct.project(objectWorldPos);

		// Cull based on detail draw distance
		float squaredDistance = renderer.sceneCamera.squaredDistanceTo(objectWorldPos[0], objectWorldPos[1], objectWorldPos[2]);
		int detailDrawDistanceTiles = plugin.configDetailDrawDistance * LOCAL_TILE_SIZE;
		if (squaredDistance > detailDrawDistanceTiles * detailDrawDistanceTiles && !renderer.detailDrawBlockList.contains(tileObject.getId()))
			return;

		// Hide everything outside the current area if area hiding is enabled
		if (ctx.sceneContext.currentArea != null) {
			var base = ctx.sceneContext.sceneBase;
			assert base != null;
			boolean inArea = ctx.sceneContext.currentArea.containsPoint(
				base[0] + ((int)objectWorldPos[0] >> Perspective.LOCAL_COORD_BITS),
				base[1] + ((int)objectWorldPos[2] >> Perspective.LOCAL_COORD_BITS),
				base[2] + client.getTopLevelWorldView().getPlane()
			);
			if (!inArea)
				return;
		}

		final int[] tileWorldPos = asyncWorldPos[renderThreadId + 1];
		ctx.sceneContext.localToWorld(tileObject.getLocalLocation(), tileObject.getPlane(), tileWorldPos);
		int uuid = ModelHash.generateUuid(client, tileObject.getHash(), r);
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, tileWorldPos);
		if (modelOverride.hide)
			return;

		m.calculateBoundsCylinder();

		final int modelClassification = renderer.sceneCamera.classifySphere(
			objectWorldPos[0], objectWorldPos[1], objectWorldPos[2], m.getRadius());
		boolean isOffScreen = modelClassification == -1;
		// Additional Culling checks to help reduce dynamic object perf impact when off-screen
		if (isOffScreen && (
			!modelOverride.castShadows ||
			!renderer.directionalShadowCasterVolume.intersectsPoint(
				(int) objectWorldPos[0],
				(int) objectWorldPos[1],
				(int) objectWorldPos[2]
			)
		)) {
			return;
		}
		drawnDynamicRenderableCount[renderThreadId + 1]++;

		final int preOrientation = HDUtils.getModelPreOrientation(HDUtils.getObjectConfig(tileObject));
		final boolean hasAlpha = m.getFaceTransparencies() != null || modelOverride.mightHaveTransparency;

		if (renderThreadId >= 0)
			client.checkClickbox(projection, m, orient, x, y, z, tileObject.getHash());

		final boolean isModelPartiallyVisible = sceneManager.isRoot(ctx) && modelClassification == 0;
		final AsyncCachedModel asyncModelCache = obtainAvailableAsyncCachedModel(renderThreadId >= 0);
		if (asyncModelCache != null) {
			// Fast path, buffer the model into the job queue to unblock rl internals
			asyncModelCache.queue(
				m, hasAlpha ? zone : null,
				(sceneUploader, facePrioritySorter, visibleFaces, culledFaces, cachedModel) -> {
					final long asyncStart = System.nanoTime();
					uploadDynamicModel(
						sceneUploader,
						facePrioritySorter,
						visibleFaces,
						culledFaces,
						ctx,
						projection,
						tileObject,
						modelOverride,
						cachedModel,
						zone,
						isModelPartiallyVisible,
						hasAlpha,
						preOrientation, orient,
						x, y, z
					);
					frameTimer.add(Timer.DRAW_DYNAMIC_ASYNC, System.nanoTime() - asyncStart);
				}
			);
			return;
		}

		if (renderThreadId >= 0)
			return;

		try (
			SceneUploader sceneUploader = SceneUploader.POOL.acquire();
			FacePrioritySorter facePrioritySorter = FacePrioritySorter.POOL.acquire()
		) {
			uploadDynamicModel(
				sceneUploader,
				facePrioritySorter,
				clientVisibleFaces,
				clientCulledFaces,
				ctx,
				projection,
				tileObject,
				modelOverride,
				m,
				zone,
				isModelPartiallyVisible,
				hasAlpha,
				preOrientation, orient,
				x, y, z
			);
		}
	}

	private void uploadDynamicModel(
		SceneUploader sceneUploader,
		FacePrioritySorter facePrioritySorter,
		PrimitiveIntArray visibleFaces,
		PrimitiveIntArray culledFaces,
		WorldViewContext ctx,
		Projection projection,
		TileObject tileObject,
		ModelOverride modelOverride,
		Model m,
		Zone zone,
		boolean isModelPartiallyVisible,
		boolean hasAlpha,
		int preOrientation,
		int orient,
		int x,
		int y,
		int z
	) {
		try {
			boolean shouldSort = hasAlpha && (!sceneManager.isRoot(ctx) || zone.inSceneFrustum);
			shouldSort &= sceneUploader.preprocessTempModel(
				projection,
				plugin.cameraFrustum,
				shouldSort ? facePrioritySorter.faceDistances : null,
				visibleFaces,
				culledFaces,
				isModelPartiallyVisible,
				m,
				x,
				y,
				z,
				orient
			);

			if (shouldSort)
				facePrioritySorter.sortModelFaces(visibleFaces, m);

			if (culledFaces.length > 0 && (!sceneManager.isRoot(ctx) || zone.inShadowFrustum)) {
				final VAO.VAOView shadowView = ctx.beginDraw(VAO_SHADOW, culledFaces.length);
				sceneUploader.uploadTempModel(
					culledFaces,
					m,
					modelOverride,
					preOrientation,
					orient,
					true,
					shadowView,
					shadowView
				);
				shadowView.end();
			}

			if (visibleFaces.length > 0) {
				final VAO.VAOView opaqueView = ctx.beginDraw(VAO_OPAQUE, visibleFaces.length);
				final VAO.VAOView alphaView = hasAlpha ? ctx.beginDraw(VAO_ALPHA, visibleFaces.length) : opaqueView;

				sceneUploader.uploadTempModel(
					visibleFaces,
					m,
					modelOverride,
					preOrientation,
					orient,
					false,
					opaqueView,
					alphaView
				);

				opaqueView.end();
				if (opaqueView != alphaView) {
					alphaView.end();
					if (alphaView.getEndOffset() > alphaView.getStartOffset()) {
						// level is checked prior to this callback being run, in order to cull clickboxes, but
						// tileObject.getPlane()>maxLevel if visbelow is set - lower the object to the max level
						int plane = Math.min(ctx.maxLevel, tileObject.getPlane());
						// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
						zone.addTempAlphaModel(
							modelOverride,
							alphaView,
							plane,
							x & 1023,
							y,
							z & 1023
						);
					}
				}
			}
		} catch (Exception e) {
			log.error("Error rendering dynamic object", e);
		}
	}

	public void ensureAsyncUploadsComplete(Zone zone) {
		if (AsyncCachedModel.POOL == null)
			return;

		frameTimer.begin(Timer.MODEL_UPLOAD_COMPLETE);
		pending.clear();
		AsyncCachedModel model;
		while ((model = (zone != null ? zone.pendingModelJobs.poll() : AsyncCachedModel.INFLIGHT.poll())) != null)
			pending.add(model);

		AsyncCachedModel pendingModel;
		boolean shouldBlock = false, hasStolen = false;
		int idx = 0;
		while (!pending.isEmpty()) {
			pendingModel = pending.get(idx);

			if (pendingModel.isQueued() && pendingModel.canStart() && pendingModel.processing.compareAndSet(false, true)) {
				try (
					SceneUploader sceneUploader = SceneUploader.POOL.acquire();
					FacePrioritySorter facePrioritySorter = FacePrioritySorter.POOL.acquire()
				) {
					clientVisibleFaces.reset();
					clientCulledFaces.reset();
					pendingModel.uploadFunc.upload(
						sceneUploader,
						facePrioritySorter,
						clientVisibleFaces,
						clientCulledFaces,
						pendingModel
					);
				}

				pending.remove(idx);
				hasStolen = true;
			}

			if (shouldBlock && pendingModel.waitForCompletion(10))
				pending.remove(idx);

			if (pending.isEmpty())
				break;

			idx = (idx + 1) % pending.size();
			if (idx == 0 && !shouldBlock) {
				// We've wrapped around to the start, check if any work was stolen.
				// If no work was stolen, the next iteration we should block since we'll never be able to steal
				shouldBlock = !hasStolen;
				hasStolen = false;
			}
		}

		frameTimer.end(Timer.MODEL_UPLOAD_COMPLETE);
	}


	private AsyncCachedModel obtainAvailableAsyncCachedModel(boolean shouldBlock) {
		if (AsyncCachedModel.POOL == null || disabledRenderThreads)
			return null;

		return shouldBlock ? AsyncCachedModel.POOL.acquireBlocking() : AsyncCachedModel.POOL.acquire();
	}
}
