package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.lang.reflect.Array;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.renderer.zone.Zone.AlphaModel;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.jobs.Job;

import static rs117.hd.utils.collections.PooledArrayType.BYTE;
import static rs117.hd.utils.collections.PooledArrayType.FLOAT;
import static rs117.hd.utils.collections.PooledArrayType.INT;
import static rs117.hd.utils.collections.PooledArrayType.SHORT;

@Slf4j
@Getter
@Setter
@Accessors(chain = false)
public final class AsyncCachedModel extends Job implements Model {
	public static final Runtime RUNTIME = Runtime.getRuntime();
	public static final ConcurrentLinkedQueue<AsyncCachedModel> INFLIGHT = new ConcurrentLinkedQueue<>();
	public static ConcurrentPool<AsyncCachedModel> POOL;

	public static void initialize(Injector injector) {
		if (AsyncCachedModel.POOL == null)
			AsyncCachedModel.POOL = new ConcurrentPool<>(() -> injector.getInstance(AsyncCachedModel.class), 32);
	}

	public static void destroy() {
		INFLIGHT.clear();
		if (AsyncCachedModel.POOL != null)
			AsyncCachedModel.POOL.destroy();
		AsyncCachedModel.POOL = null;
	}

	private int sceneId;
	private int bufferOffset;
	private int uvBufferOffset;

	private int bottomY;
	private int radius;
	private int diameter;
	private int XYZMag;
	private int renderMode;

	private int modelHeight;
	private int animationHeightOffset;

	@Accessors(fluent = true)
	private boolean useBoundingBox;

	private int verticesCount;
	private int faceCount;

	private byte transparency;
	private byte overrideAmount;
	private byte overrideHue;
	private byte overrideSaturation;
	private byte overrideLuminance;

	private long hash;

	private final CachedArrayField<?>[] cachedFields = new CachedArrayField<?>[21];

	private final CachedArrayField<float[]> verticesX = addField(FLOAT, VERTEX_TYPE);
	private final CachedArrayField<float[]> verticesY = addField(FLOAT, VERTEX_TYPE);
	private final CachedArrayField<float[]> verticesZ = addField(FLOAT, VERTEX_TYPE);

	private final CachedArrayField<int[]> faceIndices1 = addField(INT, FACE_TYPE);
	private final CachedArrayField<int[]> faceIndices2 = addField(INT, FACE_TYPE);
	private final CachedArrayField<int[]> faceIndices3 = addField(INT, FACE_TYPE);

	private final CachedArrayField<int[]> faceColors1 = addField(INT, FACE_TYPE);
	private final CachedArrayField<int[]> faceColors2 = addField(INT, FACE_TYPE);
	private final CachedArrayField<int[]> faceColors3 = addField(INT, FACE_TYPE);

	private final CachedArrayField<short[]> unlitFaceColors = addField(SHORT, FACE_TYPE);
	private final CachedArrayField<short[]> faceTextures = addField(SHORT, FACE_TYPE);

	private final CachedArrayField<byte[]> faceRenderPriorities = addField(BYTE, FACE_TYPE);
	private final CachedArrayField<byte[]> faceTransparencies = addField(BYTE, FACE_TYPE);
	private final CachedArrayField<byte[]> faceBias = addField(BYTE, FACE_TYPE);
	private final CachedArrayField<byte[]> textureFaces = addField(BYTE, FACE_TYPE);

	private final CachedArrayField<int[]> texIndices1 = addField(INT, TEX_TYPE);
	private final CachedArrayField<int[]> texIndices2 = addField(INT, TEX_TYPE);
	private final CachedArrayField<int[]> texIndices3 = addField(INT, TEX_TYPE);

	private final CachedArrayField<int[]> vertexNormalsX = addField(INT, VERTEX_TYPE);
	private final CachedArrayField<int[]> vertexNormalsY = addField(INT, VERTEX_TYPE);
	private final CachedArrayField<int[]> vertexNormalsZ = addField(INT, VERTEX_TYPE);

