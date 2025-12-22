package rs117.hd.scene.model_overrides;

import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import rs117.hd.utils.collection.Int2IntHashMap;
@RequiredArgsConstructor
public final class AhslPredicateCache implements ModelOverride.AhslPredicate {
	public static final int MAX_CACHE_SIZE = 512;

	private final AtomicInteger seq = new AtomicInteger(); // even = stable, odd = write in progress
	private final Int2IntHashMap cache = new Int2IntHashMap(16);
	private final ModelOverride.AhslPredicate predicate;

	private int ageCounter = 0; // fits in 31 bits
	private int lastAhsl = -1;
	private boolean lastAhslResult;

	private int getCachedResult(int ahsl) {
		for (int r = 0; r < 100; r++) {
			final int s = seq.get();
			if ((s & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}

			final int idx = cache.findIndex(ahsl);
			if (seq.get() != s)
				continue;

			return idx >= 0 ? (cache.getValue(idx) & 1) : -1;
		}
		return -1;
	}

	private synchronized void putCachedResult(int ahsl, boolean result) {
		seq.incrementAndGet(); // write start

		try {
			if ((ageCounter >>> 30) != 0) {
				// Normalize the ages since we've overflowed the ageCounter, not doing so will break eviction logic
				int minAge = Integer.MAX_VALUE;

				for (int i = 0; i < cache.size(); i++) {
					int age = cache.getValue(i) >>> 1;
					if (age < minAge)
						minAge = age;
				}

				for (int i = 0; i < cache.size(); i++) {
					int packed = cache.getValue(i);
					int age = (packed >>> 1) - minAge;
					cache.setValue(i, (age << 1) | (packed & 1));
				}

				ageCounter -= minAge;
			}

			cache.put(ahsl, ((++ageCounter) << 1) | (result ? 1 : 0));

			if (cache.size() > MAX_CACHE_SIZE) {
				// Cache has exceeded the max size, evict the oldest value
				int oldestIndex = 0;
				int oldestAge = cache.getValue(0) >>> 1;

				for (int i = 1; i < cache.size(); i++) {
					int age = cache.getValue(i) >>> 1;
					if (age < oldestAge) {
						oldestAge = age;
						oldestIndex = i;
					}
				}

				cache.removeIndex(oldestIndex);
			}
		} finally {
			seq.incrementAndGet(); // write end
		}
	}

	@Override
	public boolean test(int ahsl) {
		if (ahsl == lastAhsl)
			return lastAhslResult;

		lastAhsl = ahsl;

		int cached = getCachedResult(ahsl);
		if (cached != -1)
			return lastAhslResult = cached == 1;

		boolean result = predicate.test(ahsl);
		putCachedResult(ahsl, result);
		return lastAhslResult = result;
	}
}
