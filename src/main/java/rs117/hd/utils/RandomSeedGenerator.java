package rs117.hd.utils;

public class RandomSeedGenerator {
	private static final long MULTIPLIER = 0x5DEECE66DL;
	private static final long ADDEND = 0xBL;
	private static final long MASK = (1L << 48) - 1;
	private static final int SHIFT = 48 - 31;
	private static final int HASH_MULTIPLIER = 31;

	public static long generateSeed(int... worldPos) {
		long hash = 0;
		for (int coord : worldPos) {
			hash = hash * HASH_MULTIPLIER + coord;
		}
		return (hash ^ MULTIPLIER) & MASK;
	}

	public static int getRandomIndex(long seed, int length) {
		seed = (seed * MULTIPLIER + ADDEND) & MASK;
		return (int) (seed >>> SHIFT) % length;
	}
}
