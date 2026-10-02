package rs117.hd.scene;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.Camera;
import rs117.hd.utils.DebugDraw;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.Job;

import static rs117.hd.utils.MathUtils.*;

@Singleton
public class SceneCullingManager {
	private static final int COARSE_PRIMITIVE_THRESHOLD = 3;

	// Width, in floats, of a single primitive's data. Sphere: x, y, z, radius, -, -. AABB: cx, cy, cz, ex, ey, ez.
	private static final int FLOATS_PER_PRIMITIVE = 6;
	private static final int INITIAL_PRIMITIVE_SLOT_CAPACITY = 64;

	private static final int INITIAL_PRIMITIVE_CAPACITY = 4;
	private static final int PRIMITIVES_OFFSET = FLOATS_PER_PRIMITIVE; // slot 0 of the scratch buffer is reserved for the coarse AABB

	// projected/world scratch layout per CullingResult during build():
	//   [0..5]                                    -> coarse AABB (min/max), only valid & used when primitiveCount > COARSE_PRIMITIVE_THRESHOLD
	//   [PRIMITIVES_OFFSET + i*FLOATS_PER_PRIMITIVE .. +5] -> built bounds for primitive i
	//     AABB primitive   -> minX, minY, minZ, maxX, maxY, maxZ
	//     Sphere primitive -> x, y, z, radius, (unused), (unused)
	// These scratch buffers start small and grow only on the rare frame where a result needs more room
	// than ever before - not a per-frame allocation in steady state.
	private static final int INITIAL_SCRATCH_SIZE = PRIMITIVES_OFFSET + INITIAL_PRIMITIVE_CAPACITY * FLOATS_PER_PRIMITIVE;

	private static final byte PRIMITIVE_SPHERE = 0;
	private static final byte PRIMITIVE_AABB = 1;

	// Packs (primitive type, slot index) into a single int: the top bit is the type (0 = sphere, 1 = AABB),
	// the remaining 31 bits are the slot's index into SceneCullingManager.primitiveData.
	private static final int TYPE_SHIFT = 31;
	private static final int SLOT_MASK = 0x7FFFFFFF;

	private static int pack(byte type, int slot) { return (((int) type) << TYPE_SHIFT) | slot; }

	private static byte unpackType(int packed) { return (byte) (packed >>> TYPE_SHIFT); }

	private static int unpackSlot(int packed) { return packed & SLOT_MASK; }

	private float[] primitiveData = new float[INITIAL_PRIMITIVE_SLOT_CAPACITY * FLOATS_PER_PRIMITIVE];
	private int[] freeSlotQueue = identitySlots(INITIAL_PRIMITIVE_SLOT_CAPACITY);
	private int freeSlotHead;
	private int freeSlotCount = INITIAL_PRIMITIVE_SLOT_CAPACITY;

	private final ConcurrentPool<CullingJob> CULLING_JOB_POOL = new ConcurrentPool<>(CullingJob::new);
	private final ConcurrentPool<CullingResult> CULLING_RESULT_POOL = new ConcurrentPool<>(CullingResult::new);

	private final Queue<CullingResult> pendingCullingResults = new ArrayDeque<>();
	private final List<Camera> cullingCameras = new ArrayList<>();
	private final float[] debugProjected = new float[4];
	private float[] debugScratch = new float[INITIAL_SCRATCH_SIZE];

	private int nextJobId = 0;

	private static int[] identitySlots(int capacity) {
		int[] slots = new int[capacity];
		for (int i = 0; i < capacity; i++)
			slots[i] = i;
		return slots;
	}

	private synchronized int allocatePrimitiveSlot() {
		if (freeSlotCount == 0)
			growPrimitiveStorage();

		final int slot = freeSlotQueue[freeSlotHead];
		freeSlotHead = (freeSlotHead + 1) % freeSlotQueue.length;
		freeSlotCount--;
		return slot;
	}

