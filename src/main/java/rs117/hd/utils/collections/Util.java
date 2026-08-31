package rs117.hd.utils.collections;

import java.util.Comparator;
import java.util.List;

public final class Util {
	public static final int DEFAULT_CAPACITY = 16;
	public static final int EMPTY = Integer.MIN_VALUE;
	public static final float LOAD_FACTOR = 0.7f;
	public static final float DEFAULT_GROWTH = 1.5f;

	static {
		// noinspection ConstantValue
		assert LOAD_FACTOR < 1 : "Must be less than 1 for to avoid infinite loops";
	}

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

	public static int findIndex(final int key, final int mask, final int[] keys, final int[] distances) {
		int idx = murmurHash3(key) & mask;
		for (int dist = 0; dist == 0 || distances[idx] >= dist; dist++) {
			final int k = keys[idx];

			if (k == EMPTY)
				break;

			if (k == key)
				return idx;

			idx = (idx + 1) & mask;
		}

		return -1;
	}

	/**
	 * Allocation-free quicksort for Object arrays and Lists.
	 * Algorithm based on the JDK 8 Dual-Pivot Quicksort by Yaroslavskiy, Bentley,
	 *   https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/DualPivotQuicksort.java
	 */
	private static final int INSERTION_SORT_THRESHOLD = 47;
	private static final ThreadLocal<IntAccessor> LOCAL_INT_ACCESSOR = ThreadLocal.withInitial(IntAccessor::new);
	private static final ThreadLocal<FloatAccessor> LOCAL_FLOAT_ACCESSOR = ThreadLocal.withInitial(FloatAccessor::new);
	private static final ThreadLocal<ObjectAccessor> LOCAL_OBJECT_ACCESSOR = ThreadLocal.withInitial(ObjectAccessor::new);

	private static void quickSortInternal(SortAccessor accessor, int left, int right) {
		if (right <= left) return;

		/*
		 * Stack layout: pairs of (lo, hi) indices.
		 * Worst-case depth with "always push larger, tail-loop smaller" is
		 * ceil(log2(n)), so 64 int slots (= 32 pairs) covers 2^32 elements.
		 * We borrow from the pool to keep the entire call heap-allocation-free.
		 */
		final int[] stack = PooledArrayType.INT.borrow(64);
		try {
			int top = 0;
			stack[top++] = left;
			stack[top++] = right;

			while (top > 0) {
				right = stack[--top];
				left  = stack[--top];

				// tail-call loop for the smaller partition
				while (left < right) {
					int length = right - left + 1;

					// insertion sort for tiny ranges (JDK 8)
					if (length <= INSERTION_SORT_THRESHOLD) {
						for (int i = left + 1; i <= right; i++) {
							int j = i;
							while (j > left && accessor.compare(j - 1, j) > 0) {
								accessor.swap(j - 1, j);
								j--;
							}
						}
						break;
					}

					// 5-sample pivot selection (mirrors JDK 8 sort)
					//
					// Divide the range into 6 equal-ish segments, take the
					// element at each interior boundary, sort those 5 candidates
					// in-place, then use the middle one (e3) as the pivot.
					// This keeps the pivot selection cost at O(1) while giving a
					// much better median estimate than median-of-three alone.
					int sixth = length / 6;
					int e1 = left  + sixth;
					int e5 = right - sixth;
					int e3 = left  + (length >>> 1); // midpoint
					int e2 = e3   - sixth;
					int e4 = e3   + sixth;

					// sort the 5 samples with a 5-element sort network (7 comparisons)
					if (accessor.compare(e1, e2) > 0) accessor.swap(e1, e2);
					if (accessor.compare(e4, e5) > 0) accessor.swap(e4, e5);
					if (accessor.compare(e1, e3) > 0) accessor.swap(e1, e3);
					if (accessor.compare(e2, e3) > 0) accessor.swap(e2, e3);
					if (accessor.compare(e1, e4) > 0) accessor.swap(e1, e4);
					if (accessor.compare(e3, e4) > 0) accessor.swap(e3, e4);
					if (accessor.compare(e2, e5) > 0) accessor.swap(e2, e5);
					if (accessor.compare(e2, e3) > 0) accessor.swap(e2, e3);
					if (accessor.compare(e4, e5) > 0) accessor.swap(e4, e5);
					// invariant: a[e1] <= a[e2] <= a[e3] <= a[e4] <= a[e5]

					accessor.savePivot(e3); // true median of 5 samples

					// 3-way partition (Dutch-flag / Bentley-McIlroy)
					int lt = left;
					int gt = right;
					int i  = left;

					while (i <= gt) {
						int cmp = accessor.compareToPivot(i);

						if (cmp < 0) {
							accessor.swap(lt++, i++);
						} else if (cmp > 0) {
							accessor.swap(i, gt--);
						} else {
							i++;
						}
					}

					// push larger partition; tail-loop smaller
					//
					// This is the key invariant that keeps the pooled stack at
					int leftLen  = lt - 1 - left;
					int rightLen = right - (gt + 1);

					if (leftLen >= rightLen) {
						// left side larger -> push it, tail-recurse right
						if (left < lt - 1) {
							stack[top++] = left;
							stack[top++] = lt - 1;
						}
						left = gt + 1; // continue with right partition
					} else {
						// right side larger -> push it, tail-recurse left
						if (gt + 1 < right) {
							stack[top++] = gt + 1;
							stack[top++] = right;
						}
						right = lt - 1; // continue with left partition
					}
				}
			}
		} finally {
			PooledArrayType.INT.release(stack);
		}
	}

