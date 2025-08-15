package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOLights extends UniformBuffer<GLBuffer> {

	public static final int MAX_LIGHTS = 1000; // Struct is 64 Bytes, UBO Max size is 64 KB
	public final Property attenuationFactor;
	private final LightStruct[] lights;
	private final Property[] lightPositions;

	public UBOLights(boolean isCullingUBO) {
		super(GL_DYNAMIC_DRAW);
		if (isCullingUBO) {
			attenuationFactor = null;
			lights = null;
			lightPositions = addPropertyArray(PropertyType.FVec4, "lightPositions", MAX_LIGHTS);
		} else {
			attenuationFactor = addProperty(PropertyType.Float, "attenuationFactor");
			lights = addStructs(new LightStruct[MAX_LIGHTS], LightStruct::new);
			lightPositions = null;
		}
	}

	@Override
	public String getUniformBlockName() {
		return lights != null ? "UBOLights" : "UBOLightsCulling";
	}

	public void setLight(int lightIdx, float[] position, float[] color) {
		if (lightIdx >= 0 && lightIdx < MAX_LIGHTS) {
			if(lights != null) {
				var struct = lights[lightIdx];
				struct.position.set(position);
				struct.color.set(color);
			} else {
				lightPositions[lightIdx].set(position);
			}
		}
	}

	public static class LightStruct extends UniformBuffer.StructProperty {
		public Property position = addProperty(PropertyType.FVec4, "position");
		public Property color = addProperty(PropertyType.FVec4, "color");
	}
}
