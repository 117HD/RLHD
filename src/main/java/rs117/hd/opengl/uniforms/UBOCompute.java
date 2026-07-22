package rs117.hd.opengl.uniforms;

import javax.inject.Inject;
import rs117.hd.scene.DisplacementManager;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.scene.DisplacementManager.MAX_CHARACTER_POSITION_COUNT;

public class UBOCompute extends UniformBuffer<SharedGLBuffer> {
	// Camera uniforms
	public Property yaw = addProperty(PropertyType.Float, "yaw");
	public Property pitch = addProperty(PropertyType.Float, "pitch");
	public Property centerX = addProperty(PropertyType.Int, "centerX");
	public Property centerY = addProperty(PropertyType.Int, "centerY");
	public Property zoom = addProperty(PropertyType.Int, "zoom");
	public Property cameraX = addProperty(PropertyType.Float, "cameraX");
	public Property cameraY = addProperty(PropertyType.Float, "cameraY");
	public Property cameraZ = addProperty(PropertyType.Float, "cameraZ");

	// Wind uniforms
	public Property windDirectionX = addProperty(PropertyType.Float, "windDirectionX");
	public Property windDirectionZ = addProperty(PropertyType.Float, "windDirectionZ");
	public Property windStrength = addProperty(PropertyType.Float, "windStrength");
	public Property windCeiling = addProperty(PropertyType.Float, "windCeiling");
	public Property windOffset = addProperty(PropertyType.Float, "windOffset");

	private final Property characterPositionCount = addProperty(PropertyType.Int, "characterPositionCount");
	private final Property[] characterPositions = addPropertyArray(PropertyType.FVec3, "characterPositions", MAX_CHARACTER_POSITION_COUNT);

	@Inject
	private DisplacementManager displacementManager;

	public UBOCompute() {
		super(GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
	}

	@Override
	protected void preUpload() {
		displacementManager.writeCharacterPositions(characterPositions, characterPositionCount);
	}
}
