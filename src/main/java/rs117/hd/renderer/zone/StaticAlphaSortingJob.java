package rs117.hd.renderer.zone;

import java.util.Arrays;
import java.util.IdentityHashMap;
import javax.inject.Inject;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.Camera;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.renderer.zone.Zone.VERT_SIZE;
import static rs117.hd.utils.MathUtils.*;

public final class StaticAlphaSortingJob extends Job {

	@Inject
	private FrameTimer frameTimer;

	private AlphaModelInstance[] models = new AlphaModelInstance[16];
	private int[] sortedAlphaIndicies;
	private int size = 0;

	// Per-zone assignment of shared StaticAlphaModel sorted-index blocks. Each distinct model is written once, and
	// every instance referencing it draws the same block with its own baseVertex.
	private final IdentityHashMap<StaticAlphaModel, Integer> modelBlocks = new IdentityHashMap<>();
	private StaticAlphaModel[] blockModels = new StaticAlphaModel[16];
	private int[] blockSourceOffsets = new int[16];
	private int blockCount = 0;
	private int assignedIndexStart = 0;
	private int assignedIndexCount = 0;

	private int[] zsortHead;
	private int[] zsortTail;
	private int[] zsortNext;

	private Camera camera;
	private int zx, zz;
	private int minBoundX, minBoundY, minBoundZ, maxBoundX, maxBoundY, maxBoundZ;
	private boolean frustumCull;

	public StaticAlphaSortingJob() { super(true); }

	public void beginAssignment(int startOffset) {
		modelBlocks.clear();
		blockCount = 0;
		assignedIndexStart = startOffset;
		assignedIndexCount = startOffset;
	}

	public int getAssignedIndexCount() {
		return assignedIndexCount - assignedIndexStart;
	}

	public int getBlockCount() {
		return blockCount;
	}

	public void addAlphaModel(AlphaModelInstance m) {
		if (size == models.length) {
			final int newCapacity = ceilPow2(models.length * 2);
			models = Arrays.copyOf(models, newCapacity);
		}

		m.asyncSortIdx = size;
		models[size] = m;
		size++;

		final int modelX = ((zx - m.zofx) << 10) + m.x;
		final int modelZ = ((zz - m.zofz) << 10) + m.z;
		minBoundX = min(minBoundX, modelX - m.extentsX);
		maxBoundX = max(maxBoundX, modelX + m.extentsX);
		minBoundY = min(minBoundY, m.y - m.extentsY);
		maxBoundY = max(maxBoundY, m.y + m.extentsY);
		minBoundZ = min(minBoundZ, modelZ - m.extentsZ);
		maxBoundZ = max(maxBoundZ, modelZ + m.extentsZ);

		final StaticAlphaModel model = m.staticModel;
		if (model == null)
			return;

		Integer block = modelBlocks.get(model);
		if (block == null) {
			block = blockCount++;
			modelBlocks.put(model, block);
			if (blockModels.length <= block) {
				final int newCapacity = ceilPow2(block + 1);
				blockModels = Arrays.copyOf(blockModels, newCapacity);
				blockSourceOffsets = Arrays.copyOf(blockSourceOffsets, newCapacity);
			}
			blockModels[block] = model;
			blockSourceOffsets[block] = assignedIndexCount;
			assignedIndexCount += model.getFaceCount() * 3;
		}

		m.blockIndex = block;
		m.sortedIndiciesOffset = blockSourceOffsets[block];
		m.sortedIndiciesCount = model.getFaceCount() * 3;
		m.baseVertex = m.startpos / (VERT_SIZE >> 2);
	}

	public void setup(Camera camera, int zx, int zz, boolean frustumCull){
		this.camera = camera;
		this.zx = zx;
		this.zz = zz;
		this.frustumCull = frustumCull;
	}

	public void queue(int[] sortedAlphaIndicies) {
		if(size == 0)
			return;
		this.sortedAlphaIndicies = sortedAlphaIndicies;
		super.queue();
	}

	public void reset() {
		Arrays.fill(models, 0, size, null);
		size = 0;
		minBoundX = minBoundY = minBoundZ = Integer.MAX_VALUE;
		maxBoundX = maxBoundY = maxBoundZ = Integer.MIN_VALUE;
	}

