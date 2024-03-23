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
import net.runelite.client.ui.overlay.Overlay;
import rs117.hd.HdPlugin;
import rs117.hd.data.WaterType;
import rs117.hd.utils.ColorUtils;

public class FinishingSpotHandler {

	List<Integer> npcs = new ArrayList<>(Arrays.asList(
		394, 635, 1506, 1507, 1508, 1509, 1510, 1511, 1512, 1513, 1514, 1515, 1516, 1517, 1518, 1519, 1520,
		1521, 1522, 1523, 1524, 1525, 1526, 1527, 1528, 1529, 1530, 1531, 1532, 1533, 1534, 1535, 1536, 1542,
		1544, 2146, 2653, 2654, 2655, 3317, 3417, 3418, 3419, 3657, 3913, 3914, 3915, 4079, 4080, 4081, 4082,
		4316, 4476, 4477, 4710, 4711, 4712, 4713, 4714, 5233, 5234, 5820, 5821, 6731, 6825, 7155, 7199, 7200,
		7323, 7459, 7460, 7461, 7462, 7463, 7464, 7465, 7466, 7467, 7468, 7469, 7470, 7946, 7947, 8524, 8525,
		8526, 8527, 9171, 9172, 9173, 9174, 9478, 12267
	));

	public final int FISHING_SPOT_MODEL = 41238;
	public final int FISHING_SPOT_ANIMATION = 10793;

	private ModelData FISHING_SPOT_NORMAL;

	private Animation FISHING_ANIMATION;

	@Inject
	private TileOverrideManager tileOverrideManager;

	Map<Integer,RuneLiteObject> npcIndexToModel = new HashMap<>();

	@Inject
	private Client client;

	@Inject
	private EnvironmentManager environmentManager;

	public boolean respawn = false;

	public void start() {
		FISHING_SPOT_NORMAL = createFishingModel();
		FISHING_ANIMATION = client.loadAnimation(FISHING_SPOT_ANIMATION);
	}

	public ModelData createFishingModel() {
		return client.loadModelData(FISHING_SPOT_MODEL).cloneVertices();
	}



	public void spawnAllFishingSpots(WaterType type) {
		if (respawn) {
			reset();
			client.getNpcs().forEach(npc -> {
				if (!npcs.contains(npc.getId())) return;
				RuneLiteObject fishingSpot = client.createRuneLiteObject();
				fishingSpot.setAnimation(FISHING_ANIMATION);
				fishingSpot.setLocation(npc.getLocalLocation(), 0);
				fishingSpot.setDrawFrontTilesFirst(false);
				fishingSpot.setActive(true);
				fishingSpot.setShouldLoop(true);
				LocalPoint pos = npc.getLocalLocation();
				Tile tile = client.getScene().getTiles()[npc.getWorldLocation().getPlane()][pos.getSceneX()][pos.getSceneY()];
				WaterType waterType = tileOverrideManager.getOverride(client.getScene(),tile).waterType;

				if (waterType.fishingColor != null) {
					System.out.println(waterType.fishingColor.toString());
					ModelData data = FISHING_SPOT_NORMAL.cloneColors();
					short[] faceColors = data.getFaceColors();
					short recolor = JagexColor.rgbToHSL(waterType.fishingColor.getRGB(),100);
					for (int i = 0; i < data.getFaceColors().length; i++) {
						data.recolor(faceColors[i], recolor);
					}
					fishingSpot.setModel(data.light());
				} else {
					fishingSpot.setModel(FISHING_SPOT_NORMAL.light());
				}
				npcIndexToModel.put(npc.getIndex(), fishingSpot);
			});
			respawn = false;
		}
	}

	public void reset() {
		npcIndexToModel.forEach((index,object) -> {
			object.setActive(false);
		});
		npcIndexToModel.clear();
	}

	public void updateFishingSpotObjects() {
		npcIndexToModel.forEach((index,object) -> {
			object.setLocation(client.getNpcs().stream().filter(npc -> npc.getIndex() == index).findFirst().get().getLocalLocation(),0);
		});
	}

}