	private final AtomicBoolean isProcessing = new AtomicBoolean(false);
	private final AtomicBoolean isCompleted = new AtomicBoolean(false);
	private WorldViewContext ctx;
	private Projection projection;
	@Nullable
	private TileObject tileObject;
	private Renderable renderable;
	private ModelOverride modelOverride;
	private Zone zone;
	private AlphaModel alphaModel;
	private boolean isModelPartiallyVisible;
	private int drawIndex;
	private float fade;
	private int orientation;
	private int x;
	private int y;
	private int z;
	private UploadModelFunc uploadFunc;
	private long availableMemory;

	@SuppressWarnings("unchecked")
	private <T> CachedArrayField<T> addField(PooledArrayType arrayType, int fieldType) {
		for (int i = 0; i < cachedFields.length; i++)
			if (cachedFields[i] == null)
				return (CachedArrayField<T>) (cachedFields[i] = new CachedArrayField<>(this, arrayType, fieldType));
		throw new RuntimeException("Created too many fields, only expected: " + cachedFields.length);
	}

	@Override
	public int[] getFaceColors1() { return faceColors1.getValue(); }

	@Override
	public int[] getFaceColors2() { return faceColors2.getValue(); }

	@Override
	public int[] getFaceColors3() { return faceColors3.getValue(); }

	@Override
	public short[] getUnlitFaceColors() { return unlitFaceColors.getValue(); }

	@Override
	public byte[] getFaceRenderPriorities() { return faceRenderPriorities.getValue(); }

	@Override
	public byte[] getFaceBias() { return faceBias.getValue(); }

	@Override
	public int[] getVertexNormalsX() { return vertexNormalsX.getValue(); }

	@Override
	public int[] getVertexNormalsY() { return vertexNormalsY.getValue(); }

	@Override
	public int[] getVertexNormalsZ() { return vertexNormalsZ.getValue(); }

	@Override
	public byte[] getTextureFaces() { return textureFaces.getValue(); }

	@Override
	public int[] getTexIndices1() { return texIndices1.getValue(); }

	@Override
	public int[] getTexIndices2() { return texIndices2.getValue(); }

	@Override
	public int[] getTexIndices3() { return texIndices3.getValue(); }

	@Override
	public float[] getVerticesX() { return verticesX.getValue(); }

	@Override
	public float[] getVerticesY() { return verticesY.getValue(); }

	@Override
	public float[] getVerticesZ() { return verticesZ.getValue(); }

	@Override
	public int[] getFaceIndices1() { return faceIndices1.getValue(); }

	@Override
	public int[] getFaceIndices2() { return faceIndices2.getValue(); }

	@Override
	public int[] getFaceIndices3() { return faceIndices3.getValue(); }

	@Override
	public byte[] getFaceTransparencies() { return faceTransparencies.getValue(); }

	@Override
	public short[] getFaceTextures() { return faceTextures.getValue(); }

	public synchronized boolean setup(Model model) {
		// Wait for completion so that the job has cleared the job system before clearing the isProcessing flag
		waitForCompletion(true);

		availableMemory = RUNTIME.freeMemory();
		if (processCachedFields(model, false))
			return true;

		// We've failed to obtain arrays to cache the model, return any we obtained and return false
		for (int i = 0; i < cachedFields.length; i++)
			cachedFields[i].reset();

		return false;
	}

