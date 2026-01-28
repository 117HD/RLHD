package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.hooks.*;
import net.runelite.client.callback.RenderCallbackManager;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.ThreadingMode;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.PrimitiveIntArray;

import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.PROCESSOR_COUNT;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_ALPHA;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PLAYER;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_SHADOW;

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
	private SceneUploader sceneUploader;

	@Inject
	private FacePrioritySorter facePrioritySorter;

	@Inject
	private ZoneRenderer renderer;

	private final int[] worldPos = new int[3];
	private final int[] drawnDynamicRenderableCount = new int[RL_RENDER_THREADS + 1];
	private int[][] asyncDynamicWorldPos = new int[RL_RENDER_THREADS][3];

	private AsyncUploadData[] asyncUploadPool;
	private int lastAsyncUploadIdx;
	private boolean disabledRenderThreads;

	private final ArrayList<AsyncCachedModel> pending = new ArrayList<>();
	private final PrimitiveIntArray clientVisibleFaces = new PrimitiveIntArray();
	private final PrimitiveIntArray clientCulledFaces = new PrimitiveIntArray();

	public int gpuFlags() {
		asyncUploadPool = null;

		int flags = 0;
		ThreadingMode threadingMode = config.threadedUpload();
		if(threadingMode != ThreadingMode.DISABLED && PROCESSOR_COUNT > 1) {
			int threadCount = (int) (PROCESSOR_COUNT * threadingMode.threadRatio);
			// RENDER_THREADS will act as suppliers into the Job System, so this will be 2 + Client Suppliers
			flags |= DrawCallbacks.RENDER_THREADS(RL_RENDER_THREADS);

			asyncUploadPool = new AsyncUploadData[threadCount];
			for(int i = 0; i < asyncUploadPool.length; i++) {
				if(asyncUploadPool[i] == null)
					asyncUploadPool[i] = new AsyncUploadData(8, plugin.getInjector());
			}
		}
		return flags;
	}

	public void update() {
		if(asyncUploadPool == null)
			return;

		if(plugin.isPowerSaving) {
			if(!disabledRenderThreads) {
				disabledRenderThreads = true;
				client.setGpuFlags(plugin.gpuFlags & ~DrawCallbacks.RENDER_THREADS(RL_RENDER_THREADS));
			}
		} else if(disabledRenderThreads) {
			disabledRenderThreads = false;
			client.setGpuFlags(plugin.gpuFlags);
		}

		Arrays.fill(drawnDynamicRenderableCount, 0);
	}

	public int getDrawnDynamicRenderableCount() {
		int count = 0;
		for(int i = 0; i < drawnDynamicRenderableCount.length; i++)
			count += drawnDynamicRenderableCount[i];
		return count;
	}

	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orientation, int x, int y, int z) {
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || ctx.isLoading || !renderCallbackManager.drawObject(scene, gameObject))
			return;

		ctx.sceneContext.localToWorld(gameObject.getLocalLocation(), gameObject.getPlane(), worldPos);
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

		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (gameObject.getX() >> 10) + offset;
		int zz = (gameObject.getY() >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		m.calculateBoundsCylinder();

		final int modelCalcification = sceneManager.isRoot(ctx) ? renderer.sceneCamera.classifySphere(x, y, z, m.getRadius()) : 1;
		if (sceneManager.isRoot(ctx)) {
			// Additional Culling checks to help reduce dynamic object perf impact when off screen
			if (!zone.inSceneFrustum && zone.inShadowFrustum && !modelOverride.castShadows)
				return;

			if (zone.inSceneFrustum && !modelOverride.castShadows && modelCalcification == -1)
				return;

			if (!zone.inSceneFrustum && zone.inShadowFrustum && modelOverride.castShadows &&
				!renderer.directionalShadowCasterVolume.intersectsPoint(x, y, z))
				return;
		}
		plugin.drawnTempRenderableCount++;

		final boolean hasAlpha = m.getFaceTransparencies() != null || modelOverride.mightHaveTransparency;
		final AsyncCachedModel asyncModelCache = obtainAvailableAsyncCachedModel(false);
		if (asyncModelCache != null) {
			asyncModelCache.queue(m, hasAlpha ? zone : null,
				(sceneUploader, facePrioritySorter, visibleFaces, culledFaces, cachedModel) -> {
					final long asyncStart = System.nanoTime();
					drawTempAsync(
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
						modelCalcification == 0,
						hasAlpha,
						orientation, x, y, z
					);
					frameTimer.add(Timer.DRAW_TEMP_ASYNC, System.nanoTime() - asyncStart);
				}
			);
			return;
		}

		try {
			drawTempAsync(
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
				hasAlpha,
				modelCalcification == 0,
				orientation, x, y, z
			);
		} catch (Exception e) {
			log.error("Error drawing temp object", e);
		}
	}

	private void drawTempAsync(
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
		int orientation, int x, int y, int z) {

		boolean shouldSort = (hasAlpha || renderable instanceof Player) && (!sceneManager.isRoot(ctx) || zone.inSceneFrustum);
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


		if(visibleFaces.length > 0) {
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

			opaqueView.end();
			if (renderable instanceof Player) {
				if (opaqueView.getEndOffset() > opaqueView.getStartOffset()) {
					// Fix rendering projectiles from boats with hide roofs enabled
					int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
					zone.addPlayerModel(
						opaqueView.vao,
						opaqueView.tboTexId,
						opaqueView.getStartOffset(),
						opaqueView.getEndOffset(),
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
					// Fix rendering projectiles from boats with hide roofs enabled
					int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
					zone.addTempAlphaModel(
						modelOverride,
						alphaView.vao,
						alphaView.tboTexId,
						alphaView.getStartOffset(),
						alphaView.getEndOffset(),
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
		if (ctx == null || ctx.isLoading || !renderCallbackManager.drawObject(scene, tileObject))
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (x >> 10) + offset;
		int zz = (z >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		if (sceneManager.isRoot(ctx)) {
			// Cull based on detail draw distance
			float squaredDistance = renderer.sceneCamera.squaredDistanceTo(x, y, z);
			int detailDrawDistanceTiles = plugin.configDetailDrawDistance * LOCAL_TILE_SIZE;
			if (squaredDistance > detailDrawDistanceTiles * detailDrawDistanceTiles)
				return;

			// Hide everything outside the current area if area hiding is enabled
			if (ctx.sceneContext.currentArea != null) {
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

			if (!zone.initialized)
				return;
		}

		int[] worldPos = renderThreadId > 0 ? asyncDynamicWorldPos[renderThreadId] : this.worldPos;
		ctx.sceneContext.localToWorld(tileObject.getLocalLocation(), tileObject.getPlane(), worldPos);
		int uuid = ModelHash.generateUuid(client, tileObject.getHash(), r);
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		m.calculateBoundsCylinder();

		final int modelCalcification = sceneManager.isRoot(ctx) ? renderer.sceneCamera.classifySphere(x, y, z, m.getRadius()) : 1;
		if (sceneManager.isRoot(ctx)) {
			// Additional Culling checks to help reduce dynamic object perf impact when off screen
			if (!zone.inSceneFrustum && zone.inShadowFrustum && !modelOverride.castShadows)
				return;

			if (zone.inSceneFrustum && !modelOverride.castShadows && modelCalcification == -1)
				return;

			if (!zone.inSceneFrustum && zone.inShadowFrustum && modelOverride.castShadows &&
				!renderer.directionalShadowCasterVolume.intersectsPoint(x, y, z))
				return;
		}
		drawnDynamicRenderableCount[renderThreadId + 1]++;

		final int preOrientation = HDUtils.getModelPreOrientation(HDUtils.getObjectConfig(tileObject));
		final boolean hasAlpha = m.getFaceTransparencies() != null || modelOverride.mightHaveTransparency;

		final AsyncCachedModel asyncModelCache = obtainAvailableAsyncCachedModel(renderThreadId >= 0);
		if(asyncModelCache != null) {
			// Fast path, buffer the model into the job queue to unblock rl internals
			asyncModelCache.queue(m, hasAlpha ? zone : null,
				(sceneUploader, facePrioritySorter, visibleFaces, culledFaces, cachedModel) -> {
					final long asyncStart = System.nanoTime();
					drawDynamicAsync(
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
						modelCalcification == 0,
						hasAlpha,
						preOrientation, orient,
						x, y, z
					);
					frameTimer.add(Timer.DRAW_DYNAMIC_ASYNC, System.nanoTime() - asyncStart);
				});
			return;
		}

		if(renderThreadId >= 0)
			return;

		drawDynamicAsync(
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
			modelCalcification == 0,
			hasAlpha,
			preOrientation, orient,
			x, y, z
		);
	}

	private void drawDynamicAsync(
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

			if(visibleFaces.length > 0) {
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
							alphaView.vao,
							alphaView.tboTexId,
							alphaView.getStartOffset(),
							alphaView.getEndOffset(),
							plane,
							x & 1023,
							y,
							z & 1023
						);
					}
				}
			}
		}catch (Exception e) {
			log.error("Error rendering dynamic object", e);
		}
	}

	public void ensureAsyncUploadsComplete(Zone zone) {
		if(asyncUploadPool == null)
			return;

		frameTimer.begin(Timer.MODEL_UPLOAD_COMPLETE);
		pending.clear();
		if(zone != null) {
			AsyncCachedModel model;
			while ((model = zone.pendingModelJobs.poll()) != null)
				pending.add(model);
		} else {
			for (AsyncUploadData asyncData : asyncUploadPool) {
				for(int i = 0; i < asyncData.models.length; i++)
					pending.add(asyncData.models[i]);
			}
		}

		AsyncCachedModel pendingModel;
		boolean shouldBlock = false, hasStolen = false;
		int idx = 0;
		while (!pending.isEmpty()) {
			pendingModel = pending.get(idx);

			if(pendingModel.isQueued() && pendingModel.canStart() && pendingModel.processing.compareAndSet(false, true)) {
				clientVisibleFaces.reset();
				clientCulledFaces.reset();
				pendingModel.uploadFunc.upload(
					sceneUploader,
					facePrioritySorter,
					clientVisibleFaces,
					clientCulledFaces,
					pendingModel);

				pending.remove(idx);
				hasStolen = true;
			}

			if(shouldBlock && pendingModel.waitForCompletion(10))
				pending.remove(idx);

			if(pending.isEmpty())
				break;

			idx = (idx + 1) % pending.size();
			if(idx == 0 && !shouldBlock) {
				// We've wrapped around to the start, check if any work was stolen.
				// If no work was stolen, the next iteration we should block since we'll never be able to steal
				shouldBlock = !hasStolen;
				hasStolen = false;
			}
		}

		frameTimer.end(Timer.MODEL_UPLOAD_COMPLETE);
	}

	private AsyncCachedModel obtainAvailableAsyncCachedModel(boolean shouldBlock) {
		if(asyncUploadPool == null || disabledRenderThreads)
			return null;

		final long TIME_OUT = shouldBlock ? TimeUnit.MILLISECONDS.toNanos(10) : TimeUnit.MICROSECONDS.toNanos(5);
		final int len = asyncUploadPool.length;
		final int offset = lastAsyncUploadIdx;
		final long start = System.nanoTime();
		while (true) {
			for (int i = 0; i < len; i++) {
				final int idx = (offset + i) % len;
				final AsyncUploadData data = asyncUploadPool[idx];

				if(data.freeModelsCount.get() > 0) {
					AsyncCachedModel model = data.freeModels.poll();
					if (model != null) {
						lastAsyncUploadIdx = (idx + 1) % len;
						data.freeModelsCount.decrementAndGet();
						return model;
					}
				}
			}
			if(System.nanoTime() - start > TIME_OUT)
				return null;

			Thread.yield();
		}
	}
}
