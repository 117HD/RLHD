package rs117.hd.renderer.zone;

import java.lang.reflect.Array;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.jobs.Job;

import static java.lang.System.arraycopy;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class AsyncCachedModel extends Job implements Model  {
	private int sceneId;
	private int bufferOffset;
	private int uvBufferOffset;

	private int bottomY;
	private int radius;
	private int diameter;
	private int xyzMag;

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

	private final CachedArrayField<float[]> verticesX = addField(float[]::new, 0);
	private final CachedArrayField<float[]> verticesY = addField(float[]::new, 1);
	private final CachedArrayField<float[]> verticesZ = addField(float[]::new, 2);

	private final CachedArrayField<int[]> faceIndices1 = addField(int[]::new, 3);
	private final CachedArrayField<int[]> faceIndices2 = addField(int[]::new, 4);
	private final CachedArrayField<int[]> faceIndices3 = addField(int[]::new, 5);

	private final CachedArrayField<int[]> faceColors1 = addField(int[]::new, 6);
	private final CachedArrayField<int[]> faceColors2 = addField(int[]::new, 7);
	private final CachedArrayField<int[]> faceColors3 = addField(int[]::new, 8);

	private final CachedArrayField<short[]> unlitFaceColors = addField(short[]::new, 9);
	private final CachedArrayField<short[]> faceTextures = addField(short[]::new, 10);

	private final CachedArrayField<byte[]> faceRenderPriorities = addField(byte[]::new, 11);
	private final CachedArrayField<byte[]> faceTransparencies = addField(byte[]::new, 12);
	private final CachedArrayField<byte[]> faceBias = addField(byte[]::new, 13);
	private final CachedArrayField<byte[]> textureFaces = addField(byte[]::new, 14);

	private final CachedArrayField<int[]> texIndices1 = addField(int[]::new, 15);
	private final CachedArrayField<int[]> texIndices2 = addField(int[]::new, 16);
	private final CachedArrayField<int[]> texIndices3 = addField(int[]::new, 17);

	private final CachedArrayField<int[]> vertexNormalsX = addField(int[]::new, 18);
	private final CachedArrayField<int[]> vertexNormalsY = addField(int[]::new, 19);
	private final CachedArrayField<int[]> vertexNormalsZ = addField(int[]::new, 20);

	// Job Data
	private final AsyncUploadData asyncData;
	private UploadModelFunc uploadFunc;

	public AsyncCachedModel(AsyncUploadData asyncData) {
		this.asyncData = asyncData;
	}

	private <T> CachedArrayField<T> addField(ArraySupplier<T> supplier, int fieldIdx) {
		CachedArrayField<T> newField = new CachedArrayField<>(supplier);
		cachedFields[fieldIdx] = newField;
		return newField;
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

	public void queue(@Nonnull Model model, UploadModelFunc func) {
		for (CachedArrayField<?> cachedField : cachedFields)
			cachedField.setStatus(false);
		uploadFunc = func;

		// Scalars
		sceneId = model.getSceneId();
		bufferOffset = model.getBufferOffset();
		uvBufferOffset = model.getUvBufferOffset();

		bottomY = model.getBottomY();
		radius = model.getRadius();
		diameter = model.getDiameter();
		xyzMag = model.getXYZMag();

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

		queue();

		// Caching is done in order of access
		// Following are accessed by SceneUploader.transformModelVertices
		// verticesX, verticesY, verticesZ, vertexNormalsX, vertexNormalsY, vertexNormalsZ

		// Following are then accessed by FacePrioritySorter.sortModelFaces
		// faceIndices1, faceIndices2, faceIndices3, faceColors3, faceRenderPriorities

		verticesX.cache(model.getVerticesX(), verticesCount);
		verticesY.cache(model.getVerticesY(), verticesCount);
		verticesZ.cache(model.getVerticesZ(), verticesCount);

		faceIndices1.cache(model.getFaceIndices1(), faceCount);
		faceIndices2.cache(model.getFaceIndices2(), faceCount);
		faceIndices3.cache(model.getFaceIndices3(), faceCount);

		faceColors3.cache( model.getFaceColors3(), faceCount);
		faceRenderPriorities.cache(model.getFaceRenderPriorities(), faceCount);

		// Following are accessed by uploadTempModel and can be cached in any order
		vertexNormalsX.cache(model.getVertexNormalsX(), verticesCount);
		vertexNormalsY.cache(model.getVertexNormalsY(), verticesCount);
		vertexNormalsZ.cache(model.getVertexNormalsZ(), verticesCount);

		faceColors2.cache(model.getFaceColors2(), faceCount);
		faceColors1.cache(model.getFaceColors1(), faceCount);

		unlitFaceColors.cache(model.getUnlitFaceColors(), faceCount);
		faceTextures.cache(model.getFaceTextures(), faceCount);

		faceTransparencies.cache(model.getFaceTransparencies(), faceCount);
		faceBias.cache(model.getFaceBias(), faceCount);
		textureFaces.cache(model.getTextureFaces(), faceCount);

		// Texture indices
		texIndices1.cache(model.getTexIndices1(), faceCount);
		texIndices2.cache(model.getTexIndices2(), faceCount);
		texIndices3.cache(model.getTexIndices3(), faceCount);
	}

	@Override
	protected void onRun()  {
		try {
			asyncData.syncLock.lockInterruptibly();
			asyncData.sortedFaces.reset();
			asyncData.unsortedFaces.reset();
			uploadFunc.upload(asyncData, this);
		} catch (Exception e) {
			log.error("Error drawing temp object", e);
		} finally {
			asyncData.syncLock.unlock();
		}
	}

	@Override
	protected void onCompletion() {
		asyncData.freeModels.add(this);
	}

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
		void upload(AsyncUploadData asyncData, AsyncCachedModel model);
	}

	@FunctionalInterface
	interface ArraySupplier<T> {
		T get(int capacity);
	}

	@RequiredArgsConstructor
	private static final class CachedArrayField<T> {
		private volatile boolean cached = false;
		private volatile Thread waiter;
		private final ArraySupplier<T> supplier;
		private int capacity;
		private T pooled;
		public T value;

		private void setStatus(boolean status) {
			cached = status;
			if (cached && waiter != null)
				LockSupport.unpark(waiter);
		}

		public T getValue() {
			if (!cached) {
				assert waiter == null : "Multiple threads cannot wait on the same field";
				waiter = Thread.currentThread();
				while (!cached)
					LockSupport.park();
				waiter = null;
			}
			return value;
		}

		public void cache(T src, int size) {
			if (src == null) {
				if (value != null) {
					assert pooled == null;
					pooled = value;
				}
				value = null;
				setStatus(true);
				return;
			}

			size = min(size, Array.getLength(src));
			if (value == null || capacity < size) {
				if (pooled != null && capacity >= size) {
					value = pooled;
				} else {
					value = supplier.get(capacity = size);
				}
				pooled = null;
			}

			arraycopy(src, 0, value, 0, size);
			setStatus(true);
		}
	}
}
