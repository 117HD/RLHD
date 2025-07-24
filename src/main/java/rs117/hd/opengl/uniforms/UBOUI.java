package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOUI extends UniformBuffer<GLBuffer> {
	public UBOUI() {
		super(GL_DYNAMIC_DRAW);
	}

	public Property sourceDimensions = addProperty(PropertyType.IVec2, "sourceDimensions");
	public Property targetDimensions = addProperty(PropertyType.IVec2, "targetDimensions");
	public Property alphaOverlay = addProperty(PropertyType.FVec4, "alphaOverlay");
}
