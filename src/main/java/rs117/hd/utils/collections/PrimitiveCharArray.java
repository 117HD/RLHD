package rs117.hd.utils.collections;

import java.util.Arrays;

import static java.lang.System.arraycopy;
import static rs117.hd.utils.HDUtils.ceilPow2;

public final class PrimitiveCharArray {
	public char[] array = new char[16];
	public int length;

	public PrimitiveCharArray reset() {
		length = 0;
		return this;
	}

	public PrimitiveCharArray ensureCapacity(int count) {
		if (length + count > array.length)
			array = Arrays.copyOf(array, ceilPow2(length + count));
		return this;
	}

	public void put(char i) {
		if (length < array.length)
			array[length++] = i;
	}

	public void put(char[] ints, int offset, int count) {
		ensureCapacity(count);
		arraycopy(ints, offset, array, length, count);
		length += count;
	}

	public void removeAt(int idx) {
		if (idx < 0 || idx >= length)
			return;
		arraycopy(array, idx + 1, array, idx, length - idx - 1);
		length--;
	}

	public void removeAtSwap(int idx) {
		if (idx < 0 || idx >= length)
			return;
		array[idx] = array[--length];
	}
}
