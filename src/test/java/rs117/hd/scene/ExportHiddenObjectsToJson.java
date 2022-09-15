package rs117.hd.scene;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import rs117.hd.scene.objects.LocationInfo;
import rs117.hd.utils.ResourcePath;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static rs117.hd.utils.ResourcePath.path;

public class ExportHiddenObjectsToJson {
    public static void main(String[] args) throws IOException {
		Set<LocationInfo> uniqueHiddenObjects = new LinkedHashSet<>();
        ResourcePath configPath = path(ObjectManager.class, "hidden_objects.jsonc").toFileSystemPath();

        System.out.println("Loading current hidden objects from JSON: " + configPath);

        LocationInfo[] currentObjects = configPath.loadJson(LocationInfo[].class);
        Collections.addAll(uniqueHiddenObjects, currentObjects);
        System.out.println("Loaded " + currentObjects.length + " hidden objects");

        Gson gson = new GsonBuilder().setLenient().setPrettyPrinting().create();

        String json = gson.toJson(uniqueHiddenObjects);

        System.out.println("Writing " + uniqueHiddenObjects.size() + " hidden objects to JSON file: " + configPath);
        configPath.mkdirs().writeString(json);
    }
}
