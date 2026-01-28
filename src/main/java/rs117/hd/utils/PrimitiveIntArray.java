package rs117.hd.utils;

import java.util.Arrays;

import static rs117.hd.utils.HDUtils.ceilPow2;

public class PrimitiveIntArray {
	public int[] faces = new int[16];
	public int length;

	public PrimitiveIntArray reset() {
		length = 0;
		return this;
	}

	public PrimitiveIntArray ensureCapacity(int count) {
		if (length + count > faces.length)
			faces = Arrays.copyOf(faces, ceilPow2(length + count));
		return this;
	}

	public void putFace(int f) {
		if (length < faces.length)
			faces[length++] = f;
	}

	public void putFaces(int[] indices, int offset, int count) {
		ensureCapacity(count);
		System.arraycopy(indices, offset, faces, length, count);
		length += count;
	}

	public void removeAt(int idx) {
		if (idx < 0 || idx >= length)
			return;
		System.arraycopy(faces, idx + 1, faces, idx, length - idx - 1);
		length--;
	}

	public void removeAtSwap(int idx) {
		if (idx < 0 || idx >= length)
			return;
		faces[idx] = faces[--length];
	}
}
