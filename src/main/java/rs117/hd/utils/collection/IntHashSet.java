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

public final class IntHashSet implements Iterable<Integer> {
	private final float growthFactor;

	private final long[] readCache = new long[READ_CACHE_SIZE];
	private int[] keys;
	private int[] distances;

	private int size;
	private int mask;

	public IntHashSet() {
		this(DEFAULT_CAPACITY, DEFAULT_GROWTH);
	}

	public IntHashSet(int initialCapacity) {
		this(initialCapacity, DEFAULT_GROWTH);
	}

	public IntHashSet(int initialCapacity, float growthFactor) {
		int cap = max((int) HDUtils.ceilPow2(initialCapacity), DEFAULT_CAPACITY);

		keys = new int[cap];
		distances = new int[cap];

		Arrays.fill(keys, EMPTY);

		this.growthFactor = growthFactor;
		this.size = 0;
		this.mask = cap - 1;
	}

	private void resize() {
		int newCapacity = (int) HDUtils.ceilPow2(
			max((int) (keys.length * growthFactor), keys.length + 1)
		);

		int[] oldKeys = keys;

		keys = new int[newCapacity];
		distances = new int[newCapacity];

		Arrays.fill(keys, EMPTY);

		size = 0;
		mask = newCapacity - 1;

		for (int i = 0; i < oldKeys.length; i++) {
			int key = oldKeys[i];
			if (key != EMPTY) {
				add(key);
			}
		}
	}

	public boolean add(Object key) { return key != null && add(key.hashCode()); }

	public boolean add(int key) {
		if (size + 1.0 >= keys.length * LOAD_FACTOR)
			resize();

		final int[] keys = this.keys;
		final int[] distances = this.distances;

		int idx = murmurHash3(key) & mask;
		for (int dist = 0; ; dist++) {
			final int k = keys[idx];

			if (k == EMPTY) {
				keys[idx] = key;
				distances[idx] = dist;
				size++;
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

	public boolean contains(Object key) {return key != null && contains(key.hashCode()); }

	public boolean contains(int key) {
		return findIndex(key, mask, keys, distances, readCache) >= 0;
	}

	public boolean remove(Object key) { return key != null && remove(key.hashCode()); }

	public boolean remove(int key) {
		int idx = findIndex(key, mask, keys, distances, readCache);
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
		Arrays.fill(keys, EMPTY);
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
	public Iterator<Integer> iterator() {
		return new Iterator<>() {
			private int index = -1;
			private int visited = 0;

			@Override
			public boolean hasNext() {
				return visited < size;
			}

			@Override
			public Integer next() {
				if (!hasNext())
					throw new NoSuchElementException();

				while (++index < keys.length) {
					int key = keys[index];
					if (key != EMPTY) {
						visited++;
						return key;
					}
				}

				throw new NoSuchElementException();
			}
		};
	}
}
