package rs117.hd.scene;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.data.WaterType;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ModelHash;

public class FinishingSpotHandler {

	private static final List<Integer> NPC_IDS = Arrays.asList(
		394, 635, 1506, 1507, 1508, 1509, 1510, 1511, 1512, 1513, 1514, 1515, 1516, 1517, 1518, 1519, 1520,
		1521, 1522, 1523, 1524, 1525, 1526, 1527, 1528, 1529, 1530, 1531, 1532, 1533, 1534, 1535, 1536, 1542,
		1544, 2146, 2653, 2654, 2655, 3317, 3417, 3418, 3419, 3657, 3913, 3914, 3915, 4079, 4080, 4081, 4082,
		4316, 4476, 4477, 4710, 4711, 4712, 4713, 4714, 5233, 5234, 5820, 5821, 6731, 6825, 7155, 7199, 7200,
		7323, 7459, 7460, 7461, 7462, 7463, 7464, 7465, 7466, 7467, 7468, 7469, 7470, 7946, 7947, 8524, 8525,
		8526, 8527, 9171, 9172, 9173, 9174, 9478, 12267
	);
	private static final int FISHING_SPOT_MODEL = 41238;
	private static final int FISHING_SPOT_ANIMATION = 10793;

	private Animation fishingAnimation;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private Client client;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private HdPlugin plugin;

	private final Map<Integer, RuneLiteObject> npcIndexToModel = new HashMap<>();
	public boolean respawn = false;

	public void start() {
		this.fishingAnimation = client.loadAnimation(FISHING_SPOT_ANIMATION);
	}

	public void loadModelOverride(boolean hidden) {
		ModelOverride override = new ModelOverride();
		override.hide = hidden;
		NPC_IDS.forEach(npc -> modelOverrideManager.addEntry(ModelHash.TYPE_NPC, npc, override));
	}

	public void spawnAllFishingSpots() {
		if (!plugin.config.fishingSpots()) {
			return;
		}

		if (!respawn) {
			return;
		}

		reset();
		client.getNpcs().forEach(this::spawnFishingSpot);
		respawn = false;
	}

	public void spawnFishingSpot(NPC npc) {
		if (!plugin.config.fishingSpots()) {
			return;
		}
		if (!NPC_IDS.contains(npc.getId())) {
			return;
		}

		LocalPoint pos = npc.getLocalLocation();
		Tile tile = client.getScene().getTiles()[npc.getWorldLocation().getPlane()][pos.getSceneX()][pos.getSceneY()];
		WaterType waterType = tileOverrideManager.getOverride(client.getScene(), tile).waterType;

		RuneLiteObject fishingSpot = createRuneLiteObject(waterType.fishingColor);
		fishingSpot.setLocation(npc.getLocalLocation(), 0);
		npcIndexToModel.put(npc.getIndex(), fishingSpot);
	}

	private RuneLiteObject createRuneLiteObject(Color color) {
		RuneLiteObject fishingSpot = client.createRuneLiteObject();
		fishingSpot.setAnimation(fishingAnimation);
		fishingSpot.setDrawFrontTilesFirst(false);
		fishingSpot.setActive(true);
		fishingSpot.setShouldLoop(true);
		ModelData modelData = client.loadModelData(FISHING_SPOT_MODEL).cloneVertices();
		ModelData data = color != null ? modelData.cloneColors() : modelData;
		if (color != null) {
			applyColorToModel(data, color);
		}
		fishingSpot.setModel(data.light());

		return fishingSpot;
	}

	private void applyColorToModel(ModelData modelData, Color color) {
		short recolor = JagexColor.rgbToHSL(color.getRGB(), 100);
		for (int i = 0; i < modelData.getFaceColors().length; i++) {
			modelData.recolor(modelData.getFaceColors()[i], recolor);
		}
	}

	public void reset() {
		npcIndexToModel.values().forEach(object -> object.setActive(false));
		npcIndexToModel.clear();
	}

	public void updateFishingSpotObjects() {
		npcIndexToModel.forEach((index, object) -> client.getNpcs().stream()
			.filter(npc -> npc.getIndex() == index)
			.findFirst()
			.ifPresentOrElse(npc -> object.setLocation(npc.getLocalLocation(), 0), () -> npcIndexToModel.remove(index)));
	}
}