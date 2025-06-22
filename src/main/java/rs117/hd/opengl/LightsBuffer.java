package rs117.hd.opengl;

public class LightsBuffer extends UniformBuffer {
	public LightsBuffer() {
		super("Lights");
	}

	public LightStruct[] lights = addStructs(new LightStruct[100], LightStruct::new);

	public static class LightStruct extends UniformBuffer.StructProperty {
		public Property position = addProperty(PropertyType.FVec4, "Position");
		public Property color = addProperty(PropertyType.FVec3, "Color");
	}
}
