package rs117.hd.scene.area;

import com.google.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.data.environments.Area;
import rs117.hd.utils.AABB;
import rs117.hd.utils.Env;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.ResourcePath;

import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class AreaManager {


    @Getter
    @Setter
    AreaData currentArea = null;

    private static final String ENV_AREA_PATH = "RLHD_AREA_PATH";
    private static final ResourcePath areaDataPath =  Env.getPathOrDefault(ENV_AREA_PATH,
            () -> path(AreaManager.class, "areas.json"));


    public ArrayList<AreaData> areas = new ArrayList<AreaData>();

    @Inject
    private ClientThread clientThread;

    @Inject
    private Client client;

    @Inject
    private HdPlugin plugin;

    public void startUp() {
        areas.clear();
        areaDataPath.watch(path -> {
            try {
                AreaData[] temp = path.loadJson(plugin.getGson(), AreaData[].class);
                Collections.addAll(areas, temp);
                log.debug("Loaded {} areas", areas.size());
            } catch (IOException ex) {
                log.error("Failed to load areas: ", ex);
            }
        });
    }

    public void update(WorldPoint point) {
        for (AreaData area : areas) {
            for (AABB aabb : area.aabbs) {
                if (aabb.contains(point)) {
                    currentArea = area;
                }
            }
        }

    }

    public boolean shouldHide(int x, int z) {
        if (currentArea == null) {
            return false;
        }

        if(currentArea.hideOtherRegions) {
            for (AABB aabbs : currentArea.aabbs) {
                WorldPoint location = ModelHash.getWorldTemplateLocation(client, x, z);
                if (!aabbs.contains(location)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean shouldHide(WorldPoint worldPoint) {
        if (currentArea == null) {
            return false;
        }


        if(currentArea.hideOtherRegions) {
            for (AABB aabbs : currentArea.aabbs) {
                if (!aabbs.contains(worldPoint)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean horizonAvailable() {
        return currentArea.horizonTile != null;
    }

}