	private synchronized void freePrimitiveSlot(int slot) {
		final int tail = (freeSlotHead + freeSlotCount) % freeSlotQueue.length;
		freeSlotQueue[tail] = slot;
		freeSlotCount++;
	}

	private void growPrimitiveStorage() {
		int oldCapacity = freeSlotQueue.length;
		int newCapacity = oldCapacity + (oldCapacity >> 1);
		if (newCapacity <= oldCapacity)
			newCapacity = oldCapacity + 1;

		float[] newData = new float[newCapacity * FLOATS_PER_PRIMITIVE];
		System.arraycopy(primitiveData, 0, newData, 0, primitiveData.length);
		primitiveData = newData;

		final int[] newQueue = new int[newCapacity];
		for (int i = 0; i < freeSlotCount; i++)
			newQueue[i] = freeSlotQueue[(freeSlotHead + i) % freeSlotQueue.length];

		freeSlotQueue = newQueue;
		freeSlotHead = 0;

		for (int slot = oldCapacity; slot < newCapacity; slot++)
			freeSlotQueue[freeSlotCount++] = slot;
	}

	public void addCamera(Camera camera) {
		if (cullingCameras.contains(camera))
			return;

		assert cullingCameras.size() < 8 : "SceneCullingManager supports at most 8 cameras";
		cullingCameras.add(camera);
	}

	public void removeCamera(Camera camera) {
		cullingCameras.remove(camera);
	}

	public synchronized CullingResult obtainResult() {
		CullingResult result = CULLING_RESULT_POOL.acquire();
		assert result.state == STATE_RELEASED && result.primitiveCount == 0 : "Acquired a CullingResult that still owns primitives";
		result.state = STATE_USED;
		return result;
	}

	public synchronized void flush() {
		if (pendingCullingResults.isEmpty() || cullingCameras.isEmpty())
			return;

		CullingJob job = CULLING_JOB_POOL.acquire();
		job.id = nextJobId++;
		job.cullingCameras.addAll(cullingCameras);

		CullingResult result;
		while ((result = pendingCullingResults.poll()) != null) {
			result.job = job;
			result.cullingJobId = job.id;
			job.pendingCullingResults.add(result);
		}

		job.queue();
	}

	public void debugDraw(CullingResult... results) {
		for(int i = 0; i < results.length; i++)
			debugDraw(results[i]);
	}

	public void debugDraw(CullingResult result) {
		if (result == null)
			return;

		int required = result.requiredScratchSize();
		if (debugScratch.length < required)
			debugScratch = new float[required];

		result.build(debugProjected, debugScratch);
		result.debugDraw(debugScratch);
	}

	private boolean contains(int container, int contained) {
		int containerBase = unpackSlot(container) * FLOATS_PER_PRIMITIVE;
		int containedBase = unpackSlot(contained) * FLOATS_PER_PRIMITIVE;
		boolean containerIsSphere = unpackType(container) == PRIMITIVE_SPHERE;
		boolean containedIsSphere = unpackType(contained) == PRIMITIVE_SPHERE;

		if (containerIsSphere)
			return containedIsSphere
				? sphereContainsSphere(containerBase, containedBase)
				: sphereContainsAABB(containerBase, containedBase);

		return containedIsSphere
			? aabbContainsSphere(containerBase, containedBase)
			: aabbContainsAABB(containerBase, containedBase);
	}

	private boolean sphereContainsSphere(int containerBase, int containedBase) {
		float dx = primitiveData[containerBase] - primitiveData[containedBase];
		float dy = primitiveData[containerBase + 1] - primitiveData[containedBase + 1];
		float dz = primitiveData[containerBase + 2] - primitiveData[containedBase + 2];
		float radiusDifference = primitiveData[containerBase + 3] - primitiveData[containedBase + 3];
		return radiusDifference >= 0 && dx * dx + dy * dy + dz * dz <= radiusDifference * radiusDifference;
	}

