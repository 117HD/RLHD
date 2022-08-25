package rs117.hd.scene;

import com.google.gson.*;
import rs117.hd.data.materials.UvType;
import rs117.hd.scene.objects.ObjectProperties;
import rs117.hd.scene.objects.TzHaarRecolorType;
import rs117.hd.utils.ResourcePath;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static rs117.hd.utils.ResourcePath.RESOURCE_DIR;
import static rs117.hd.utils.ResourcePath.path;

@SuppressWarnings("deprecation")
public class ExportObjectPropertiesToJson {

    public static void main(String[] args) throws IOException {

        Gson gson = new Gson();

		Set<ObjectProperties> uniqueObjects = new LinkedHashSet<>();
        Path configPath = ResourcePath.path(RESOURCE_DIR, "rs117/hd/scene", "objects_properties.jsonc").toPath();

        System.out.println("Loading current object Properties from JSON...");

        ObjectProperties[] currentObjects = path(configPath).loadJson(ObjectProperties[].class);
        Collections.addAll(uniqueObjects, currentObjects);
        System.out.println("Loaded " + currentObjects.length + " object Properties");

        GsonBuilder gsonBuilder = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting();
        gsonBuilder.registerTypeAdapter(ObjectProperties.class, (JsonSerializer<ObjectProperties>) (obj, type, jsonSerializationContext) -> {
            JsonObject object = (JsonObject) gson.toJsonTree(obj);
            if (obj.tzHaarRecolorType == TzHaarRecolorType.NONE) {
                object.remove("tzHaarRecolorType");
            }
            if (!obj.description.equals("UNKNOWN")) {
                object.remove("description");
            }
            if (!obj.flatNormals) {
                object.remove("flatNormals");
            }
            if (obj.uvType == UvType.GEOMETRY) {
                object.remove("uvType");
            }
            if (!obj.inheritTileColor) {
                object.remove("inheritTileColor");
            }
            return object;
        });

        String json = gsonBuilder.create().toJson(uniqueObjects);

        System.out.println("Writing " + uniqueObjects.size() + " object Properties to JSON file: " + configPath.toAbsolutePath());
        configPath.toFile().getParentFile().mkdirs();

        OutputStreamWriter os = new OutputStreamWriter(
				Files.newOutputStream(configPath.toFile().toPath()),
                StandardCharsets.UTF_8);

        os.write(json);
        os.close();

    }

}


