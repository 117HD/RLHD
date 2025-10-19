package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;

public class UBOCommandBuffer extends UniformBuffer<GLBuffer> {
	public UBOCommandBuffer() {
		super(GL_DYNAMIC_DRAW);
	}

	public Property worldViewId = addProperty(PropertyType.Int, "worldViewId");
	public Property sceneBase = addProperty(PropertyType.IVec3, "sceneBase");
}
