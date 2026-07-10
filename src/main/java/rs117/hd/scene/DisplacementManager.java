package rs117.hd.scene;

import java.util.ArrayList;
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
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.utils.NpcDisplacementCache;

import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class DisplacementManager {
	public static final int MAX_CHARACTER_POSITION_COUNT = 50;

	private static final Comparator<CharacterPositionPair> CHARACTER_POSITION_PAIR_COMPARATOR =
		Comparator.comparingDouble(p -> p.dist);

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

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
		if(!plugin.configCharacterDisplacement)
			return;

		var localPlayer = client.getLocalPlayer();
		if(localPlayer == null)
			return;

		// The local player needs to be added first for distance culling
		var lp = localPlayer.getLocalLocation();
		Model playerModel = localPlayer.getModel();
		if (playerModel != null) {
			WorldViewContext ctx = sceneManager.getContext(localPlayer.getWorldView().getScene());
			if(ctx != null && !sceneManager.isRoot(ctx))
				return;

			addCharacterPosition(lp.getX(), lp.getY(), (int) (Perspective.LOCAL_TILE_SIZE * 1.33f));
		}
	}

	public void addCharacterPosition(Scene scene, int x, int z, Renderable renderable, Model m) {
		if(!plugin.configCharacterDisplacement || !(renderable instanceof Actor))
			return;

		WorldViewContext ctx = sceneManager.getContext(scene);
		if(ctx != null && !sceneManager.isRoot(ctx))
			return;

		if (plugin.enableDetailedTimers)
			frameTimer.begin(Timer.CHARACTER_DISPLACEMENT);

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
				addCharacterPosition(x, z, displacementRadius);
			}
		} else if (renderable instanceof Player && renderable != client.getLocalPlayer()) {
			addCharacterPosition(x, z, (int) (Perspective.LOCAL_TILE_SIZE * 1.33f));
		}

		if (plugin.enableDetailedTimers)
			frameTimer.end(Timer.CHARACTER_DISPLACEMENT);
	}

	public void addCharacterPosition(int localX, int localZ, int modelRadius) {
		if(!plugin.configCharacterDisplacement)
			return;

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
	}

	public void writeCharacterPositions(Property[] characterPositions, Property characterPositionCount) {
		for (int i = 0; i < writtenCharacterPositions; i++) {
			CharacterPositionPair pair = characterPositionsPairs.get(i);
			pair.dist = Float.MAX_VALUE;

			if (i < characterPositions.length)
				characterPositions[i].set(pair.x, pair.z, pair.radius);
		}
		characterPositionCount.set(min(writtenCharacterPositions, characterPositions.length));
		writtenCharacterPositions = 0;
	}

	private static class CharacterPositionPair {
		public float x;
		public float z;
		public float radius;
		public float dist = Float.MAX_VALUE;
	}
}
