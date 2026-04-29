package rs117.hd.utils.collections;

import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;

import static java.lang.reflect.Array.getLength;
import static rs117.hd.utils.MathUtils.*;

@RequiredArgsConstructor
public enum PooledArrayType {
	BYTE(byte[]::new, 1),
	SHORT(short[]::new, 2),
	INT(int[]::new, 4),
	FLOAT(float[]::new, 4);

	private static final int MAX_BUCKET = 18;
	private static final long SHRINK_DELAY_MS = 60_000;
	private static final int CLEANUP_INTERVAL = 64;
	private static final double ALPHA = 0.1;

	@FunctionalInterface
	public interface ArraySupplier<T> {
		T get(int capacity);
	}

	public final ArraySupplier<?> supplier;
	public final int stride;

	private final Bucket[] buckets = new Bucket[MAX_BUCKET + 1];

	{
		for (int i = 0; i < buckets.length; i++) {
			buckets[i] = new Bucket();
		}
	}

	private static final class Bucket {
		private final ArrayDeque<Object> stack = new ArrayDeque<>();
		private final ReentrantLock lock = new ReentrantLock();

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

	private static int bucket(int size) {
		if (size <= 1) return 0;
		int b = 32 - Integer.numberOfLeadingZeros(size - 1);
		return min(b, MAX_BUCKET);
	}

	@SuppressWarnings("unchecked")
	public <T> T borrow(int requestedSize) {
		final int b = bucket(requestedSize);
		final Bucket bucket = buckets[b];

		if(!bucket.stack.isEmpty()) {
			bucket.lock.lock();
			try {
				bucket.inUse++;
				bucket.peakInUse = Math.max(bucket.peakInUse, bucket.inUse);
				bucket.maybeCleanup();

				T array = (T) bucket.stack.poll();
				if(array != null)
					return array;
			} finally {
				bucket.lock.unlock();
			}
		}

		return (T) supplier.get(1 << b);
	}

	public void release(Object array) {
		if (array == null)
			return;

		final int b = bucket(getLength(array));
		final Bucket bucket = buckets[b];

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
