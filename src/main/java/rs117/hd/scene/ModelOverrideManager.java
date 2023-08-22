package rs117.hd.scene;

import java.io.IOException;
import java.util.HashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.AABB;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class ModelOverrideManager {
    private static final ResourcePath MODEL_OVERRIDES_PATH =  Props.getPathOrDefault("rlhd.model-overrides-path",
        () -> path(ModelOverrideManager.class, "model_overrides.json"));

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private HdPlugin plugin;

    @Inject
    private ModelPusher modelPusher;

    private final HashMap<Long, ModelOverride> modelOverrides = new HashMap<>();
    private final HashMap<Long, AABB[]> modelsToHide = new HashMap<>();

    public void startUp() {
        MODEL_OVERRIDES_PATH.watch(path -> {
            modelOverrides.clear();
            modelsToHide.clear();

            try {
                ModelOverride[] entries = path.loadJson(plugin.getGson(), ModelOverride[].class);
                if (entries == null)
                    throw new IOException("Empty or invalid: " + path);
                for (ModelOverride override : entries) {
					override.gsonReallyShouldSupportThis();
                    for (int npcId : override.npcIds)
                        addEntry(ModelHash.packUuid(npcId, ModelHash.TYPE_NPC), override);
                    for (int objectId : override.objectIds)
                        addEntry(ModelHash.packUuid(objectId, ModelHash.TYPE_OBJECT), override);
                }

				clientThread.invoke(() -> {
					modelPusher.clearModelCache();
					if (client.getGameState() == GameState.LOGGED_IN)
						plugin.uploadScene();
				});

                log.debug("Loaded {} model overrides", modelOverrides.size());
            } catch (IOException ex) {
                log.error("Failed to load model overrides:", ex);
            }
        });
    }

    private void addEntry(long uuid, ModelOverride entry) {
        ModelOverride old = modelOverrides.put(uuid, entry);
        modelsToHide.put(uuid, entry.hideInAreas);

        if (Props.DEVELOPMENT && old != null) {
            if (entry.hideInAreas.length > 0) {
				System.err.printf("Replacing ID %d from '%s' with hideInAreas-override '%s'. This is likely a mistake...\n",
					ModelHash.getIdOrIndex(uuid), old.description, entry.description
				);
			} else if (old.hideInAreas.length == 0) {
				System.err.printf("Replacing ID %d from '%s' with '%s'. The first-mentioned override should be removed.\n",
					ModelHash.getIdOrIndex(uuid), old.description, entry.description
				);
			}
        }
    }

    public boolean shouldHideModel(long hash, int x, int z) {
		assert client.isClientThread();
        long uuid = ModelHash.getUuid(client, hash);

        AABB[] aabbs = modelsToHide.get(uuid);
        if (aabbs != null) {
			WorldPoint location = HDUtils.cameraSpaceToWorldPoint(client, x, z);
			for (AABB aabb : aabbs)
				if (aabb.contains(location))
					return true;
		}

        return false;
    }

    @NonNull
    public ModelOverride getOverride(long hash) {
        return modelOverrides.getOrDefault(ModelHash.getUuid(client, hash), ModelOverride.NONE);
    }
}