	public void destroy() {
		waitForCompletion();
		PooledArrayType.INT.release(zsortHead);
		PooledArrayType.INT.release(zsortTail);
		PooledArrayType.INT.release(zsortNext);
		zsortHead = zsortTail = zsortNext = null;
		modelBlocks.clear();
	}

	private void ensureCapacity(int diameter, int faceCount) {
		zsortHead = PooledArrayType.INT.ensureCapacity(zsortHead, min(FacePrioritySorter.MAX_DIAMETER, diameter + 1));
		zsortTail = PooledArrayType.INT.ensureCapacity(zsortTail, min(FacePrioritySorter.MAX_DIAMETER, diameter + 1));
		zsortNext = PooledArrayType.INT.ensureCapacity(zsortNext, min(FacePrioritySorter.MAX_FACE_COUNT, faceCount));
	}

	@Override
	protected void onRun() {
		final long start = System.nanoTime();

		final int cx = (int) camera.getPositionX();
		final int cy = (int) camera.getPositionY();
		final int cz = (int) camera.getPositionZ();
		final int yaw = camera.getFixedYaw();
		final int yawSin = SINE14[yaw];
		final int yawCos = COSINE14[yaw];
		final int pitch = camera.getFixedPitch();
		final int pitchSin = SINE14[pitch];
		final int pitchCos = COSINE14[pitch];

		// Face order depends only on camera orientation, so this identifies a StaticAlphaModel's cached sort
		final int sortKey = yaw << 16 | pitch;

		// Classify the combined AABB of every alpha model against the frustum, so we can skip
		// per-model culling entirely when the zone's alpha geometry is fully visible.
		final int boundsClassification = frustumCull ?
			camera.classifyAABB(minBoundX, minBoundY, minBoundZ, maxBoundX, maxBoundY, maxBoundZ) : 1;

		final boolean fullyOutside = boundsClassification == -1;
		final boolean partiallyVisible = boundsClassification == 0;

		for (int i = 0; i < size; i++) {
			final AlphaModelInstance m = models[i];
			m.sortedIndiciesCount = 0;

			if (fullyOutside) {
				m.setHidden();
			} else if (partiallyVisible) {
				final int modelX = ((zx - m.zofx) << 10) + m.x;
				final int modelZ = ((zz - m.zofz) << 10) + m.z;
				if (!camera.intersectsAABB(
					modelX - m.extentsX, m.y - m.extentsY, modelZ - m.extentsZ,
					modelX + m.extentsX, m.y + m.extentsY, modelZ + m.extentsZ
				)) {
					// Alpha Models always start as visible to avoid pop-in, so we mark them as hidden since we know they're not visible
					m.setHidden();
				}
			}

			if (m.isVisible()) {
				m.calculateDist(cx, cy, cz, zx, zz);
				m.setSorted();
			}
		}

		// Sort and write each distinct model's local indices once, then assign the resulting index count to every
		// instance referencing it. Unsorted models (e.g. too large) yield a face count of zero.
		for (int block = 0; block < blockCount; block++) {
			final StaticAlphaModel model = blockModels[block];
			sortStaticModelFacesByDistance(sortedAlphaIndicies, model, blockSourceOffsets[block], sortKey, yawCos, yawSin, pitchCos, pitchSin);
		}

		for (int i = 0; i < size; i++) {
			final AlphaModelInstance m = models[i];
			if (m.isVisible() && m.staticModel != null)
				m.sortedIndiciesCount = m.staticModel.sortedFaceCount * 3;
		}

		frameTimer.add(Timer.STATIC_ALPHA_SORT, System.nanoTime() - start);
	}

