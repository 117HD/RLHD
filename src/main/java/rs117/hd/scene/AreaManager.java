package rs117.hd.scene;

import com.google.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.data.AreaDefinition;
import rs117.hd.utils.AABB;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class AreaManager {
	private static final ResourcePath AREA_DATA_PATH = Props.getPathOrDefault(
		"rlhd.area-path",
		() -> path(AreaManager.class, "areas.json")
	);

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Getter
	private AreaDefinition currentArea = null;

	public ArrayList<AreaDefinition> areas = new ArrayList<>();

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

	public boolean shouldHideTile(LocalPoint point) {
		// TODO: since this is used during scene uploading, it's wrong to use the client's current scene
		if (client.getScene().isInstance() || !plugin.config.hideUnrelatedMaps())
		{
			return false;
		}

		return shouldHide(WorldPoint.fromLocalInstance(client, point));
	}

	public boolean shouldHide(WorldPoint location) {
		if (currentArea != null && currentArea.hideOtherRegions) {
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
