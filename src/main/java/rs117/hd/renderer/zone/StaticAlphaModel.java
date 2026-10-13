package rs117.hd.renderer.zone;

import java.util.Arrays;

public final class StaticAlphaModel {
	final int[] packedFaces;
	final int[] doubleSidedBitSet; // null if there are no double-sided faces
	final char doubleSidedCount;
	final int radius;
	private final int hash;

	// Number of AlphaModelInstances referencing this model. Guarded by the owning WorldViewContext
	int refCount;

	final char[] sortedFaces;
	int sortedFaceCount;
	int sortKey = -1;
	int sortedIndiciesOffset = -1;

	StaticAlphaModel(int[] packedFaces, int[] doubleSidedBitSet, char doubleSidedCount, int radius) {
		this.packedFaces = packedFaces;
		this.doubleSidedBitSet = doubleSidedBitSet;
		this.doubleSidedCount = doubleSidedCount;
		this.radius = radius;
		this.sortedFaces = new char[packedFaces.length + doubleSidedCount];

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
