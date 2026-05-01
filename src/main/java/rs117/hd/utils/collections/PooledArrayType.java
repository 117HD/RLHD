package rs117.hd.utils.collections;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;

import static rs117.hd.utils.MathUtils.*;

public enum PooledArrayType {
	BOOL(boolean[]::new, 1),
	BYTE(byte[]::new, 1),
	CHAR(char[]::new, 2),
	SHORT(short[]::new, 2),
	INT(int[]::new, 4),
	FLOAT(float[]::new, 4),
	OBJECT(Object[]::new, 4);

	public static final PooledArrayType[] VALUES = values();

	private static final int MAX_BUCKET = 30;
	private static final long SHRINK_DELAY_MS = 60_000;
	private static final int CLEANUP_INTERVAL = 64;
	private static final double ALPHA = 0.1;

	@FunctionalInterface
	public interface ArraySupplier<T> {
		T get(int capacity);
	}

	private final Bucket[] buckets = new Bucket[MAX_BUCKET + 1];
	public final ArraySupplier<?> supplier;
	public final int stride;

	PooledArrayType(ArraySupplier<?> supplier, int stride) {
		this.supplier = supplier;
		this.stride = stride;
		for (int i = 0; i < buckets.length; i++)
			buckets[i] = new Bucket(1 << i);
	}

	@RequiredArgsConstructor
	private static final class Bucket {
		private final ArrayDeque<Object> stack = new ArrayDeque<>();
		private final ReentrantLock lock = new ReentrantLock();

		private final int size;
		private int opCounter;
		private int inUse;
		private int peakInUse;
		private float avgDemand;
		private long lastOverTargetTime;

		private void maybeCleanup() {
			if ((++opCounter & (CLEANUP_INTERVAL - 1)) != 0)
				return;

			final long now = System.currentTimeMillis();

			avgDemand = (float)(ALPHA * peakInUse + (1 - ALPHA) * avgDemand);
			peakInUse = inUse;

			if (stack.size() > avgDemand) {
				if (lastOverTargetTime == 0) {
					lastOverTargetTime = now;
				} else if (now - lastOverTargetTime > SHRINK_DELAY_MS) {
					int target = max((int)(avgDemand * 0.5f), 1);

					while (stack.size() > target)
						stack.poll();

					lastOverTargetTime = now;
				}
			} else {
				lastOverTargetTime = 0;
			}
		}
	}

	private static int ceilPow2(int x) {
		if (x <= 1) return 1;
		if (x > (1 << 30)) return Integer.MAX_VALUE; // prevent overflow
		return 1 << (32 - Integer.numberOfLeadingZeros(x - 1));
	}

	private static int bucket(int size) {
		if (size <= 1) return 0;
		int b = 32 - Integer.numberOfLeadingZeros(size - 1);
		return min(b, MAX_BUCKET);
	}

	public static long getTotalCacheSize() {
		long size = 0;
		for(int i = 0; i < VALUES.length; i++) {
			final PooledArrayType type = VALUES[i];
			for (int b = 0; b < type.buckets.length; b++) {
				final Bucket bucket = type.buckets[b];
				size += (long) bucket.stack.size() * bucket.size * type.stride;
			}
		}
		return size ;
	}

	@SuppressWarnings("unchecked")
	public <T> T ensureCapacity(Object array, int requestedSize) {
		final int arrayLen = array != null ? Array.getLength(array) : 0;
		if(arrayLen >= requestedSize)
			return (T) array;
		release(array);
		return borrow(requestedSize);
	}

	@SuppressWarnings("SuspiciousSystemArraycopy")
	public <T> T cache(Object array, int offset, int size) {
		T cached = borrow(size);
		System.arraycopy(array, offset, cached, 0, size);
		return cached;
	}

	@SuppressWarnings("unchecked")
	public <T> T borrow(int requestedSize) {
		final int roundedSize = ceilPow2(requestedSize);
		final int b = bucket(roundedSize);

		if (b < 0 || b >= buckets.length) // Out of range, allocate directly
			return (T) supplier.get(requestedSize);

		final Bucket bucket = buckets[b];
		assert bucket.size == roundedSize;
		assert roundedSize >= requestedSize;
		bucket.lock.lock();
		try {
			bucket.inUse++;
			bucket.peakInUse = Math.max(bucket.peakInUse, bucket.inUse);

			T array = (T) bucket.stack.poll();
			bucket.maybeCleanup();

			if (array != null)
				return array;
		} finally {
			bucket.lock.unlock();
		}

		// Always allocate exact bucket size (power of two)
		return (T) supplier.get(bucket.size);
	}

	public void release(Object array) {
		if (array == null)
			return;

		final int arrayLen = Array.getLength(array);
		final int b = bucket(arrayLen);

		if (b < 0 || b >= buckets.length)
			return;

		final Bucket bucket = buckets[b];

		// Strict invariant: only exact bucket-sized arrays allowed
		if (arrayLen != bucket.size)
			return;

		bucket.lock.lock();
		try {
			bucket.inUse--;
			bucket.stack.add(array);
			bucket.maybeCleanup();
		} finally {
			bucket.lock.unlock();
		}
	}
}