	private boolean sphereContainsAABB(int sphereBase, int aabbBase) {
		float dx = max(abs(aabbMin(aabbBase, 0) - primitiveData[sphereBase]), abs(aabbMax(aabbBase, 0) - primitiveData[sphereBase]));
		float dy = max(abs(aabbMin(aabbBase, 1) - primitiveData[sphereBase + 1]), abs(aabbMax(aabbBase, 1) - primitiveData[sphereBase + 1]));
		float dz = max(abs(aabbMin(aabbBase, 2) - primitiveData[sphereBase + 2]), abs(aabbMax(aabbBase, 2) - primitiveData[sphereBase + 2]));
		float radius = primitiveData[sphereBase + 3];
		return dx * dx + dy * dy + dz * dz <= radius * radius;
	}

	private boolean aabbContainsSphere(int aabbBase, int sphereBase) {
		float radius = primitiveData[sphereBase + 3];
		return primitiveData[sphereBase] - radius >= aabbMin(aabbBase, 0) && primitiveData[sphereBase] + radius <= aabbMax(aabbBase, 0)
			   && primitiveData[sphereBase + 1] - radius >= aabbMin(aabbBase, 1) && primitiveData[sphereBase + 1] + radius <= aabbMax(aabbBase, 1)
			   && primitiveData[sphereBase + 2] - radius >= aabbMin(aabbBase, 2) && primitiveData[sphereBase + 2] + radius <= aabbMax(aabbBase, 2);
	}

	private boolean aabbContainsAABB(int containerBase, int containedBase) {
		for (int axis = 0; axis < 3; axis++) {
			if (aabbMin(containedBase, axis) < aabbMin(containerBase, axis)
				|| aabbMax(containedBase, axis) > aabbMax(containerBase, axis))
				return false;
		}
		return true;
	}

	private boolean canMerge(int first, int second, float mergeThreshold) {
		int firstBase = unpackSlot(first) * FLOATS_PER_PRIMITIVE;
		int secondBase = unpackSlot(second) * FLOATS_PER_PRIMITIVE;

		for (int joiningAxis = 0; joiningAxis < 3; joiningAxis++) {
			if (!rangesTouch(firstBase, secondBase, joiningAxis, mergeThreshold))
				continue;

			boolean matchingRanges = true;
			for (int axis = 0; axis < 3; axis++) {
				if (axis != joiningAxis && !rangesMatch(firstBase, secondBase, axis, mergeThreshold)) {
					matchingRanges = false;
					break;
				}
			}

			if (matchingRanges)
				return true;
		}

		return false;
	}

	private boolean rangesTouch(int firstBase, int secondBase, int axis, float threshold) {
		return aabbMin(firstBase, axis) <= aabbMax(secondBase, axis) + threshold
			   && aabbMin(secondBase, axis) <= aabbMax(firstBase, axis) + threshold;
	}

	private boolean rangesMatch(int firstBase, int secondBase, int axis, float threshold) {
		return abs(aabbMin(firstBase, axis) - aabbMin(secondBase, axis)) <= threshold
			   && abs(aabbMax(firstBase, axis) - aabbMax(secondBase, axis)) <= threshold;
	}

	private void mergeAABBs(int first, int second) {
		int firstBase = unpackSlot(first) * FLOATS_PER_PRIMITIVE;
		int secondBase = unpackSlot(second) * FLOATS_PER_PRIMITIVE;

		for (int axis = 0; axis < 3; axis++) {
			float minimum = min(aabbMin(firstBase, axis), aabbMin(secondBase, axis));
			float maximum = max(aabbMax(firstBase, axis), aabbMax(secondBase, axis));
			primitiveData[firstBase + axis] = (minimum + maximum) * 0.5f;
			primitiveData[firstBase + 3 + axis] = (maximum - minimum) * 0.5f;
		}
	}

	private float aabbMin(int base, int axis) {
		return primitiveData[base + axis] - primitiveData[base + 3 + axis];
	}

	private float aabbMax(int base, int axis) {
		return primitiveData[base + axis] + primitiveData[base + 3 + axis];
	}

