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
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.collections.PrimitiveCharArray;

import static net.runelite.api.Perspective.*;
import static net.runelite.api.hooks.DrawCallbacks.*;
import static rs117.hd.HdPlugin.PROCESSOR_COUNT;
import static rs117.hd.renderer.zone.FrameContext.VAO_ALPHA;
import static rs117.hd.renderer.zone.FrameContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.FrameContext.VAO_PLAYER;
import static rs117.hd.renderer.zone.FrameContext.VAO_SHADOW;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class ModelStreamingManager {
	public static final ConcurrentPool<PrimitiveCharArray> FACE_INDICES = new ConcurrentPool<>(PrimitiveCharArray::new);
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
			AsyncCachedModel.initialize(injector);

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

	private boolean isAlphaModel(Model m) {
		if (m.getTransparency() != 0 || m.getFaceTransparencies() != null)
			return true;

		final short[] faceTextures = m.getFaceTextures();
		if (faceTextures != null) {
			int faceCount = m.getFaceCount();
			for (int f = 0; f < faceCount; f++)
				if (Material.hasVanillaTransparency(faceTextures[f]))
					return true;
		}

		return false;
	}

	public void drawTemp(
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

		if (!zone.initialized || zone.fadingAlpha > 1.0f)
			return;

		final StreamingContext streamingContext = context(renderThreadId);
		final float[] objectWorldPos = vec4(streamingContext.objectWorldPos, x, y, z, 1.0f);
		if (ctx.uboWorldViewStruct != null)
			ctx.uboWorldViewStruct.project(objectWorldPos);

		final int uuid;
		final float modelFade;
		if (r instanceof DynamicObject) {
			int id = tileObject.getId();
			int impostorId = root.sceneContext.animatedDynamicObjectImpostors.getOrDefault(id, id);
			uuid = ModelHash.packUuid(ModelHash.getType(tileObject.getHash()), impostorId);

			// Cull dynamic models based on detail draw distance
			final int detailDrawDistanceTiles = plugin.configDetailDrawDistance * LOCAL_TILE_SIZE;
			final float squaredDistance = renderer.sceneCamera.squaredDistanceTo(objectWorldPos[0], objectWorldPos[1], objectWorldPos[2]);
			final float detailDrawDistanceTilesSquared = detailDrawDistanceTiles * detailDrawDistanceTiles;
			if (squaredDistance > detailDrawDistanceTilesSquared && modelOverrideManager.allowDetailCulling(uuid))
				return;

			// Fade dynamic models that are close to the detail draw distance so that they don't pop in/out
			final float fadeRange = 8.0f * LOCAL_TILE_SIZE;
			final float fadeEndSq = detailDrawDistanceTiles * detailDrawDistanceTiles;
			final float fadeStart = max(0.0f, detailDrawDistanceTiles - fadeRange);
			final float fadeStartSq = fadeStart * fadeStart;
			modelFade = saturate((squaredDistance - fadeStartSq) / (fadeEndSq - fadeStartSq));
		} else {
			uuid = ModelHash.generateUuid(client, tileObject.getHash(), r);
			modelFade = 0;
		}

		// Hide everything outside the current area if area hiding is enabled
		if (ctx.sceneContext.currentArea != null && scene.getWorldViewId() == WorldView.TOPLEVEL) {
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

		ctx.sceneContext.localToWorld(x, z, tileObject.getPlane(), streamingContext.worldPos);
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
			(modelOverride.mightHaveTransparency || isAlphaModel(m)) &&
			(!sceneManager.isRoot(ctx) || zone.inSceneFrustum);
		final Zone.AlphaModel alphaModel = hasAlpha ?
			zone.requestTempAlphaModel(
				modelOverride,
				Math.min(ctx.maxLevel, tileObject.getPlane()),
				x & 1023,
				y - (r instanceof Actor ? r.getModelHeight() : 0), // order players over objects?
				z & 1023
			) : null;

		final int drawIndex = renderThreadId != -1 ? -1 : renderer.frameContext().obtainDrawIndex(r instanceof Player ? VAO_PLAYER : VAO_OPAQUE);
		final boolean isModelPartiallyVisible = sceneManager.isRoot(ctx) && modelClassification == 0;
		final AsyncCachedModel asyncModelCache = obtainAvailableAsyncCachedModel(m);
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
				modelFade,
				orient,
				x, y, z,
				this::uploadTempModelAsync
			);
			return;
		}

		uploadTempModel(
			ctx,
			projection,
			tileObject,
			r,
			modelOverride,
			m,
			zone,
			alphaModel,
			isModelPartiallyVisible,
			-1,
			drawIndex,
			modelFade,
			orient,
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
		float modelFade,
		int orientation,
		int x, int y, int z
	) {
		final long t = System.nanoTime();
		uploadTempModel(
			ctx,
			projection,
			tileObject,
			renderable,
			modelOverride,
			model,
			zone,
			alphaModel,
			isModelPartiallyVisible,
			-1,
			drawIndex,
			modelFade,
			orientation,
			x, y, z
		);
		frameTimer.add(renderable instanceof Actor ? Timer.DRAW_TEMP_ASYNC : Timer.DRAW_DYNAMIC_ASYNC, System.nanoTime() - t);
	}

	public void uploadTempModel(
		WorldViewContext ctx,
		Projection projection,
		@Nullable TileObject tileObject,
		Renderable renderable,
		ModelOverride modelOverride,
		Model m,
		@Nullable Zone zone,
		@Nullable Zone.AlphaModel alphaModel,
		boolean isModelPartiallyVisible,
		int vaoType,
		int drawIndex,
		float modelFade,
		int orient,
		float x, float y, float z
	) {
		final PrimitiveCharArray visibleFaces = FACE_INDICES.acquire();
		final PrimitiveCharArray culledFaces = FACE_INDICES.acquire();

		boolean isActor = renderable instanceof Actor;
		boolean isPlayer = renderable instanceof Player;
		final int renderMode = renderable.getRenderMode();
		boolean shouldSort =
			m.getTransparency() != 0 ||
			m.getFaceTransparencies() != null ||
			modelOverride.mightHaveTransparency ||
			renderable instanceof Player ||
			(
				renderMode != Renderable.RENDERMODE_UNSORTED &&
				renderMode != Renderable.RENDERMODE_DEFAULT &&
				renderMode != Renderable.RENDERMODE_UNSORTED_NO_DEPTH
			);

		try (
			SceneUploader sceneUploader = SceneUploader.POOL.acquire();
			FacePrioritySorter facePrioritySorter = shouldSort ? FacePrioritySorter.POOL.acquire() : null
		) {
			final int[] faceDistances = shouldSort ? PooledArrayType.INT.borrow(m.getFaceCount()) : null;
			shouldSort &= sceneUploader.preprocessTempModel(
				projection,
				plugin.cameraFrustum,
				faceDistances,
				visibleFaces,
				culledFaces,
				isModelPartiallyVisible,
				modelOverride,
				m,
				isPlayer,
				orient,
				x, y, z
			);

			final int preOrientation = HDUtils.getModelPreOrientation(HDUtils.getObjectConfig(tileObject));
			final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
			if (shouldSort && !isSquashed)
				facePrioritySorter.sortModelFaces(visibleFaces, m, faceDistances, !isActor);

			if (facePrioritySorter != null)
				PooledArrayType.INT.release(faceDistances);

			if (culledFaces.length > 0 &&
				modelOverride.castShadows &&
				plugin.configShadowMode != ShadowMode.OFF &&
				(!sceneManager.isRoot(ctx) || zone != null && zone.inShadowFrustum)
			) {
				final DynamicModelVAO.View shadowView = ctx.beginDraw(VAO_SHADOW, culledFaces.length);
				final int shadowModelIdx = SceneUploader.writeDynamicModelData(shadowView.tboM, x, y, z, modelFade, m, modelOverride, ctx, zone);
				sceneUploader.uploadTempModel(
					culledFaces,
					m,
					modelOverride,
					preOrientation,
					orient,
					shadowModelIdx,
					shadowModelIdx,
					true,
					shadowView,
					shadowView
				);
				shadowView.end();
			}

			if (visibleFaces.length > 0) {
				final int alphaFaceCount = alphaModel != null ? sceneUploader.tempModelAlphaFaces : 0;
				final int opaqueFaceCount = visibleFaces.length - alphaFaceCount;
				assert opaqueFaceCount >= 0 && alphaFaceCount >= 0 : "Invalid face counts: " + opaqueFaceCount + ", " + alphaFaceCount;

				// opaque player faces have their own vao and are drawn in a separate pass from normal opaque faces
				// because they are not depth tested. transparent player faces don't need their own vao because normal
				// transparent faces are already not depth tested
				if (vaoType == -1)
					vaoType = isPlayer ? VAO_PLAYER : VAO_OPAQUE;
				final DynamicModelVAO.View opaqueView = ctx.beginDraw(vaoType, drawIndex, opaqueFaceCount);
				final DynamicModelVAO.View alphaView = alphaFaceCount > 0 ? ctx.beginDraw(VAO_ALPHA, alphaFaceCount) : opaqueView;

				final int opaqueModelIdx = SceneUploader.writeDynamicModelData(opaqueView.tboM, x, y, z, modelFade, m, modelOverride, ctx, zone);
				final int alphaModelIdx = alphaFaceCount > 0 ? SceneUploader.writeDynamicModelData(alphaView.tboM, x, y, z, modelFade, m, modelOverride, ctx, zone) : opaqueModelIdx;

				sceneUploader.uploadTempModel(
					visibleFaces,
					m,
					modelOverride,
					preOrientation,
					orient,
					opaqueModelIdx,
					alphaModelIdx,
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


	private synchronized AsyncCachedModel obtainAvailableAsyncCachedModel(Model model) {
		if (AsyncCachedModel.POOL == null || numRenderThreads <= 0)
			return null;

		AsyncCachedModel result = AsyncCachedModel.POOL.acquire();
		if (result == null)
			return null;

		if (result.setup(model))
			return result;

		// We failed to reserve space to cache the model, so return the model back to the pool
		AsyncCachedModel.POOL.recycle(result);

		return null;
	}
}