	public synchronized void queue(
		@Nonnull WorldViewContext ctx,
		@Nonnull Projection projection,
		@Nullable TileObject tileObject,
		@Nonnull Renderable renderable,
		@Nonnull ModelOverride modelOverride,
		@Nonnull Model model,
		@Nonnull Zone zone,
		AlphaModel alphaModel,
		boolean isModelPartiallyVisible,
		int drawIndex,
		float fade,
		int orientation,
		int x, int y, int z,
		@Nonnull UploadModelFunc uploadFunc
	) {
		this.ctx = ctx;
		this.projection = projection;
		this.tileObject = tileObject;
		this.renderable = renderable;
		this.modelOverride = modelOverride;
		this.zone = zone;
		this.alphaModel = alphaModel;
		this.isModelPartiallyVisible = isModelPartiallyVisible;
		this.drawIndex = drawIndex;
		this.fade = fade;
		this.orientation = orientation;
		this.x = x;
		this.y = y;
		this.z = z;
		this.uploadFunc = uploadFunc;

		// Scalars
		sceneId = model.getSceneId();
		bufferOffset = model.getBufferOffset();
		uvBufferOffset = model.getUvBufferOffset();

		bottomY = model.getBottomY();
		radius = model.getRadius();
		diameter = model.getDiameter();
		XYZMag = model.getXYZMag();
		renderMode = model.getRenderMode();

		modelHeight = model.getModelHeight();
		animationHeightOffset = model.getAnimationHeightOffset();

		useBoundingBox = model.useBoundingBox();
		hash = model.getHash();

		transparency = model.getTransparency();
		overrideAmount = model.getOverrideAmount();
		overrideHue = model.getOverrideHue();
		overrideSaturation = model.getOverrideSaturation();
		overrideLuminance = model.getOverrideLuminance();

		// Counts
		verticesCount = model.getVerticesCount();
		faceCount = model.getFaceCount();

		if (alphaModel != null)
			zone.pendingModelJobs.add(this);

		isProcessing.set(false);
		isCompleted.set(false);

		INFLIGHT.add(this);
		queue();

		processCachedFields(model, true);
	}

	private boolean processCachedFields(Model model, boolean cache) {
		// Caching is done in order of access
		// Ideally this should be updated to reflect any changes
		boolean success = true;
		success &= verticesX.cache(model, model.getVerticesX(), cache);
		success &= verticesY.cache(model, model.getVerticesY(), cache);
		success &= verticesZ.cache(model, model.getVerticesZ(), cache);

		success &= faceColors1.cache(model, model.getFaceColors1(), cache);
		success &= faceColors3.cache(model, model.getFaceColors3(), cache);

		success &= faceIndices1.cache(model, model.getFaceIndices1(), cache);
		success &= faceIndices2.cache(model, model.getFaceIndices2(), cache);
		success &= faceIndices3.cache(model, model.getFaceIndices3(), cache);

		success &= faceTransparencies.cache(model, model.getFaceTransparencies(), cache);
		success &= faceTextures.cache(model, model.getFaceTextures(), cache);
		success &= textureFaces.cache(model, model.getTextureFaces(), cache);

		success &= faceRenderPriorities.cache(model, model.getFaceRenderPriorities(), cache);

		success &= vertexNormalsX.cache(model, model.getVertexNormalsX(), cache);
		success &= vertexNormalsY.cache(model, model.getVertexNormalsY(), cache);
		success &= vertexNormalsZ.cache(model, model.getVertexNormalsZ(), cache);

		success &= faceColors2.cache(model, model.getFaceColors2(), cache);
		success &= unlitFaceColors.cache(model, model.getUnlitFaceColors(), cache);
		success &= faceBias.cache(model, model.getFaceBias(), cache);

		success &= texIndices1.cache(model, model.getTexIndices1(), cache);
		success &= texIndices2.cache(model, model.getTexIndices2(), cache);
		success &= texIndices3.cache(model, model.getTexIndices3(), cache);

		return success;
	}

	@Override
	protected boolean canStart() {
		if (isProcessing.get()) // Work has been stolen, so pop it off the queue
			return true;

		return
			verticesX.isCached() && verticesY.isCached() && verticesZ.isCached() &&
			faceIndices1.isCached() && faceIndices2.isCached() && faceIndices3.isCached() &&
			faceColors3.isCached();
	}

	@Override
	protected void onRun() {
		processModel();
	}

