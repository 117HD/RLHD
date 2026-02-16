package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;

public class UBOOcclusion extends UniformBuffer<GLBuffer> {
	public static final int MAX_AABBS = 2000;

	public UBOOcclusion() {
		super(GL_DYNAMIC_DRAW);
	}

	public final Property[] aabbs = addPropertyArray(PropertyType.FVec3, "aabbs", MAX_AABBS * 2);
}
