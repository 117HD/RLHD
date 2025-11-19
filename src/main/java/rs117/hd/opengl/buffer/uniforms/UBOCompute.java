package rs117.hd.opengl.buffer.uniforms;

import rs117.hd.opengl.buffer.UniformStructuredBuffer;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL33C.*;

public class UBOCompute extends UniformStructuredBuffer<SharedGLBuffer> {
	// Camera uniforms
	public Property yaw = addProperty(PropertyType.Float, "yaw");
	public Property pitch = addProperty(PropertyType.Float, "pitch");
	public Property centerX = addProperty(PropertyType.Int, "centerX");
	public Property centerY = addProperty(PropertyType.Int, "centerY");
	public Property zoom = addProperty(PropertyType.Int, "zoom");
	public Property cameraX = addProperty(PropertyType.Float, "cameraX");
	public Property cameraY = addProperty(PropertyType.Float, "cameraY");
	public Property cameraZ = addProperty(PropertyType.Float, "cameraZ");

	public UBOCompute() {
		super(GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
	}
}
