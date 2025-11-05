package rs117.hd.utils;

import lombok.Getter;
import java.util.*;

public class SliceAllocator<T> {
	@Getter
	public class Slice {
		private final int offset;
		private int size;

		public Slice(int offset, int size) {
			this.offset = offset;
			this.size = size;
		}

		public T get(int index) {
			assert index >= 0 && index < size;
			return elements.get(offset + index);
		}

		public void free() {
			boolean removed = activeSlices.remove(this);
			assert removed : "Slice being freed was not in active slices!";

			int newOffset = this.offset;
			int newEnd = this.offset + this.size;

			// Merge with overlapping or adjacent free slices
			NavigableSet<Slice> overlapping = freeSlices.subSet(
				new Slice(newOffset, 0),
				true,
				new Slice(newEnd, 0),
				true
			);

			List<Slice> toRemove = new ArrayList<>(overlapping);
			for (Slice other : toRemove) {
				newOffset = Math.min(newOffset, other.offset);
				newEnd = Math.max(newEnd, other.offset + other.size);
			}

			freeSlices.removeAll(toRemove);
			freeSlices.add(new Slice(newOffset, newEnd - newOffset));
			validateSlices();
		}

		@Override
		public String toString() {
			return "[" + offset + ", " + (offset + size) + "]";
		}
	}

	private final List<T> elements;
	private final NavigableSet<Slice> freeSlices = new TreeSet<>(Comparator.comparingInt(Slice::getOffset));
	private final List<Slice> activeSlices = new ArrayList<>();

	private final boolean validate;
	private final Allocator<T> allocator;

	public SliceAllocator(List<T> elements, Allocator<T> allocator, int initialCapacity, boolean validate) {
		this.elements = elements;
		this.allocator = allocator;
		this.validate = validate;

		Slice initialSlice = new Slice(0, initialCapacity);
		for (int i = 0; i < initialCapacity; i++) {
			elements.add(allocator.allocate(i));
		}
		freeSlices.add(initialSlice);
	}

	public Slice allocate(int size) {
		assert size > 0;
		defrag();

		Slice bestFit = null;
		for (Slice candidate : freeSlices) {
			if (candidate.size >= size) {
				bestFit = candidate;
				break;
			}
		}

		if (bestFit != null) {
			freeSlices.remove(bestFit);
			Slice allocated = new Slice(bestFit.offset, size);
			activeSlices.add(allocated);

			int remaining = bestFit.size - size;
			if (remaining > 0) {
				freeSlices.add(new Slice(bestFit.offset + size, remaining));
			}

			validateSlices();
			return allocated;
		}

		// Expand buffer if no free slice fits
		int oldSize = elements.size();
		Slice newSlice = new Slice(oldSize, size);
		for (int i = 0; i < size; i++) {
			elements.add(allocator.allocate(oldSize + i));
		}

		activeSlices.add(newSlice);
		validateSlices();
		return newSlice;
	}

	public void defrag() {
		if (freeSlices.isEmpty()) return;

		List<Slice> merged = new ArrayList<>();
		Slice current = null;
		for (Slice slice : freeSlices) {
			if (current == null) {
				current = slice;
			} else if (current.offset + current.size >= slice.offset) {
				current.size = Math.max(current.offset + current.size, slice.offset + slice.size) - current.offset;
			} else {
				merged.add(current);
				current = slice;
			}
		}
		if (current != null) merged.add(current);

		freeSlices.clear();
		freeSlices.addAll(merged);
		validateSlices();
	}

	private void validateSlices() {
		if (!validate) return;

		Slice prev = null;
		for (Slice curr : freeSlices) {
			if (prev != null) {
				assert prev.offset + prev.size <= curr.offset :
					"Free slices overlap! " + prev + " vs " + curr;
			}
			prev = curr;
		}

		activeSlices.sort(Comparator.comparingInt(Slice::getOffset));
		for (int i = 1; i < activeSlices.size(); i++) {
			Slice a = activeSlices.get(i - 1);
			Slice b = activeSlices.get(i);
			assert a.offset + a.size <= b.offset :
				"Active slices overlap! " + a + " vs " + b;
		}

		for (Slice free : freeSlices) {
			for (Slice active : activeSlices) {
				boolean overlap = free.offset < active.offset + active.size && active.offset < free.offset + free.size;
				assert !overlap : "Free " + free + " overlaps active " + active;
			}
		}
	}

	@FunctionalInterface
	public interface Allocator<T> {
		T allocate(int index);
	}
}
