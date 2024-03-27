package rs117.hd.scene;

import com.google.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.AreaDefinition;
import rs117.hd.utils.AABB;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class AreaManager {
	@Getter
	private AreaDefinition currentArea = null;

	private static final ResourcePath AREA_DATA_PATH = Props.getPathOrDefault(
		"rlhd.area-path",
		() -> path(AreaManager.class, "areas.json")
	);


	public ArrayList<AreaDefinition> areas = new ArrayList<>();

	@Inject
	private ClientThread clientThread;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	public void startUp() {
		AREA_DATA_PATH.watch(path -> {
			try {
				areas.clear();
				Collections.addAll(areas, path.loadJson(plugin.getGson(), AreaDefinition[].class));
				log.debug("Loaded {} areas", areas.size());
				plugin.reloadSceneNextGameTick();
			} catch (IOException ex) {
				log.error("Failed to load areas: ", ex);
			}
		});
	}

	public void update(WorldPoint point) {
		currentArea = null;
		for (AreaDefinition area : areas) {
			if (area.aabbs.length != 0) {
				for (AABB aabb : area.aabbs) {
					if (aabb.contains(point)) {
						currentArea = area;
						return;
					}
				}
			} else if (area.region != -1) {
				if (point.getRegionID() == area.region) {
					currentArea = area;
					return;
				}
			}
		}
	}

	public boolean shouldHideTile(int tileX, int tileY) {
		return shouldHide(WorldPoint.fromLocalInstance(client, LocalPoint.fromWorld(client, tileX, tileY)));
	}

	public boolean shouldHide(WorldPoint location) {
		if (currentArea != null && plugin.config.hideUnrelatedMaps() && currentArea.hideOtherRegions) {
			for (AABB aabbs : currentArea.aabbs) {
				if (aabbs.contains(location)) {
					return false;
				}
			}
			return currentArea.region != location.getRegionID();
		}

		return false;
	}

}