package rs117.hd.utils.collection;

import java.util.Arrays;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collection.Util.DEFAULT_CAPACITY;
import static rs117.hd.utils.collection.Util.DEFAULT_GROWTH;
import static rs117.hd.utils.collection.Util.EMPTY;
import static rs117.hd.utils.collection.Util.LOAD_FACTOR;
import static rs117.hd.utils.collection.Util.READ_CACHE_SIZE;
import static rs117.hd.utils.collection.Util.findIndex;
import static rs117.hd.utils.collection.Util.murmurHash3;

public final class Int2IntHashMap {
	private final float growthFactor;

	private final long[] readCache = new long[READ_CACHE_SIZE];
	private int[] keys;
	private int[] values;
	private int[] distances;

	private int size;
	private int mask;


	public Int2IntHashMap() {
		this(DEFAULT_CAPACITY, DEFAULT_GROWTH);
	}

	public Int2IntHashMap(int initialCapacity) {
		this(initialCapacity, DEFAULT_GROWTH);
	}

	public Int2IntHashMap(int initialCapacity, float growthFactor) {
		int cap = max((int) HDUtils.ceilPow2(initialCapacity), DEFAULT_CAPACITY);

		keys = new int[cap];
		values = new int[cap];
		distances = new int[cap];

		Arrays.fill(keys, EMPTY);

		this.growthFactor = growthFactor;
		this.mask = cap - 1;
		this.size = 0;
	}

	private void resize() {
		int newCapacity = (int) HDUtils.ceilPow2(
			max((int) (keys.length * growthFactor), keys.length + 1)
		);

		int[] oldKeys = keys;
		int[] oldValues = values;

		keys = new int[newCapacity];
		values = new int[newCapacity];
		distances = new int[newCapacity];

		Arrays.fill(keys, EMPTY);

		mask = newCapacity - 1;
		size = 0;

		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] != EMPTY) {
				put(oldKeys[i], oldValues[i]);
			}
		}
	}

	public boolean put(Object key, int value) { return key != null && put(key.hashCode(), value); }

	public boolean put(int key, int value) {
		return put(key, value, true);
	}

	public boolean putIfAbsent(Object key, int value) { return key != null && put(key.hashCode(), value, false); }

	public boolean putIfAbsent(int key, int value) {
		return put(key, value, false);
	}

	private boolean put(int key, int value, boolean overwrite) {
		if (size + 1.0 >= keys.length * LOAD_FACTOR)
			resize();

		final int[] keys = this.keys;
		final int[] distances = this.distances;

		int idx = murmurHash3(key) & mask;
		for (int dist = 0; ; dist++) {
			final int k = keys[idx];

			if (k == EMPTY) {
				keys[idx] = key;
				values[idx] = value;
				distances[idx] = dist;
				size++;
				return true;
			}

			if (k == key) {
				if (overwrite)
					values[idx] = value;
				return false;
			}

			// Robin Hood swap: steal slot if we've probed farther
			if (distances[idx] < dist) {
				int tmpKey = keys[idx];
				int tmpVal = values[idx];
				int tmpDist = distances[idx];

				keys[idx] = key;
				values[idx] = value;
				distances[idx] = dist;

				key = tmpKey;
				value = tmpVal;
				dist = tmpDist;
			}

			idx = (idx + 1) & mask;
			dist++;
		}
	}

	public int getOrDefault(Object key, int defaultValue) { return key != null ? getOrDefault(key.hashCode(), defaultValue) : defaultValue; }

	public int getOrDefault(int key, int defaultValue) {
		int idx = findIndex(key, mask, keys, distances, readCache);
		return idx >= 0 ? values[idx] : defaultValue;
	}

	public boolean containsKey(Object key) { return key != null && containsKey(key.hashCode()); }

	public boolean containsKey(int key) {
		return findIndex(key, mask, keys, distances, readCache) >= 0;
	}

	public int getValue(int idx) {
		return values[idx];
	}

	public void setValue(int idx, int value) {
		values[idx] = value;
	}

	public boolean remove(Object key) { return key != null && remove(key.hashCode()); }

	public boolean remove(int key) {
		int idx = findIndex(key, mask, keys, distances, readCache);
		if (idx < 0)
			return false;

		removeIndex(idx);
		return true;
	}

	public void removeIndex(int idx) {
		keys[idx] = EMPTY;
		values[idx] = 0;
		distances[idx] = 0;
		size--;

		int last = idx;

		// Shift backward while probe distance allows
		while (true) {
			int next = (last + 1) & mask;
			if (keys[next] == EMPTY || distances[next] == 0)
				break;

			keys[last] = keys[next];
			values[last] = values[next];
			distances[last] = distances[next] - 1;

			keys[next] = EMPTY;
			values[next] = 0;
			distances[next] = 0;

			last = next;
		}
	}

	public void clear() {
		Arrays.fill(keys, EMPTY);
		Arrays.fill(values, 0);
		Arrays.fill(distances, 0);
		size = 0;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public int size() {
		return size;
	}
}
