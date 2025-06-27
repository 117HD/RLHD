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
	public Property playerX = addProperty(PropertyType.Float, "playerX");
	public Property playerY = addProperty(PropertyType.Float, "playerY");
	public Property playerZ = addProperty(PropertyType.Float, "playerZ");

	// Wind Uniforms
	public Property windDirectionX = addProperty(PropertyType.Float, "windDirectionX");
	public Property windDirectionZ = addProperty(PropertyType.Float, "windDirectionZ");
	public Property windStrength = addProperty(PropertyType.Float, "windStrength");
	public Property windCeiling = addProperty(PropertyType.Float, "windCeiling");
	public Property windOffset = addProperty(PropertyType.Float, "windOffset");
}
