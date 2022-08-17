package rs117.hd.scene;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.annotations.JsonAdapter;
import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.scene.lights.Light.ObjectIDAdapter;
import rs117.hd.utils.AABB;
import rs117.hd.utils.Env;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class HiddenObjectManager {

    @Inject
    Client client;

    public static String ENV_HIDDEN_OBJECTS = "RLHD_HIDDEN_OBJECTS_PATH";

    private final Multimap<Integer, AABB> hiddenObjects = ArrayListMultimap.create();

    private static class LocationInfo {
        @JsonAdapter(ObjectIDAdapter.class)
        public HashSet<Integer> objectIds = new HashSet<>();
        @JsonAdapter(AABB.JsonAdapter.class)
        public AABB[] aabbs = {};
    }

    public void startUp() {
        Env.getPathOrDefault(ENV_HIDDEN_OBJECTS, () -> path(HiddenObjectManager.class, "hidden_objects.jsonc"))
            .watch(path -> {
                hiddenObjects.clear();

                try {
                    LocationInfo[] entries = path.loadJson(LocationInfo[].class);
                    if (entries == null)
                        throw new IOException("Empty or invalid: " + path);
                    for (LocationInfo entry : entries) {
                        for (int objectId : entry.objectIds) {
                            hiddenObjects.putAll(objectId, Arrays.asList(entry.aabbs));
                        }
                    }
                    log.debug("Loaded {} hidden objects in {} areas", hiddenObjects.keySet().size(), hiddenObjects.values().size());
                } catch (IOException ex) {
                    log.error("Failed to load hidden objects:", ex);
                }
            });
    }

    public boolean shouldHide(int objectID, WorldPoint location) {
        return hiddenObjects.get(objectID)
            .stream()
            .anyMatch(aabb -> aabb.contains(location) && hasNoActions(objectID));
    }

    private boolean hasNoActions(int objectID) {
       return Arrays.stream(client.getObjectDefinition(objectID).getActions()).allMatch(Objects::isNull);
    }
}
