package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOTiledLights extends UniformBuffer<GLBuffer> {
	public UBOTiledLights() {
		super(GL_STATIC_DRAW);
	}

	public Property tileCountX = addProperty(PropertyType.Int, "tileCountX");
	public Property tileCountY = addProperty(PropertyType.Int, "tileCountY");
	public Property pointLightsCount = addProperty(PropertyType.Int, "pointLightsCount");
	public Property cameraPos = addProperty(PropertyType.FVec3, "cameraPos");
	public Property invProjectionMatrix = addProperty(PropertyType.Mat4, "invProjectionMatrix");
}
