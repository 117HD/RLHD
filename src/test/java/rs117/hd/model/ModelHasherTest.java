package rs117.hd.model;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

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

    private long runFastHasher() {
        long start = System.nanoTime();
        for (int i = 0; i < this.testIterations; i++) {
            accumulatedHash *= 31;
            accumulatedHash += ModelHasher.fastIntHash(intArrays.get(this.random.nextInt(this.testDataCount)), -1);
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

        long standardSmallBatchResult = runStandardHasher();
        System.out.printf("Standard hasher small data set:  \t%,.3f seconds\n", (double) standardSmallBatchResult / 1e9);
        long fastSmallBatchResult = runFastHasher();
        System.out.printf("Fast hasher small data set:      \t%,.3f seconds\n", (double) fastSmallBatchResult / 1e9);
        System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardSmallBatchResult, fastSmallBatchResult));

        // medium arrays (size=2048)
        intArrays.clear();
        for (int i = 0; i < testDataCount; i++) {
            intArrays.add(generateRandomIntArray(2048));
        }

        long standardMediumBatchResult = runStandardHasher();
        System.out.printf("Standard hasher medium data set: \t%,.3f seconds\n", (double) standardMediumBatchResult / 1e9);
        long fastMediumBatchResult = runFastHasher();
        System.out.printf("Fast hasher medium data set:     \t%,.3f seconds\n", (double) fastMediumBatchResult / 1e9);
        System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardMediumBatchResult, fastMediumBatchResult));

        // large arrays (size=6144)
        intArrays.clear();
        for (int i = 0; i < testDataCount; i++) {
            intArrays.add(generateRandomIntArray(6144));
        }

        long standardLargeBatchResult = runStandardHasher();
        System.out.printf("Standard hasher large data set:  \t%,.3f seconds\n", (double) standardLargeBatchResult / 1e9);
        long fastLargeBatchResult = runFastHasher();
        System.out.printf("Fast hasher large data set:      \t%,.3f seconds\n", (double) fastLargeBatchResult / 1e9);
        System.out.printf("Improvement =\t%.2f%%\n\n", percentageDifference(standardLargeBatchResult, fastLargeBatchResult));

        System.out.println("Hash: " + accumulatedHash);
    }
}
