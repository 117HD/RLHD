package rs117.hd.scene;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UniformBuffer.Property;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.NpcDisplacementCache;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.HDUtils.EXTENDED_SCENE_OFFSET;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class DisplacementManager {
	public static final int MAX_CHARACTER_POSITION_COUNT = 50;
	public static final int MAX_BOAT_COUNT = 20;

	private static final Comparator<CharacterPositionPair> CHARACTER_POSITION_PAIR_COMPARATOR =
		Comparator.comparingDouble(p -> p.dist);

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	private final boolean[] groundItems = new boolean[EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];

	private final ArrayList<CharacterPositionPair> characterPositionsPairs = new ArrayList<>(MAX_CHARACTER_POSITION_COUNT);
	private int writtenCharacterPositions;
	private float playerPosX, playerPosZ;

	private CharacterPositionPair getCharacterPositionPair() {
		if (writtenCharacterPositions >= characterPositionsPairs.size()) {
			CharacterPositionPair newPair = new CharacterPositionPair();
			characterPositionsPairs.add(newPair);
			return newPair;
		}

		return characterPositionsPairs.get(writtenCharacterPositions);
	}

	public void addLocalPlayer() {
		if (!plugin.configCharacterDisplacement)
			return;

		var localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
			return;

		// The local player needs to be added first for distance culling
		var lp = localPlayer.getLocalLocation();
		if (lp != null)
			addCharacterPosition(lp.getX(), lp.getY(), (int) (LOCAL_TILE_SIZE * 1.33f), 1.0f);
	}

	public void addCharacterPosition(Scene scene, int x, int z, Renderable renderable, Model m) {
		if (!plugin.configCharacterDisplacement)
			return;

		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
			return;

		if (renderable instanceof NPC) {
			var npc = (NPC) renderable;
			var entry = npcDisplacementCache.get(npc);
			if (entry.canDisplace) {
				int displacementRadius = entry.idleRadius;
				if (displacementRadius == -1) {
					displacementRadius = m.getXYZMag(); // Fallback to model radius since we don't know the idle radius yet
					if (npc.getIdlePoseAnimation() == npc.getPoseAnimation() && npc.getAnimation() == -1) {
						displacementRadius *= 2; // Double the idle radius, so that it fits most other animations
						entry.idleRadius = displacementRadius;
					}
				}
				addCharacterPosition(x, z, displacementRadius, 1.0f);
			}
		} else if (renderable instanceof Player && renderable != client.getLocalPlayer()) {
			addCharacterPosition(x, z, (int) (LOCAL_TILE_SIZE * 1.33f), 1.0f);
		} else if (renderable instanceof TileItem) {
			int tileExX = clamp(EXTENDED_SCENE_OFFSET + (x / 128), 0, EXTENDED_SCENE_SIZE - 1);
			int tileExY = clamp(EXTENDED_SCENE_OFFSET + (z / 128), 0, EXTENDED_SCENE_SIZE - 1);
			final int tileIdx = tileExX * EXTENDED_SCENE_SIZE + tileExY;
			if (!groundItems[tileIdx]) {
				groundItems[tileIdx] = true;
				addCharacterPosition(x, z, (int) (LOCAL_TILE_SIZE * 0.5f), 4.0f);
			}
		}
	}

	public void addCharacterPosition(int localX, int localZ, int modelRadius, float strength) {
		if (!plugin.configCharacterDisplacement)
			return;

		if (plugin.enableDetailedTimers)
			frameTimer.begin(Timer.CHARACTER_DISPLACEMENT);

		int writeIndex = writtenCharacterPositions;
		CharacterPositionPair pair = getCharacterPositionPair();
		characterPositionsPairs.remove(writeIndex);

		pair.x = localX;
		pair.z = localZ;
		pair.radius = modelRadius * 1.25f;
		pair.strength = strength;

		if (writeIndex == 0) {
			playerPosX = pair.x;
			playerPosZ = pair.z;
			pair.dist = 0.0f;
		} else {
			pair.dist = abs(playerPosX - pair.x) + abs(playerPosZ - pair.z);

			if (writeIndex > 1) {
				int index = Collections.binarySearch(
					characterPositionsPairs.subList(1, writeIndex),
					pair,
					CHARACTER_POSITION_PAIR_COMPARATOR
				);

				writeIndex = index >= 0 ? index : -index - 1;
			}
		}

		characterPositionsPairs.add(writeIndex, pair);
		writtenCharacterPositions++;

		if (plugin.enableDetailedTimers)
			frameTimer.end(Timer.CHARACTER_DISPLACEMENT);
	}

	public void writeCharacterPositions(Property[] characterPositions, Property characterPositionCount) {
		final int count = min(writtenCharacterPositions, characterPositions.length);
		characterPositionCount.set(count);
		writtenCharacterPositions = 0;

		for (int i = 0; i < count; i++) {
			CharacterPositionPair pair = characterPositionsPairs.get(i);
			pair.dist = Float.MAX_VALUE;

			if (i < characterPositions.length)
				characterPositions[i].set(pair.x, pair.z, pair.radius, pair.strength);
		}

		Arrays.fill(groundItems, false);
	}

	private static class CharacterPositionPair {
		public float x;
		public float z;
		public float radius;
		public float strength;
		public float dist = Float.MAX_VALUE;
	}
}
