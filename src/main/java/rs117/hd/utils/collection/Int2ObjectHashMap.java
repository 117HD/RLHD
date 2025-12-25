package rs117.hd.utils.collection;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.HashUtil.murmurHash3;
import static rs117.hd.utils.MathUtils.*;

public final class Int2ObjectHashMap<T> implements Iterable<Int2ObjectHashMap.Entry<T>> {
	private static final int DEFAULT_CAPACITY = 16;
	private static final float DEFAULT_GROWTH = 1.5f;
	private static final float LOAD_FACTOR = 0.7f;
	private static final int EMPTY = Integer.MIN_VALUE;

	public interface Supplier<T> { T[] get(int capacity); }

	private final Supplier<T> defaultValueSupplier;
	private final float growthFactor;

	private int[] keys;
	private T[] values;
	private int size;
	private int mask;
	private volatile long lastRead; // high 32 bits = key, low 32 bits = idx

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

	public Int2ObjectHashMap(int initialCapacity, float growthFactor, Supplier<T> defaultValueSupplier) {
		this.defaultValueSupplier = defaultValueSupplier != null ? defaultValueSupplier : (capacity) -> (T[]) new Object[capacity];
		this.growthFactor = growthFactor;

		keys = new int[max((int) HDUtils.ceilPow2(initialCapacity), 16)];
		values = this.defaultValueSupplier.get(keys.length);

		Arrays.fill(keys, EMPTY);

		this.size = 0;
		this.mask = keys.length - 1;
	}

	private void resize() {
		int newCapacity = (int) HDUtils.ceilPow2(max((int)(keys.length * growthFactor), keys.length + 1));
		int[] oldKeys = keys;
		T[] oldValues = values;

		keys = new int[newCapacity];
		values = defaultValueSupplier.get(newCapacity);
		Arrays.fill(keys, EMPTY);
		size = 0;
		mask = newCapacity - 1;

		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] != EMPTY) {
				put(oldKeys[i], oldValues[i]);
			}
		}
	}

	public boolean put(int key, T value) { return put(key, value, true);}

	public boolean putIfAbsent(int key, T value) { return put(key, value, false); }

	private boolean put(int key, T value, boolean overwrite) {
		int idx = murmurHash3(key) & mask;
		int currentKey;
		while ((currentKey = keys[idx]) != EMPTY) {
			if (currentKey == key) {
				if(overwrite)
					values[idx] = value;
				return false;
			}
			idx = (idx + 1) & mask; // fast wrap-around using bitmask
		}

		if (size + 1.0 >= keys.length * LOAD_FACTOR)
			resize();

		keys[idx] = key;
		values[idx] = value;
		size++;
		return true;
	}

	public int findIndex(int key) {
		final long lastRead = this.lastRead;
		if((lastRead & 0xFFFFFFFFL) == key)
			return (int) (lastRead >> 32);

		final int[] keys = this.keys;
		final int mask = this.mask;

		int idx = murmurHash3(key) & mask;
		int currentKey;
		while ((currentKey = keys[idx]) != EMPTY) {
			if (currentKey == key) {
				this.lastRead = ((long)idx << 32) | (key & 0xFFFFFFFFL);
				return idx;
			}
			idx = (idx + 1) & mask;
		}

		return -1;
	}

	public T getOrDefault(int key, T defaultValue) {
		int idx = findIndex(key);
		return idx >= 0 ? values[idx] : defaultValue;
	}

	public T get(int key) {
		int idx = findIndex(key);
		return idx >= 0 ? values[idx] : null;
	}

	public T getValue(int idx) { return values[idx]; }

	public void setValue(int idx, T value) { values[idx] = value; }

	public boolean containsKey(int key) { return findIndex(key) >= 0; }

	public boolean remove(int key) {
		int idx = findIndex(key);
		if (idx < 0)
			return false;
		removeIndex(idx);
		return true;
	}

	public void removeIndex(int idx) {
		int lastIdx = idx;
		keys[idx] = EMPTY;
		values[idx] = null;
		size--;

		while (true) {
			int nextIdx = (lastIdx + 1) & mask;
			if (keys[nextIdx] == EMPTY) break;

			int idealIdx = murmurHash3(keys[nextIdx]) & mask;
			if ((nextIdx > lastIdx && (idealIdx <= lastIdx || idealIdx > nextIdx)) ||
				(nextIdx < lastIdx && (idealIdx <= lastIdx && idealIdx > nextIdx))) {
				keys[lastIdx] = keys[nextIdx];
				values[lastIdx] = values[nextIdx];
				keys[nextIdx] = EMPTY;
				values[nextIdx] = null;
				lastIdx = nextIdx;
			} else {
				break;
			}
		}
	}

	public void clear() {
		Arrays.fill(keys, EMPTY);
		Arrays.fill(values, null);
		size = 0;
	}

	public boolean isEmpty() { return size == 0;}

	public int size() { return size; }

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

		EntryIterator() { advance(); }

		private void advance() {
			do {
				nextIndex++;
			} while (nextIndex < keys.length && keys[nextIndex] == EMPTY);
		}

		@Override
		public boolean hasNext() { return nextIndex < keys.length; }

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
			index = -1; // prevent double remove
		}
	}
}