package rs117.hd.scene;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.HdPlugin;
import rs117.hd.data.materials.Material;
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
    Client client;
    @Inject
    HdPlugin plugin;

    public static ObjectProperties NONE = new ObjectProperties(Material.NONE);

    public static String ENV_HIDDEN_OBJECTS = "RLHD_HIDDEN_OBJECTS_PATH";
    public static String ENV_HIDDEN_PROPERTIES = "RLHD_OBJECTS_PROPERTIES_PATH";

    private final HashMap<Integer, ObjectProperties> objectProperties = new HashMap<>();

    private final Multimap<Integer, AABB> hiddenObjects = ArrayListMultimap.create();


    public void startUp() {
        loadObjectProperties();
        loadHiddenObjects();
    }


    public void loadObjectProperties() {
        Env.getPathOrDefault(ENV_HIDDEN_PROPERTIES, () -> path(ObjectManager.class, "objects_properties.jsonc"))
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

    public ObjectProperties getObjectProperties(int objectId)
    {
        return objectProperties.getOrDefault(objectId, NONE);
    }


}
