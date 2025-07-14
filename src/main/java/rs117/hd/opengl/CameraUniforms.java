package rs117.hd.opengl;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL31C.*;

public class CameraUniforms extends SharedUniformBuffer {
	public CameraUniforms() {
		super("Camera", GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
	}

	public Property yaw = addProperty(PropertyType.Float, "yaw");
	public Property pitch = addProperty(PropertyType.Float, "pitch");
	public Property centerX = addProperty(PropertyType.Int, "centerX");
	public Property centerY = addProperty(PropertyType.Int, "centerY");
	public Property zoom = addProperty(PropertyType.Int, "zoom");
	public Property cameraX = addProperty(PropertyType.Float, "cameraX");
	public Property cameraY = addProperty(PropertyType.Float, "cameraY");
	public Property cameraZ = addProperty(PropertyType.Float, "cameraZ");
}
