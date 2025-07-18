package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class SharedUniformBuffer extends UniformBuffer {
	public final SharedGLBuffer glBuffer;

	public SharedUniformBuffer(String name, String uniformBlockName, int glUsage, int clUsage) {
		super(new SharedGLBuffer("UBO " + name, GL_UNIFORM_BUFFER, glUsage, clUsage), uniformBlockName);
		glBuffer = (SharedGLBuffer) super.glBuffer;
	}
}
