package rs117.hd.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import junit.framework.TestCase;
import rs117.hd.model.ModelHasher;

public class ModelHasherTest extends TestCase {
    private final ArrayList<int[]> intArrays;
    private final Random random;
    private final int testDataCount;
    private final int testIterations;

    private long accumulatedHash = 0;

    public ModelHasherTest() {
        this.intArrays = new ArrayList<>();
        this.random = new Random(1337);
        this.testDataCount = 1000;
        this.testIterations = 10000000;
    }

    private int[] generateRandomIntArray(int size) {
        int[] a = new int[size];

        for (int i = 0; i < size; i++) {
            a[i] = this.random.nextInt();
        }

        return a;
    }

	private long runStandardHasher() {
		long start = System.nanoTime();
		for (int i = 0; i < this.testIterations; i++) {
			accumulatedHash *= 31;
			accumulatedHash += Arrays.hashCode(intArrays.get(this.random.nextInt(this.testDataCount)));
		}
		return System.nanoTime() - start;
	}

	private long runFastIntHasher() {
		long start = System.nanoTime();
		for (int i = 0; i < this.testIterations; i++) {
			accumulatedHash *= 31;
			accumulatedHash += ModelHasher.fastHash(intArrays.get(this.random.nextInt(this.testDataCount)), -1);
		}
		return System.nanoTime() - start;
	}

	private long runFastHasher() {
		long start = System.nanoTime();
		for (int i = 0; i < this.testIterations; i++) {
			accumulatedHash *= 31;
			accumulatedHash += ModelHasher.fastHash(intArrays.get(this.random.nextInt(this.testDataCount)), -1);
		}
		return System.nanoTime() - start;
	}

	private double percentageDifference(long original, long changed) {
		long diff = original - changed;
		double delta = (double) diff / original;
		return delta * 100;
	}

	public void testHashPerformance() {
		System.out.printf("Java version: %s\n\n", System.getProperty("java.version"));
		System.out.printf("Comparing hash performance of with %,d test items and %,d iterations\n\n", testDataCount, testIterations);

		// small arrays (size=512)
		for (int i = 0; i < testDataCount; i++) {
			// generate random test data
			intArrays.add(generateRandomIntArray(512));
		}

		long standardBatchResult = runStandardHasher();
		System.out.printf("Standard hasher small data set:  \t%,.3f seconds\n", (double) standardBatchResult / 1e9);
		long fastIntBatchResult = runFastHasher();
		System.out.printf("Fast int hasher small data set:  \t%,.3f seconds\n", (double) fastIntBatchResult / 1e9);
		System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardBatchResult, fastIntBatchResult));
		long fastBatchResult = runFastHasher();
		System.out.printf("Fast hasher small data set:      \t%,.3f seconds\n", (double) fastBatchResult / 1e9);
		System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardBatchResult, fastBatchResult));

		// medium arrays (size=2048)
		intArrays.clear();
		for (int i = 0; i < testDataCount; i++) {
			intArrays.add(generateRandomIntArray(2048));
		}

		standardBatchResult = runStandardHasher();
		System.out.printf("Standard hasher medium data set:  \t%,.3f seconds\n", (double) standardBatchResult / 1e9);
		fastIntBatchResult = runFastHasher();
		System.out.printf("Fast int hasher medium data set:  \t%,.3f seconds\n", (double) fastIntBatchResult / 1e9);
		System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardBatchResult, fastIntBatchResult));
		fastBatchResult = runFastHasher();
		System.out.printf("Fast hasher medium data set:      \t%,.3f seconds\n", (double) fastBatchResult / 1e9);
		System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardBatchResult, fastBatchResult));

		// large arrays (size=6144)
		intArrays.clear();
		for (int i = 0; i < testDataCount; i++) {
			intArrays.add(generateRandomIntArray(6144));
		}

		standardBatchResult = runStandardHasher();
		System.out.printf("Standard hasher large data set:  \t%,.3f seconds\n", (double) standardBatchResult / 1e9);
		fastIntBatchResult = runFastHasher();
		System.out.printf("Fast int hasher large data set:  \t%,.3f seconds\n", (double) fastIntBatchResult / 1e9);
		System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardBatchResult, fastIntBatchResult));
		fastBatchResult = runFastHasher();
		System.out.printf("Fast hasher large data set:      \t%,.3f seconds\n", (double) fastBatchResult / 1e9);
		System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardBatchResult, fastBatchResult));

		System.out.println("Hash: " + accumulatedHash);
	}
}
