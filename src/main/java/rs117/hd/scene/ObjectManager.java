package rs117.hd.scene;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.HdPlugin;
import rs117.hd.scene.objects.LocationInfo;
import rs117.hd.scene.objects.ObjectProperties;
import rs117.hd.utils.AABB;
import rs117.hd.utils.Env;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class ObjectManager {
    @Inject
    private Client client;

    @Inject
    private HdPlugin plugin;

    private static final String ENV_HIDDEN_OBJECTS = "RLHD_HIDDEN_OBJECTS_PATH";
    private static final String ENV_OBJECT_PROPERTIES = "RLHD_OBJECTS_PROPERTIES_PATH";

    private static final HashMap<Integer, ObjectProperties> objectProperties = new HashMap<>();

    private final HashMap<Integer, AABB[]> hiddenObjects = new HashMap<>();

    public void startUp() {
        loadObjectProperties();
        loadHiddenObjects();
    }

    public void loadObjectProperties() {
        Env.getPathOrDefault(ENV_OBJECT_PROPERTIES, () -> path(ObjectManager.class, "object_properties.jsonc"))
                .watch(path -> {
                    objectProperties.clear();

                    try {
                        ObjectProperties[] entries = path.loadJson(ObjectProperties[].class);
                        if (entries == null)
                            throw new IOException("Empty or invalid: " + path);
                        for (ObjectProperties entry : entries) {
                            for (int objectId : entry.objectIds) {
                                objectProperties.put(objectId, entry);
                            }
                        }
                        if (client.getGameState() == GameState.LOGGED_IN) {
                            plugin.reloadScene();
                        }
                        log.debug("Loaded {} object Properties", objectProperties.size());
                    } catch (IOException ex) {
                        log.error("Failed to load object Properties:", ex);
                    }
                });
    }

    public void loadHiddenObjects() {
        Env.getPathOrDefault(ENV_HIDDEN_OBJECTS, () -> path(ObjectManager.class, "hidden_objects.jsonc"))
                .watch(path -> {
                    try {
                        hiddenObjects.clear();

                        LocationInfo[] entries = path.loadJson(LocationInfo[].class);
                        if (entries == null)
                            throw new IOException("Empty or invalid: " + path);
                        for (LocationInfo entry : entries) {
                            for (int objectId : entry.objectIds) {
                                hiddenObjects.put(objectId, entry.aabbs);
                            }
                        }
                        log.debug("Loaded {} hidden objects", hiddenObjects.keySet().size());
                    } catch (IOException ex) {
                        log.error("Failed to load hidden objects:", ex);
                    }
                });
    }

    public boolean shouldHide(int objectID, WorldPoint location) {
        AABB[] aabbs = hiddenObjects.get(objectID);
        if (aabbs != null)
            for (AABB aabb : aabbs)
                if (aabb.contains(location) && hasNoActions(objectID))
                    return true;
        return false;
    }

    private boolean hasNoActions(int objectID) {
       return Arrays.stream(client.getObjectDefinition(objectID).getActions()).allMatch(Objects::isNull);
    }

    public ObjectProperties getObjectProperties(int objectId)
    {
        return objectProperties.getOrDefault(objectId, ObjectProperties.NONE);
    }
}
