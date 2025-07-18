package rs117.hd.opengl.uniforms;

import rs117.hd.config.MaxDynamicLights;

import static org.lwjgl.opengl.GL33C.*;

public class LightUniforms extends UniformBuffer {
	public LightUniforms() {
		super("Lights", "PointLightUniforms", GL_DYNAMIC_DRAW);
	}

	public LightStruct[] lights = addStructs(new LightStruct[MaxDynamicLights.MAX_LIGHTS], LightStruct::new);

	public static class LightStruct extends UniformBuffer.StructProperty {
		public Property position = addProperty(PropertyType.FVec4, "position");
		public Property color = addProperty(PropertyType.FVec3, "color");
	}
}