	private final class CullingJob extends Job {
		private final Queue<CullingResult> pendingCullingResults = new ArrayDeque<>();
		private final List<Camera> cullingCameras = new ArrayList<>();
		private final float[] projected = new float[4];
		private float[] scratch = new float[INITIAL_SCRATCH_SIZE];
		private int id;

		@Override
		protected void onRun() {
			CullingResult result;
			while ((result = pendingCullingResults.poll()) != null){
				final int required = result.requiredScratchSize();
				if (scratch.length < required)
					scratch = new float[required];

				result.build(projected, scratch);

				byte newFlags = 0;
				for (int camIdx = 0; camIdx < cullingCameras.size(); camIdx++) {
					if (result.test(cullingCameras.get(camIdx), scratch))
						newFlags |= (byte) (1 << camIdx);
				}

				result.visibilityFlags = newFlags;
				result.cullingJobId = -1;
			}

		}

		@Override
		protected void onCompletion() {
			// A Job must not be returned to its pool until JobHandle has marked it complete. Reusing it from
			// onRun() races with the worker's completion bookkeeping and can corrupt a subsequently queued job.
			pendingCullingResults.clear();
			cullingCameras.clear();
			CULLING_JOB_POOL.recycle(this);
		}
	}

	private static final byte STATE_RELEASED = 0;
	private static final byte STATE_USED = 1;
	private static final byte STATE_QUEUED = 2;

	public final class CullingResult {
		private int[] primitiveTypes = new int[INITIAL_PRIMITIVE_CAPACITY];
		private int primitiveCount;

		// Running local-space union of every added primitive's bounds, used as the coarse early-out AABB.
		private float coarseMinX, coarseMinY, coarseMinZ;
		private float coarseMaxX, coarseMaxY, coarseMaxZ;

		@Getter
		private byte visibilityFlags = 0;

		private CullingJob job;
		public Projection projection;

		public float offsetX, offsetY, offsetZ;
		private int cullingJobId = -1;
		private byte state;

		private int requiredScratchSize() { return PRIMITIVES_OFFSET + primitiveCount * FLOATS_PER_PRIMITIVE; }

		private void checkNotReleased() {
			if (state == STATE_RELEASED)
				throw new IllegalStateException("CullingResult has been released");
		}

		private void ensureCapacity(int minCapacity) {
			if (minCapacity <= primitiveTypes.length)
				return;

			int newCapacity = primitiveTypes.length + (primitiveTypes.length >> 1);
			if (newCapacity < minCapacity)
				newCapacity = minCapacity;

			primitiveTypes = Arrays.copyOf(primitiveTypes, newCapacity);
		}

		public void addSphere(float x, float y, float z, float radius) {
			assert !Float.isNaN(x);
			assert !Float.isNaN(y);
			assert !Float.isNaN(z);
			assert !Float.isNaN(radius);

			checkNotReleased();
			ensureCapacity(primitiveCount + 1);

			int slot = allocatePrimitiveSlot();
			int base = slot * FLOATS_PER_PRIMITIVE;
			primitiveData[base] = x;
			primitiveData[base + 1] = y;
			primitiveData[base + 2] = z;
			primitiveData[base + 3] = radius;

			primitiveTypes[primitiveCount] = pack(PRIMITIVE_SPHERE, slot);

			expandCoarseAABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);

			primitiveCount++;
		}

		public void addBox(float centerX, float centerY, float centerZ, float extentX, float extentY, float extentZ) {
			assert !Float.isNaN(centerX);
			assert !Float.isNaN(centerY);
			assert !Float.isNaN(centerZ);

			assert !Float.isNaN(extentX);
			assert !Float.isNaN(extentY);
			assert !Float.isNaN(extentZ);

			checkNotReleased();
			ensureCapacity(primitiveCount + 1);

			int slot = allocatePrimitiveSlot();
			int base = slot * FLOATS_PER_PRIMITIVE;
			primitiveData[base] = centerX;
			primitiveData[base + 1] = centerY;
			primitiveData[base + 2] = centerZ;
			primitiveData[base + 3] = extentX;
			primitiveData[base + 4] = extentY;
			primitiveData[base + 5] = extentZ;

			primitiveTypes[primitiveCount] = pack(PRIMITIVE_AABB, slot);

			expandCoarseAABB(
				centerX - extentX, centerY - extentY, centerZ - extentZ,
				centerX + extentX, centerY + extentY, centerZ + extentZ
			);

			primitiveCount++;
		}

