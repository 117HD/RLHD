package rs117.hd.utils.collection;

public final class Util {
	public static final int DEFAULT_CAPACITY = 16;
	public static final int EMPTY = Integer.MIN_VALUE;
	public static final float LOAD_FACTOR = 0.7f;
	public static final float DEFAULT_GROWTH = 1.5f;
	public static final int READ_CACHE_SIZE = 4;

	public static int murmurHash3(int x) {
		x ^= x >>> 16;
		x *= 0x85ebca6b;
		x ^= x >>> 13;
		x *= 0xc2b2ae35;
		x ^= x >>> 16;
		return x;
	}

	public static long murmurHash3(long x) {
		x ^= x >>> 33;
		x *= 0xff51afd7ed558ccdL;
		x ^= x >>> 33;
		x *= 0xc4ceb9fe1a85ec53L;
		x ^= x >>> 33;
		return x;
	}

	public static int findIndex(final int key, final int mask, final int[] keys, final int[] distances, final long[] readCache) {
		final int cachePos = key & (READ_CACHE_SIZE - 1);
		final long lastRead = readCache[cachePos];
		if ((int) lastRead == key)
			return (int) (lastRead >>> 32);

		int idx = murmurHash3(key) & mask;
		for (int dist = 0; dist == 0 || distances[idx] >= dist; dist++) {
			final int k = keys[idx];

			if (k == EMPTY)
				break;

			if (k == key) {
				readCache[cachePos] = ((long) idx << 32) | (key & 0xFFFFFFFFL);
				return idx;
			}

			idx = (idx + 1) & mask;
		}

		return -1;
	}
}
