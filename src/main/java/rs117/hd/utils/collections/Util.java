package rs117.hd.utils.collections;

import java.util.Collections;
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
	private static final ThreadLocal<SortAccessor> LOCAL_ACCESSOR = ThreadLocal.withInitial(SortAccessor::new);

	private static void quickSortInternal(SortAccessor accessor, int left, int right) {
		if (right <= left) return;

		if (right - left + 1 <= INSERTION_SORT_THRESHOLD) {
			insertionSort(accessor, left, right);
			return;
		}

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

					// insertion sort for tiny ranges
					if (length <= INSERTION_SORT_THRESHOLD) {
						insertionSort(accessor, left, right);
						break;
					}

					// 5-sample pivot selection
					int sixth = length / 6;
					int e1 = left + sixth;
					int e5 = right - sixth;
					int e3 = left + (length >>> 1);
					int e2 = e3 - sixth;
					int e4 = e3 + sixth;

					// sort the 5 samples
					if (accessor.compare(e1, e2) > 0) accessor.swap(e1, e2);
					if (accessor.compare(e4, e5) > 0) accessor.swap(e4, e5);
					if (accessor.compare(e1, e3) > 0) accessor.swap(e1, e3);
					if (accessor.compare(e2, e3) > 0) accessor.swap(e2, e3);
					if (accessor.compare(e1, e4) > 0) accessor.swap(e1, e4);
					if (accessor.compare(e3, e4) > 0) accessor.swap(e3, e4);
					if (accessor.compare(e2, e5) > 0) accessor.swap(e2, e5);
					if (accessor.compare(e2, e3) > 0) accessor.swap(e2, e3);
					if (accessor.compare(e4, e5) > 0) accessor.swap(e4, e5);

					accessor.savePivot(e3);

					// 3-way partition
					int lt = left;
					int gt = right;
					int i = left;

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

					// push larger partition; continue with smaller
					int leftLen = lt - 1 - left;
					int rightLen = right - (gt + 1);

					if (leftLen >= rightLen) {
						if (left < lt - 1) {
							stack[top++] = left;
							stack[top++] = lt - 1;
						}
						left = gt + 1;
					} else {
						if (gt + 1 < right) {
							stack[top++] = gt + 1;
							stack[top++] = right;
						}
						right = lt - 1;
					}
				}
			}
		} finally {
			PooledArrayType.INT.release(stack);
		}
	}

	private static void insertionSort(SortAccessor accessor, int left, int right) {
		for (int i = left + 1; i <= right; i++) {
			int j = i;
			while (j > left && accessor.compare(j - 1, j) > 0) {
				accessor.swap(j - 1, j);
				j--;
			}
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
			quickSortInternal(LOCAL_ACCESSOR.get().setupFloat(a, comparator), left, right);
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
			quickSortInternal(LOCAL_ACCESSOR.get().setupInt(a, comparator), left, right);
	}

	public static <T> void quickSort(T[] a, Comparator<? super T> comparator) {
		if (a.length > 1)
			quickSort(a, 0, a.length - 1, comparator);
	}

	public static <T> void quickSort(T[] a, int left, int right, Comparator<? super T> comparator) {
		if (right > left)
			quickSortInternal(LOCAL_ACCESSOR.get().setupObject(a, comparator), left, right);
	}

	@SuppressWarnings("unchecked")
	public static <T> void quickSort(List<T> list, Comparator<? super T> comparator) {
		final int size = list.size();
		if (size <= 1) return;

		quickSortInternal(LOCAL_ACCESSOR.get().setupList((List<Object>) list, comparator), 0, size - 1);
	}

	public static <T> void quickSortByKey(int[] keys, List<T> payload) {
		if (keys.length > 1)
			quickSortByKey(keys, payload, 0, keys.length - 1);
	}

	@SuppressWarnings("unchecked")
	public static <T> void quickSortByKey(int[] keys, List<T> payload, int left, int right) {
		if (right > left)
			quickSortInternal(LOCAL_ACCESSOR.get().setupKeyedList(keys, (List<Object>) payload), left, right);
	}

	@SuppressWarnings("rawtypes")
	private static final class SortAccessor {
		private enum Kind { INT, FLOAT, OBJECT, LIST, KEYED_LIST }
		private Kind kind;

		private int[] intArray;
		private float[] floatArray;
		private Object[] objArray;
		private List<Object> list;
		private int[] keys;

		private Comparator comparator;

		private int intPivot;
		private float floatPivot;
		private Object objPivot;

		SortAccessor setupInt(int[] array, Comparator<Integer> comparator) {
			this.kind = Kind.INT;
			this.intArray = array;
			this.comparator = comparator;
			this.intPivot = 0;
			return this;
		}

		SortAccessor setupFloat(float[] array, Comparator<Float> comparator) {
			this.kind = Kind.FLOAT;
			this.floatArray = array;
			this.comparator = comparator;
			this.floatPivot = 0;
			return this;
		}

		SortAccessor setupObject(Object[] array, Comparator comparator) {
			this.kind = Kind.OBJECT;
			this.objArray = array;
			this.comparator = comparator;
			this.objPivot = null;
			return this;
		}

		SortAccessor setupList(List<Object> list, Comparator comparator) {
			this.kind = Kind.LIST;
			this.list = list;
			this.comparator = comparator;
			this.objPivot = null;
			return this;
		}

		SortAccessor setupKeyedList(int[] keys, List<Object> list) {
			this.kind = Kind.KEYED_LIST;
			this.keys = keys;
			this.list = list;
			this.comparator = null;
			this.intPivot = 0;
			return this;
		}

		int compare(int i, int j) {
			switch (kind) {
				case INT:
					return comparator != null ? comparator.compare(intArray[i], intArray[j]) : Integer.compare(intArray[i], intArray[j]);
				case FLOAT:
					return comparator != null ? comparator.compare(floatArray[i], floatArray[j]) : Float.compare(floatArray[i], floatArray[j]);
				case OBJECT:
					return comparator.compare(objArray[i], objArray[j]);
				case LIST:
					return comparator.compare(list.get(i), list.get(j));
				case KEYED_LIST:
					return Integer.compare(keys[i], keys[j]);
				default:
					throw new IllegalStateException("Unknown kind: " + kind);
			}
		}

		int compareToPivot(int i) {
			switch (kind) {
				case INT:
					return comparator != null ? comparator.compare(intArray[i], intPivot) : Integer.compare(intArray[i], intPivot);
				case FLOAT:
					return comparator != null ? comparator.compare(floatArray[i], floatPivot) : Float.compare(floatArray[i], floatPivot);
				case OBJECT:
					return comparator.compare(objArray[i], objPivot);
				case LIST:
					return comparator.compare(list.get(i), objPivot);
				case KEYED_LIST:
					return Integer.compare(keys[i], intPivot);
				default:
					throw new IllegalStateException("Unknown kind: " + kind);
			}
		}

		void savePivot(int index) {
			switch (kind) {
				case INT:        intPivot = intArray[index]; break;
				case FLOAT:      floatPivot = floatArray[index]; break;
				case OBJECT:     objPivot = objArray[index]; break;
				case LIST:       objPivot = list.get(index); break;
				case KEYED_LIST: intPivot = keys[index]; break;
				default: throw new IllegalStateException("Unknown kind: " + kind);
			}
		}

		void swap(int i, int j) {
			switch (kind) {
				case INT: {
					final int t = intArray[i];
					intArray[i] = intArray[j];
					intArray[j] = t;
					break;
				}
				case FLOAT: {
					final float t = floatArray[i];
					floatArray[i] = floatArray[j];
					floatArray[j] = t;
					break;
				}
				case OBJECT: {
					final Object t = objArray[i];
					objArray[i] = objArray[j];
					objArray[j] = t;
					break;
				}
				case KEYED_LIST: {
					final int tk = keys[i];
					keys[i] = keys[j];
					keys[j] = tk;
				}
				case LIST: {
					Collections.swap(list, i, j);
					break;
				}
				default:
					throw new IllegalStateException("Unknown kind: " + kind);
			}
		}
	}
}
