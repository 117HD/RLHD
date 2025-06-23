package rs117.hd.opengl;

import static org.lwjgl.opengl.GL15.*;

public class CameraBuffer extends UniformBuffer {
	public CameraBuffer() {
		super("Camera", GL_DYNAMIC_DRAW);
	}

	public Property yaw = addProperty(PropertyType.Float, "yaw");
	public Property pitch = addProperty(PropertyType.Float, "pitch");
	public Property centerX = addProperty(PropertyType.Int, "centerX");
	public Property centerY = addProperty(PropertyType.Int, "centerY");
	public Property zoom = addProperty(PropertyType.Int, "zoom");
	public Property cameraX = addProperty(PropertyType.Float, "cameraX");
	public Property cameraY = addProperty(PropertyType.Float, "cameraY");
	public Property cameraZ = addProperty(PropertyType.Float, "cameraZ");

	// Wind Uniforms
	public Property globalWindDirection = addProperty(PropertyType.FVec3, "globalWindDirection");
	public Property windSpeed = addProperty(PropertyType.Float, "windSpeed");
	public Property windStrength = addProperty(PropertyType.Float, "windStrength");
	public Property elapsedTime = addProperty(PropertyType.Float, "elapsedTime");
}
