package rs117.hd.opengl;

import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opengl.GL31C.*;

public class SharedUniformBuffer extends UniformBuffer {
	public final SharedGLBuffer glBuffer;

	public SharedUniformBuffer(String name, int glUsage, int clUsage) {
		super(new SharedGLBuffer("UBO " + name, GL_UNIFORM_BUFFER, glUsage, clUsage));
		glBuffer = (SharedGLBuffer) super.glBuffer;
	}
}