	public boolean processModel() {
		if (!isProcessing.compareAndSet(false, true))
			return false;

		try {
			uploadFunc.upload(
				ctx,
				projection,
				tileObject,
				renderable,
				modelOverride,
				this,
				zone,
				alphaModel,
				isModelPartiallyVisible,
				drawIndex,
				fade,
				orientation,
				x, y, z
			);
			isCompleted.set(true);
		} catch (Exception e) {
			log.error("Error drawing temp object", e);
		} finally {
			// Reset cached status before returning to the POOL
			for (int i = 0; i < cachedFields.length; i++) {
				final CachedArrayField<?> field = cachedFields[i];
				field.ensureCached();
				field.reset();
			}

			if (alphaModel != null)
				zone.pendingModelJobs.remove(this);

			ctx = null;
			projection = null;
			zone = null;
			alphaModel = null;
			tileObject = null;
			renderable = null;
			modelOverride = null;
			drawIndex = -1;

			INFLIGHT.remove(this);
			POOL.recycle(this);
		}

		return true;
	}

	@Override
	public Model getUnskewedModel() { return this; }

	@Override
	public void calculateBoundsCylinder() {}

	@Override
	public void drawFrustum(int zero, int xRotate, int yRotate, int zRotate, int xCamera, int yCamera, int zCamera) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void drawOrtho(int zero, int xRotate, int yRotate, int zRotate, int xCamera, int yCamera, int zCamera, int zoom) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void calculateExtreme(int orientation) {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public AABB getAABB(int orientation) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Model rotateY90Ccw() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Model rotateY180Ccw() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Model rotateY270Ccw() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Model translate(int x, int y, int z) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Model scale(int x, int y, int z) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Model getModel() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Node getNext() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Node getPrevious() {
		throw new UnsupportedOperationException();
	}

	@FunctionalInterface
	public interface UploadModelFunc {
		void upload(
			@Nonnull WorldViewContext ctx,
			@Nonnull Projection projection,
			@Nullable TileObject tileObject,
			@Nonnull Renderable renderable,
			@Nonnull ModelOverride modelOverride,
			@Nonnull Model model,
			@Nonnull Zone zone,
			AlphaModel alphaModel,
			boolean isModelPartiallyVisible,
			int drawIndex,
			float fade,
			int orientation,
			int x, int y, int z
		);
	}

	private static final int VERTEX_TYPE = 0;
	private static final int FACE_TYPE = 1;
	private static final int TEX_TYPE = 2;

	@RequiredArgsConstructor
	private final class CachedArrayField<T> {
		private final AsyncCachedModel model;
		private final PooledArrayType arrayType;
		private final int fieldType;

		private T value;
		private final AtomicBoolean cached = new AtomicBoolean(false);

		public boolean isCached() {
			return cached.get();
		}

		public void ensureCached() {
			while (!cached.get())
				LockSupport.parkNanos(this, 5);
		}

		public T getValue() {
			ensureCached();
			return value;
		}

		public void reset() {
			if (value != null)
				arrayType.release(value);
			value = null;
			cached.set(false);
		}

		@SuppressWarnings("SuspiciousSystemArraycopy")
		public boolean cache(final Model m, T src, boolean cache) {
			if (src == null) {
				if (cache)
					cached.set(true);
				return true;
			}

			final int arraySize;
			switch (fieldType) {
				case VERTEX_TYPE:
					arraySize = m.getVerticesCount();
					break;
				case FACE_TYPE:
					arraySize = m.getFaceCount();
					break;
				default:
					arraySize = Array.getLength(src);
					break;
			}

			if (!cache) {
				// Attempt to get an array from the pool, if we fail check if enough memory is available before creating
				final long requested = (long) arraySize * arrayType.stride;
				value = arrayType.borrow(arraySize, false);

				if (value == null && requested < availableMemory) {
					availableMemory -= requested;
					value = arrayType.create(arraySize);
				}

				return value != null;
			}

			if (!model.isCompleted.get())
				System.arraycopy(src, 0, value, 0, arraySize);

			cached.set(true);
			return true;
		}
	}
}
