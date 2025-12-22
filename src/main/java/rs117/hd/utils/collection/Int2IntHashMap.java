package rs117.hd.utils.collection;

import java.util.Arrays;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.MathUtils.*;

public final class Int2IntHashMap {
	private static final int DEFAULT_CAPACITY = 16;
	private static final float DEFAULT_GROWTH = 1.5f;
	private static final int EMPTY = Integer.MIN_VALUE;

	private int[] keys;
	private int[] values;
	private int size;
	private final float growthFactor;
	private int mask;

	public Int2IntHashMap() {
		this(DEFAULT_CAPACITY, DEFAULT_GROWTH);
	}

	public Int2IntHashMap(int initialCapacity) {
		this(initialCapacity, DEFAULT_GROWTH);
	}

	public Int2IntHashMap(int initialCapacity, float growthFactor) {
		keys = new int[(int) HDUtils.ceilPow2(initialCapacity)];
		values = new int[keys.length];
		Arrays.fill(keys, EMPTY);
		this.growthFactor = growthFactor;
		this.size = 0;
		this.mask = keys.length - 1;
	}

	private int hash(int key) {
		return key & mask;
	}

	private void resize() {
		int newCapacity = (int) HDUtils.ceilPow2(max((int)(keys.length * growthFactor), keys.length + 1));
		int[] oldKeys = keys;
		int[] oldValues = values;

		keys = new int[newCapacity];
		values = new int[newCapacity];
		Arrays.fill(keys, EMPTY);
		size = 0;
		mask = newCapacity - 1;

		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] != EMPTY) {
				put(oldKeys[i], oldValues[i]);
			}
		}
	}

	public boolean put(int key, int value) {
		if (size >= keys.length * 0.75f) resize();

		int idx = hash(key);
		while (keys[idx] != EMPTY) {
			if (keys[idx] == key) {
				values[idx] = value; // overwrite existing
				return false;
			}
			idx = (idx + 1) & mask; // fast wrap-around using bitmask
		}

		keys[idx] = key;
		values[idx] = value;
		size++;
		return true;
	}

	public int findIndex(int key) {
		int idx = hash(key);
		while (keys[idx] != EMPTY) {
			if (keys[idx] == key) return idx;
			idx = (idx + 1) & mask;
		}
		return -1;
	}

	public int getOrDefault(int key, int defaultValue) {
		int idx = findIndex(key);
		return idx >= 0 ? values[idx] : defaultValue;
	}

	public int getValue(int idx) { return values[idx]; }

	public void setValue(int idx, int value) { values[idx] = value; }

	public boolean containsKey(int key) { return findIndex(key) >= 0; }

	public boolean remove(int key) {
		int idx = findIndex(key);
		if (idx < 0)
			return false;
		removeIndex(idx);
		return true;
	}

	public void removeIndex(int idx) {
		int lastIdx = idx;
		keys[idx] = EMPTY;
		values[idx] = 0;
		size--;

		while (true) {
			int nextIdx = (lastIdx + 1) & mask;
			if (keys[nextIdx] == EMPTY) break;

			int idealIdx = hash(keys[nextIdx]);
			if ((nextIdx > lastIdx && (idealIdx <= lastIdx || idealIdx > nextIdx)) ||
				(nextIdx < lastIdx && (idealIdx <= lastIdx && idealIdx > nextIdx))) {
				keys[lastIdx] = keys[nextIdx];
				values[lastIdx] = values[nextIdx];
				keys[nextIdx] = EMPTY;
				values[nextIdx] = 0;
				lastIdx = nextIdx;
			} else {
				break;
			}
		}
	}

	public int size() {
		return size;
	}
}