package rs117.hd.opengl;

public class CameraBuffer extends UniformBuffer {

	public CameraBuffer() {
		super("Camera UBO");
	}

	public Property CameraYaw = AddProperty(PropertyType.Float, "CameraYaw");
	public Property CameraPitch = AddProperty(PropertyType.Float, "cameraPitch");
	public Property CenterX = AddProperty(PropertyType.Int, "centerX");
	public Property CenterY = AddProperty(PropertyType.Int, "centerY");
	public Property Zoom = AddProperty(PropertyType.Int, "zoom");
	public Property CameraX = AddProperty(PropertyType.Float, "CameraX");
	public Property CameraY = AddProperty(PropertyType.Float, "CameraY");
	public Property CameraZ = AddProperty(PropertyType.Float, "CameraZ");
}