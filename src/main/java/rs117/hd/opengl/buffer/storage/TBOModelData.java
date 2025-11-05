package rs117.hd.opengl.buffer.storage;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import rs117.hd.opengl.buffer.TextureStructuredBuffer;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.SliceAllocator;


@Singleton
public class TBOModelData extends TextureStructuredBuffer {

	@RequiredArgsConstructor
	public static class ModelData extends StructProperty {
		public final int modelOffset;
		public final Property position = addProperty(PropertyType.IVec3, "position");
		public final Property height = addProperty(PropertyType.Int, "height");
		public final Property flags = addProperty(PropertyType.Int, "flags");

		public void set(Renderable renderable, Model model, ModelOverride override, int x, int y, int z) {
			position.set(x, y, z);
			height.set(model.getModelHeight());
			flags.set(((override.windDisplacementModifier + 3) & 0x7) << 12
					  | (override.windDisplacementMode.ordinal() & 0x7) << 9
					  | (override.invertDisplacementStrength ? 1 : 0) << 8);
		}
	}

	private final List<ModelData> modelDataProperties = new ArrayList<>();
	private final SliceAllocator<ModelData> allocator;
	private final List<SliceAllocator<TBOModelData.ModelData>.Slice> frameModelDataSlices = new ArrayList<>();
	private int sliceModelDataCount = 0;

	public TBOModelData() {
		super();
		this.allocator = new SliceAllocator<>(
			modelDataProperties,
			i -> addStruct(new ModelData(i)),
			1000,
			false
		);
	}

	public SliceAllocator<ModelData>.Slice obtainSlice(int size) {
		SliceAllocator<ModelData>.Slice slice = allocator.allocate(size);
		upload(); // keep texture buffer in sync with GPU
		return slice;
	}

	public int addDynamicModelData(Renderable renderable, Model model, ModelOverride override, int x, int y, int z) {
		TBOModelData.ModelData dynamicModelData = null;
		if(!frameModelDataSlices.isEmpty()) {
			// Check room in the last one
			var currentSlice = frameModelDataSlices.get(frameModelDataSlices.size() - 1);
			if(sliceModelDataCount < currentSlice.getSize()) {
				dynamicModelData = currentSlice.get(sliceModelDataCount++);
			}
		}

		if(dynamicModelData == null) {
			var newSlice = obtainSlice(100);
			frameModelDataSlices.add(newSlice);
			sliceModelDataCount = 0;
			dynamicModelData = newSlice.get(sliceModelDataCount++);
		}

		dynamicModelData.set(renderable, model, override, x, y, z);
		return dynamicModelData.modelOffset;
	}

	public void freeDynamicModelData() {
		for(var slice : frameModelDataSlices)
			slice.free();
		frameModelDataSlices.clear();
		sliceModelDataCount = 0;
	}

	public void defrag() { allocator.defrag(); }
}
