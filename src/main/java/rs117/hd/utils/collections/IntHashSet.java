package rs117.hd.utils.collections;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import lombok.NonNull;

import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.DEFAULT_CAPACITY;
import static rs117.hd.utils.collections.Util.DEFAULT_GROWTH;
import static rs117.hd.utils.collections.Util.EMPTY;
import static rs117.hd.utils.collections.Util.LOAD_FACTOR;
import static rs117.hd.utils.collections.Util.findIndex;
import static rs117.hd.utils.collections.Util.murmurHash3;

public final class IntHashSet implements Iterable<Integer> {
	private final float growthFactor;

	private int[] keys;
	private int[] distances;

	private int lowTide = Integer.MAX_VALUE;
	private int highTide;
	private int size;
	private int mask;

	public IntHashSet() {
		this(DEFAULT_CAPACITY, DEFAULT_GROWTH);
	}

	public IntHashSet(int initialCapacity) {
		this(initialCapacity, DEFAULT_GROWTH);
	}

	public IntHashSet(int initialCapacity, float growthFactor) {
		assert growthFactor > 1;
		int cap = max(ceilPow2(initialCapacity), DEFAULT_CAPACITY);

		keys = new int[cap];
		distances = new int[cap];

		Arrays.fill(keys, EMPTY);

		this.growthFactor = growthFactor;
		this.size = 0;
		this.mask = cap - 1;
	}

	public void trimToSize() {
		resizeTo(max(size, DEFAULT_CAPACITY));
	}

	private void grow() {
		resizeTo((int) (keys.length * growthFactor));
	}

	private void resizeTo(int newCapacity) {
		assert size <= newCapacity;
		newCapacity = ceilPow2(newCapacity);
		if (newCapacity == keys.length)
			return;

		final int[] oldKeys = keys;
		keys = new int[newCapacity];
		distances = new int[newCapacity];
		Arrays.fill(keys, EMPTY);

		mask = newCapacity - 1;
		lowTide = Integer.MAX_VALUE;
		highTide = 0;

		// The size will remain the same after, but we make
		// it negative to avoid growth while repopulating
		int newSize = size;
		size = -newSize;

		for (int i = 0; i < oldKeys.length; i++)
			if (oldKeys[i] != EMPTY)
				add(oldKeys[i]);

		size = newSize;
	}

	public boolean addAll(IntHashSet other) {
		if (other == null || other.size == 0)
			return false;

		int originalSize = size;
		if (size + other.size >= keys.length * LOAD_FACTOR)
			resizeTo(ceilPow2((int) ((size + other.size) / LOAD_FACTOR)));

		// The size will remain the same after, but we make
		// it negative to avoid growth while repopulating
		int newSize = size;
		size = -newSize;

		boolean modified = false;
		for (int i = 0; i < other.keys.length; i++) {
			int k = other.keys[i];
			if (k != EMPTY && add(k))
				modified = true;
		}

		size += newSize + originalSize;
		return modified;
	}

	public boolean add(int key) {
		if (size >= (int) (keys.length * LOAD_FACTOR))
			grow();

		final int[] keys = this.keys;
		final int[] distances = this.distances;

		int idx = murmurHash3(key) & mask;
		int dist = 0;
		while (true) {
			final int k = keys[idx];

			if (k == EMPTY) {
				keys[idx] = key;
				distances[idx] = dist;
				size++;
				lowTide = min(idx, lowTide);
				highTide = max(idx, highTide);
				return true;
			}

			if (k == key)
				return false; // already present

			// Robin Hood swap
			if (distances[idx] < dist) {
				int tmpKey = keys[idx];
				int tmpDist = distances[idx];

				keys[idx] = key;
				distances[idx] = dist;

				key = tmpKey;
				dist = tmpDist;
			}

			idx = (idx + 1) & mask;
			dist++;
		}
	}

	public boolean contains(int key) {
		return findIndex(key, mask, keys, distances) >= 0;
	}

	public boolean remove(int key) {
		int idx = findIndex(key, mask, keys, distances);
		if (idx < 0)
			return false;

		removeIndex(idx);
		return true;
	}

	private void removeIndex(int idx) {
		keys[idx] = EMPTY;
		distances[idx] = 0;
		size--;

		int last = idx;

		// Shift backward while probe distance allows
		while (true) {
			int next = (last + 1) & mask;
			if (keys[next] == EMPTY || distances[next] == 0)
				break;

			keys[last] = keys[next];
			distances[last] = distances[next] - 1;

			keys[next] = EMPTY;
			distances[next] = 0;

			last = next;
		}
	}

	public void clear() {
		if (size == 0)
			return;
		Arrays.fill(keys, lowTide, highTide + 1, EMPTY);
		Arrays.fill(distances, lowTide, highTide + 1, 0);
		lowTide = keys.length;
		highTide = 0;
		size = 0;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public int size() { return size; }

	public int capacity() { return keys.length; }

	@Override
	@NonNull
	public Iterator<Integer> iterator() {
		return new Iter();
	}

	private class Iter implements Iterator<Integer> {
		private int index = -1;
		private int nextIndex = -1;

		Iter() {
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
		public Integer next() {
			if (!hasNext())
				throw new NoSuchElementException();

			index = nextIndex;
			advance();

			return keys[index];
		}

		@Override
		public void remove() {
			if (index == -1)
				throw new IllegalStateException();

			removeIndex(index);
			nextIndex = index;
			index = -1;
		}
	}
}
