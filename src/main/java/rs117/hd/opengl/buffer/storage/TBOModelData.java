package rs117.hd.opengl.buffer.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import javax.inject.Singleton;
import lombok.Getter;
import rs117.hd.opengl.buffer.TextureStructuredBuffer;

@Singleton
public class TBOModelData extends TextureStructuredBuffer {

	public static class ModelData extends StructProperty {
		public final Property value = addProperty(PropertyType.Int, "flags");
	}

	private final List<ModelData> modelDataProperties = new ArrayList<>();
	private final NavigableSet<Slice> freeSlices = new TreeSet<>(Comparator.comparingInt(Slice::getOffset));
	private final List<Slice> activeSlices = new ArrayList<>();

	public TBOModelData() {
		super();
		Slice initialSlice = new Slice(this, 0, 1000);
		for (int i = 0; i < 1000; i++) {
			modelDataProperties.add(addStruct(new ModelData()));
		}
		freeSlices.add(initialSlice);
	}

	public Slice obtainSlice(int size) {
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
			Slice allocated = new Slice(this, bestFit.offset, size);
			activeSlices.add(allocated);

			int remainingSize = bestFit.size - size;
			if (remainingSize > 0) {
				freeSlices.add(new Slice(this, bestFit.offset + size, remainingSize));
			}

			validateSlices();
			return allocated;
		}

		// Expand buffer if no free slice is available
		Slice newSlice = new Slice(this, modelDataProperties.size(), size);
		for (int i = 0; i < size; i++)
			modelDataProperties.add(addStruct(new ModelData()));

		activeSlices.add(newSlice);
		upload();
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
		// Validate freeSlices are sorted and non-overlapping
		Slice prev = null;
		for (Slice curr : freeSlices) {
			if (prev != null) {
				assert prev.offset + prev.size <= curr.offset :
					"Free slices overlap! Previous: [" + prev.offset + ", " + (prev.offset + prev.size) +
					"] Current: [" + curr.offset + ", " + (curr.offset + curr.size) + "]";
			}
			prev = curr;
		}

		// Validate activeSlices do not overlap with each other
		activeSlices.sort(Comparator.comparingInt(Slice::getOffset));
		for (int i = 1; i < activeSlices.size(); i++) {
			Slice a = activeSlices.get(i - 1);
			Slice b = activeSlices.get(i);
			assert a.offset + a.size <= b.offset :
				"Active slices overlap! Previous: [" + a.offset + ", " + (a.offset + a.size) +
				"] Current: [" + b.offset + ", " + (b.offset + b.size) + "]";
		}

		// Validate freeSlices do not overlap activeSlices
		for (Slice free : freeSlices) {
			for (Slice active : activeSlices) {
				boolean overlap = free.offset < active.offset + active.size && active.offset < free.offset + free.size;
				assert !overlap :
					"Free slice [" + free.offset + ", " + (free.offset + free.size) +
					"] overlaps active slice [" + active.offset + ", " + (active.offset + active.size) + "]";
			}
		}
	}

	public static class Slice {
		@Getter
		private final TBOModelData data;
		@Getter
		private final int offset;
		@Getter
		private int size;

		public Slice(TBOModelData data, int offset, int size) {
			this.data = data;
			this.offset = offset;
			this.size = size;
		}

		public ModelData getStruct(int idx) {
			assert idx >= 0 && idx < size;
			return data.modelDataProperties.get(offset + idx);
		}

		public void free() {
			boolean removed = data.activeSlices.remove(this);
			assert removed : "Slice being freed was not in active slices!";

			int newOffset = this.offset;
			int newEnd = this.offset + this.size;

			// Find overlapping or adjacent slices using TreeSet's subset
			NavigableSet<Slice> overlapping = data.freeSlices.subSet(
				new Slice(data, newOffset, 0),
				true,
				new Slice(data, newEnd, 0),
				true
			);

			List<Slice> toRemove = new ArrayList<>(overlapping);

			for (Slice other : toRemove) {
				newOffset = Math.min(newOffset, other.offset);
				newEnd = Math.max(newEnd, other.offset + other.size);
			}

			data.freeSlices.removeAll(toRemove);
			data.freeSlices.add(new Slice(data, newOffset, newEnd - newOffset));

			data.validateSlices();
		}
	}
}