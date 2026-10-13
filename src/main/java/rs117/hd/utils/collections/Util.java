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
	private static final ThreadLocal<ListAccessor> LOCAL_LIST_ACCESSOR = ThreadLocal.withInitial(ListAccessor::new);
	private static final ThreadLocal<KeyedListAccessor> LOCAL_KEYED_LIST_ACCESSOR = ThreadLocal.withInitial(KeyedListAccessor::new);
	private static final ThreadLocal<KeyedIntArrayAccessor> LOCAL_KEYED_INT_ACCESSOR = ThreadLocal.withInitial(KeyedIntArrayAccessor::new);
	private static final ThreadLocal<KeyedFloatArrayAccessor> LOCAL_KEYED_FLOAT_ACCESSOR = ThreadLocal.withInitial(KeyedFloatArrayAccessor::new);

	private static void quickSortInternal(final SortAccessor accessor, int left, int right) {
		if (right <= left) return;

		// insertion sort for tiny ranges (JDK 8)
		if (right - left + 1 <= INSERTION_SORT_THRESHOLD) {
			insertionSort(accessor, left, right);
			return;
		}

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
						insertionSort(accessor, left, right);
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

					// sort the 5 samples with a 5-element sort network (9 compare-and-swaps)
					accessor.swapIfGreater(e1, e2);
					accessor.swapIfGreater(e4, e5);
					accessor.swapIfGreater(e1, e3);
					accessor.swapIfGreater(e2, e3);
					accessor.swapIfGreater(e1, e4);
					accessor.swapIfGreater(e3, e4);
					accessor.swapIfGreater(e2, e5);
					accessor.swapIfGreater(e2, e3);
					accessor.swapIfGreater(e4, e5);
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

	/**
	 * Straight insertion sort for small ranges.
	 * JDK 8 uses the same algorithm for ranges below its INSERTION_SORT_THRESHOLD.
	 */
	private static void insertionSort(final SortAccessor accessor, final int left, final int right) {
		for (int i = left + 1; i <= right; i++) {
			int j = i;
			while (j > left && accessor.swapIfGreater(j - 1, j))
				j--;
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
		public int compare(int i, int j) {
			return comparator != null ? comparator.compare(array[i], array[j]) : Float.compare(array[i], array[j]);
		}

		@Override
		public int compareToPivot(int i) {
			return comparator != null ? comparator.compare(array[i], pivot) : Float.compare(array[i], pivot);
		}

		@Override
		public void savePivot(int index) { pivot = array[index]; }

		@Override
		public void swap(int i, int j) {
			final float t = array[i];
			array[i] = array[j];
			array[j] = t;
		}

		@Override
		public boolean swapIfGreater(int i, int j) {
			final float a = array[i], b = array[j];
			if ((comparator != null ? comparator.compare(a, b) : Float.compare(a, b)) > 0) {
				array[i] = b;
				array[j] = a;
				return true;
			}
			return false;
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

		@Override
		public boolean swapIfGreater(int i, int j) {
			final int a = array[i], b = array[j];
			if ((comparator != null ? comparator.compare(a, b) : Integer.compare(a, b)) > 0) {
				array[i] = b;
				array[j] = a;
				return true;
			}
			return false;
		}
	}

	public static <T> void quickSort(T[] a, Comparator<? super T> comparator) {
		if (a.length > 1)
			quickSort(a, 0, a.length - 1, comparator);
	}

	public static <T> void quickSort(T[] a, int left, int right, Comparator<? super T> comparator) {
		if (right > left)
			quickSortInternal(LOCAL_OBJECT_ACCESSOR.get().setup(a, comparator), left, right);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
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
			return comparator != null ? comparator.compare(array[i], array[j]) : ((Comparable) array[i]).compareTo(array[j]);
		}

		@Override
		public int compareToPivot(int i) {
			return comparator != null ? comparator.compare(array[i], pivot) : ((Comparable) array[i]).compareTo(pivot);
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

		@Override
		public boolean swapIfGreater(int i, int j) {
			final Object a = array[i], b = array[j];
			if ((comparator != null ? comparator.compare(a, b) : ((Comparable) a).compareTo(b)) > 0) {
				array[i] = b;
				array[j] = a;
				return true;
			}
			return false;
		}
	}

	public static <T> void quickSort(List<T> list, Comparator<? super T> comparator) {
		if (list.size() > 1)
			quickSort(list, 0, list.size() - 1, comparator);
	}

	@SuppressWarnings("unchecked")
	public static <T> void quickSort(List<T> list, int left, int right, Comparator<? super T> comparator) {
		if (right <= left) return;

		final ListAccessor accessor = LOCAL_LIST_ACCESSOR.get();
		try {
			quickSortInternal(accessor.setup((List<Object>) list, comparator), left, right);
		} finally {
			accessor.release();
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static final class ListAccessor implements SortAccessor {
		private List<Object> list;
		private Comparator comparator;
		private Object pivot;

		ListAccessor setup(List<Object> list, Comparator comparator) {
			this.list = list;
			this.comparator = comparator;
			this.pivot = null;
			return this;
		}

		void release() {
			list = null;
			comparator = null;
			pivot = null;
		}

		@Override
		public int compare(int i, int j) {
			return comparator != null ? comparator.compare(list.get(i), list.get(j)) : ((Comparable) list.get(i)).compareTo(list.get(j));
		}

		@Override
		public int compareToPivot(int i) {
			return comparator != null ? comparator.compare(list.get(i), pivot) : ((Comparable) list.get(i)).compareTo(pivot);
		}

		@Override
		public void savePivot(int index) {
			pivot = list.get(index);
		}

		@Override
		public void swap(int i, int j) {
			list.set(i, list.set(j, list.get(i)));
		}

		@Override
		public boolean swapIfGreater(int i, int j) {
			final Object a = list.get(i), b = list.get(j);
			if ((comparator != null ? comparator.compare(a, b) : ((Comparable) a).compareTo(b)) > 0) {
				list.set(i, b);
				list.set(j, a);
				return true;
			}
			return false;
		}
	}

	public static <T> void quickSortByKey(int[] keys, List<T> payload) {
		if (keys.length > 1)
			quickSortByKey(keys, payload, 0, keys.length - 1);
	}

	@SuppressWarnings("unchecked")
	public static <T> void quickSortByKey(int[] keys, List<T> payload, int left, int right) {
		if (right <= left) return;

		final KeyedListAccessor accessor = LOCAL_KEYED_LIST_ACCESSOR.get();
		try {
			quickSortInternal(accessor.setup(keys, (List<Object>) payload), left, right);
		} finally {
			accessor.release();
		}
	}

	private static final class KeyedListAccessor implements SortAccessor {
		private int[] keys;
		private List<Object> payload;
		private int pivot;

		KeyedListAccessor setup(int[] keys, List<Object> payload) {
			this.keys = keys;
			this.payload = payload;
			this.pivot = 0;
			return this;
		}

		void release() {
			keys = null;
			payload = null;
			pivot = 0;
		}

		@Override
		public int compare(int i, int j) {
			return Integer.compare(keys[i], keys[j]);
		}

		@Override
		public int compareToPivot(int i) {
			return Integer.compare(keys[i], pivot);
		}

		@Override
		public void savePivot(int index) {
			pivot = keys[index];
		}

		@Override
		public void swap(int i, int j) {
			final int tk = keys[i];
			keys[i] = keys[j];
			keys[j] = tk;
			payload.set(i, payload.set(j, payload.get(i)));
		}

		@Override
		public boolean swapIfGreater(int i, int j) {
			final int ka = keys[i], kb = keys[j];
			if (ka > kb) {
				keys[i] = kb;
				keys[j] = ka;
				payload.set(i, payload.set(j, payload.get(i)));
				return true;
			}
			return false;
		}
	}

	public static void quickSortByKey(int[] keys, int[] payload) {
		if (keys.length > 1)
			quickSortByKey(keys, payload, 0, keys.length - 1);
	}

	public static void quickSortByKey(int[] keys, int[] payload, int left, int right) {
		if (right <= left) return;

		final KeyedIntArrayAccessor accessor = LOCAL_KEYED_INT_ACCESSOR.get();
		try {
			quickSortInternal(accessor.setup(keys, payload), left, right);
		} finally {
			accessor.release();
		}
	}

	public static void quickSortByKey(int[] keys, float[] payload) {
		if (keys.length > 1)
			quickSortByKey(keys, payload, 0, keys.length - 1);
	}

	public static void quickSortByKey(int[] keys, float[] payload, int left, int right) {
		if (right <= left) return;

		final KeyedFloatArrayAccessor accessor = LOCAL_KEYED_FLOAT_ACCESSOR.get();
		try {
			quickSortInternal(accessor.setup(keys, payload), left, right);
		} finally {
			accessor.release();
		}
	}

	private static final class KeyedIntArrayAccessor implements SortAccessor {
		private int[] keys;
		private int[] payload;
		private int pivot;

		KeyedIntArrayAccessor setup(int[] keys, int[] payload) {
			this.keys = keys;
			this.payload = payload;
			this.pivot = 0;
			return this;
		}

		void release() {
			keys = null;
			payload = null;
			pivot = 0;
		}

		@Override
		public int compare(int i, int j) {
			return Integer.compare(keys[i], keys[j]);
		}

		@Override
		public int compareToPivot(int i) {
			return Integer.compare(keys[i], pivot);
		}

		@Override
		public void savePivot(int index) {
			pivot = keys[index];
		}

		@Override
		public void swap(int i, int j) {
			final int tk = keys[i];
			keys[i] = keys[j];
			keys[j] = tk;

			final int tp = payload[i];
			payload[i] = payload[j];
			payload[j] = tp;
		}

		@Override
		public boolean swapIfGreater(int i, int j) {
			final int ka = keys[i], kb = keys[j];
			if (ka > kb) {
				keys[i] = kb;
				keys[j] = ka;

				final int tp = payload[i];
				payload[i] = payload[j];
				payload[j] = tp;
				return true;
			}
			return false;
		}
	}

	private static final class KeyedFloatArrayAccessor implements SortAccessor {
		private int[] keys;
		private float[] payload;
		private int pivot;

		KeyedFloatArrayAccessor setup(int[] keys, float[] payload) {
			this.keys = keys;
			this.payload = payload;
			this.pivot = 0;
			return this;
		}

		void release() {
			keys = null;
			payload = null;
			pivot = 0;
		}

		@Override
		public int compare(int i, int j) {
			return Integer.compare(keys[i], keys[j]);
		}

		@Override
		public int compareToPivot(int i) {
			return Integer.compare(keys[i], pivot);
		}

		@Override
		public void savePivot(int index) {
			pivot = keys[index];
		}

		@Override
		public void swap(int i, int j) {
			final int tk = keys[i];
			keys[i] = keys[j];
			keys[j] = tk;

			final float tp = payload[i];
			payload[i] = payload[j];
			payload[j] = tp;
		}

		@Override
		public boolean swapIfGreater(int i, int j) {
			final int ka = keys[i], kb = keys[j];
			if (ka > kb) {
				keys[i] = kb;
				keys[j] = ka;

				final float tp = payload[i];
				payload[i] = payload[j];
				payload[j] = tp;
				return true;
			}
			return false;
		}
	}

	interface SortAccessor {
		int compare(int i, int j);
		int compareToPivot(int i);
		void savePivot(int index);
		void swap(int i, int j);

		/** Swaps elements i and j if element i > element j. Returns true if a swap happened. */
		default boolean swapIfGreater(int i, int j) {
			if (compare(i, j) > 0) {
				swap(i, j);
				return true;
			}
			return false;
		}
	}
}