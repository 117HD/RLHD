package rs117.hd.model;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

public class ModelHasherTest extends TestCase {
    private ArrayList<int[]> intArrays;
    private Random random;
    private int testDataCount;
    private int testIterations;

    public ModelHasherTest() {
        this.intArrays = new ArrayList<>();
        this.random = new Random(System.nanoTime());
        this.testDataCount = 1000;
        this.testIterations = 10000000;
    }

    private int[] generateRandomIntArray(int size) {
        int[] a = new int[size];
        Random rand = new Random(System.nanoTime());

        for (int i = 0; i < size; i++) {
            a[i] = rand.nextInt();
        }

        return a;
    }

    private long runStandardHasher() {
        long start = System.nanoTime();
        for (int i = 0; i < this.testIterations; i++) {
            Arrays.hashCode(intArrays.get(this.random.nextInt(this.testDataCount)));
        }
        return System.nanoTime() - start;
    }

    private long runFastHasher() {
        long start = System.nanoTime();
        for (int i = 0; i < this.testIterations; i++) {
            ModelHasher.fastIntHash(intArrays.get(this.random.nextInt(this.testDataCount)), -1);
        }
        return System.nanoTime() - start;
    }

    private double percentageDifference(long original, long changed) {
        long diff = original - changed;
        double delta = (double) diff / original;
        return delta * 100;
    }

    public void testHashPerformance() {
        System.out.printf("\n\nComparing hash performance of with %d test items and %d iterations\n\n", testDataCount, testIterations);

        // small arrays (size=512)
        for (int i = 0; i < testDataCount; i++) {
            // generate random test data
            intArrays.add(generateRandomIntArray(512));
        }

        long standardSmallBatchResult = runStandardHasher();
        System.out.printf("Standard hasher small data set: %d nanoseconds\n", standardSmallBatchResult);

        long fastSmallBatchResult = runFastHasher();
        System.out.printf("Fast hasher small data set: %d nanoseconds\n", fastSmallBatchResult);

        System.out.printf("Difference = %.2f%%\n\n", percentageDifference(standardSmallBatchResult, fastSmallBatchResult));

        // medium arrays (size=2048)
        intArrays.clear();
        for (int i = 0; i < testDataCount; i++) {
            intArrays.add(generateRandomIntArray(2048));
        }

        long standardMediumBatchResult = runStandardHasher();
        System.out.printf("Standard hasher medium data set: %d nanoseconds\n", standardMediumBatchResult);

        long fastMediumBatchResult = runFastHasher();
        System.out.printf("Fast hasher medium data set: %d nanoseconds\n", fastMediumBatchResult);

        System.out.printf("Difference = %.2f%%\n\n", percentageDifference(standardMediumBatchResult, fastMediumBatchResult));

        // large arrays (size=6144)
        intArrays.clear();
        for (int i = 0; i < testDataCount; i++) {
            intArrays.add(generateRandomIntArray(6144));
        }

        long standardLargeBatchResult = runStandardHasher();
        System.out.printf("Standard hasher large data set: %d nanoseconds\n", standardLargeBatchResult);

        long fastLargeBatchResult = runFastHasher();
        System.out.printf("Fast hasher large data set: %d nanoseconds\n", fastLargeBatchResult);

        System.out.printf("Difference = %.2f%%\n\n", percentageDifference(standardLargeBatchResult, fastLargeBatchResult));
    }
}