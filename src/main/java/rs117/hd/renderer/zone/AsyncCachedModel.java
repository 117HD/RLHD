package rs117.hd.renderer.zone;

import java.lang.reflect.Array;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PrimitiveIntArray;
import rs117.hd.utils.jobs.Job;

import static rs117.hd.HdPlugin.MAX_FACE_COUNT;
import static rs117.hd.renderer.zone.SceneUploader.MAX_VERTEX_COUNT;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Getter
@Setter
@Accessors(chain = false)
public final class AsyncCachedModel extends Job implements Model {
	public static final ConcurrentLinkedQueue<AsyncCachedModel> INFLIGHT = new ConcurrentLinkedQueue<>();
	public static ConcurrentPool<AsyncCachedModel> POOL;

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

	private byte overrideAmount;
	private byte overrideHue;
	private byte overrideSaturation;
	private byte overrideLuminance;

	private long hash;

	private Zone zone;

	private final CachedArrayField<?>[] cachedFields = new CachedArrayField<?>[21];

	private final CachedArrayField<float[]> verticesX = addField(ArrayType.VERTEX_FLOAT);
	private final CachedArrayField<float[]> verticesY = addField(ArrayType.VERTEX_FLOAT);
	private final CachedArrayField<float[]> verticesZ = addField(ArrayType.VERTEX_FLOAT);

	private final CachedArrayField<int[]> faceIndices1 = addField(ArrayType.FACE_INT);
	private final CachedArrayField<int[]> faceIndices2 = addField(ArrayType.FACE_INT);
	private final CachedArrayField<int[]> faceIndices3 = addField(ArrayType.FACE_INT);

	private final CachedArrayField<int[]> faceColors1 = addField(ArrayType.FACE_INT);
	private final CachedArrayField<int[]> faceColors2 = addField(ArrayType.FACE_INT);
	private final CachedArrayField<int[]> faceColors3 = addField(ArrayType.FACE_INT);

	private final CachedArrayField<short[]> unlitFaceColors = addField(ArrayType.FACE_SHORT);
	private final CachedArrayField<short[]> faceTextures = addField(ArrayType.FACE_SHORT);

	private final CachedArrayField<byte[]> faceRenderPriorities = addField(ArrayType.FACE_BYTE);
	private final CachedArrayField<byte[]> faceTransparencies = addField(ArrayType.FACE_BYTE);
	private final CachedArrayField<byte[]> faceBias = addField(ArrayType.FACE_BYTE);
	private final CachedArrayField<byte[]> textureFaces = addField(ArrayType.FACE_BYTE);

	private final CachedArrayField<int[]> texIndices1 = addField(ArrayType.TEX_INT);
	private final CachedArrayField<int[]> texIndices2 = addField(ArrayType.TEX_INT);
	private final CachedArrayField<int[]> texIndices3 = addField(ArrayType.TEX_INT);

	private final CachedArrayField<int[]> vertexNormalsX = addField(ArrayType.VERTEX_INT);
	private final CachedArrayField<int[]> vertexNormalsY = addField(ArrayType.VERTEX_INT);
	private final CachedArrayField<int[]> vertexNormalsZ = addField(ArrayType.VERTEX_INT);

	private final PrimitiveIntArray visibleFaces = new PrimitiveIntArray();
	private final PrimitiveIntArray culledFaces = new PrimitiveIntArray();

	private final AtomicBoolean processing = new AtomicBoolean(false);
	private UploadModelFunc uploadFunc;

	public static long calculateMaxModelSizeBytes() {
		long size = 0;
		for (ArrayType modelArrayDef : ArrayType.values()) {
			size += (long) modelArrayDef.stride * (
				modelArrayDef.type == VERTEX_TYPE ? MAX_VERTEX_COUNT : MAX_FACE_COUNT
			);
		}
		return size;
	}

