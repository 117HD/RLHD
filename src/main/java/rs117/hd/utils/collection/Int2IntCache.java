package rs117.hd.utils.collection;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.HashUtil.murmurHash3;
import static rs117.hd.utils.MathUtils.*;

public final class Int2IntCache {
	private static final int EMPTY = Integer.MIN_VALUE;
	private static final float LOAD_FACTOR = 0.7f;
	private static final float DEFAULT_GROWTH = 1.5f;

	private final int maxSize;
	private final float growthFactor;

	private final AtomicInteger seq = new AtomicInteger(); // even = stable, odd = write

	private int[] keys;
	private int[] values;
	private int[] ages;

	private int size;
	private int mask;
	private int ageCounter;

	public Int2IntCache(int initialCapacity, int maxSize) {
		this(initialCapacity, maxSize, DEFAULT_GROWTH);
	}

	public Int2IntCache(int initialCapacity, int maxSize, float growthFactor) {
		int cap = max((int) HDUtils.ceilPow2(initialCapacity), 16);

		this.keys = new int[cap];
		this.values = new int[cap];
		this.ages = new int[cap];

		Arrays.fill(keys, EMPTY);

		this.mask = cap - 1;
		this.maxSize = maxSize;
		this.growthFactor = growthFactor;
	}

	public int getOrDefault(int key, int defaultValue) {
		int spins = 0;
		while (true) {
			final int s = seq.get();
			if ((s & 1) != 0) {
				if (spins++ > 100)
					return defaultValue;
				Thread.onSpinWait();
				continue;
			}

			final int idx = findIndex(key);
			if (seq.get() != s)
				continue;

			if (idx >= 0) {
				ages[idx] = ++ageCounter;
				return values[idx];
			}
			return defaultValue;
		}
	}

	public synchronized void put(int key, int value) {
		seq.incrementAndGet(); // write start
		try {
			normalizeAgesIfNeeded();

			if (size + 1.0 >= keys.length * LOAD_FACTOR)
				resize();

			int idx = insertIndex(key);
			values[idx] = value;
			ages[idx] = ++ageCounter;

			if (size > maxSize)
				evictOldest();

		} finally {
			seq.incrementAndGet(); // write end
		}
	}

	private int findIndex(int key) {
		final int[] keys = this.keys;
		final int mask = this.mask;

		int idx = murmurHash3(key) & mask;
		int currentKey;
		while ((currentKey = keys[idx]) != EMPTY) {
			if (currentKey == key) {
				return idx;
			}
			idx = (idx + 1) & mask;
		}

		return -1;
	}

	private int insertIndex(int key) {
		int idx = murmurHash3(key) & mask;
		int k;
		while ((k = keys[idx]) != EMPTY) {
			if (k == key)
				return idx;
			idx = (idx + 1) & mask;
		}

		keys[idx] = key;
		size++;
		return idx;
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
		size--;

		int last = idx;
		while (true) {
			int next = (last + 1) & mask;
			if (keys[next] == EMPTY)
				break;

			int ideal = murmurHash3(keys[next]) & mask;
			if ((next > last && (ideal <= last || ideal > next)) ||
				(next < last && (ideal <= last && ideal > next))) {

				keys[last] = keys[next];
				values[last] = values[next];
				ages[last] = ages[next];

				keys[next] = EMPTY;
				values[next] = 0;
				ages[next] = 0;

				last = next;
			} else {
				break;
			}
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

	public synchronized void clear() {
		seq.incrementAndGet();
		try {
			Arrays.fill(keys, EMPTY);
			Arrays.fill(values, 0);
			Arrays.fill(ages, 0);
			size = 0;
			ageCounter = 0;
		} finally {
			seq.incrementAndGet();
		}
	}
}
