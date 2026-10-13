package rs117.hd.renderer.zone;

import java.util.Arrays;

/**
 * The shared, position-independent geometry of an alpha model. Multiple {@link AlphaModelInstance}s can reference the
 * same {@link StaticAlphaModel}, since the packed face data only encodes the model's local shape and orientation.
 * <p>
 * Because the sorted face order only depends on the camera orientation, a single sort can be shared by every instance
 * referencing this model. The cached sort result is guarded by {@code synchronized(this)}.
 */
public final class StaticAlphaModel {
	final int[] packedFaces;
	final int[] doubleSidedBitSet; // null if there are no double-sided faces
	final char doubleSidedCount;
	final int radius;
	private final int hash;

	// Number of AlphaModelInstances referencing this model. Guarded by the owning WorldViewContext
	int refCount;

	// Cached sort result shared by all instances. Guarded by synchronized(this)
	int[] sortedFaces;
	int sortedFaceCount;
	int sortKey = -1;

	// Offset of this model's sorted-index block within the world view's packed index buffer, assigned per frame by
	// StaticAlphaSortingJob, or -1 when the model has no block this frame.
	int sortedIndiciesOffset = -1;

	StaticAlphaModel(int[] packedFaces, int[] doubleSidedBitSet, char doubleSidedCount, int radius) {
		this.packedFaces = packedFaces;
		this.doubleSidedBitSet = doubleSidedBitSet;
		this.doubleSidedCount = doubleSidedCount;
		this.radius = radius;

		int h = Arrays.hashCode(packedFaces);
		h = 31 * h + Arrays.hashCode(doubleSidedBitSet);
		h = 31 * h + doubleSidedCount;
		h = 31 * h + radius;
		hash = h;
	}

	int getFaceCount() {
		return packedFaces.length + doubleSidedCount;
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof StaticAlphaModel))
			return false;

		final StaticAlphaModel other = (StaticAlphaModel) o;
		return hash == other.hash &&
			   radius == other.radius &&
			   doubleSidedCount == other.doubleSidedCount &&
			   Arrays.equals(packedFaces, other.packedFaces) &&
			   Arrays.equals(doubleSidedBitSet, other.doubleSidedBitSet);
	}
}
