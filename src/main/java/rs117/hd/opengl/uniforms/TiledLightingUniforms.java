package rs117.hd.opengl.uniforms;

import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;

public class TiledLightingUniforms extends UniformBuffer {
	public TiledLightingUniforms() {
		super("TiledLights", "TiledLightsUniforms", GL_STATIC_DRAW);
	}

	public Property tileCountX = addProperty(PropertyType.Int, "tileCountX");
	public Property tileCountY = addProperty(PropertyType.Int, "tileCountY");
	public Property pointLightsCount = addProperty(PropertyType.Int, "pointLightsCount");
	public Property cameraPos = addProperty(PropertyType.FVec3, "cameraPos");
	public Property invProjectionMatrix = addProperty(PropertyType.Mat4, "invProjectionMatrix");
}
