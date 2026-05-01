package rs117.hd.utils.collections;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;
import lombok.RequiredArgsConstructor;

import static java.lang.Integer.numberOfLeadingZeros;
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
	private static final int STRIPES = 8;
	private static final int STRIPES_MASK = STRIPES - 1;
	private static final int CLEANUP_INTERVAL = 64;
	private static final long SHRINK_DELAY_MS = 60_000;
	private static final double ALPHA = 0.1;

	@FunctionalInterface
	public interface ArraySupplier<T> {
		T get(int capacity);
	}

	private final Bucket[][] buckets = new Bucket[MAX_BUCKET + 1][STRIPES];
	public final ArraySupplier<?> supplier;
	public final int stride;

	PooledArrayType(ArraySupplier<?> supplier, int stride) {
		this.supplier = supplier;
		this.stride = stride;

		for (int i = 0; i < buckets.length; i++) {
			int size = 1 << i;
			for (int s = 0; s < STRIPES; s++)
				buckets[i][s] = new Bucket(size);
		}
	}

	@RequiredArgsConstructor
	private static final class Bucket {
		private final ArrayDeque<Object> stack = new ArrayDeque<>();
		private final StampedLock lock = new StampedLock();
		private final AtomicBoolean isEmpty = new AtomicBoolean(true);

		private final int size;
		private int opCounter;
		private int inUse;
		private int peakInUse;
		private float avgDemand;
		private long lastOverTargetTime;
	}

	private static int ceilPow2(int x) {
		if (x <= 1) return 1;
		if (x > (1 << 30)) return Integer.MAX_VALUE;
		return 1 << (32 - numberOfLeadingZeros(x - 1));
	}

	private static int bucket(int size) {
		if (size <= 1) return 0;
		int b = 32 - numberOfLeadingZeros(size - 1);
		return min(b, MAX_BUCKET);
	}

	private static int stripeIndex() {
		final int hash = Thread.currentThread().hashCode();
		return (hash ^ (hash >>> 16)) & STRIPES_MASK;
	}

	public static long getCurrentTotalCacheSize() {
		long size = 0;
		for (PooledArrayType t : VALUES)
			size += t.getCurrentCacheSize();
		return size;
	}

	public long getCurrentCacheSize() {
		long size = 0;
		for (int b = 0; b < buckets.length; b++) {
			for (int s = 0; s < STRIPES; s++) {
				Bucket bucket = buckets[b][s];
				size += (long) bucket.stack.size() * bucket.size;
			}
		}
		return size * stride;
	}

	private void maybeCleanup(int b, int s, Bucket bucket) {
		if ((++bucket.opCounter & (CLEANUP_INTERVAL - 1)) != 0)
			return;


		bucket.avgDemand = (float) (ALPHA * bucket.peakInUse + (1 - ALPHA) * bucket.avgDemand);
		bucket.peakInUse = bucket.inUse;

		if (bucket.stack.size() <= bucket.avgDemand) {
			bucket.lastOverTargetTime = 0;
			return;
		}

		final long now = System.currentTimeMillis();
		if (bucket.lastOverTargetTime == 0) {
			bucket.lastOverTargetTime = now;
			return;
		}

		if (now - bucket.lastOverTargetTime <= SHRINK_DELAY_MS)
			return;

		final int target = max((int) (bucket.avgDemand * 0.5f), 1);
		int excess = bucket.stack.size() - target;

		while (excess-- > 0) {
			Object arr = bucket.stack.poll();
			if (arr == null)
				break;

			spill(b, s, arr);
		}
		bucket.isEmpty.set(bucket.stack.isEmpty());
		bucket.lastOverTargetTime = now;
	}

	private boolean spill(int b, int fromStripe, Object array) {
		final Bucket[] stripes = buckets[b];
		for (int i = 1; i < STRIPES; i++) {
			final int s = (fromStripe + i) & STRIPES_MASK;
			final Bucket other = stripes[s];

			if (other.stack.size() > other.avgDemand)
				continue;

			final long stamp = other.lock.tryWriteLock();
			if (stamp == 0)
				continue;

			try {
				if (other.stack.size() <= other.avgDemand) {
					other.stack.add(array);
					other.isEmpty.set(false);
					return true;
				}
			} finally {
				other.lock.unlockWrite(stamp);
			}
		}

		return false;
	}

	@SuppressWarnings("unchecked")
	public <T> T ensureCapacity(Object array, int requestedSize) {
		final int len = array != null ? Array.getLength(array) : 0;
		if (len >= requestedSize)
			return (T) array;

		release(array);
		return borrow(requestedSize);
	}

	@SuppressWarnings("SuspiciousSystemArraycopy")
	public <T> T cache(Object array, int offset, int size) {
		final T cached = borrow(size);
		System.arraycopy(array, offset, cached, 0, size);
		return cached;
	}

	@SuppressWarnings("unchecked")
	public <T> T borrow(int requestedSize) {
		final int roundedSize = ceilPow2(requestedSize);
		final int b = bucket(roundedSize);

		if (b < 0 || b >= buckets.length)
			return (T) supplier.get(requestedSize);

		final Bucket[] bucketStripes = buckets[b];
		final int startStripe = stripeIndex();

		for (int i = 0; i < STRIPES * 2; i++) {
			final int s = (startStripe + i) & STRIPES_MASK;
			final Bucket bucket = bucketStripes[s];
			if (bucket.isEmpty.get())
				continue;

			final long stamp = i < STRIPES ? bucket.lock.tryWriteLock() : bucket.lock.writeLock();
			if(stamp == 0)
				continue;

			try {
				final T arr = (T) bucket.stack.poll();
				if (arr != null) {
					bucket.inUse++;
					bucket.peakInUse = Math.max(bucket.peakInUse, bucket.inUse);
					bucket.isEmpty.set(bucket.stack.isEmpty());
					maybeCleanup(b, s, bucket);
					return arr;
				}
			} finally {
				bucket.lock.unlockWrite(stamp);
			}
		}

		return (T) supplier.get(roundedSize);
	}

	public void release(Object array) {
		if (array == null)
			return;

		final int len = Array.getLength(array);
		if(len != ceilPow2(len))
			return;

		final int b = bucket(len);
		if (b < 0 || b >= buckets.length)
			return;

		final int startStripe = stripeIndex();
		final Bucket[] bucketStripes = buckets[b];

		for (int i = 0; i < STRIPES * 2; i++) {
			final int s = (startStripe + i) & STRIPES_MASK;
			final Bucket bucket = bucketStripes[s];
			final long stamp = i < STRIPES ? bucket.lock.tryWriteLock() : bucket.lock.writeLock();
			if(stamp == 0)
				continue;

			try {
				bucket.inUse--;
				bucket.stack.add(array);
				bucket.isEmpty.set(false);
				maybeCleanup(b, s, bucket);
				return;
			} finally {
				bucket.lock.unlockWrite(stamp);
			}
		}
	}
}