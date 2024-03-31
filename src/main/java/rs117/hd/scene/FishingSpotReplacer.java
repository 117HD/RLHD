package rs117.hd.scene;

import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPluginConfig;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.model_overrides.ModelOverride;

import static rs117.hd.utils.ColorUtils.hsl;

public class FishingSpotReplacer {
	private static final int FISHING_SPOT_MODEL_ID = 41238;
	private static final int FISHING_SPOT_ANIMATION_ID = 10793;
	private static final int LAVA_SPOT_MODEL_ID = 2331;
	private static final int LAVA_SPOT_ANIMATION_ID = 525;
	private static final int LAVA_SPOT_COLOR = hsl("#837574");

	// @formatter:off
	private static final Set<Integer> FISHING_SPOT_IDS = Set.of(394, 635, 1506, 1507, 1508, 1509, 1510, 1511, 1512, 1513, 1514, 1515, 1516, 1517, 1518, 1519, 1520, 1521, 1522, 1523, 1524, 1525, 1526, 1527, 1528, 1529, 1530, 1531, 1532, 1533, 1534, 1535, 1536, 1542, 1544, 2146, 2653, 2654, 2655, 3317, 3417, 3418, 3419, 3657, 3913, 3914, 3915, 4079, 4080, 4081, 4082, 4316, 4476, 4477, 4710, 4711, 4712, 4713, 4714, 5233, 5234, 5820, 5821, 6731, 6825, 7155, 7199, 7200, 7323, 7459, 7460, 7461, 7462, 7463, 7464, 7465, 7466, 7467, 7468, 7469, 7470, 7946, 7947, 8524, 8525, 8526, 8527, 9171, 9172, 9173, 9174, 9478, 12267);
	private static final Set<Integer> LAVA_FISHING_SPOT_IDS = Set.of(4928);
	// @formatter:on
	private static final Set<Integer> NPC_IDS = Sets.union(FISHING_SPOT_IDS, LAVA_FISHING_SPOT_IDS).immutableCopy();

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPluginConfig config;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private FrameTimer frameTimer;

	private final Map<Integer, RuneLiteObject> npcIndexToModel = new HashMap<>();
	private Animation fishingSpotAnimation;
	private Animation lavaFishingSpotAnimation;

	public void startUp() {
		eventBus.register(this);
		fishingSpotAnimation = client.loadAnimation(FISHING_SPOT_ANIMATION_ID);
		lavaFishingSpotAnimation = client.loadAnimation(LAVA_SPOT_ANIMATION_ID);
	}

	public void shutDown() {
		eventBus.unregister(this);
		despawnRuneLiteObjects();
		fishingSpotAnimation = null;
		lavaFishingSpotAnimation = null;
	}

	public void despawnRuneLiteObjects() {
		npcIndexToModel.values().forEach(rlobj -> rlobj.setActive(false));
		npcIndexToModel.clear();
	}

	public ModelOverride getModelOverride() {
		if (!config.replaceFishingSpots())
			return null;

		ModelOverride override = new ModelOverride();
		override.hide = true;
		override.npcIds = NPC_IDS;
		return override;
	}

	public void update() {
		if (!config.replaceFishingSpots())
			return;

		frameTimer.begin(Timer.REPLACE_FISHING_SPOTS);

		// Despawn fishing spots for inactive NPCs
		Set<Integer> npcIndices = client.getNpcs().stream().map(NPC::getIndex).collect(Collectors.toSet());
		npcIndexToModel.entrySet().removeIf(entry -> {
			if (npcIndices.contains(entry.getKey()))
				return false;
			entry.getValue().setActive(false);
			return true;
		});

		client.getNpcs().forEach(this::spawnFishingSpot);

		// Update the location of active fishing spots to match their corresponding NPC's current position
		npcIndexToModel.forEach((index, runeLiteObject) -> {
			NPC npc = client.getCachedNPCs()[index];
			if (npc != null)
				runeLiteObject.setLocation(npc.getLocalLocation(), client.getPlane());
		});

		frameTimer.end(Timer.REPLACE_FISHING_SPOTS);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned npcSpawned) {
		spawnFishingSpot(npcSpawned.getNpc());
	}

	public void spawnFishingSpot(NPC npc) {
		if (!NPC_IDS.contains(npc.getId()))
			return;

		npcIndexToModel.computeIfAbsent(npc.getIndex(), i -> {
			int modelId = FISHING_SPOT_MODEL_ID;
			Animation animation = fishingSpotAnimation;
			int recolor = -1;

			if (LAVA_FISHING_SPOT_IDS.contains(npc.getId())) {
				modelId = LAVA_SPOT_MODEL_ID;
				animation = lavaFishingSpotAnimation;
				recolor = LAVA_SPOT_COLOR;
			} else {
				var lp = npc.getLocalLocation();
				if (lp.isInScene()) {
					Tile tile = client.getScene().getTiles()[client.getPlane()][lp.getSceneX()][lp.getSceneY()];
					recolor = tileOverrideManager.getOverride(client.getScene(), tile).waterType.fishingSpotRecolor;
				}
			}

			ModelData modelData = client.loadModelData(modelId);
			if (modelData == null)
				return null;

			if (recolor != -1) {
				modelData = modelData.cloneColors();
				Arrays.fill(modelData.getFaceColors(), (short) recolor);
			}

			RuneLiteObject fishingSpot = client.createRuneLiteObject();
			fishingSpot.setAnimation(animation);
			fishingSpot.setDrawFrontTilesFirst(false);
			fishingSpot.setActive(true);
			fishingSpot.setShouldLoop(true);
			fishingSpot.setModel(modelData.light());
			return fishingSpot;
		});
	}
}
