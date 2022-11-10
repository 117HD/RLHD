package rs117.hd.scene.area;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Env;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.ResourcePath;

import javax.inject.Singleton;
import java.io.IOException;
import java.util.List;

import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class AreaManager {

    private static final String ENV_AREA_PATH = "RLHD_AREA_PATH";
    private static final ResourcePath areaDataPath =  Env.getPathOrDefault(ENV_AREA_PATH,
            () -> path(AreaManager.class, "areas.json"));


    private AreaData[] areas;

    @Inject
    private ClientThread clientThread;

    @Inject
    private HdPlugin plugin;

    public void startUp() {
        areaDataPath.watch(path -> {
            try {
                areas = path.loadJson(plugin.getGson(), AreaData[].class);
                log.debug("Loaded {} areas", areas.length);
            } catch (IOException ex) {
                log.error("Failed to load areas: ", ex);
            }
        });
    }

}
