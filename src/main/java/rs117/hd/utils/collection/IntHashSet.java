package rs117.hd.utils.collection;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.HashUtil.murmurHash3;
import static rs117.hd.utils.MathUtils.*;

public final class IntHashSet implements Iterable<Integer> {
	private static final int DEFAULT_CAPACITY = 16;
	private static final float DEFAULT_GROWTH = 1.5f;
	private static final float LOAD_FACTOR = 0.7f;
	private static final int EMPTY = Integer.MIN_VALUE;

	private final float growthFactor;

	private int[] keys;
	private int size;
	private int mask;
	private volatile long lastRead; // high 32 bits = key, low 32 bits = idx

	public IntHashSet() {
		this(DEFAULT_CAPACITY, DEFAULT_GROWTH);
	}

	public IntHashSet(int initialCapacity) {
		this(initialCapacity, DEFAULT_GROWTH);
	}

	public IntHashSet(int initialCapacity, float growthFactor) {
		keys = new int[max((int) HDUtils.ceilPow2(initialCapacity), 16)];
		Arrays.fill(keys, EMPTY);
		this.growthFactor = growthFactor;
		this.size = 0;
		this.mask = keys.length - 1;
	}

	private void resize() {
		int newCapacity = (int) HDUtils.ceilPow2(
			max((int) (keys.length * growthFactor), keys.length + 1)
		);

		int[] oldKeys = keys;

		keys = new int[newCapacity];
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

	public boolean add(int key) {
		int idx = murmurHash3(key) & mask;
		int currentKey;

		while ((currentKey = keys[idx]) != EMPTY) {
			if (currentKey == key) {
				return false; // already present
			}
			idx = (idx + 1) & mask;
		}

		if (size + 1.0 >= keys.length * LOAD_FACTOR)
			resize();

		keys[idx] = key;
		size++;
		return true;
	}

	public boolean contains(int key) {
		return findIndex(key) >= 0;
	}

	private int findIndex(int key) {
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

	public boolean remove(int key) {
		int idx = findIndex(key);
		if (idx < 0)
			return false;
		removeIndex(idx);
		return true;
	}

	private void removeIndex(int idx) {
		int lastIdx = idx;
		keys[idx] = EMPTY;
		size--;

		while (true) {
			int nextIdx = (lastIdx + 1) & mask;
			int key = keys[nextIdx];
			if (key == EMPTY)
				break;

			int idealIdx = murmurHash3(key) & mask;
			if ((nextIdx > lastIdx && (idealIdx <= lastIdx || idealIdx > nextIdx)) ||
				(nextIdx < lastIdx && (idealIdx <= lastIdx && idealIdx > nextIdx))) {
				keys[lastIdx] = key;
				keys[nextIdx] = EMPTY;
				lastIdx = nextIdx;
			} else {
				break;
			}
		}
	}

	public void clear() {
		Arrays.fill(keys, EMPTY);
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