	@SuppressWarnings("unchecked")
	private <T> CachedArrayField<T> addField(ArrayType fieldDef) {
		for (int i = 0; i < cachedFields.length; i++) {
			if (cachedFields[i] == null)
				return (CachedArrayField<T>) (cachedFields[i] = new CachedArrayField<>(fieldDef));
		}
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

	public synchronized void queue(@Nonnull Model model, Zone zone, UploadModelFunc uploadFunc) {
		this.zone = zone;
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

		overrideAmount = model.getOverrideAmount();
		overrideHue = model.getOverrideHue();
		overrideSaturation = model.getOverrideSaturation();
		overrideLuminance = model.getOverrideLuminance();

		// Counts
		verticesCount = model.getVerticesCount();
		faceCount = model.getFaceCount();

		processing.set(false);
		if (zone != null)
			zone.pendingModelJobs.add(this);
		INFLIGHT.add(this);
		queue();

		// Caching is done in order of access
		// Ideally this should be updated to reflect any changes
		verticesX.cache(model, model.getVerticesX());
		verticesY.cache(model, model.getVerticesY());
		verticesZ.cache(model, model.getVerticesZ());

		faceColors1.cache(model, model.getFaceColors1());
		faceColors3.cache(model, model.getFaceColors3());

		faceIndices1.cache(model, model.getFaceIndices1());
		faceIndices2.cache(model, model.getFaceIndices2());
		faceIndices3.cache(model, model.getFaceIndices3());

		faceTransparencies.cache(model, model.getFaceTransparencies());
		faceTextures.cache(model, model.getFaceTextures());
		textureFaces.cache(model, model.getTextureFaces());

		faceRenderPriorities.cache(model, model.getFaceRenderPriorities());

		vertexNormalsX.cache(model, model.getVertexNormalsX());
		vertexNormalsY.cache(model, model.getVertexNormalsY());
		vertexNormalsZ.cache(model, model.getVertexNormalsZ());

		faceColors2.cache(model, model.getFaceColors2());
		unlitFaceColors.cache(model, model.getUnlitFaceColors());
		faceBias.cache(model, model.getFaceBias());

		texIndices1.cache(model, model.getTexIndices1());
		texIndices2.cache(model, model.getTexIndices2());
		texIndices3.cache(model, model.getTexIndices3());
	}

	@Override
	protected boolean canStart() {
		if (processing.get()) // Work has been stolen, so pop it off the queue
			return true;

		return
			verticesX.cached && verticesY.cached && verticesZ.cached &&
			faceIndices1.cached && faceIndices2.cached && faceIndices3.cached &&
			faceColors3.cached;
	}

	@Override
	protected void onRun() {
		processModel();
	}

	public boolean processModel() {
		if (!processing.compareAndSet(false, true))
			return false;

		try (
			SceneUploader sceneUploader = SceneUploader.POOL.acquire();
			FacePrioritySorter facePrioritySorter = FacePrioritySorter.POOL.acquire()
		) {
			uploadFunc.upload(sceneUploader, facePrioritySorter, visibleFaces, culledFaces, this);
		} catch (Exception e) {
			log.error("Error drawing temp object", e);
		} finally {
			INFLIGHT.remove(this);
			if (zone != null)
				zone.pendingModelJobs.remove(this);
			zone = null;

			// Reset cached status before returning to the POOL
			for (int i = 0; i < cachedFields.length; i++)
				cachedFields[i].cached = false;

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
			SceneUploader sceneUploader,
			FacePrioritySorter facePrioritySorter,
			PrimitiveIntArray visibleFaces,
			PrimitiveIntArray culledFaces,
			Model model
		);
	}

	@FunctionalInterface
	interface ArraySupplier<T> {
		T get(int capacity);
	}

	private static final int VERTEX_TYPE = 0;
	private static final int FACE_TYPE = 1;
	private static final int TEX_TYPE = 2;

	@RequiredArgsConstructor
	private enum ArrayType {
		VERTEX_INT(int[]::new, 4, VERTEX_TYPE),
		VERTEX_FLOAT(float[]::new, 4, VERTEX_TYPE),

		FACE_INT(int[]::new, 4, FACE_TYPE),
		FACE_SHORT(short[]::new, 2, FACE_TYPE),
		FACE_BYTE(byte[]::new, 1, FACE_TYPE),

		TEX_INT(int[]::new, 4, TEX_TYPE);

		private final ArraySupplier<?> supplier;
		private final int stride;
		private final int type;
	}

	private static final class CachedArrayField<T> {
		private final int arrayType;
		private final ArraySupplier<T> supplier;

		private int capacity;
		private T pooled;
		private T value;

		public volatile boolean cached;

		private CachedArrayField(ArrayType arrayType) {
			this.arrayType = arrayType.type;
			// noinspection unchecked
			this.supplier = (ArraySupplier<T>) arrayType.supplier;
			this.value = supplier.get((int) KiB);
		}

		public T getValue() {
			while (!cached)
				LockSupport.parkNanos(this, 5);
			return value;
		}

		public void cache(final Model m, T src) {
			if (src == null) {
				if (value != null)
					pooled = value;
				value = null;
				cached = true;
				return;
			}

			final int arraySize;
			switch (arrayType) {
				case VERTEX_TYPE: arraySize = m.getVerticesCount(); break;
				case FACE_TYPE: arraySize = m.getFaceCount(); break;
				default: arraySize = Array.getLength(src); break;
			}

			if (value == null || capacity < arraySize) {
				if (pooled != null && capacity >= arraySize) {
					value = pooled;
				} else {
					value = supplier.get(capacity = arraySize);
				}
				pooled = null;
			}

			// noinspection SuspiciousSystemArraycopy
			System.arraycopy(src, 0, value, 0, arraySize);
			cached = true;
		}
	}
}
