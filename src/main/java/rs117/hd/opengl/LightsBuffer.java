package rs117.hd.opengl;

import rs117.hd.config.MaxDynamicLights;

public class LightsBuffer extends UniformBuffer {
	public LightsBuffer() {
		super("Lights");
	}

	public LightStruct[] lights = addStructs(new LightStruct[MaxDynamicLights.MAX_LIGHTS], LightStruct::new);

	public static class LightStruct extends UniformBuffer.StructProperty {
		public Property position = addProperty(PropertyType.FVec4, "position");
		public Property color = addProperty(PropertyType.FVec3, "color");
	}
}