		public void addAABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			assert minX != Float.POSITIVE_INFINITY;
			assert minY != Float.POSITIVE_INFINITY;
			assert minZ != Float.POSITIVE_INFINITY;

			assert maxX != Float.NEGATIVE_INFINITY;
			assert maxY != Float.NEGATIVE_INFINITY;
			assert maxZ != Float.NEGATIVE_INFINITY;

			float lowerX = min(minX, maxX);
			float lowerY = min(minY, maxY);
			float lowerZ = min(minZ, maxZ);
			float upperX = max(minX, maxX);
			float upperY = max(minY, maxY);
			float upperZ = max(minZ, maxZ);

			addBox(
				(lowerX + upperX) * 0.5f, (lowerY + upperY) * 0.5f, (lowerZ + upperZ) * 0.5f,
				(upperX - lowerX) * 0.5f, (upperY - lowerY) * 0.5f, (upperZ - lowerZ) * 0.5f
			);
		}

		public int optimise() { return optimise(0); }

		public int optimise(float mergeThreshold) {
			checkNotReleased();
			if (mergeThreshold < 0 || Float.isNaN(mergeThreshold))
				throw new IllegalArgumentException("mergeThreshold must be non-negative");

			int originalCount = primitiveCount;
			removeEncapsulatedPrimitives();

			// A merge can make the resulting box compatible with another box.
			int iteration = 0;
			while (mergeCompatibleAABBs(mergeThreshold)) {
				iteration++;
				if(iteration > originalCount * 4) {
					// Avoid infinite loop to cap at 4 times the original count, which should be more than enough.
					break;
				}
			}

			return originalCount - primitiveCount;
		}

		private void removeEncapsulatedPrimitives() {
			for (int primitiveIndex = 0; primitiveIndex < primitiveCount; primitiveIndex++) {
				int primitive = primitiveTypes[primitiveIndex];
				for (int candidateIndex = 0; candidateIndex < primitiveCount; candidateIndex++) {
					if (primitiveIndex != candidateIndex && contains(primitiveTypes[candidateIndex], primitive)) {
						removePrimitive(primitiveIndex--);
						break;
					}
				}
			}
		}

		private boolean mergeCompatibleAABBs(float mergeThreshold) {
			for (int firstIndex = 0; firstIndex < primitiveCount; firstIndex++) {
				int first = primitiveTypes[firstIndex];
				if (unpackType(first) != PRIMITIVE_AABB)
					continue;

				for (int secondIndex = firstIndex + 1; secondIndex < primitiveCount; secondIndex++) {
					int second = primitiveTypes[secondIndex];
					if (unpackType(second) != PRIMITIVE_AABB || !canMerge(first, second, mergeThreshold))
						continue;

					mergeAABBs(first, second);
					removePrimitive(secondIndex);
					return true;
				}
			}

			return false;
		}

		private void removePrimitive(int index) {
			freePrimitiveSlot(unpackSlot(primitiveTypes[index]));
			int elementsAfter = primitiveCount - index - 1;
			if (elementsAfter > 0)
				System.arraycopy(primitiveTypes, index + 1, primitiveTypes, index, elementsAfter);
			primitiveCount--;
		}

		private void expandCoarseAABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			if (primitiveCount == 0) {
				coarseMinX = minX;
				coarseMinY = minY;
				coarseMinZ = minZ;
				coarseMaxX = maxX;
				coarseMaxY = maxY;
				coarseMaxZ = maxZ;
			} else {
				coarseMinX = min(coarseMinX, minX);
				coarseMinY = min(coarseMinY, minY);
				coarseMinZ = min(coarseMinZ, minZ);
				coarseMaxX = max(coarseMaxX, maxX);
				coarseMaxY = max(coarseMaxY, maxY);
				coarseMaxZ = max(coarseMaxZ, maxZ);
			}
		}

		private void build(float[] p, float[] scratch) {
			if (primitiveCount > COARSE_PRIMITIVE_THRESHOLD) {
				buildAABB(
					p,
					offsetX + coarseMinX, offsetY + coarseMinY, offsetZ + coarseMinZ,
					offsetX + coarseMaxX, offsetY + coarseMaxY, offsetZ + coarseMaxZ,
					scratch, 0
				);
			}

			for (int i = 0; i < primitiveCount; i++) {
				int packed = primitiveTypes[i];
				int base = unpackSlot(packed) * FLOATS_PER_PRIMITIVE;
				int outBase = PRIMITIVES_OFFSET + i * FLOATS_PER_PRIMITIVE;

				if (unpackType(packed) == PRIMITIVE_SPHERE) {
					buildSphere(
						p,
						offsetX + primitiveData[base],
						offsetY + primitiveData[base + 1],
						offsetZ + primitiveData[base + 2],
						primitiveData[base + 3],
						scratch, outBase
					);
				} else {
					float cx = primitiveData[base];
					float cy = primitiveData[base + 1];
					float cz = primitiveData[base + 2];
					float ex = primitiveData[base + 3];
					float ey = primitiveData[base + 4];
					float ez = primitiveData[base + 5];

					buildAABB(
						p,
						offsetX + cx - ex, offsetY + cy - ey, offsetZ + cz - ez,
						offsetX + cx + ex, offsetY + cy + ey, offsetZ + cz + ez,
						scratch, outBase
					);
				}
			}
		}

		private void buildAABB(
			float[] p,
			float minX, float minY, float minZ,
			float maxX, float maxY, float maxZ,
			float[] scratch, int outBase
		) {
			if (projection == null) {
				scratch[outBase] = minX;
				scratch[outBase + 1] = minY;
				scratch[outBase + 2] = minZ;
				scratch[outBase + 3] = maxX;
				scratch[outBase + 4] = maxY;
				scratch[outBase + 5] = maxZ;
				return;
			}

			float outMinX = Float.POSITIVE_INFINITY;
			float outMinY = Float.POSITIVE_INFINITY;
			float outMinZ = Float.POSITIVE_INFINITY;

			float outMaxX = Float.NEGATIVE_INFINITY;
			float outMaxY = Float.NEGATIVE_INFINITY;
			float outMaxZ = Float.NEGATIVE_INFINITY;

			for (int ix = 0; ix <= 1; ix++) {
				for (int iy = 0; iy <= 1; iy++) {
					for (int iz = 0; iz <= 1; iz++) {
						projection.project(
							ix == 0 ? minX : maxX,
							iy == 0 ? minY : maxY,
							iz == 0 ? minZ : maxZ,
							p
						);

						outMinX = min(outMinX, p[0]);
						outMinY = min(outMinY, p[1]);
						outMinZ = min(outMinZ, p[2]);

						outMaxX = max(outMaxX, p[0]);
						outMaxY = max(outMaxY, p[1]);
						outMaxZ = max(outMaxZ, p[2]);
					}
				}
			}

			scratch[outBase] = outMinX;
			scratch[outBase + 1] = outMinY;
			scratch[outBase + 2] = outMinZ;
			scratch[outBase + 3] = outMaxX;
			scratch[outBase + 4] = outMaxY;
			scratch[outBase + 5] = outMaxZ;
		}

		private void buildSphere(
			float[] p,
			float worldX, float worldY, float worldZ, float radius,
			float[] scratch, int outBase
		) {
			if (projection == null) {
				scratch[outBase] = worldX;
				scratch[outBase + 1] = worldY;
				scratch[outBase + 2] = worldZ;
				scratch[outBase + 3] = radius;
				return;
			}

			projection.project(worldX, worldY, worldZ, p);
			float cx = p[0];
			float cy = p[1];
			float cz = p[2];

			projection.project(worldX + radius, worldY + radius, worldZ + radius, p);
			float projectedRadius = max(abs(p[0] - cx), max(abs(p[1] - cy), abs(p[2] - cz)));

			scratch[outBase] = cx;
			scratch[outBase + 1] = cy;
			scratch[outBase + 2] = cz;
			scratch[outBase + 3] = projectedRadius;
		}

		private boolean test(Camera camera, float[] scratch) {
			if (primitiveCount > COARSE_PRIMITIVE_THRESHOLD) {
				boolean coarseVisible = camera.intersectsAABB(
					scratch[0], scratch[1], scratch[2],
					scratch[3], scratch[4], scratch[5]
				);

				if (!coarseVisible)
					return false;
			}

			for (int i = 0; i < primitiveCount; i++) {
				int base = PRIMITIVES_OFFSET + i * FLOATS_PER_PRIMITIVE;

				boolean hit = unpackType(primitiveTypes[i]) == PRIMITIVE_SPHERE
					? camera.intersectsSphere(scratch[base], scratch[base + 1], scratch[base + 2], scratch[base + 3])
					: camera.intersectsAABB(
					scratch[base], scratch[base + 1], scratch[base + 2],
					scratch[base + 3], scratch[base + 4], scratch[base + 5]
				);

				if (hit)
					return true;
			}

			return false;
		}

		private void debugDraw(float[] scratch) {
			Color color = isVisible() ? Color.GREEN : Color.RED;

			if (primitiveCount > COARSE_PRIMITIVE_THRESHOLD) {
				DebugDraw.drawMinMax(
					scratch[0], scratch[1], scratch[2],
					scratch[3], scratch[4], scratch[5],
					Color.YELLOW, false
				);
			}

			for (int i = 0; i < primitiveCount; i++) {
				int base = PRIMITIVES_OFFSET + i * FLOATS_PER_PRIMITIVE;

				if (unpackType(primitiveTypes[i]) == PRIMITIVE_SPHERE) {
					DebugDraw.drawSphere(scratch[base], scratch[base + 1], scratch[base + 2], scratch[base + 3], color, false);
				} else {
					DebugDraw.drawMinMax(
						scratch[base], scratch[base + 1], scratch[base + 2],
						scratch[base + 3], scratch[base + 4], scratch[base + 5],
						color, false
					);
				}
			}
		}

		private void ensureJobCompletion() {
			if (job == null)
				return;
			while (cullingJobId == job.id)
				job.waitForCompletion(100);
			job = null;
			state = STATE_USED;
		}

		public boolean isVisible() {
			ensureJobCompletion();
			return visibilityFlags != 0;
		}

		public boolean isVisible(Camera camera) {
			ensureJobCompletion();
			return (visibilityFlags & camera.getCullingMask()) != 0;
		}

		public void queue() { queue(true); }

		public synchronized void queue(boolean shouldFlush) {
			checkNotReleased();
			ensureJobCompletion();

			synchronized (SceneCullingManager.this) {
				state = STATE_QUEUED;
				pendingCullingResults.add(this);

				if (shouldFlush && pendingCullingResults.size() >= HdPlugin.PROCESSOR_COUNT)
					flush();
			}
		}

		public void reset() {
			checkNotReleased();
			visibilityFlags = 0;
		}

		public synchronized void release() {
			if (state == STATE_RELEASED)
				return;

			synchronized (SceneCullingManager.this) {
				pendingCullingResults.remove(this);
			}

			ensureJobCompletion();
			for (int i = 0; i < primitiveCount; i++)
				freePrimitiveSlot(unpackSlot(primitiveTypes[i]));

			primitiveCount = 0;
			offsetX = offsetY = offsetZ = 0;
			visibilityFlags = 0;
			projection = null;
			job = null;
			cullingJobId = -1;
			state = STATE_RELEASED;
			CULLING_RESULT_POOL.recycle(this);
		}
	}
}