package rs117.hd.renderer.zone;

import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.PrimitiveIntArray;
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

	private final CachedArrayField<float[]> verticesX = addField(float[]::new, Model::getVerticesX, true);
	private final CachedArrayField<float[]> verticesY = addField(float[]::new, Model::getVerticesY, true);
	private final CachedArrayField<float[]> verticesZ = addField(float[]::new, Model::getVerticesZ, true);

	private final CachedArrayField<int[]> faceIndices1 = addField(int[]::new, Model::getFaceIndices1, false);
	private final CachedArrayField<int[]> faceIndices2 = addField(int[]::new, Model::getFaceIndices2, false);
	private final CachedArrayField<int[]> faceIndices3 = addField(int[]::new, Model::getFaceIndices3, false);

	private final CachedArrayField<int[]> faceColors1 = addField(int[]::new, Model::getFaceColors1, false);
	private final CachedArrayField<int[]> faceColors2 = addField(int[]::new, Model::getFaceColors2, false);
	private final CachedArrayField<int[]> faceColors3 = addField(int[]::new, Model::getFaceColors3, false);

	private final CachedArrayField<short[]> unlitFaceColors = addField(short[]::new, Model::getUnlitFaceColors, false);
	private final CachedArrayField<short[]> faceTextures = addField(short[]::new, Model::getFaceTextures, false);

	private final CachedArrayField<byte[]> faceRenderPriorities = addField(byte[]::new, Model::getFaceRenderPriorities, false);
	private final CachedArrayField<byte[]> faceTransparencies = addField(byte[]::new, Model::getFaceTransparencies, false);
	private final CachedArrayField<byte[]> faceBias = addField(byte[]::new, Model::getFaceBias, false);
	private final CachedArrayField<byte[]> textureFaces = addField(byte[]::new, Model::getTextureFaces, false);

	private final CachedArrayField<int[]> texIndices1 = addField(int[]::new, Model::getTexIndices1, false);
	private final CachedArrayField<int[]> texIndices2 = addField(int[]::new, Model::getTexIndices2, false);
	private final CachedArrayField<int[]> texIndices3 = addField(int[]::new, Model::getTexIndices3, false);

	private final CachedArrayField<int[]> vertexNormalsX = addField(int[]::new, Model::getVertexNormalsX, true);
	private final CachedArrayField<int[]> vertexNormalsY = addField(int[]::new, Model::getVertexNormalsY, true);
	private final CachedArrayField<int[]> vertexNormalsZ = addField(int[]::new, Model::getVertexNormalsZ, true);

	// Job Data
	private final AsyncUploadData asyncData;
	public final AtomicBoolean processing = new AtomicBoolean(false);
	public UploadModelFunc uploadFunc;

	@Getter
	private Zone zone;

	public AsyncCachedModel(AsyncUploadData asyncData) {
		this.asyncData = asyncData;
	}

	private <T> CachedArrayField<T> addField(ArraySupplier<T> supplier, ModelGetter<T> getter, boolean isVertexArray) {
		for(int i = 0; i < cachedFields.length; i++) {
			if(cachedFields[i] == null) {
				CachedArrayField<T> newField = new CachedArrayField<>(supplier, getter, isVertexArray);
				newField.value = supplier.get((int)KiB);
				cachedFields[i] = newField;
				return newField;
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

	public void queue(@Nonnull Model model, Zone zone, UploadModelFunc uploadFunc) {
		this.zone = zone;
		this.uploadFunc = uploadFunc;
		processing.set(false);
		for (CachedArrayField<?> cachedField : cachedFields)
			cachedField.setStatus(false);

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

		if(zone != null)
			zone.pendingModelJobs.add(this);
		queue();

		// Caching is done in order of access, Ideally this should be updated to reflect any changes

		verticesX.cache(model);
		verticesY.cache(model);
		verticesZ.cache(model);

		faceColors3.cache( model);

		faceIndices1.cache(model);
		faceIndices2.cache(model);
		faceIndices3.cache(model);

		faceRenderPriorities.cache(model);

		faceTransparencies.cache(model);
		faceTextures.cache(model);

		vertexNormalsX.cache(model);
		vertexNormalsY.cache(model);
		vertexNormalsZ.cache(model);

		faceColors1.cache(model);
		faceColors2.cache(model);

		unlitFaceColors.cache(model);

		faceBias.cache(model);
		textureFaces.cache(model);

		texIndices1.cache(model);
		texIndices2.cache(model);
		texIndices3.cache(model);
	}

	@Override
	protected boolean canStart() {
		if(processing.get()) // Work has been stollen so pop it off the queue
			return true;

		// Check if the first grabbed field is cached before allowing this model to be processed
		if(verticesX.cached && verticesY.cached && verticesZ.cached && faceIndices1.cached && faceIndices2.cached && faceIndices3.cached && faceColors3.cached)
			return asyncData.client.isClientThread() || asyncData.syncLock.tryLock();

		return false;
	}

	@Override
	protected void onRun()  {
		try {
			if(!processing.compareAndSet(false, true))
				return; // Client thread has stolen this job

			asyncData.visibleFaces.reset();
			asyncData.culledFaces.reset();
			uploadFunc.upload(asyncData.sceneUploader, asyncData.facePrioritySorter, asyncData.visibleFaces, asyncData.culledFaces, this);
		} catch (Exception e) {
			log.error("Error drawing temp object", e);
		} finally {
			if(zone != null)
				zone.pendingModelJobs.remove(this);

			if(asyncData.syncLock.isHeldByCurrentThread())
				asyncData.syncLock.unlock();
		}
	}

	@Override
	protected void onCancel() {
		if(asyncData.syncLock.isHeldByCurrentThread())
			asyncData.syncLock.unlock();
	}

	@Override
	protected void onCompletion() {
		asyncData.freeModels.add(this);
		asyncData.freeModelsCount.incrementAndGet();
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
		void upload(SceneUploader sceneUploader, FacePrioritySorter facePrioritySorter, PrimitiveIntArray visibleFaces, PrimitiveIntArray culledFaces, Model model);
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
	private static final class CachedArrayField<T> {
		private final ArraySupplier<T> supplier;
		private final ModelGetter<T> getter;
		private final boolean isVertexArray;

		private volatile boolean cached;
		private volatile Thread waiter;

		private int capacity;
		private T pooled;
		private T value;

		private void setStatus(boolean status) {
			cached = status;
			if (status && waiter != null)
				LockSupport.unpark(waiter);
		}

		public T getValue() {
			if (!cached) {
				waiter = Thread.currentThread();
				while (!cached)
					LockSupport.parkNanos(this, 5);
				waiter = null;
			}
			return value;
		}

		public void cache(Model m) {
			T src = getter.get(m);
			if (src == null) {
				if (value != null)
					pooled = value;
				value = null;
				setStatus(true);
				return;
			}

			final int size = min(isVertexArray ? m.getVerticesCount() : m.getFaceCount(), Array.getLength(src));
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
