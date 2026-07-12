package rs117.hd.opengl.uniforms;

import javax.inject.Inject;
import rs117.hd.scene.DisplacementManager;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static rs117.hd.scene.DisplacementManager.MAX_BOAT_COUNT;
import static rs117.hd.scene.DisplacementManager.MAX_CHARACTER_POSITION_COUNT;

public class UBODisplacement extends UniformBuffer<GLBuffer> {
	public Property windDirectionX = addProperty(PropertyType.Float, "windDirectionX");
	public Property windDirectionZ = addProperty(PropertyType.Float, "windDirectionZ");
	public Property windStrength = addProperty(PropertyType.Float, "windStrength");
	public Property windCeiling = addProperty(PropertyType.Float, "windCeiling");
	public Property windOffset = addProperty(PropertyType.Float, "windOffset");

	private final Property characterPositionCount = addProperty(PropertyType.Int, "characterPositionCount");
	private final Property boatAABBCount = addProperty(PropertyType.Int, "boatCount");

	private final Property[] characterPositions = addPropertyArray(PropertyType.FVec3, "characterPositions", MAX_CHARACTER_POSITION_COUNT);
	private final Property[] boatData = addPropertyArray(PropertyType.FVec4, "boatData", MAX_BOAT_COUNT * 2);

	@Inject
	private DisplacementManager displacementManager;

	public UBODisplacement() {
		super(GL_DYNAMIC_DRAW);
	}

	@Override
	protected void preUpload() {
		displacementManager.writeCharacterPositions(characterPositions, characterPositionCount);
		displacementManager.writeBoatAABBs(boatData, boatAABBCount);
	}
}
