package rs117.hd.opengl.buffer.storage;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import rs117.hd.opengl.buffer.ShaderStructuredBuffer;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.SliceAllocator;

public class SSBOModelData extends ShaderStructuredBuffer {
	@RequiredArgsConstructor
	public static class ModelData extends StructProperty {
		public final int modelOffset;
		public final Property position = addProperty(PropertyType.IVec3, "position");
		public final Property height = addProperty(PropertyType.Int, "height");
		public final Property flags = addProperty(PropertyType.Int, "flags");
		public final Property padding = addProperty(PropertyType.Int, "padding");

		public int set(
			Renderable renderable,
			Model model,
			ModelOverride override,
			int x,
			int y,
			int z,
			boolean isStaticModel,
			boolean isDetailModel
		) {
			this.position.set(x, y, z);
			this.height.set(model.getModelHeight());
			this.flags.set(
				(isStaticModel ? 1 : 0) |
				(isDetailModel ? 1 : 0) << 1);
			return modelOffset;
		}
	}

	private final List<ModelData> modelDataProperties = new ArrayList<>();
	private final SliceAllocator<Slice> allocator = new SliceAllocator<>(Slice::new, 1, 100, true);

	// Dynamic Model Data
	private final List<Slice> frameModelDataSlices = new ArrayList<>();
	private int maxDynamicModelCount = 100;
	private int frameDynamicModelCount;

	public synchronized Slice obtainSlice(int size) {
		Slice slice = allocator.allocate(size);
		upload();
		return slice;
	}

	public synchronized int addDynamicModelData(
		Renderable renderable,
		Model model,
		ModelOverride override,
		int x,
		int y,
		int z,
		boolean isDetailModel
	) {
		ModelData dynamicModelData = null;
		if (!frameModelDataSlices.isEmpty()) {
			Slice currentSlice = frameModelDataSlices.get(frameModelDataSlices.size() - 1);
			if (currentSlice.hasSpace()) {
				dynamicModelData = currentSlice.add();
			}
		}

		if (dynamicModelData == null) {
			Slice newSlice = obtainSlice(maxDynamicModelCount);
			frameModelDataSlices.add(newSlice);
			dynamicModelData = newSlice.add();
		}

		frameDynamicModelCount++;
		dynamicModelData.set(renderable, model, override, x, y, z, false, isDetailModel);
		return dynamicModelData.modelOffset;
	}

	public synchronized void freeDynamicModelData() {
		for (var slice : frameModelDataSlices)
			slice.free();
		frameModelDataSlices.clear();

		if (frameDynamicModelCount > maxDynamicModelCount)
			maxDynamicModelCount = frameDynamicModelCount;
		frameDynamicModelCount = 0;
	}

	public void defrag() { allocator.defrag(); }

	public class Slice extends SliceAllocator.Slice {
		private int modelCount;

		public Slice(int offset, int size) {
			super(offset, size);
		}

		@Override
		protected void allocate() {
			while (modelDataProperties.size() < offset + size)
				modelDataProperties.add(addStruct(new ModelData(modelDataProperties.size())));
		}

		@Override
		protected void onFreed() { modelCount = 0; }

		public boolean hasSpace() { return modelCount < size; }

		public ModelData add() {
			if (!hasSpace()) {
				return null;
			}
			return modelDataProperties.get(offset + modelCount++);
		}
	}
}
