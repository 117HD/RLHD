package rs117.hd.utils.collection;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collection.Util.DEFAULT_GROWTH;
import static rs117.hd.utils.collection.Util.EMPTY;
import static rs117.hd.utils.collection.Util.READ_CACHE_SIZE;
import static rs117.hd.utils.collection.Util.findIndex;
import static rs117.hd.utils.collection.Util.murmurHash3;

public final class Int2IntCache {
	private final int maxSize;
	private final float growthFactor;

	private final StampedLock lock = new StampedLock();

	private final long[] readCache = new long[READ_CACHE_SIZE];
	private int[] keys;
	private int[] values;
	private int[] ages;
	private int[] distances;

	private int size;
	private int mask;
	private int ageCounter;

	public Int2IntCache(int initialCapacity, int maxSize) {
		this(initialCapacity, maxSize, DEFAULT_GROWTH);
	}

	public Int2IntCache(int initialCapacity, int maxSize, float growthFactor) {
		int cap = max((int) HDUtils.ceilPow2(initialCapacity), 16);

		keys = new int[cap];
		values = new int[cap];
		ages = new int[cap];
		distances = new int[cap];

		Arrays.fill(keys, EMPTY);

		this.mask = cap - 1;
		this.maxSize = maxSize;
		this.growthFactor = growthFactor;
	}

	public int getOrDefault(int key, int defaultValue) {
		long stamp = lock.tryOptimisticRead();
		int idx = findIndex(key, mask, keys, distances, readCache);

		if (!lock.validate(stamp)) {
			stamp = lock.readLock();
			try {
				idx = findIndex(key, mask, keys, distances, readCache);
			} finally {
				lock.unlockRead(stamp);
			}
		}

		if (idx >= 0) {
			ages[idx] = ++ageCounter;
			return values[idx];
		}

		return defaultValue;
	}

	public void put(int key, int value) {
		long stamp = lock.writeLock();
		try {
			normalizeAgesIfNeeded();

			if (size + 1.0 >= keys.length * Util.LOAD_FACTOR)
				resize();

			int idx = insertIndex(key);
			values[idx] = value;
			ages[idx] = ++ageCounter;

			if (size > maxSize)
				evictOldest();
		} finally {
			lock.unlockWrite(stamp);
		}
	}

	private int insertIndex(int key) {
		final int[] keys = this.keys;
		final int[] distances = this.distances;

		int idx = murmurHash3(key) & mask;
		for (int dist = 0; ; dist++) {
			final int k = keys[idx];

			if (k == EMPTY) {
				keys[idx] = key;
				distances[idx] = dist;
				size++;
				return idx;
			}

			if (k == key)
				return idx;

			if (distances[idx] < dist) {
				// Robin Hood swap
				int tmpKey = keys[idx];
				int tmpVal = values[idx];
				int tmpAge = ages[idx];
				int tmpDist = distances[idx];

				keys[idx] = key;
				values[idx] = 0;
				ages[idx] = 0;
				distances[idx] = dist;

				key = tmpKey;
				values[idx] = tmpVal;
				ages[idx] = tmpAge;
				dist = tmpDist;
			}

			idx = (idx + 1) & mask;
			dist++;
		}
	}

	private void resize() {
		int newCap = (int) HDUtils.ceilPow2(
			max((int) (keys.length * growthFactor), keys.length + 1)
		);

		int[] oldKeys = keys;
		int[] oldValues = values;
		int[] oldAges = ages;

		keys = new int[newCap];
		values = new int[newCap];
		ages = new int[newCap];
		distances = new int[newCap];

		Arrays.fill(keys, EMPTY);

		size = 0;
		mask = newCap - 1;

		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] != EMPTY) {
				int idx = insertIndex(oldKeys[i]);
				values[idx] = oldValues[i];
				ages[idx] = oldAges[i];
			}
		}
	}

	private void evictOldest() {
		int oldestIdx = -1;
		int oldestAge = Integer.MAX_VALUE;

		for (int i = 0; i < keys.length; i++) {
			if (keys[i] != EMPTY && ages[i] < oldestAge) {
				oldestAge = ages[i];
				oldestIdx = i;
			}
		}

		if (oldestIdx >= 0)
			removeIndex(oldestIdx);
	}

	private void removeIndex(int idx) {
		keys[idx] = EMPTY;
		values[idx] = 0;
		ages[idx] = 0;
		distances[idx] = 0;
		size--;

		int last = idx;
		while (true) {
			int next = (last + 1) & mask;
			if (keys[next] == EMPTY || distances[next] == 0)
				break;

			keys[last] = keys[next];
			values[last] = values[next];
			ages[last] = ages[next];
			distances[last] = distances[next] - 1;

			keys[next] = EMPTY;
			values[next] = 0;
			ages[next] = 0;
			distances[next] = 0;

			last = next;
		}
	}

	private void normalizeAgesIfNeeded() {
		if ((ageCounter >>> 30) == 0)
			return;

		int minAge = Integer.MAX_VALUE;
		for (int i = 0; i < keys.length; i++) {
			if (keys[i] != EMPTY)
				minAge = Math.min(minAge, ages[i]);
		}

		for (int i = 0; i < keys.length; i++) {
			if (keys[i] != EMPTY)
				ages[i] -= minAge;
		}

		ageCounter -= minAge;
	}

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public void clear() {
		long stamp = lock.writeLock();
		try {
			Arrays.fill(keys, EMPTY);
			Arrays.fill(values, 0);
			Arrays.fill(ages, 0);
			Arrays.fill(distances, 0);
			size = 0;
			ageCounter = 0;
		} finally {
			lock.unlockWrite(stamp);
		}
	}
}