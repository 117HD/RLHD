package rs117.hd.utils.collection;

import java.util.Arrays;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.HashUtil.murmurHash3;
import static rs117.hd.utils.MathUtils.*;

public final class IntHashSet {
	private static final int DEFAULT_CAPACITY = 16;
	private static final float DEFAULT_GROWTH = 1.5f;
	private static final int EMPTY = Integer.MIN_VALUE;

	private final float growthFactor;

	private int[] keys;
	private int size;
	private int mask;

	public IntHashSet() {
		this(DEFAULT_CAPACITY, DEFAULT_GROWTH);
	}

	public IntHashSet(int initialCapacity) {
		this(initialCapacity, DEFAULT_GROWTH);
	}

	public IntHashSet(int initialCapacity, float growthFactor) {
		keys = new int[max((int) HDUtils.ceilPow2(initialCapacity), 16)];
		Arrays.fill(keys, EMPTY);
		this.growthFactor = growthFactor;
		this.size = 0;
		this.mask = keys.length - 1;
	}

	private void resize() {
		int newCapacity = (int) HDUtils.ceilPow2(
			max((int) (keys.length * growthFactor), keys.length + 1)
		);

		int[] oldKeys = keys;

		keys = new int[newCapacity];
		Arrays.fill(keys, EMPTY);
		size = 0;
		mask = newCapacity - 1;

		for (int i = 0; i < oldKeys.length; i++) {
			int key = oldKeys[i];
			if (key != EMPTY) {
				add(key);
			}
		}
	}

	public boolean add(int key) {
		int idx = murmurHash3(key) & mask;
		int currentKey;

		while ((currentKey = keys[idx]) != EMPTY) {
			if (currentKey == key) {
				return false; // already present
			}
			idx = (idx + 1) & mask;
		}

		if ((size + 1) >= keys.length)
			resize();

		keys[idx] = key;
		size++;
		return true;
	}

	public boolean contains(int key) {
		return findIndex(key) >= 0;
	}

	private int findIndex(int key) {
		int idx = murmurHash3(key) & mask;
		int currentKey;

		while ((currentKey = keys[idx]) != EMPTY) {
			if (currentKey == key)
				return idx;
			idx = (idx + 1) & mask;
		}
		return -1;
	}

	public boolean remove(int key) {
		int idx = findIndex(key);
		if (idx < 0)
			return false;
		removeIndex(idx);
		return true;
	}

	private void removeIndex(int idx) {
		int lastIdx = idx;
		keys[idx] = EMPTY;
		size--;

		while (true) {
			int nextIdx = (lastIdx + 1) & mask;
			int key = keys[nextIdx];
			if (key == EMPTY)
				break;

			int idealIdx = murmurHash3(key) & mask;
			if ((nextIdx > lastIdx && (idealIdx <= lastIdx || idealIdx > nextIdx)) ||
				(nextIdx < lastIdx && (idealIdx <= lastIdx && idealIdx > nextIdx))) {
				keys[lastIdx] = key;
				keys[nextIdx] = EMPTY;
				lastIdx = nextIdx;
			} else {
				break;
			}
		}
	}

	public void clear() {
		Arrays.fill(keys, EMPTY);
		size = 0;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public int size() {
		return size;
	}
}
