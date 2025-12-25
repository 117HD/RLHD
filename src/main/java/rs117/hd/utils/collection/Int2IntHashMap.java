package rs117.hd.utils.collection;

import java.util.Arrays;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.HashUtil.murmurHash3;
import static rs117.hd.utils.MathUtils.*;

public final class Int2IntHashMap {
	private static final int DEFAULT_CAPACITY = 16;
	private static final float DEFAULT_GROWTH = 1.5f;
	private static final float LOAD_FACTOR = 0.7f;
	private static final int EMPTY = Integer.MIN_VALUE;

	private final float growthFactor;

	private int[] keys;
	private int[] values;
	private int[] distances;

	private int size;
	private int mask;

	// high 32 bits = idx, low 32 bits = key
	private volatile long lastRead;

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

	public boolean add(int key) {
		return put(key, 1);
	}

	public boolean put(int key, int value) {
		return put(key, value, true);
	}

	public boolean putIfAbsent(int key, int value) {
		return put(key, value, false);
	}

	private boolean put(int key, int value, boolean overwrite) {
		if (size + 1.0 >= keys.length * LOAD_FACTOR)
			resize();

		int hash = murmurHash3(key);
		int idx = hash & mask;
		int dist = 0;

		while (true) {
			int k = keys[idx];

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

	public int findIndex(int key) {
		final long lastRead = this.lastRead;
		if ((int) lastRead == key)
			return (int) (lastRead >>> 32);

		int hash = murmurHash3(key);
		int idx = hash & mask;
		int dist = 0;

		while (true) {
			int k = keys[idx];

			if (k == EMPTY)
				return -1;

			if (k == key) {
				this.lastRead = ((long) idx << 32) | (key & 0xFFFFFFFFL);
				return idx;
			}

			// Robin Hood early-exit: no further keys can match
			if (distances[idx] < dist)
				return -1;

			idx = (idx + 1) & mask;
			dist++;
		}
	}

	public int getOrDefault(int key, int defaultValue) {
		int idx = findIndex(key);
		return idx >= 0 ? values[idx] : defaultValue;
	}

	public boolean containsKey(int key) {
		return findIndex(key) >= 0;
	}

	public int getValue(int idx) {
		return values[idx];
	}

	public void setValue(int idx, int value) {
		values[idx] = value;
	}

	public boolean remove(int key) {
		int idx = findIndex(key);
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
