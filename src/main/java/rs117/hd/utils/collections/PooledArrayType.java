package rs117.hd.utils.collections;

import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;
import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static java.lang.Integer.numberOfLeadingZeros;
import static rs117.hd.utils.HDUtils.formatThreadString;
import static rs117.hd.utils.HDUtils.getThreadId;
import static rs117.hd.utils.HDUtils.getThreadStackTrace;
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

	private static final boolean VERBOSE = false;
	public enum BorrowFlag { NONE, CREATE_IF_NOT_FULL, ALWAYS_CREATE }

	public static final PooledArrayType[] VALUES = values();

	private static final double MAX_HEAP_FRACTION = 0.05; // 768 MB * 0.05 = 38.4 MB
	private static final long MAX_POOL_BYTES = max((long) (Runtime.getRuntime().maxMemory() * MAX_HEAP_FRACTION), 10 * MB);

	private static final int MAX_BUCKET = 30;
	private static final int STRIPES = 8;
	private static final int STRIPES_MASK = STRIPES - 1;
	private static final double ALPHA = 0.25;

	private static final AtomicLong CURRENT_POOL_BYTES = new AtomicLong();
	private static final AtomicLong TOTAL_POOL_BYTES = new AtomicLong();

	private static final Cleaner CLEANER = Cleaner.create();

	public final ArraySupplier<?> supplier;
	public final int stride;

	private final Bucket[] buckets = new Bucket[MAX_BUCKET + 1];

	PooledArrayType(ArraySupplier<?> supplier, int stride) {
		this.supplier = supplier;
		this.stride = stride;

		for (int i = 0; i < buckets.length; i++)
			buckets[i] = new Bucket(1 << i);
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

	private static boolean canReservePoolBytes(long bytes) {
		return bytes <= MAX_POOL_BYTES - CURRENT_POOL_BYTES.get();
	}

	private static boolean tryReservePoolBytes(long bytes) {
		long current = CURRENT_POOL_BYTES.get();

		for (;;) {
			if (current > MAX_POOL_BYTES - bytes)
				return false;

			final long newValue = current + bytes;
			final long witness = CURRENT_POOL_BYTES.compareAndExchange(current, newValue);

			if (witness == current)
				return true;

			current = witness;
		}
	}

	public static void incrementalCleanup(int frame) {
		int ArrayTypeTarget = floor(frame / (float)MAX_BUCKET) % VALUES.length;
		int BucketTarget = frame % MAX_BUCKET;

		VALUES[ArrayTypeTarget].cleanup(BucketTarget);
	}

	public static void forceCleanup(boolean full) {
		for (int v = 0; v < VALUES.length; v++) {
			final PooledArrayType type = VALUES[v];
			for (int b = 0; b < type.buckets.length; b++) {
				final Bucket group = type.buckets[b];

				if (full) {
					final long bytesPerElement = type.bytesFor(group.size);
					long reclaimed = 0;
					for (int i = 0; i < STRIPES; i++)
						reclaimed += group.stripes[i].empty(bytesPerElement);

					if (reclaimed > 0)
						CURRENT_POOL_BYTES.addAndGet(-reclaimed);
				} else {
					type.cleanup(b);
				}
			}
		}
	}

	public static void shutdown() {
		CURRENT_POOL_BYTES.set(0);

		for (int v = 0; v < VALUES.length; v++) {
			final PooledArrayType type = VALUES[v];
			for (int b = 0; b < type.buckets.length; b++) {
				final Bucket group = type.buckets[b];

				for (int i = 0; i < STRIPES; i++)
					group.stripes[i].empty(0);

				group.avgDemand = 0;
				type.buckets[b] = new Bucket(group.size);
			}
		}
	}

	public static long getCurrentTotalCacheSize() { return CURRENT_POOL_BYTES.get(); }

	public static long getTotalPoolBytes() { return TOTAL_POOL_BYTES.get(); }

	private boolean cleanup(int b) {
		final Bucket group = buckets[b];

		if (!group.cleaning.compareAndSet(false, true))
			return false;

		try {
			final int[] sizes = group.sizes;
			long peakSum = 0;
			long totalSize = 0;
			for (int i = 0; i < STRIPES; i++) {
				final Stripe stripe = group.stripes[i];
				peakSum += stripe.peakInUse.getAndSet(0);
				sizes[i] = stripe.stack.size();
				totalSize += sizes[i];
			}

			group.avgDemand = (float) (ALPHA * peakSum + (1 - ALPHA) * group.avgDemand);
			if (totalSize <= group.avgDemand)
				return false;

			final int targetTotal = max((int) (group.avgDemand * 0.5f), 1);
			final int excessTotal = (int) (totalSize - targetTotal);
			if(excessTotal <= 0)
				return false;

			final int perStripeShare = max(targetTotal / STRIPES, 1);
			final long bytesPerElement = bytesFor(group.size);
			if (VERBOSE && excessTotal > 0)
				log.debug(
					"PooledArray::{} Bucket {} totals {} pooled arrays across {} stripes, target {} (excess {}) peakSum: {} avgDemand: {}",
					this, b, totalSize, STRIPES, targetTotal, excessTotal, peakSum, group.avgDemand
				);

			int dropped = 0;
			int spilled = 0;
			for(int i = 0; i < excessTotal; i++){
				int maxIdx = -1;
				int maxSize = 0;

				for (int k = 0; k < STRIPES; k++) {
					if (group.sizes[k] > maxSize) {
						maxSize = group.sizes[k];
						maxIdx = k;
					}
				}

				if (maxIdx == -1)
					break;

				final Stripe stripe = group.stripes[maxIdx];
				final PooledArray<Object> wrapper = stripe.poll(bytesPerElement);

				if (wrapper == null) {
					group.sizes[maxIdx] = 0;
					continue;
				}

				group.sizes[maxIdx]--;

				if (spill(group, maxIdx, perStripeShare, group.sizes, wrapper, bytesPerElement)) {
					spilled++;
					continue;
				}

				dropped++;
			}

			if (VERBOSE && (dropped > 0 || spilled > 0))
				log.debug(
					"PooledArray::{} Freed {} and spilled {} pooled arrays from bucket {}",
					this, dropped, spilled, b
				);
		} finally {
			group.cleaning.set(false);
		}

		return true;
	}

	private boolean spill(Bucket group, int fromIdx, int perStripeShare, int[] sizes, PooledArray<Object> wrapper, long bytes) {
		int targetIdx = -1;
		int targetSize = Integer.MAX_VALUE;

		for (int i = 0; i < STRIPES; i++) {
			if (i == fromIdx)
				continue;
			if (sizes[i] >= perStripeShare)
				continue;
			if (sizes[i] < targetSize) {
				targetSize = sizes[i];
				targetIdx = i;
			}
		}

		if (targetIdx == -1)
			return false;

		group.stripes[targetIdx].add(wrapper);
		CURRENT_POOL_BYTES.addAndGet(bytes);

		sizes[targetIdx]++;
		return true;
	}

	private long bytesFor(int len) {
		return (long) len * stride;
	}

	@SuppressWarnings("unchecked")
	public <T> PooledArray<T> create(int requestedSize) {
		final int cap = ceilPow2(requestedSize);
		final long bytes = bytesFor(cap);
		final Object array = supplier.get(cap);

		if(VERBOSE)
			log.debug("Created new PooledArray::{} of size {} ({} bytes)", this, cap, bytes);

		final PooledArray<Object> wrapper = new PooledArray<>(this, array, bytes);
		wrapper.metadata.borrowedByThreadId = getThreadId();
		wrapper.metadata.releasedByThreadId = -1L;
		TOTAL_POOL_BYTES.addAndGet(bytes);

		return (PooledArray<T>) wrapper;
	}

	public <T> PooledArrayRef<T> ref(String context) {
		return new PooledArrayRef<>(this, context);
	}

	public <T> PooledArray<T> borrow(String context, int requestedSize) {
		return borrow(context, requestedSize, BorrowFlag.ALWAYS_CREATE);
	}

	public <T> PooledArray<T> ensureCapacity(PooledArray<T> current, int requestedSize) {
		if (current != null && current.array != null && Array.getLength(current.array) >= requestedSize)
			return current;

		final PooledArray<T> grown = borrow(current != null ? current.metadata.context : null, requestedSize);
		release(current);
		return grown;
	}

	@SuppressWarnings("SuspiciousSystemArraycopy")
	public <T> PooledArray<T> ensureCapacity(PooledArray<T> current, int requestedSize, int offset, int count) {
		if (current != null && current.array != null && Array.getLength(current.array) >= requestedSize)
			return current;

		final PooledArray<T> grown = borrow(current != null ? current.metadata.context : null, requestedSize);
		if (current != null && current.array != null && count > 0)
			System.arraycopy(current.array, offset, grown.array, 0, count);
		release(current);
		return grown;
	}

	@SuppressWarnings("unchecked")
	public <T> PooledArray<T> borrow(String context, int requestedSize, BorrowFlag flag) {
		final int roundedSize = ceilPow2(requestedSize);
		final int b = bucket(roundedSize);

		final long bytes = bytesFor(roundedSize);
		final Bucket group = buckets[b];
		final int startStripe = stripeIndex();

		for (int i = 0; i < STRIPES; i++) {
			final int s = (startStripe + i) & STRIPES_MASK;
			final Stripe stripe = group.stripes[s];
			if (stripe.stack.isEmpty())
				continue;

			final PooledArray<Object> wrapper = stripe.poll(bytes);
			if (wrapper != null) {
				stripe.updatePeak(stripe.inUse.incrementAndGet());

				wrapper.metadata.context = context;
				wrapper.metadata.borrowedByThreadId = getThreadId();

				return (PooledArray<T>) wrapper;
			}
		}

		if (flag == BorrowFlag.NONE || (flag == BorrowFlag.CREATE_IF_NOT_FULL && !canReservePoolBytes(bytes)))
			return null;

		final Stripe stripe = group.stripes[startStripe];
		stripe.updatePeak(stripe.inUse.incrementAndGet());

		final PooledArray<T> wrapper = create(roundedSize);
		wrapper.metadata.context = context;
		wrapper.metadata.borrowedByThreadId = getThreadId();

		return wrapper;
	}

	@SuppressWarnings("unchecked")
	public void release(PooledArray<?> wrapper) {
		if (wrapper == null || wrapper.array == null)
			return;

		final Thread currentThread = Thread.currentThread();
		if (wrapper.metadata.isInPool()) {
			log.warn(
				"Attempted to release a PooledArray that's already back in the pool: {} " +
				"(borrowed by {}, previously released by {}, this release attempted by {})\n{}",
				wrapper, formatThreadString(wrapper.metadata.borrowedByThreadId), formatThreadString(wrapper.metadata.releasedByThreadId), formatThreadString(currentThread), getThreadStackTrace(currentThread)
			);
			return;
		}

		final int len = Array.getLength(wrapper.array);
		if (len != ceilPow2(len))
			return;

		final int b = bucket(len);

		final long bytes = bytesFor(len);
		final PooledArray<Object> objWrapper = (PooledArray<Object>) wrapper;

		if (!tryReservePoolBytes(bytes))
			return;

		final int s = stripeIndex();
		final Stripe stripe = buckets[b].stripes[s];

		stripe.inUse.decrementAndGet();
		stripe.add(objWrapper);
		objWrapper.metadata.releasedByThreadId = currentThread.getId();
	}

	@FunctionalInterface
	public interface ArraySupplier<T> {
		T get(int capacity);
	}

	private static final class Bucket {
		private final AtomicBoolean cleaning = new AtomicBoolean();
		private float avgDemand;

		private final Stripe[] stripes = new Stripe[STRIPES];
		private final int[] sizes = new int[STRIPES]; // Used During Cleaning

		private final int size;

		public Bucket(int size) {
			this.size = size;
			for (int i = 0; i < STRIPES; i++)
				stripes[i] = new Stripe();
		}
	}

	private static final class Stripe {
		private final IntrusiveTreiberStack<PooledArray<Object>> stack = new IntrusiveTreiberStack<>();
		private final AtomicInteger inUse = new AtomicInteger();
		private final AtomicInteger peakInUse = new AtomicInteger();

		public void add(PooledArray<Object> wrapper) {
			wrapper.metadata.context = null;
			if (!stack.push(wrapper)) {
				log.warn(
					"Attempted to release a PooledArray that was already in the pool: {} (borrowed by {})",
					wrapper, formatThreadString(wrapper.metadata.borrowedByThreadId)
				);
			}
		}

		public PooledArray<Object> poll(long bytes) {
			final PooledArray<Object> wrapper = stack.pop();
			if (wrapper == null)
				return null;

			CURRENT_POOL_BYTES.addAndGet(-bytes);
			return wrapper;
		}

		public long empty(long bytesPerElement) {
			final long[] reclaimed = { 0L };
			stack.drain(wrapper -> {
				wrapper.cleanable.clean();
				reclaimed[0] += bytesPerElement;
			});
			inUse.set(0);
			return reclaimed[0];
		}

		private void updatePeak(int value) {
			int cur = peakInUse.get();
			while (value > cur) {
				int witness = peakInUse.compareAndExchange(cur, value);
				if (witness == cur) return;
				cur = witness;
			}
		}
	}

	public static final class PooledArrayRef<T> implements AutoCloseable {
		private final PooledArrayType arrayType;
		private final String context;

		@Getter
		private PooledArray<T> pooledArray;

		private PooledArrayRef(PooledArrayType arrayType, String context) {
			this.arrayType = arrayType;
			this.context = context;
		}

		public int length() { return pooledArray != null ? pooledArray.length : 0; }

		public T getArray() { return pooledArray != null ? pooledArray.getArray() : null; }

		public <E> E get(int idx) { return pooledArray != null ? pooledArray.get(idx) : null; }

		public <E> void set(int idx, E value) {
			if(pooledArray != null)
				pooledArray.set(idx, value);
		}

		public T ensureCapacity(int requestedSize) {
			if(pooledArray == null) {
				pooledArray = arrayType.borrow(context, requestedSize);
				return pooledArray.getArray();
			}

			pooledArray = arrayType.ensureCapacity(pooledArray, requestedSize);
			return pooledArray.getArray();
		}

		public T ensureCapacity(int requestedSize, int offset, int count) {
			if(pooledArray == null) {
				pooledArray = arrayType.borrow(context, requestedSize);
				return pooledArray.getArray();
			}

			pooledArray = pooledArray.ensureCapacity(requestedSize, offset, count);
			return pooledArray.getArray();
		}

		@Override
		public void close() {
			if(pooledArray != null)
				pooledArray.close();
			pooledArray = null;
		}
	}

	public static final class PooledArray<T> implements AutoCloseable, IntrusiveTreiberStack.Node<PooledArray<Object>> {
		private final T array;
		private final Object[] boxedArray;
		private final Cleanable cleanable;
		private final Metadata metadata;

		@Getter
		private volatile PooledArray<Object> next;

		@Getter
		private final int length;

		private PooledArray(PooledArrayType arrayType, T array, long bytes) {
			this.array = array;
			this.boxedArray = array instanceof Object[] ? (Object[]) array : null;
			this.length = Array.getLength(array);

			final Metadata metadata = this.metadata = new Metadata(arrayType);
			this.cleanable = CLEANER.register(this, () -> {
				TOTAL_POOL_BYTES.addAndGet(-bytes);

				final String context = metadata.context;
				if (!metadata.isInPool() && context != null) {
					log.warn(
						"A {} PooledArray ({} bytes) was garbage collected while still borrowed by {}. " +
						"Borrowed on {}, last accessed on {}. " +
						"This usually means close()/release() was never called - check for a leak.",
						metadata.type, bytes, context, formatThreadString(metadata.borrowedByThreadId), formatThreadString(metadata.lastAccessThreadId)
					);
				}
			});
		}

		public T getArray() {
			if (metadata.isInPool()) {
				final Thread currentThread = Thread.currentThread();
				log.warn(
					"Attempted to use a PooledArray after it was released back to the pool " +
					"(borrowed by {}, released by {}, attempted access by {})\n{}",
					formatThreadString(metadata.borrowedByThreadId), formatThreadString(metadata.releasedByThreadId), formatThreadString(currentThread), getThreadStackTrace(currentThread)
				);
				return null;
			}
			metadata.lastAccessThreadId = getThreadId();
			return array;
		}

		@SuppressWarnings("unchecked")
		public <E> E get(int idx) { return (E) boxedArray[idx]; }

		public <E> void set(int idx, E value) { boxedArray[idx] = value; }

		public PooledArray<T> ensureCapacity(int requestedSize) {
			return metadata.type.ensureCapacity(this, requestedSize);
		}

		public PooledArray<T> ensureCapacity(int requestedSize, int offset, int count) { return metadata.type.ensureCapacity(this, requestedSize, offset, count); }

		@Override
		public String toString() {
			return metadata.type.toString() + "[" + length + "]@" + Integer.toHexString(System.identityHashCode(array));
		}

		@Override
		public void close() {
			metadata.type.release(this);
		}

		@Override
		public void setNext(PooledArray<Object> next) {
			this.next = next;
		}

		@Override
		public boolean tryClaim() {
			return metadata.tryClaim();
		}

		@Override
		public boolean tryMarkInStack() {
			return metadata.tryMarkPooled();
		}
	}

	@RequiredArgsConstructor
	private static final class Metadata extends AtomicBoolean {
		final PooledArrayType type;
		String context;

		volatile long borrowedByThreadId = -1L;
		volatile long releasedByThreadId = -1L;
		volatile long lastAccessThreadId = -1L;

		boolean isInPool() { return get(); }

		boolean tryMarkPooled() { return compareAndSet(false, true); }

		boolean tryClaim() { return compareAndSet(true, false); }
	}
}