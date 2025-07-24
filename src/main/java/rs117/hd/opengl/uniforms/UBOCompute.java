package rs117.hd.opengl.uniforms;

import java.util.ArrayList;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL33C.*;

public class UBOCompute extends UniformBuffer<SharedGLBuffer> {
	public static final int MAX_CHARACTER_POSITION_COUNT = 50;

	// Camera uniforms
	public Property yaw = addProperty(PropertyType.Float, "yaw");
	public Property pitch = addProperty(PropertyType.Float, "pitch");
	public Property centerX = addProperty(PropertyType.Int, "centerX");
	public Property centerY = addProperty(PropertyType.Int, "centerY");
	public Property zoom = addProperty(PropertyType.Int, "zoom");
	public Property cameraPos = addProperty(PropertyType.FVec3, "cameraPos");

	// Wind uniforms
	public Property windDirectionX = addProperty(PropertyType.Float, "windDirectionX");
	public Property windDirectionZ = addProperty(PropertyType.Float, "windDirectionZ");
	public Property windStrength = addProperty(PropertyType.Float, "windStrength");
	public Property windCeiling = addProperty(PropertyType.Float, "windCeiling");
	public Property windOffset = addProperty(PropertyType.Float, "windOffset");

	private final Property characterPositionCount = addProperty(PropertyType.Int, "characterPositionCount");
	private final Property[] characterPositions = addPropertyArray(PropertyType.FVec3, "characterPositions", MAX_CHARACTER_POSITION_COUNT);

	private final ArrayList<CharacterPositionPair> characterPositionsPairs = new ArrayList<>(characterPositions.length);
	private int writtenCharacterPositions;
	private float playerPosX, playerPosZ;

	private static class CharacterPositionPair {
		public float x;
		public float z;
		public float radius;
		public float dist = Float.MAX_VALUE;
	}

	public UBOCompute() {
		super(GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
	}

	private CharacterPositionPair getCharacterPositionPair() {
		if (writtenCharacterPositions >= characterPositionsPairs.size()) {
			CharacterPositionPair newPair = new CharacterPositionPair();
			characterPositionsPairs.add(newPair);
			return newPair;
		}

		return characterPositionsPairs.get(writtenCharacterPositions);
	}

	public void addCharacterPosition(int localX, int localZ, int modelRadius) {
		int writeIndex = writtenCharacterPositions;
		CharacterPositionPair pair = getCharacterPositionPair();
		characterPositionsPairs.remove(writeIndex);

		pair.x = localX;
		pair.z = localZ;
		pair.radius = modelRadius * 1.25f;

		if (writeIndex == 0) {
			playerPosX = pair.x;
			playerPosZ = pair.z;
			pair.dist = 0.0f;
		} else {
			pair.dist = Math.abs(playerPosX - pair.x) + Math.abs(playerPosZ - pair.z);

			for (int i = 0; i < writeIndex; i++) {
				if (characterPositionsPairs.get(i).dist >= pair.dist) {
					writeIndex = i;
					break;
				}
			}
		}

		characterPositionsPairs.add(writeIndex, pair);
		writtenCharacterPositions++;
	}

	@Override
	protected void preUpload() {
		for (int i = 0; i < writtenCharacterPositions; i++) {
			CharacterPositionPair pair = characterPositionsPairs.get(i);
			pair.dist = Float.MAX_VALUE;

			if (i < characterPositions.length)
				characterPositions[i].set(pair.x, pair.z, pair.radius);
		}
		characterPositionCount.set(Math.min(writtenCharacterPositions, characterPositions.length));
		writtenCharacterPositions = 0;
	}
}
