package rs117.hd.utils.collection;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collection.Util.DEFAULT_CAPACITY;
import static rs117.hd.utils.collection.Util.DEFAULT_GROWTH;
import static rs117.hd.utils.collection.Util.EMPTY;
import static rs117.hd.utils.collection.Util.LOAD_FACTOR;
import static rs117.hd.utils.collection.Util.READ_CACHE_SIZE;
import static rs117.hd.utils.collection.Util.findIndex;
import static rs117.hd.utils.collection.Util.murmurHash3;

public final class Int2ObjectHashMap<T> implements Iterable<Int2ObjectHashMap.Entry<T>> {
	public interface Supplier<T> { T[] get(int capacity); }

	private final Supplier<T> defaultValueSupplier;
	private final float growthFactor;

	private final long[] readCache = new long[READ_CACHE_SIZE];
	private int[] keys;
	private T[] values;
	private int[] distances;

	private int size;
	private int mask;

	public Int2ObjectHashMap() {
		this(DEFAULT_CAPACITY, DEFAULT_GROWTH, null);
	}

	public Int2ObjectHashMap(Supplier<T> defaultValueSupplier) {
		this(DEFAULT_CAPACITY, DEFAULT_GROWTH, defaultValueSupplier);
	}

	public Int2ObjectHashMap(int initialCapacity) {
		this(initialCapacity, DEFAULT_GROWTH, null);
	}

	public Int2ObjectHashMap(int initialCapacity, Supplier<T> defaultValueSupplier) {
		this(initialCapacity, DEFAULT_GROWTH, defaultValueSupplier);
	}

	@SuppressWarnings("unchecked")
	public Int2ObjectHashMap(int initialCapacity, float growthFactor, Supplier<T> defaultValueSupplier) {
		this.defaultValueSupplier =
			defaultValueSupplier != null
				? defaultValueSupplier
				: (capacity) -> (T[]) new Object[capacity];

		this.growthFactor = growthFactor;

		int cap = max((int) HDUtils.ceilPow2(initialCapacity), DEFAULT_CAPACITY);

		keys = new int[cap];
		values = this.defaultValueSupplier.get(cap);
		distances = new int[cap];

		Arrays.fill(keys, EMPTY);

		this.size = 0;
		this.mask = cap - 1;
	}

	private void resize() {
		int newCapacity = (int) HDUtils.ceilPow2(
			max((int) (keys.length * growthFactor), keys.length + 1)
		);

		int[] oldKeys = keys;
		T[] oldValues = values;

		keys = new int[newCapacity];
		values = defaultValueSupplier.get(newCapacity);
		distances = new int[newCapacity];

		Arrays.fill(keys, EMPTY);

		mask = newCapacity - 1;
		size = 0;

		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] != EMPTY) {
				put(oldKeys[i], oldValues[i]);
			}
		}
	}

	public boolean put(int key, T value) {
		return put(key, value, true);
	}

	public boolean putIfAbsent(int key, T value) {
		return put(key, value, false);
	}

	private boolean put(int key, T value, boolean overwrite) {
		if (size + 1.0 >= keys.length * LOAD_FACTOR)
			resize();

		final int[] keys = this.keys;
		final int[] distances = this.distances;

		int idx = murmurHash3(key) & mask;
		for (int dist = 0; ; dist++) {
			final int k = keys[idx];

			if (k == EMPTY) {
				keys[idx] = key;
				values[idx] = value;
				distances[idx] = dist;
				size++;
				return true;
			}

			if (k == key) {
				if (overwrite)
					values[idx] = value;
				return false;
			}

			// Robin Hood swap: steal slot if we probed farther
			if (distances[idx] < dist) {
				int tmpKey = keys[idx];
				T tmpVal = values[idx];
				int tmpDist = distances[idx];

				keys[idx] = key;
				values[idx] = value;
				distances[idx] = dist;

				key = tmpKey;
				value = tmpVal;
				dist = tmpDist;
			}

			idx = (idx + 1) & mask;
			dist++;
		}
	}

	public T getOrDefault(Object key, T defaultValue) {
		return key != null ? getOrDefault(key.hashCode(), defaultValue) : defaultValue;
	}

	public T getOrDefault(int key, T defaultValue) {
		int idx = findIndex(key, mask, keys, distances, readCache);
		return idx >= 0 ? values[idx] : defaultValue;
	}

	public T get(Object key) {
		return key != null ? get(key.hashCode()) : null;
	}

	public T get(int key) {
		int idx = findIndex(key, mask, keys, distances, readCache);
		return idx >= 0 ? values[idx] : null;
	}

	public boolean containsKey(Object key) { return key != null && containsKey(key.hashCode()); }

	public boolean containsKey(int key) {
		return findIndex(key, mask, keys, distances, readCache) >= 0;
	}

	public T getValue(int idx) {
		return values[idx];
	}

	public void setValue(int idx, T value) {
		values[idx] = value;
	}

	public boolean remove(Object key) { return key != null && remove(key.hashCode()); }

	public boolean remove(int key) {
		int idx = findIndex(key, mask, keys, distances, readCache);
		if (idx < 0)
			return false;

		removeIndex(idx);
		return true;
	}

	public void removeIndex(int idx) {
		keys[idx] = EMPTY;
		values[idx] = null;
		distances[idx] = 0;
		size--;

		int last = idx;

		// Shift backward while probe distance allows
		while (true) {
			int next = (last + 1) & mask;
			if (keys[next] == EMPTY || distances[next] == 0)
				break;

			keys[last] = keys[next];
			values[last] = values[next];
			distances[last] = distances[next] - 1;

			keys[next] = EMPTY;
			values[next] = null;
			distances[next] = 0;

			last = next;
		}
	}

	public void clear() {
		Arrays.fill(keys, EMPTY);
		Arrays.fill(values, null);
		Arrays.fill(distances, 0);
		size = 0;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public int size() {
		return size;
	}

	@Override
	public Iterator<Entry<T>> iterator() {
		return new EntryIterator();
	}

	public static class Entry<T> {
		public final int key;
		public T value;

		Entry(int key, T value) {
			this.key = key;
			this.value = value;
		}
	}

	private class EntryIterator implements Iterator<Entry<T>> {
		private int index = -1;
		private int nextIndex = -1;

		EntryIterator() {
			advance();
		}

		private void advance() {
			do {
				nextIndex++;
			} while (nextIndex < keys.length && keys[nextIndex] == EMPTY);
		}

		@Override
		public boolean hasNext() {
			return nextIndex < keys.length;
		}

		@Override
		public Entry<T> next() {
			if (!hasNext())
				throw new NoSuchElementException();

			index = nextIndex;
			advance();
			return new Entry<>(keys[index], values[index]);
		}

		@Override
		public void remove() {
			if (index == -1)
				throw new IllegalStateException();

			removeIndex(index);
			index = -1;
		}
	}
}