	public static void quickSort(float[] a) {
		if (a.length > 1)
			quickSort(a, 0, a.length - 1);
	}

	public static void quickSort(float[] a, int left, int right) {
		if (right > left)
			quickSort(a, left, right, null);
	}

	public static void quickSort(float[] a, int left, int right, Comparator<Float> comparator) {
		if (right > left)
			quickSortInternal(LOCAL_FLOAT_ACCESSOR.get().setup(a, comparator), left, right);
	}

	private static final class FloatAccessor implements SortAccessor {
		private float[] array;
		private Comparator<Float> comparator;
		private float pivot;

		FloatAccessor setup(float[] array, Comparator<Float> comparator) {
			this.array = array;
			this.comparator = comparator;
			this.pivot = 0;
			return this;
		}

		@Override
		public int compare(int i, int j) { return comparator != null ? comparator.compare(array[i], array[j]) : Float.compare(array[i], array[j]); }

		@Override
		public int compareToPivot(int i) { return comparator != null ? comparator.compare(array[i], pivot) :Float.compare(array[i], pivot); }

		@Override
		public void savePivot(int index) { pivot = array[index]; }

		@Override
		public void swap(int i, int j) {
			final float t = array[i];
			array[i] = array[j];
			array[j] = t;
		}
	}

	public static void quickSort(int[] a) {
		if (a.length > 1)
			quickSort(a, 0, a.length - 1);
	}

	public static void quickSort(int[] a, int left, int right) {
		if (right > left)
			quickSort(a, left, right, null);
	}

	public static void quickSort(int[] a, int left, int right, Comparator<Integer> comparator) {
		if (right > left)
			quickSortInternal(LOCAL_INT_ACCESSOR.get().setup(a, comparator), left, right);
	}

	private static final class IntAccessor implements SortAccessor {
		private int[] array;
		private Comparator<Integer> comparator;
		private int pivot;

		IntAccessor setup(int[] array, Comparator<Integer> comparator) {
			this.array = array;
			this.comparator = comparator;
			this.pivot = 0;
			return this;
		}

		@Override
		public int compare(int i, int j) {
			return comparator != null ? comparator.compare(array[i], array[j]) : Integer.compare(array[i], array[j]);
		}

		@Override
		public int compareToPivot(int i) {
			return comparator != null ? comparator.compare(array[i], pivot) : Integer.compare(array[i], pivot);
		}

		@Override
		public void savePivot(int index) {
			pivot = array[index];
		}

		@Override
		public void swap(int i, int j) {
			final int t = array[i];
			array[i] = array[j];
			array[j] = t;
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> void quickSort(List<T> list, Comparator<? super T> comparator) {
		final int size = list.size();
		if (size <= 1) return;

		final Object[] buf = PooledArrayType.OBJECT.borrow(size);
		try {
			list.toArray(buf);
			quickSort((T[]) buf, 0, size - 1, comparator);
			for (int i = 0; i < size; i++)
				list.set(i, (T) buf[i]);
		} finally {
			PooledArrayType.OBJECT.release(buf);
		}
	}

	public static <T> void quickSort(T[] a, Comparator<? super T> comparator) {
		if (a.length > 1)
			quickSort(a, 0, a.length - 1, comparator);
	}

	public static <T> void quickSort(T[] a, int left, int right, Comparator<? super T> comparator) {
		if (right > left) {
			quickSortInternal(LOCAL_OBJECT_ACCESSOR.get().setup(a, comparator), left, right);
		}
	}

	@SuppressWarnings("unchecked")
	private static final class ObjectAccessor implements SortAccessor {
		private Object[] array;
		private Comparator comparator;
		private Object pivot;

		ObjectAccessor setup(Object[] array, Comparator comparator) {
			this.array = array;
			this.comparator = comparator;
			this.pivot = null;
			return this;
		}

		@Override
		public int compare(int i, int j) {
			return comparator.compare(array[i], array[j]);
		}

		@Override
		public int compareToPivot(int i) {
			return comparator.compare(array[i], pivot);
		}

		@Override
		public void savePivot(int index) {
			pivot = array[index];
		}

		@Override
		public void swap(int i, int j) {
			final Object t = array[i];
			array[i] = array[j];
			array[j] = t;
		}
	}

	interface SortAccessor {
		int compare(int i, int j);
		int compareToPivot(int i);
		void savePivot(int index);
		void swap(int i, int j);
	}
}
