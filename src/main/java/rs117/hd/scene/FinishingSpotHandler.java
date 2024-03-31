package rs117.hd.scene;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.data.WaterType;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ModelHash;

import static rs117.hd.utils.ColorUtils.srgb;
import static rs117.hd.utils.ColorUtils.srgbToPackedHsl;

public class FinishingSpotHandler {


	private static final List<Integer> NPC_IDS = new ArrayList<>(Arrays.asList(
		NpcID.ROD_FISHING_SPOT, NpcID.FISHING_SPOT, NpcID.ROD_FISHING_SPOT_1506,
		NpcID.ROD_FISHING_SPOT_1507, NpcID.ROD_FISHING_SPOT_1508, NpcID.ROD_FISHING_SPOT_1509,
		NpcID.FISHING_SPOT_1510, NpcID.FISHING_SPOT_1511, NpcID.ROD_FISHING_SPOT_1512,
		NpcID.ROD_FISHING_SPOT_1513, NpcID.FISHING_SPOT_1514, NpcID.ROD_FISHING_SPOT_1515,
		NpcID.ROD_FISHING_SPOT_1516, NpcID.FISHING_SPOT_1517, NpcID.FISHING_SPOT_1518,
		NpcID.FISHING_SPOT_1519, NpcID.FISHING_SPOT_1520, NpcID.ROD_FISHING_SPOT_7463,
		NpcID.ROD_FISHING_SPOT_7464, NpcID.ROD_FISHING_SPOT_7468, NpcID.ROD_FISHING_SPOT_3417,
		NpcID.ROD_FISHING_SPOT_3418, NpcID.ROD_FISHING_SPOT_6825, NpcID.ROD_FISHING_SPOT_8524,
		NpcID.FISHING_SPOT_1521, NpcID.FISHING_SPOT_1522,
		NpcID.FISHING_SPOT_1523, NpcID.FISHING_SPOT_1524, NpcID.FISHING_SPOT_1525,
		NpcID.ROD_FISHING_SPOT_1526, NpcID.ROD_FISHING_SPOT_1527, NpcID.FISHING_SPOT_1528,
		NpcID.ROD_FISHING_SPOT_1529, NpcID.FISHING_SPOT_1530, NpcID.ROD_FISHING_SPOT_1531,
		NpcID.FISHING_SPOT_1532, NpcID.FISHING_SPOT_1533, NpcID.FISHING_SPOT_1534,
		NpcID.FISHING_SPOT_1535, NpcID.FISHING_SPOT_1536, NpcID.FISHING_SPOT_1542,
		NpcID.FISHING_SPOT_1544, NpcID.FISHING_SPOT_2146, NpcID.FISHING_SPOT_2653,
		NpcID.FISHING_SPOT_2654, NpcID.FISHING_SPOT_2655, NpcID.FISHING_SPOT_3317,
		NpcID.FISHING_SPOT_3419, NpcID.FISHING_SPOT_3657, NpcID.FISHING_SPOT_3913,
		NpcID.FISHING_SPOT_3914, NpcID.FISHING_SPOT_3915, NpcID.FISHING_SPOT_4079,
		NpcID.FISHING_SPOT_4080, NpcID.FISHING_SPOT_4081, NpcID.FISHING_SPOT_4082,
		NpcID.FISHING_SPOT_4316, NpcID.FISHING_SPOT_4476, NpcID.FISHING_SPOT_4477,
		NpcID.FISHING_SPOT_4710, NpcID.FISHING_SPOT_4711, NpcID.FISHING_SPOT_4712,
		NpcID.FISHING_SPOT_4713, NpcID.FISHING_SPOT_4714, NpcID.FISHING_SPOT_5233,
		NpcID.FISHING_SPOT_5234, NpcID.FISHING_SPOT_5820, NpcID.FISHING_SPOT_5821,
		NpcID.FISHING_SPOT_6731, NpcID.FISHING_SPOT_7155, NpcID.FISHING_SPOT_7199,
		NpcID.FISHING_SPOT_7200, NpcID.FISHING_SPOT_7323, NpcID.FISHING_SPOT_7459,
		NpcID.FISHING_SPOT_7460, NpcID.FISHING_SPOT_7461, NpcID.FISHING_SPOT_7462,
		NpcID.FISHING_SPOT_7465, NpcID.FISHING_SPOT_7466, NpcID.FISHING_SPOT_7467,
		NpcID.FISHING_SPOT_7469, NpcID.FISHING_SPOT_7470, NpcID.FISHING_SPOT_7946,
		NpcID.FISHING_SPOT_7947, NpcID.FISHING_SPOT_8525, NpcID.FISHING_SPOT_8526,
		NpcID.FISHING_SPOT_8527, NpcID.FISHING_SPOT_9171, NpcID.FISHING_SPOT_9172,
		NpcID.FISHING_SPOT_9173, NpcID.FISHING_SPOT_9174, NpcID.FISHING_SPOT_9478,
		NpcID.FISHING_SPOT_12267
	));