	/**
	 * Ensures the shared {@link StaticAlphaModel} has a sorted face order for the current camera orientation, then
	 * writes the model's local indices (shared by every instance referencing it) at the given offset. The expensive
	 * sort is performed at most once per orientation, no matter how many instances reference the model.
	 */
	private void sortStaticModelFacesByDistance(
		int[] sortedAlphaIndicies,
		StaticAlphaModel model,
		int sortedOffset,
		int sortKey,
		int yawCos, int yawSin,
		int pitchCos, int pitchSin
	) {
		// The monitor guards both the sort cache and the cached order it points at. Sorting only happens when the
		// camera orientation changes, so this lock is almost always uncontended, and the copy loop is cheap.
		synchronized (model) {
			if (model.sortKey != sortKey || model.sortedFaces == null) {
				sortStaticModelFaces(model, yawCos, yawSin, pitchCos, pitchSin);
				model.sortKey = sortKey;
			}

			final int[] sortedFaces = model.sortedFaces;
			final int sortedFaceCount = model.sortedFaceCount;
			if (sortedFaces == null || sortedFaceCount <= 0)
				return;

			for (int i = 0; i < sortedFaceCount; i++) {
				final int faceStart = sortedFaces[i] * 3;
				sortedAlphaIndicies[sortedOffset++] = faceStart;
				sortedAlphaIndicies[sortedOffset++] = faceStart + 1;
				sortedAlphaIndicies[sortedOffset++] = faceStart + 2;
			}
		}
	}

	/**
	 * Sorts a {@link StaticAlphaModel}'s local face slots by distance for the given camera orientation, caching the
	 * result on the model. Must be called while holding the model's monitor.
	 */
	private void sortStaticModelFaces(
		StaticAlphaModel model,
		int yawCos, int yawSin,
		int pitchCos, int pitchSin
	) {
		model.sortedFaceCount = 0;

		final int radius = model.radius;
		final int diameter = 1 + radius * 2;
		if (diameter >= FacePrioritySorter.MAX_DIAMETER)
			return;

		final int[] packedFaces = model.packedFaces;
		final int[] doubleSidedBitSet = model.doubleSidedBitSet;
		final int faceCount = packedFaces.length;
		final int maxFaceCount = faceCount + model.doubleSidedCount;
		if (maxFaceCount <= 0)
			return;

		ensureCapacity(diameter, maxFaceCount);

		final int[] zsortHead = this.zsortHead;
		final int[] zsortTail = this.zsortTail;
		final int[] zsortNext = this.zsortNext;

		Arrays.fill(zsortHead, 0, diameter, -1);
		Arrays.fill(zsortTail, 0, diameter, -1);

		// A single int[] per triangle slot, reused across orientations to avoid per-frame allocation
		if (model.sortedFaces == null || model.sortedFaces.length < maxFaceCount)
			model.sortedFaces = new int[maxFaceCount];
		final int[] sortedFaces = model.sortedFaces;
		int sortedCount = 0;
		int backfaceWord = 0;
		int minFz = diameter, maxFz = 0;

		for (int i = 0, f = 0; i < faceCount; ++i, ++f) {
			final int packed = packedFaces[i];
			final short x = (short) (packed >> 21);
			final short y = (short) ((packed << 11) >> 22);
			final short z = (short) ((packed << 21) >> 21);

			// We do rotations with 16 bits of extra precision, which we discard post-rotation.
			// Very little of this can actually be pre-computed without discarding that precision.
			int fz = (z * yawCos - x * yawSin) >> 16;
			fz = ((y * pitchSin + fz * pitchCos) >> 16) + radius;

			final int tailFaceIdx = zsortTail[fz];
			if (tailFaceIdx == -1) {
				zsortHead[fz] = f;
				zsortTail[fz] = f;
				minFz = min(minFz, fz);
				maxFz = max(maxFz, fz);
			} else {
				zsortNext[tailFaceIdx] = f;
				zsortTail[fz] = f;
			}
			zsortNext[f] = -1;

			// Backfaces are not sorted, so we emit them first if this face is marked as double-sided
			if (doubleSidedBitSet != null) {
				if ((i & 31) == 0)
					backfaceWord = doubleSidedBitSet[i >> 5];

				if ((backfaceWord & 1) != 0)
					sortedFaces[sortedCount++] = ++f;

				backfaceWord >>>= 1;
			}
		}

		for (int i = maxFz; i >= minFz; --i) {
			for (int f = zsortHead[i]; f != -1; f = zsortNext[f]) {
				if (f >= maxFaceCount)
					continue;
				sortedFaces[sortedCount++] = f;
			}
		}

		model.sortedFaceCount = sortedCount;
	}
}
