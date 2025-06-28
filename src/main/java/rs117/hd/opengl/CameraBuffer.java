package rs117.hd.opengl;

import java.util.ArrayList;

import static org.lwjgl.opengl.GL15.*;

public class CameraBuffer extends UniformBuffer {

	private static class CharacterPositionPair {
		public float x;
		public float z;
		public float dist = Float.MAX_VALUE;

		public float getDistance() {
			return dist;
		}
	}

	public CameraBuffer() {
		super("Camera", GL_DYNAMIC_DRAW);

		for(int i = 0; i < characterPositions.length; i++) {
			characterPositionsPairs.add(new CharacterPositionPair());
		}
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
	public Property windDirectionX = addProperty(PropertyType.Float, "windDirectionX");
	public Property windDirectionZ = addProperty(PropertyType.Float, "windDirectionZ");
	public Property windStrength = addProperty(PropertyType.Float, "windStrength");
	public Property windCeiling = addProperty(PropertyType.Float, "windCeiling");
	public Property windOffset = addProperty(PropertyType.Float, "windOffset");

	private final Property characterPositionCount = addProperty(PropertyType.Int, "characterPositionCount");
	private final Property[] characterPositions = addProperties(PropertyType.FVec2, 50, "characterPositions");

	private int writtenCharacterPositions = 0;
	private final ArrayList<CharacterPositionPair> characterPositionsPairs = new ArrayList<>();
	private float playerPosX, playerPosZ;

	public void addCharacterPosition(float x, float z) {
		if(writtenCharacterPositions >= characterPositions.length){
			return; // We've exceeded the count
		}

		int writeIndex = writtenCharacterPositions;
		CharacterPositionPair pair = characterPositionsPairs.get(writeIndex);
		characterPositionsPairs.remove(writeIndex);

		if(writeIndex == 0) {
			playerPosX = pair.x = x;
			playerPosZ = pair.z = z;
			pair.dist = 0.0f;
		} else {
			pair.x = x;
			pair.z = z;
			pair.dist = Math.abs(playerPosX - x) + Math.abs(playerPosZ - z);

			for(int i = 0; i < writeIndex; i++) {
				if(characterPositionsPairs.get(i).dist >= pair.dist){
					writeIndex = i + 1;
					break;
				}
			}
		}

		characterPositionsPairs.add(writeIndex, pair);
		writtenCharacterPositions++;
	}

	@Override
	protected void preupload() {
		for(int i = 0; i < writtenCharacterPositions; i++) {
			CharacterPositionPair pair = characterPositionsPairs.get(i);
			pair.dist = Float.MAX_VALUE;

			characterPositions[i].set(pair.x, pair.z);
		}
		characterPositionCount.set(writtenCharacterPositions);
		writtenCharacterPositions = 0;
	}
}
