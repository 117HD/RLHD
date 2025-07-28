package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOLights extends UniformBuffer<GLBuffer> {
	public UBOLights() {
		super(GL_DYNAMIC_DRAW);
	}

	public static final int MAX_LIGHTS = 1000; // Struct is 64 Bytes, UBO Max size is 64 KB
	public LightStruct[] lights = addStructs(new LightStruct[MAX_LIGHTS], LightStruct::new);

	public static class LightStruct extends UniformBuffer.StructProperty {
		public Property position = addProperty(PropertyType.FVec4, "position");
		public Property color = addProperty(PropertyType.FVec4, "color");
	}
}
