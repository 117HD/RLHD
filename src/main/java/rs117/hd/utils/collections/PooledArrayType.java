package rs117.hd.utils.collections;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.Props;

import static java.lang.Integer.numberOfLeadingZeros;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public enum PooledArrayType {
	BOOL(boolean[]::new, 1),
	BYTE(byte[]::new, 1),
	CHAR(char[]::new, 2),
	SHORT(short[]::new, 2),
	INT(int[]::new, 4),
	FLOAT(float[]::new, 4),
	OBJECT(Object[]::new, 4);

	public static final PooledArrayType[] VALUES = values();

	private static final double MAX_HEAP_FRACTION = 0.05; // 768 MB * 0.05 = 38.4 MB
	private static final long MAX_POOL_BYTES = Math.max((long) (Runtime.getRuntime().maxMemory() * MAX_HEAP_FRACTION), (long) 1e+7);

	private static final int MAX_BUCKET = 30;
	private static final int STRIPES = 8;
	private static final int STRIPES_MASK = STRIPES - 1;
	private static final int CLEANUP_INTERVAL = 64;
	private static final long SHRINK_DELAY_MS = 60_000;
	private static final double ALPHA = 0.1;

	private static final AtomicLong CURRENT_POOL_BYTES = new AtomicLong();

	public final ArraySupplier<?> supplier;
	public final int stride;

	private final Bucket[][] buckets = new Bucket[MAX_BUCKET + 1][STRIPES];

	PooledArrayType(ArraySupplier<?> supplier, int stride) {
		this.supplier = supplier;
		this.stride = stride;

		for (int i = 0; i < buckets.length; i++) {
			int size = 1 << i;
			for (int s = 0; s < STRIPES; s++)
				buckets[i][s] = new Bucket(size);
		}
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

	private static boolean isPoolFull(long additionalBytes) {
		return CURRENT_POOL_BYTES.get() + additionalBytes > MAX_POOL_BYTES;
	}

	public static void forceCleanup(boolean full) {
		for (int v = 0; v < VALUES.length; v++) {
			final PooledArrayType type = VALUES[v];
			for (int b = 0; b < type.buckets.length; b++) {
				for (int s = 0; s < STRIPES; s++) {
					final Bucket bucket = type.buckets[b][s];
					final long stamp = bucket.lock.writeLock();
					try {
						bucket.opCounter = 0;
						if (full) {
							bucket.inUse = 0;
							bucket.isEmpty = true;
							bucket.stack.clear();
						} else {
							type.maybeCleanup(b, s, bucket, true);
						}
					} finally {
						bucket.lock.unlockWrite(stamp);
					}
				}
			}
		}
		if (full)
			CURRENT_POOL_BYTES.set(0);
	}

	public static void shutdown() {
		CURRENT_POOL_BYTES.set(0);

		for (int v = 0; v < VALUES.length; v++) {
			final PooledArrayType type = VALUES[v];
			for (int b = 0; b < VALUES[v].buckets.length; b++) {
				for (int s = 0; s < STRIPES; s++) {
					final Bucket bucket = type.buckets[b][s];
					bucket.stack.clear();
					bucket.isEmpty = true;

					bucket.inUse = 0;
					bucket.peakInUse = 0;
					bucket.avgDemand = 0;
					bucket.lastOverTargetTime = 0;

					// Recreate the bucket to clear the stack inner arrays
					type.buckets[b][s] = new Bucket(bucket.size);
				}
			}
		}
	}

	public static long getCurrentTotalCacheSize() {
		return CURRENT_POOL_BYTES.get();
	}

	private void maybeCleanup(int b, int s, Bucket bucket) {
		maybeCleanup(b, s, bucket, false);
	}

	private void maybeCleanup(int b, int s, Bucket bucket, boolean forced) {
		if (!forced && (++bucket.opCounter & (CLEANUP_INTERVAL - 1)) != 0)
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

		if (!forced && now - bucket.lastOverTargetTime <= SHRINK_DELAY_MS)
			return;

		final int target = max((int) (bucket.avgDemand * 0.5f), 1);
		int excess = bucket.stack.size() - target;

		while (excess-- > 0) {
			Object arr = bucket.poll(bytesFor(bucket.size));
			if (arr == null)
				break;

			spill(b, s, arr);
		}

		bucket.lastOverTargetTime = now;
	}

	private boolean spill(int b, int fromStripe, Object array) {
		final Bucket[] stripes = buckets[b];

		final long bytes = bytesFor(Array.getLength(array));

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
					other.add(array, bytes);
					return true;
				}
			} finally {
				other.lock.unlockWrite(stamp);
			}
		}

		return false;
	}

	private long bytesFor(int len) {
		return (long) len * stride;
	}

	@SuppressWarnings("unchecked")
	public <T> T create(int requestedSize) {
		return (T) supplier.get(ceilPow2(requestedSize));
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

	public <T> T borrow(int requestedSize) {
		return borrow(requestedSize, true);
	}

	@SuppressWarnings("unchecked")
	public <T> T borrow(int requestedSize, boolean createIfMissing) {
		final int roundedSize = ceilPow2(requestedSize);
		final int b = bucket(roundedSize);

		if (b >= 0 && b < buckets.length) {
			final long bytes = bytesFor(roundedSize);
			final Bucket[] bucketStripes = buckets[b];
			final int startStripe = stripeIndex();

			for (int i = 0; i < STRIPES * 2; i++) {
				final int s = (startStripe + i) & STRIPES_MASK;
				final Bucket bucket = bucketStripes[s];
				if (bucket.isEmpty)
					continue;

				final long stamp = i < STRIPES ? bucket.lock.tryWriteLock() : bucket.lock.writeLock();
				if (stamp == 0)
					continue;

				try {
					final T arr = (T) bucket.poll(bytes);
					if (arr != null) {
						bucket.inUse++;
						bucket.peakInUse = Math.max(bucket.peakInUse, bucket.inUse);
						maybeCleanup(b, s, bucket);
						return arr;
					}
				} finally {
					bucket.lock.unlockWrite(stamp);
				}
			}
		}

		return createIfMissing ? (T) supplier.get(roundedSize) : null;
	}

	public void release(Object array) {
		if (array == null)
			return;

		final int len = Array.getLength(array);
		if (len != ceilPow2(len))
			return;

		final int b = bucket(len);
		if (b < 0 || b >= buckets.length)
			return;

		final long bytes = bytesFor(len);
		if (isPoolFull(bytes))
			return;

		final int startStripe = stripeIndex();

		final Bucket[] bucketStripes = buckets[b];

		for (int i = 0; i < STRIPES * 2; i++) {
			final int s = (startStripe + i) & STRIPES_MASK;
			final Bucket bucket = bucketStripes[s];

			final long stamp =
				i < STRIPES
					? bucket.lock.tryWriteLock()
					: bucket.lock.writeLock();
			if (stamp == 0)
				continue;

			try {
				if (isPoolFull(bytes))
					return;

				bucket.inUse = max(0, bucket.inUse - 1);
				bucket.add(array, bytes);
				maybeCleanup(b, s, bucket);
				return;
			} finally {
				bucket.lock.unlockWrite(stamp);
			}
		}
	}

	@FunctionalInterface
	public interface ArraySupplier<T> {
		T get(int capacity);
	}

	@RequiredArgsConstructor
	private static final class Bucket {
		private final ArrayDeque<Object> stack = new ArrayDeque<>();
		private final StampedLock lock = new StampedLock();

		private final int size;
		private int opCounter;
		private int inUse;
		private int peakInUse;
		private float avgDemand;
		private long lastOverTargetTime;

		private volatile boolean isEmpty = true;

		public void add(Object array, long bytes) {
			if (Props.DEVELOPMENT && !isEmpty && stack.contains(array))
				throw new IllegalStateException("Duplicate array: " + array);
			stack.add(array);
			CURRENT_POOL_BYTES.addAndGet(bytes);
			isEmpty = false;
		}

		public Object poll(long bytes) {
			Object arr = stack.poll();
			if (arr != null)
				CURRENT_POOL_BYTES.addAndGet(-bytes);
			isEmpty = stack.isEmpty();
			return arr;
		}
	}
}
