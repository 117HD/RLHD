package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.util.ArrayList;
import java.util.Arrays;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.ShadowMode;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static net.runelite.api.Perspective.*;
import static net.runelite.api.hooks.DrawCallbacks.*;
import static rs117.hd.HdPlugin.PROCESSOR_COUNT;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_ALPHA;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PLAYER;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_SHADOW;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class ModelStreamingManager {
	public static final ConcurrentPool<PrimitiveIntArray> FACE_INDICES = new ConcurrentPool<>(PrimitiveIntArray::new);
	public static final int RL_RENDER_THREADS = 2;

	@Inject
	private Injector injector;

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private ZoneRenderer renderer;

	private final ArrayList<AsyncCachedModel> pending = new ArrayList<>();
	private final StreamingContext[] streamingContexts = new StreamingContext[RL_RENDER_THREADS + 1];
	private int numRenderThreads = -1;

	static final class StreamingContext {
		final int[] worldPos = new int[3];
		final float[] objectWorldPos = new float[4];
		int renderableCount;
	}

	public void initialize() {
		for (int i = 0; i < streamingContexts.length; i++)
			streamingContexts[i] = injector.getInstance(StreamingContext.class);

		if (useMultithreading())
			AsyncCachedModel.initialize(injector, config.asyncModelCacheSizeMiB() * MiB);

		eventBus.register(this);
		updateRenderThreads();
	}

	public void destroy() {
		ensureAsyncUploadsComplete(null);

		eventBus.unregister(this);
		AsyncCachedModel.destroy();
		Arrays.fill(streamingContexts, null);
		numRenderThreads = -1;
	}

	public void reinitialize() {
		destroy();
		initialize();
	}

	StreamingContext context() {
		return streamingContexts[0];
	}

	StreamingContext context(int renderThreadId) {
		return streamingContexts[renderThreadId + 1];
	}

	private boolean useMultithreading() {
		return config.multithreadedModelProcessing() && PROCESSOR_COUNT > 1;
	}

	private void updateRenderThreads() {
		assert client.isClientThread();
		// Render threads will act as suppliers into the job system, so RL_RENDER_THREADS + the client thread
		int renderThreads = useMultithreading() && !plugin.isPowerSaving ? RL_RENDER_THREADS : 0;
		if (renderThreads != numRenderThreads) {
			int gpuFlags = plugin.gpuFlags & ~DrawCallbacks.RENDER_THREADS(RENDER_THREADS_MASK);
			client.setGpuFlags(gpuFlags | DrawCallbacks.RENDER_THREADS(renderThreads));
			numRenderThreads = renderThreads;
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event) {
		for (int i = 0; i < streamingContexts.length; i++)
			streamingContexts[i].renderableCount = 0;

		updateRenderThreads();
	}

	public int getDrawnDynamicRenderableCount() {
		int count = 0;
		for (int i = 0; i < streamingContexts.length; i++)
			count += streamingContexts[i].renderableCount;
		return count;
	}

	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orientation, int x, int y, int z) {
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading || !renderCallbackManager.drawObject(scene, gameObject))
			return;

		final StreamingContext streamingContext = context();
		ctx.sceneContext.localToWorld(gameObject.getLocalLocation(), gameObject.getPlane(), streamingContext.worldPos);
		// Hide everything outside the current area if area hiding is enabled
		if (ctx.sceneContext.currentArea != null && scene.getWorldViewId() == WorldView.TOPLEVEL) {
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

		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, streamingContext.worldPos);
		if (modelOverride.hide)
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (gameObject.getX() >> 10) + offset;
		int zz = (gameObject.getY() >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		m.calculateBoundsCylinder();

		final float[] objectWorldPos = vec4(streamingContext.objectWorldPos, x, y, z, 1.0f);
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

		final boolean hasAlpha =
			(m.getFaceTransparencies() != null || modelOverride.mightHaveTransparency) &&
			(!sceneManager.isRoot(ctx) || zone.inSceneFrustum);
		final Zone.AlphaModel alphaModel = hasAlpha ?
			zone.requestTempAlphaModel(
				modelOverride,
				min(ctx.maxLevel, gameObject.getPlane()),
				x & 1023,
				y - renderable.getModelHeight(),
				z & 1023
			) : null;

		final int drawIndex = ctx.obtainDrawIndex(renderable instanceof Player ? VAO_PLAYER : VAO_OPAQUE);
		final boolean isModelPartiallyVisible = sceneManager.isRoot(ctx) && modelClassification == 0;
		final AsyncCachedModel asyncModelCache = obtainAvailableAsyncCachedModel(false);
		if (asyncModelCache != null) {
			asyncModelCache.queue(
				ctx,
				worldProjection,
				gameObject,
				renderable,
				modelOverride,
				m,
				zone,
				alphaModel,
				isModelPartiallyVisible,
				drawIndex,
				orientation,
				x, y, z,
				this::uploadTempModelAsync
			);
			return;
		}

		uploadTempModel(
			worldProjection,
			ctx,
			gameObject,
			renderable,
			modelOverride,
			zone,
			m,
			alphaModel,
			isModelPartiallyVisible,
			drawIndex,
			orientation,
			x, y, z
		);
	}

	private void uploadTempModelAsync(
		WorldViewContext ctx,
		Projection projection,
		TileObject tileObject,
		Renderable renderable,
		ModelOverride modelOverride,
		Model model,
		Zone zone,
		Zone.AlphaModel alphaModel,
		boolean isModelPartiallyVisible,
		int drawIndex,
		int orientation,
		int x, int y, int z
	) {
		final long asyncStart = System.nanoTime();
		uploadTempModel(
			projection,
			ctx,
			(GameObject) tileObject,
			renderable,
			modelOverride,
			zone,
			model,
			alphaModel,
			isModelPartiallyVisible,
			drawIndex,
			orientation,
			x, y, z
		);
		frameTimer.add(Timer.DRAW_TEMP_ASYNC, System.nanoTime() - asyncStart);
	}

	private void uploadTempModel(
		Projection worldProjection,
		WorldViewContext ctx,
		GameObject gameObject,
		Renderable renderable,
		ModelOverride modelOverride,
		Zone zone,
		Model m,
		Zone.AlphaModel alphaModel,
		boolean isModelPartiallyVisible,
		int drawIndex,
		int orientation,
		int x, int y, int z
	) {
		final PrimitiveIntArray visibleFaces = FACE_INDICES.acquire();
		final PrimitiveIntArray culledFaces = FACE_INDICES.acquire();

		boolean shouldSort =
			renderable.getRenderMode() == Renderable.RENDERMODE_SORTED ||
			renderable.getRenderMode() == Renderable.RENDERMODE_SORTED_NO_DEPTH;
		boolean isPlayer = renderable instanceof Player;
		try (
			SceneUploader sceneUploader = SceneUploader.POOL.acquire();
			FacePrioritySorter facePrioritySorter = shouldSort ? FacePrioritySorter.POOL.acquire() : null
		) {
			shouldSort &= sceneUploader.preprocessTempModel(
				worldProjection,
				plugin.cameraFrustum,
				shouldSort ? facePrioritySorter.faceDistances : null,
				visibleFaces,
				culledFaces,
				isModelPartiallyVisible,
				modelOverride,
				m,
				isPlayer,
				orientation,
				x, y, z
			);

			final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
			if (shouldSort && !isSquashed)
				facePrioritySorter.sortModelFaces(visibleFaces, m);

			final int preOrientation = HDUtils.getModelPreOrientation(gameObject.getConfig());
			if (culledFaces.length > 0 &&
				modelOverride.castShadows &&
				plugin.configShadowMode != ShadowMode.OFF &&
				(!sceneManager.isRoot(ctx) || zone.inShadowFrustum)
			) {
				final DynamicModelVAO.View shadowView = ctx.beginDraw(VAO_SHADOW, culledFaces.length);
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
				final int alphaFaceCount = alphaModel != null ? sceneUploader.tempModelAlphaFaces : 0;
				final int opaqueFaceCount = visibleFaces.length - alphaFaceCount;

				final DynamicModelVAO.View opaqueView = ctx.beginDraw(isPlayer ? VAO_PLAYER : VAO_OPAQUE, drawIndex, opaqueFaceCount);
				final DynamicModelVAO.View alphaView = alphaFaceCount > 0 ? ctx.beginDraw(VAO_ALPHA, alphaFaceCount) : opaqueView;

				sceneUploader.uploadTempModel(
					visibleFaces,
					m,
					modelOverride,
					preOrientation,
					orientation,
					isSquashed,
					opaqueView,
					alphaView
				);

				// Fix rendering projectiles from boats with hide roofs enabled
				if (opaqueView != alphaView && alphaView.getEndOffset() > alphaView.getStartOffset()) {
					alphaModel.setView(alphaView);
					alphaView.end();
				}
				opaqueView.end();
			}
		} catch (Exception e) {
			log.error("Error rendering temp object", e);
		} finally {
			FACE_INDICES.recycle(visibleFaces);
			FACE_INDICES.recycle(culledFaces);
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
		WorldViewContext root = sceneManager.getRoot();
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (root == null || ctx == null ||
			!sceneManager.isRoot(ctx) && ctx.isLoading ||
			!renderCallbackManager.drawObject(scene, tileObject))
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (x >> 10) + offset;
		int zz = (z >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		if (!zone.initialized)
			return;

		final StreamingContext streamingContext = context(renderThreadId);
		final float[] objectWorldPos = vec4(streamingContext.objectWorldPos, x, y, z, 1.0f);
		if (ctx.uboWorldViewStruct != null)
			ctx.uboWorldViewStruct.project(objectWorldPos);

		final int uuid;
		if (r instanceof DynamicObject) {
			int id = tileObject.getId();
			int impostorId = root.sceneContext.animatedDynamicObjectImpostors.getOrDefault(id, id);
			uuid = ModelHash.packUuid(ModelHash.getType(tileObject.getHash()), impostorId);
		} else {
			uuid = ModelHash.generateUuid(client, tileObject.getHash(), r);
		}

		// Cull based on detail draw distance
		float squaredDistance = renderer.sceneCamera.squaredDistanceTo(objectWorldPos[0], objectWorldPos[1], objectWorldPos[2]);
		int detailDrawDistanceTiles = plugin.configDetailDrawDistance * LOCAL_TILE_SIZE;
		if (squaredDistance > detailDrawDistanceTiles * detailDrawDistanceTiles && modelOverrideManager.allowDetailCulling(uuid))
			return;

		// Hide everything outside the current area if area hiding is enabled
		if (ctx.sceneContext.currentArea != null) {
			var base = ctx.sceneContext.sceneBase;
			assert base != null;
			boolean inArea = ctx.sceneContext.currentArea.containsPoint(
				base[0] + ((int) objectWorldPos[0] >> Perspective.LOCAL_COORD_BITS),
				base[1] + ((int) objectWorldPos[2] >> Perspective.LOCAL_COORD_BITS),
				base[2] + client.getTopLevelWorldView().getPlane()
			);
			if (!inArea)
				return;
		}

		ctx.sceneContext.localToWorld(tileObject.getLocalLocation(), tileObject.getPlane(), streamingContext.worldPos);
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, streamingContext.worldPos);
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
		streamingContext.renderableCount++;

		final boolean hasAlpha =
			(m.getFaceTransparencies() != null || modelOverride.mightHaveTransparency) &&
			(!sceneManager.isRoot(ctx) || zone.inSceneFrustum);
		final Zone.AlphaModel alphaModel = hasAlpha ?
			zone.requestTempAlphaModel(
				modelOverride,
				Math.min(ctx.maxLevel, tileObject.getPlane()),
				x & 1023,
				y,
				z & 1023
			) : null;

		final int drawIndex = renderThreadId == -1 ? ctx.obtainDrawIndex(VAO_OPAQUE) : -1;
		final boolean isModelPartiallyVisible = sceneManager.isRoot(ctx) && modelClassification == 0;
		final AsyncCachedModel asyncModelCache = obtainAvailableAsyncCachedModel(renderThreadId >= 0);
		if (asyncModelCache != null) {
			// Fast path, buffer the model into the job queue to unblock rl internals
			asyncModelCache.queue(
				ctx,
				projection,
				tileObject,
				r,
				modelOverride,
				m,
				zone,
				alphaModel,
				isModelPartiallyVisible,
				drawIndex,
				orient,
				x, y, z,
				this::uploadDynamicModelAsync
			);
			return;
		}

		uploadDynamicModel(
			ctx,
			projection,
			tileObject,
			r,
			modelOverride,
			m,
			zone,
			alphaModel,
			isModelPartiallyVisible,
			drawIndex,
			orient,
			x, y, z
		);
	}

	private void uploadDynamicModelAsync(
		WorldViewContext ctx,
		Projection projection,
		TileObject tileObject,
		Renderable renderable,
		ModelOverride modelOverride,
		Model model,
		Zone zone,
		Zone.AlphaModel alphaModel,
		boolean isModelPartiallyVisible,
		int drawIndex,
		int orientation,
		int x, int y, int z
	) {
		final long asyncStart = System.nanoTime();
		uploadDynamicModel(
			ctx,
			projection,
			tileObject,
			renderable,
			modelOverride,
			model,
			zone,
			alphaModel,
			isModelPartiallyVisible,
			drawIndex,
			orientation,
			x, y, z
		);
		frameTimer.add(Timer.DRAW_DYNAMIC_ASYNC, System.nanoTime() - asyncStart);
	}

	private void uploadDynamicModel(
		WorldViewContext ctx,
		Projection projection,
		TileObject tileObject,
		Renderable renderable,
		ModelOverride modelOverride,
		Model m,
		Zone zone,
		Zone.AlphaModel alphaModel,
		boolean isModelPartiallyVisible,
		int drawIndex,
		int orient,
		int x, int y, int z
	) {
		final PrimitiveIntArray visibleFaces = FACE_INDICES.acquire();
		final PrimitiveIntArray culledFaces = FACE_INDICES.acquire();

		boolean shouldSort = renderable.getRenderMode() != Renderable.RENDERMODE_UNSORTED;
		try (
			SceneUploader sceneUploader = SceneUploader.POOL.acquire();
			FacePrioritySorter facePrioritySorter = shouldSort ? FacePrioritySorter.POOL.acquire() : null
		) {
			shouldSort &= sceneUploader.preprocessTempModel(
				projection,
				plugin.cameraFrustum,
				shouldSort ? facePrioritySorter.faceDistances : null,
				visibleFaces,
				culledFaces,
				isModelPartiallyVisible,
				modelOverride,
				m,
				false,
				orient,
				x, y, z
			);

			final int preOrientation = HDUtils.getModelPreOrientation(HDUtils.getObjectConfig(tileObject));
			final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
			if (shouldSort && !isSquashed)
				facePrioritySorter.sortModelFaces(visibleFaces, m, true);

			if (culledFaces.length > 0 &&
				modelOverride.castShadows &&
				plugin.configShadowMode != ShadowMode.OFF &&
				(!sceneManager.isRoot(ctx) || zone.inShadowFrustum)
			) {
				final DynamicModelVAO.View shadowView = ctx.beginDraw(VAO_SHADOW, culledFaces.length);
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
				final int alphaFaceCount = alphaModel != null ? sceneUploader.tempModelAlphaFaces : 0;
				final int opaqueFaceCount = visibleFaces.length - alphaFaceCount;

				final DynamicModelVAO.View opaqueView = ctx.beginDraw(VAO_OPAQUE, drawIndex, opaqueFaceCount);
				final DynamicModelVAO.View alphaView = alphaFaceCount > 0 ? ctx.beginDraw(VAO_ALPHA, alphaFaceCount) : opaqueView;

				sceneUploader.uploadTempModel(
					visibleFaces,
					m,
					modelOverride,
					preOrientation,
					orient,
					isSquashed,
					opaqueView,
					alphaView
				);

				if (opaqueView != alphaView && alphaView.getEndOffset() > alphaView.getStartOffset()) {
					alphaModel.setView(alphaView);
					alphaView.end();
				}
				opaqueView.end();
			}
		} catch (Exception e) {
			log.error("Error rendering dynamic object", e);
		} finally {
			FACE_INDICES.recycle(visibleFaces);
			FACE_INDICES.recycle(culledFaces);
		}
	}

	public void ensureAsyncUploadsComplete(@Nullable Zone zone) {
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

			if (pendingModel.isQueued() && pendingModel.canStart() && pendingModel.processModel()) {
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
		if (AsyncCachedModel.POOL == null || numRenderThreads <= 0)
			return null;

		return shouldBlock ? AsyncCachedModel.POOL.acquireBlocking(5000) : AsyncCachedModel.POOL.acquire();
	}
}
