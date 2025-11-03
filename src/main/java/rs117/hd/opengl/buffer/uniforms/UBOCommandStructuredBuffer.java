package rs117.hd.opengl.buffer.uniforms;

import rs117.hd.opengl.buffer.UniformStructuredBuffer;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOCommandStructuredBuffer extends UniformStructuredBuffer<GLBuffer> {
	public UBOCommandStructuredBuffer() {
		super(GL_STREAM_DRAW);
	}

	public Property worldViewIndex = addProperty(PropertyType.Int, "worldViewIndex");
	public Property sceneBase = addProperty(PropertyType.IVec3, "sceneBase");
}
