package rs117.hd.renderer.zone;

import java.lang.reflect.Array;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PrimitiveIntArray;
import rs117.hd.utils.jobs.Job;

import static rs117.hd.HdPlugin.MAX_FACE_COUNT;
import static rs117.hd.renderer.zone.SceneUploader.MAX_VERTEX_COUNT;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class AsyncCachedModel extends Job implements Model {
	public static final ConcurrentLinkedQueue<AsyncCachedModel> INFLIGHT = new ConcurrentLinkedQueue<>();
	public static ConcurrentPool<AsyncCachedModel> POOL;

	private int sceneId;
	private int bufferOffset;
	private int uvBufferOffset;

	private int bottomY;
	private int radius;
	private int diameter;
	private int xyzMag;
	private int renderMode;

	private int modelHeight;
	private int animationHeightOffset;

	private boolean useBoundingBox;

	private int verticesCount;
	private int faceCount;

	private byte overrideAmount;
	private byte overrideHue;
	private byte overrideSaturation;
	private byte overrideLuminance;

	private long hash;

	private final CachedArrayField<?>[] cachedFields = new CachedArrayField<?>[21];

	private final CachedArrayField<float[]> verticesX = addField(ModelArrayDef.VERTICES_X);
	private final CachedArrayField<float[]> verticesY = addField(ModelArrayDef.VERTICES_Y);
	private final CachedArrayField<float[]> verticesZ = addField(ModelArrayDef.VERTICES_Z);

	private final CachedArrayField<int[]> faceIndices1 = addField(ModelArrayDef.FACE_INDICES_1);
	private final CachedArrayField<int[]> faceIndices2 = addField(ModelArrayDef.FACE_INDICES_2);
	private final CachedArrayField<int[]> faceIndices3 = addField(ModelArrayDef.FACE_INDICES_3);

	private final CachedArrayField<int[]> faceColors1 = addField(ModelArrayDef.FACE_COLORS_1);
	private final CachedArrayField<int[]> faceColors2 = addField(ModelArrayDef.FACE_COLORS_2);
	private final CachedArrayField<int[]> faceColors3 = addField(ModelArrayDef.FACE_COLORS_3);

	private final CachedArrayField<short[]> unlitFaceColors = addField(ModelArrayDef.UNLIT_FACE_COLORS);
	private final CachedArrayField<short[]> faceTextures = addField(ModelArrayDef.FACE_TEXTURES);

	private final CachedArrayField<byte[]> faceRenderPriorities = addField(ModelArrayDef.FACE_RENDER_PRIORITES);
	private final CachedArrayField<byte[]> faceTransparencies = addField(ModelArrayDef.FACE_TRANSPARENCIES);
	private final CachedArrayField<byte[]> faceBias = addField(ModelArrayDef.FACE_BIAS);
	private final CachedArrayField<byte[]> textureFaces = addField(ModelArrayDef.TEXTURE_FACES);

	private final CachedArrayField<int[]> texIndices1 = addField(ModelArrayDef.TEX_INDICIES_1);
	private final CachedArrayField<int[]> texIndices2 = addField(ModelArrayDef.TEX_INDICIES_2);
	private final CachedArrayField<int[]> texIndices3 = addField(ModelArrayDef.TEX_INDICIES_3);

	private final CachedArrayField<int[]> vertexNormalsX = addField(ModelArrayDef.VERTEX_NORMALS_X);
	private final CachedArrayField<int[]> vertexNormalsY = addField(ModelArrayDef.VERTEX_NORMALS_Y);
	private final CachedArrayField<int[]> vertexNormalsZ = addField(ModelArrayDef.VERTEX_NORMALS_Z);

	private final PrimitiveIntArray visibleFaces = new PrimitiveIntArray();
	private final PrimitiveIntArray culledFaces = new PrimitiveIntArray();

	private final AtomicBoolean processing = new AtomicBoolean(false);
	private UploadModelFunc uploadFunc;

	@Getter
	private Zone zone;

	public static long calculateMaxModelSizeBytes() {
		long size = 0;
		for (ModelArrayDef modelArrayDef : ModelArrayDef.values())
			size += (long) modelArrayDef.stride * (long) (modelArrayDef.isVertexArray ? MAX_VERTEX_COUNT : MAX_FACE_COUNT);
		return size;
	}

	@SuppressWarnings("unchecked")
	private <T> CachedArrayField<T> addField(ModelArrayDef fieldDef) {
		for (int i = 0; i < cachedFields.length; i++) {
			if (cachedFields[i] == null) {
				return (CachedArrayField<T>) (cachedFields[i] = new CachedArrayField<>(fieldDef));
			}
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
		xyzMag = model.getXYZMag();
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

		// Caching is done in order of access, Ideally this should be updated to reflect any changes
		verticesX.cache(model);
		verticesY.cache(model);
		verticesZ.cache(model);

		faceColors1.cache(model);
		faceColors3.cache(model);

		faceIndices1.cache(model);
		faceIndices2.cache(model);
		faceIndices3.cache(model);

		faceTransparencies.cache(model);
		faceTextures.cache(model);
		textureFaces.cache(model);

		faceRenderPriorities.cache(model);

		vertexNormalsX.cache(model);
		vertexNormalsY.cache(model);
		vertexNormalsZ.cache(model);

		faceColors2.cache(model);
		unlitFaceColors.cache(model);
		faceBias.cache(model);

		texIndices1.cache(model);
		texIndices2.cache(model);
		texIndices3.cache(model);
	}

	@Override
	protected boolean canStart() {
		if (processing.get()) // Work has been stollen so pop it off the queue
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

		try {
			try (
				SceneUploader sceneUploader = SceneUploader.POOL.acquire();
				FacePrioritySorter facePrioritySorter = FacePrioritySorter.POOL.acquire()
			) {
				uploadFunc.upload(sceneUploader, facePrioritySorter, visibleFaces, culledFaces, this);
			}
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
	protected void onCancel() {}

	@Override
	protected void onCompletion() {}

	@Override
	public int getSceneId() { return sceneId; }

	@Override
	public void setSceneId(int sceneId) { this.sceneId = sceneId; }

	@Override
	public int getBufferOffset() { return bufferOffset; }

	@Override
	public void setBufferOffset(int bufferOffset) { this.bufferOffset = bufferOffset; }

	@Override
	public int getUvBufferOffset() { return uvBufferOffset; }

	@Override
	public void setUvBufferOffset(int bufferOffset) { uvBufferOffset = bufferOffset; }

	@Override
	public int getBottomY() { return bottomY; }

	@Override
	public int getFaceCount() { return faceCount; }

	@Override
	public int getVerticesCount() { return verticesCount; }

	@Override
	public int getModelHeight() { return modelHeight; }

	@Override
	public int getRadius() { return radius; }

	@Override
	public int getDiameter() { return diameter; }

	@Override
	public void setModelHeight(int modelHeight) { this.modelHeight = modelHeight; }

	@Override
	public int getAnimationHeightOffset() { return animationHeightOffset; }

	@Override
	public Model getUnskewedModel() { return this; }

	@Override
	public void calculateBoundsCylinder() {}

	@Override
	public byte getOverrideAmount() { return overrideAmount; }

	@Override
	public byte getOverrideHue() { return overrideHue; }

	@Override
	public byte getOverrideSaturation() { return overrideSaturation; }

	@Override
	public byte getOverrideLuminance() { return overrideLuminance; }

	@Override
	public int getRenderMode() { return renderMode; }

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
	public int getXYZMag() { return xyzMag; }

	@Override
	public boolean useBoundingBox() { return useBoundingBox; }

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

	@Override
	public long getHash() { return hash; }

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

	@FunctionalInterface
	interface ModelGetter<T> {
		T get(Model m);
	}

	@RequiredArgsConstructor
	private enum ModelArrayDef {
		VERTICES_X(float[]::new, Model::getVerticesX, 4, true),
		VERTICES_Y(float[]::new, Model::getVerticesY, 4, true),
		VERTICES_Z(float[]::new, Model::getVerticesZ, 4, true),

		FACE_INDICES_1(int[]::new, Model::getFaceIndices1, 4, false),
		FACE_INDICES_2(int[]::new, Model::getFaceIndices2, 4, false),
		FACE_INDICES_3(int[]::new, Model::getFaceIndices3, 4, false),

		FACE_COLORS_1(int[]::new, Model::getFaceColors1, 4, false),
		FACE_COLORS_2(int[]::new, Model::getFaceColors2, 4, false),
		FACE_COLORS_3(int[]::new, Model::getFaceColors3, 4, false),

		UNLIT_FACE_COLORS(short[]::new, Model::getUnlitFaceColors, 2, false),
		FACE_TEXTURES(short[]::new, Model::getFaceTextures, 2, false),

		FACE_RENDER_PRIORITES(byte[]::new, Model::getFaceRenderPriorities, 1, false),
		FACE_TRANSPARENCIES(byte[]::new, Model::getFaceTransparencies, 1, false),
		FACE_BIAS(byte[]::new, Model::getFaceBias, 1, false),
		TEXTURE_FACES(byte[]::new, Model::getTextureFaces, 1, false),

		TEX_INDICIES_1(int[]::new, Model::getTexIndices1, 4, false),
		TEX_INDICIES_2(int[]::new, Model::getTexIndices2, 4, false),
		TEX_INDICIES_3(int[]::new, Model::getTexIndices3, 4, false),

		VERTEX_NORMALS_X(int[]::new, Model::getVertexNormalsX, 4, true),
		VERTEX_NORMALS_Y(int[]::new, Model::getVertexNormalsY, 4, true),
		VERTEX_NORMALS_Z(int[]::new, Model::getVertexNormalsZ, 4, true);

		private final ArraySupplier<?> supplier;
		private final ModelGetter<?> getter;
		private final int stride;
		private final boolean isVertexArray;
	}

	@SuppressWarnings("unchecked")
	private static final class CachedArrayField<T> {
		private final boolean isVertexArray;
		private final ArraySupplier<T> supplier;
		private final ModelGetter<T> getter;

		private int capacity;
		private T pooled;
		private T value;

		public volatile boolean cached;

		private CachedArrayField(ModelArrayDef fieldDef) {
			this.isVertexArray = fieldDef.isVertexArray;
			this.supplier = (ArraySupplier<T>) fieldDef.supplier;
			this.getter = (ModelGetter<T>) fieldDef.getter;
			this.value = supplier.get((int) KiB);
		}

		public T getValue() {
			while (!cached)
				LockSupport.parkNanos(this, 5);
			return value;
		}

		public void cache(final Model m) {
			final T src = getter.get(m);
			if (src == null) {
				if (value != null)
					pooled = value;
				value = null;
				cached = true;
				return;
			}

			final int srcLen = Array.getLength(src);
			int arraySize = isVertexArray ? m.getVerticesCount() : m.getFaceCount();

			if (srcLen < arraySize)
				arraySize = srcLen;

			if (value == null || capacity < arraySize) {
				if (pooled != null && capacity >= arraySize) {
					value = pooled;
				} else {
					value = supplier.get(capacity = arraySize);
				}
				pooled = null;
			}

			System.arraycopy(src, 0, value, 0, arraySize);
			cached = true;
		}
	}
}
