package rs117.hd.opengl.buffer.storage;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Singleton;
import lombok.Getter;
import rs117.hd.opengl.buffer.TextureStructuredBuffer;

@Singleton
public class TBOModelData extends TextureStructuredBuffer {
	public static class Slice {
		@Getter
		private TBOModelData data;
		@Getter
		private int offset;
		@Getter
		private int size;

		public ModelData getStruct(int idx) {
			assert idx >= 0 && idx < size;
			return data.modelDataProperties.get(offset + idx);
		}

		public void free() {
			for (int i = 0; i < data.freeSlices.size(); i++) {
				Slice other = data.freeSlices.get(i);
				if (other.offset + other.size == offset) {
					// Merge with slice after
					other.size += size;
					return;
				} else if (offset + size == other.offset) {
					// Merge with slice before
					size += other.size;
					data.freeSlices.remove(i);
					break;
				}
			}
			data.freeSlices.add(this);
		}
	}

	public static class ModelData extends StructProperty {
		public final Property value = addProperty(PropertyType.Int, "flags");
	}

	private final List<ModelData> modelDataProperties = new ArrayList<>();
	private final List<Slice> freeSlices = new ArrayList<>();
	private int modelCount = 0;

	public Slice obtainSlice(int size) {
		assert size > 0;
		Slice bestFit = null;
		int bestFitIndex = -1;

		for (int i = 0; i < freeSlices.size(); i++) {
			Slice candidate = freeSlices.get(i);
			if (candidate.size == size) {
				freeSlices.remove(i);
				candidate.data = this;
				return candidate;
			} else if (candidate.size > size) {
				if (bestFit == null || candidate.size < bestFit.size) {
					bestFit = candidate;
					bestFitIndex = i;
				}
			}
		}

		if (bestFit != null) {
			freeSlices.remove(bestFitIndex);

			Slice allocated = new Slice();
			allocated.data = this;
			allocated.offset = bestFit.offset;
			allocated.size = size;

			int remainingSize = bestFit.size - size;
			if (remainingSize > 0) {
				Slice remainder = new Slice();
				remainder.data = this;
				remainder.offset = bestFit.offset + size;
				remainder.size = remainingSize;
				freeSlices.add(remainder);
			}

			return allocated;
		}

		Slice slice = new Slice();
		slice.data = this;
		slice.offset = this.modelCount;
		slice.size = size;

		for (int i = 0; i < size; i++) {
			modelDataProperties.add(addStruct(new ModelData()));
			modelCount++;
		}

		upload(); //TODO: We don't need to upload, but reinitialise the staging buffers

		return slice;
	}
}
