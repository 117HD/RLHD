package rs117.hd.scene.model_overrides;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class AhslPredicateCache implements ModelOverride.AhslPredicate {
	public static final int MAX_CACHE_SIZE = 512;

	private final AtomicInteger seq = new AtomicInteger(); // even = stable, odd = write in progress
	private final ModelOverride.AhslPredicate predicate;

	private int[] keys = new int[16];       // ahsl keys
	private int[] values = new int[16];     // packed: (age << 1 | result)
	private int cacheSize = 0;
	private int ageCounter = 0;             // fits in 31 bits

	private int lastAhsl = -1;
	private boolean lastAhslResult;

	private int getCachedResult(int ahsl) {
		for (int r = 0; r < 100; r++) {
			final int s = seq.get();
			if ((s & 1) != 0) {
				Thread.onSpinWait();
				continue; // writer active
			}

			final int localSize = cacheSize;
			final int[] localKeys = keys;
			final int[] localValues = values;

			int idx = Arrays.binarySearch(localKeys, 0, localSize, ahsl);
			if (idx >= 0) {
				if (seq.get() != s)
					continue;
				return localValues[idx] & 1;
			}

			if (seq.get() == s)
				return -1;
		}
		return -1;
	}

	private synchronized void putCachedResult(int ahsl, boolean result) {
		seq.incrementAndGet(); // odd => write start

		try {
			if ((ageCounter >>> 30) != 0) normalizeAges(); // 31-bit max

			final int packed = ((++ageCounter) << 1) | (result ? 1 : 0);

			int idx = Arrays.binarySearch(keys, 0, cacheSize, ahsl);
			if (idx >= 0) {
				values[idx] = packed; // refresh age
				return;
			}

			// Grow arrays if needed
			if (cacheSize < MAX_CACHE_SIZE && cacheSize >= keys.length) {
				int newSize = Math.min(keys.length * 2, MAX_CACHE_SIZE);
				keys = Arrays.copyOf(keys, newSize);
				values = Arrays.copyOf(values, newSize);
			}

			if (cacheSize < MAX_CACHE_SIZE) {
				insertAt(-idx - 1, ahsl, packed);
				return;
			}

			// Evict oldest
			int oldestIndex = 0;
			int oldestAge = values[0] >>> 1;
			for (int i = 1; i < cacheSize; i++) {
				int age = values[i] >>> 1;
				if (age < oldestAge) {
					oldestAge = age;
					oldestIndex = i;
				}
			}

			// Remove oldest while keeping sorted order
			System.arraycopy(keys, oldestIndex + 1, keys, oldestIndex, cacheSize - oldestIndex - 1);
			System.arraycopy(values, oldestIndex + 1, values, oldestIndex, cacheSize - oldestIndex - 1);
			cacheSize--;

			// Recalculate insertion point after removal
			idx = Arrays.binarySearch(keys, 0, cacheSize, ahsl);
			insertAt((idx >= 0) ? idx : -idx - 1, ahsl, packed);

		} finally {
			seq.incrementAndGet(); // even => write complete
		}
	}

	private void normalizeAges() {
		int minAge = Integer.MAX_VALUE;
		for (int i = 0; i < cacheSize; i++) {
			int age = values[i] >>> 1;
			if (age < minAge) minAge = age;
		}

		for (int i = 0; i < cacheSize; i++) {
			int packed = values[i];
			int age = (packed >>> 1) - minAge;
			values[i] = (age << 1) | (packed & 1);
		}

		ageCounter -= minAge;
	}

	private void insertAt(int index, int ahsl, int packed) {
		System.arraycopy(keys, index, keys, index + 1, cacheSize - index);
		System.arraycopy(values, index, values, index + 1, cacheSize - index);
		keys[index] = ahsl;
		values[index] = packed;
		cacheSize++;
	}

	@Override
	public boolean test(int ahsl) {
		if(ahsl == lastAhsl) // Fastest Path, instant early out for repeated AHSL checks
			return lastAhslResult;

		lastAhsl = ahsl;
		int cached = getCachedResult(ahsl); // Faster Path, Check if the value is within the cache
		if (cached != -1)
			return lastAhslResult = cached == 1;

		// Slowest path, we've paid the cost of checking the cache & now need to perform the test + cache the result
		boolean result = predicate.test(ahsl);
		putCachedResult(ahsl, result);
		return lastAhslResult = result;
	}
}