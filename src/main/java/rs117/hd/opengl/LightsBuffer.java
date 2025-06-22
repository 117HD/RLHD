package rs117.hd.opengl;

import java.util.Arrays;

public class LightsBuffer extends UniformBuffer {
	public LightsBuffer() {
		super("Lights UBO");
	}

	public LightStruct[] Lights = AddStructs(new LightStruct[100], LightStruct::new);

	public class LightStruct extends UniformBuffer.StructProperty {
		public Property Position = AddProperty(PropertyType.FVec4, "Position");
		public Property Color = AddProperty(PropertyType.FVec3, "Color");
	}
}