	private static final List<Integer> LAVA_SPOTS = List.of(
		NpcID.FISHING_SPOT_4928
	);

	private static final int FISHING_SPOT_MODEL = 41238;

	private static final int LAVA_SPOT_MODEL = 2331;

	private static final int FISHING_SPOT_ANIMATION = 10793;

	private static final int LAVA_SPOT_ANIMATION = 525;

	private static final int LAVA_SPOT_COLOR = srgbToPackedHsl(srgb("#837574"));

	@Inject
	private TileOverrideManager tileOverrideManager;
	@Inject
	private Client client;
	@Inject
	private ModelOverrideManager modelOverrideManager;
	@Inject
	private HdPlugin plugin;
	@Inject
	private FrameTimer frameTimer;

	private final Map<Integer, RuneLiteObject> npcIndexToModel = new HashMap<>();
	private Animation fishingAnimation;
	private Animation lavaSpotAnimation;
	private ModelData lavaSpotModel;

	public void start() {
		NPC_IDS.addAll(LAVA_SPOTS);
		this.fishingAnimation = client.loadAnimation(FISHING_SPOT_ANIMATION);
		this.lavaSpotAnimation = client.loadAnimation(LAVA_SPOT_ANIMATION);
	}

	public void loadModelOverride(boolean hidden) {
		ModelOverride override = new ModelOverride();
		override.hide = hidden;
		NPC_IDS.forEach(npc -> modelOverrideManager.addEntry(ModelHash.TYPE_NPC, npc, override));
	}


	public void updateFishingSpots() {
		if (!plugin.config.fishingSpots()) return;

		frameTimer.begin(Timer.FISHING_SPOTS);
		updateFishingSpotObjects();
		client.getNpcs().forEach(this::spawnFishingSpot);
		frameTimer.end(Timer.FISHING_SPOTS);
	}


	public void spawnFishingSpot(NPC npc) {
		if (!NPC_IDS.contains(npc.getId())) return;

		npcIndexToModel.computeIfAbsent(npc.getIndex(), index -> {
			LocalPoint pos = npc.getLocalLocation();
			return LAVA_SPOTS.contains(npc.getId()) ? createRuneLiteObjectLava(Color.decode("#141414")) : createRuneLiteObjectForWater(pos);
		});
	}

	private RuneLiteObject createRuneLiteObjectForWater(LocalPoint pos) {
		Tile tile = client.getScene().getTiles()[client.getPlane()][pos.getSceneX()][pos.getSceneY()];
		WaterType waterType = tileOverrideManager.getOverride(client.getScene(), tile).waterType;
		int color = waterType.fishingSpotColor;


		RuneLiteObject fishingSpot = client.createRuneLiteObject();
		fishingSpot.setAnimation(fishingAnimation);
		fishingSpot.setDrawFrontTilesFirst(false);
		fishingSpot.setActive(true);
		fishingSpot.setShouldLoop(true);
		ModelData modelData = client.loadModelData(FISHING_SPOT_MODEL).cloneVertices();
		ModelData data = color != 0 ? modelData.cloneColors() : modelData;

		if (color != 0) {
			applyColorToModel(data, color);
		}
		fishingSpot.setModel(data.light());

		return fishingSpot;
	}

	private RuneLiteObject createRuneLiteObjectLava(Color color) {
		RuneLiteObject fishingSpot = client.createRuneLiteObject();
		fishingSpot.setAnimation(lavaSpotAnimation);
		fishingSpot.setDrawFrontTilesFirst(false);
		fishingSpot.setActive(true);
		fishingSpot.setShouldLoop(true);
		ModelData modelData = client.loadModelData(LAVA_SPOT_MODEL).cloneVertices();
		if (color != null) {
			applyColorToModel(modelData, LAVA_SPOT_COLOR);
		}
		fishingSpot.setModel(modelData.light());

		return fishingSpot;
	}

	private void applyColorToModel(ModelData modelData, int color) {
		short recolor = (short) color;
		modelData = modelData.cloneColors();
		Arrays.fill(modelData.getFaceColors(), recolor);
	}

	public void reset() {
		npcIndexToModel.values().forEach(object -> object.setActive(false));
		npcIndexToModel.clear();
	}

	public void updateFishingSpotObjects() {
		Set<Integer> npcIndices = client.getNpcs().stream().map(NPC::getIndex).collect(Collectors.toSet());
		npcIndexToModel.entrySet().removeIf(entry -> {
			if (!npcIndices.contains(entry.getKey())) {
				entry.getValue().setActive(false);
				return true;
			}
			return false;
		});

		// Update the location of active fishing spots to match their corresponding NPC's current position
		npcIndexToModel.forEach((index, runeLiteObject) -> {
			NPC npc = client.getCachedNPCs()[index];
			if (npc != null) {
				LocalPoint localPoint = npc.getLocalLocation();
				runeLiteObject.setLocation(localPoint, client.getPlane());
			}
		});
	}
}
