package rs117.hd.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import lombok.Getter;

public class SliceAllocator<SLICE extends SliceAllocator.Slice> {
	@Getter
	public static abstract class Slice {
		protected SliceAllocator<?> owner;
		protected final int offset;
		protected int size;
		private boolean freed = false;

		public Slice(int offset, int size) {
			this.offset = offset;
			this.size = size;
		}

		protected abstract void allocate();
		protected abstract void onFreed();

		public void free() {
			if (freed) return;
			freed = true;
			assert owner != null : "Slice has no owner!";
			onFreed();
			owner.free(this);
		}

		@Override
		public String toString() {
			return "[" + offset + ", " + (offset + size) + "]";
		}
	}

	private final NavigableSet<SLICE> freeSlices = new TreeSet<>(Comparator.comparingInt(Slice::getOffset));
	private final List<SLICE> activeSlices = new ArrayList<>();

	private final boolean validate;
	private final Allocator<SLICE> allocator;
	private int totalSize = 0;

	public SliceAllocator(Allocator<SLICE> allocator, int initialSliceCount, int initialSliceSize, boolean validate) {
		this.allocator = allocator;
		this.validate = validate;

		int offset = 0;
		for (int i = 0; i < initialSliceCount; i++) {
			SLICE slice = allocator.createSlice(offset, initialSliceSize);
			slice.owner = this;
			slice.allocate();
			freeSlices.add(slice);
			offset += initialSliceSize;
		}
		this.totalSize = offset;
	}

	public SLICE allocate(int size) {
		assert size > 0;
		defrag();

		// Find best-fit free slice
		SLICE bestFit = null;
		for (SLICE candidate : freeSlices) {
			if (candidate.size >= size) {
				bestFit = candidate;
				break;
			}
		}

		if (bestFit != null) {
			freeSlices.remove(bestFit);
			SLICE allocated = allocator.createSlice(bestFit.offset, size);
			allocated.owner = this;
			activeSlices.add(allocated);

			int remaining = bestFit.size - size;
			if (remaining > 0) {
				SLICE remainder = allocator.createSlice(bestFit.offset + size, remaining);
				remainder.owner = this;
				freeSlices.add(remainder);
			}

			validateSlices();
			return allocated;
		}

		int newOffset = totalSize;
		SLICE newSlice = allocator.createSlice(newOffset, size);
		newSlice.owner = this;
		newSlice.allocate();

		totalSize = newOffset + size;
		activeSlices.add(newSlice);
		validateSlices();
		return newSlice;
	}

	private void free(Slice slice) {
		@SuppressWarnings("unchecked")
		SLICE typed = (SLICE) slice;

		boolean removed = activeSlices.remove(typed);
		assert removed : "Slice being freed was not in active slices!";

		int newOffset = typed.offset;
		int newEnd = typed.offset + typed.size;

		// Merge with adjacent free slices
		List<SLICE> toMerge = new ArrayList<>();
		for (SLICE free : freeSlices) {
			if (free.offset <= newEnd && free.offset + free.size >= newOffset) {
				toMerge.add(free);
			}
		}

		for (SLICE other : toMerge) {
			freeSlices.remove(other);
			newOffset = Math.min(newOffset, other.offset);
			newEnd = Math.max(newEnd, other.offset + other.size);
		}

		SLICE merged = allocator.createSlice(newOffset, newEnd - newOffset);
		merged.owner = this;
		freeSlices.add(merged);

		validateSlices();
	}

	public void defrag() {
		if (freeSlices.isEmpty()) return;

		List<SLICE> merged = new ArrayList<>();
		SLICE current = null;
		for (SLICE slice : freeSlices) {
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
	public interface Allocator<T extends SliceAllocator.Slice> {
		T createSlice(int offset, int size);
	}
}
