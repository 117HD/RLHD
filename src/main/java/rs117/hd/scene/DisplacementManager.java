package rs117.hd.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.opengl.uniforms.UniformBuffer.Property;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.scene.areas.AABB;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.collections.IntHashSet;

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
	private SceneManager sceneManager;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	private final float[] cornerX = new float[4];
	private final float[] cornerZ = new float[4];
	private final float[] projected = new float[4];

	private final float[] boatData = new float[MAX_BOAT_COUNT * 8];
	private int writtenBoats;

	public final IntHashSet boatIds = new IntHashSet();

	private final ArrayList<CharacterPositionPair> characterPositionsPairs = new ArrayList<>(MAX_CHARACTER_POSITION_COUNT);
	private int writtenCharacterPositions;
	private float playerPosX, playerPosZ;

	public void initialize() {
		try (var handle = gamevalManager.obtainHandle()) {
			for(var entry : handle.getObjects().entrySet()) {
				if(entry.getKey().contains("BOAT"))
					boatIds.add(entry.getValue());
			}
		}
	}

	private CharacterPositionPair getCharacterPositionPair() {
		if (writtenCharacterPositions >= characterPositionsPairs.size()) {
			CharacterPositionPair newPair = new CharacterPositionPair();
			characterPositionsPairs.add(newPair);
			return newPair;
		}

		return characterPositionsPairs.get(writtenCharacterPositions);
	}

	public void addBoat(UBOWorldViews.WorldViewStruct worldViewStruct, AABB aabb) {
		if (writtenBoats >= MAX_BOAT_COUNT)
			return;

		cornerX[0] = aabb.minX;
		cornerZ[0] = aabb.minZ;

		cornerX[1] = aabb.maxX;
		cornerZ[1] = aabb.minZ;

		cornerX[2] = aabb.maxX;
		cornerZ[2] = aabb.maxZ;

		cornerX[3] = aabb.minX;
		cornerZ[3] = aabb.maxZ;

		int index = writtenBoats * 8;
		float minY = Float.POSITIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;

		for (int i = 0; i < 4; i++) {
			// Bottom corner
			worldViewStruct.project(vec4(projected, cornerX[i], aabb.minY, cornerZ[i], 1.0f));

			if (i < 3) {
				boatData[index + i * 2]     = projected[0];
				boatData[index + i * 2 + 1] = projected[2];
			}

			if (projected[1] < minY) minY = projected[1];
			if (projected[1] > maxY) maxY = projected[1];

			// Top corner
			worldViewStruct.project(vec4(projected, cornerX[i], aabb.maxY, cornerZ[i], 1.0f));

			if (projected[1] < minY) minY = projected[1];
			if (projected[1] > maxY) maxY = projected[1];
		}

		// Store vertical thickness
		boatData[index + 6] = maxY - minY;
		boatData[index + 7] = 0.0f;

		writtenBoats++;
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

	public void writeBoatAABBs(Property[] boatData, Property boatCount) {
		final int count = min(writtenBoats, MAX_BOAT_COUNT);
		boatCount.set(count);
		writtenBoats = 0;

		for(int i = 0; i < count; i++) {
			boatData[i * 2].set(this.boatData, i * 8, 4);
			boatData[i * 2 + 1].set(this.boatData, i * 8 + 4, 4);
		}
	}

	public void writeCharacterPositions(Property[] characterPositions, Property characterPositionCount) {
		final int count = min(writtenCharacterPositions, characterPositions.length);
		characterPositionCount.set(count);
		writtenCharacterPositions = 0;

		for (int i = 0; i < count; i++) {
			CharacterPositionPair pair = characterPositionsPairs.get(i);
			pair.dist = Float.MAX_VALUE;

			if (i < characterPositions.length)
				characterPositions[i].set(pair.x, pair.z, pair.radius);
		}
	}

	private static class CharacterPositionPair {
		public float x;
		public float z;
		public float radius;
		public float dist = Float.MAX_VALUE;
	}
}
