package rs117.hd.opengl.buffer.storage;

import rs117.hd.opengl.buffer.TextureStructuredBuffer;

public class TBOModelData extends TextureStructuredBuffer {

	public static class ModelData extends StructProperty {
		public final Property value = addProperty(PropertyType.Float, "value");
	}

	public ModelData[] modelData = new ModelData[10];
	public int[] freeSlots = new int[10];


}
