package rs117.hd.model.modelreplaceer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasStatue;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public final class ModelStore {
	private static final Map<String, ModelDefinition> CODE_MODELS = Map.of(
		"XMAS_LUMBRIDGE_STATUE", new XmasStatue(5575, 1452, 30, false, -10, -100, -6, false),
		"XMAS_GARDEN_QUEEN_STATUE", new XmasStatue(9134, 9135, 0, true, -2, -110, 4, true)
	);
	private static Map<String, ModelDefinition> models = CODE_MODELS;

	private ModelStore() {}

	public static void load(Gson gson, int textureCount) {
		var loaded = new HashMap<>(CODE_MODELS);
		var resource = path(ModelStore.class, "model_replacements.json");
		try {
			JsonObject definitions = resource.loadJson(gson, JsonObject.class);
			if (definitions == null)
				throw new IOException("Empty model replacements");

			for (var entry : definitions.entrySet()) {
				String name = entry.getKey();
				try {
					if (loaded.containsKey(name))
						throw new IllegalArgumentException("Name is already registered in Java");
					ModelDefinition model = gson.fromJson(entry.getValue(), ModelDefinition.class);
					if (model == null)
						throw new IllegalArgumentException("Expected a model definition");
					model.validate(textureCount);
					loaded.put(name, model);
				} catch (RuntimeException ex) {
					log.error("Invalid model replacement '{}': {}", name, ex.getMessage());
				}
			}
		} catch (IOException | RuntimeException ex) {
			log.error("Failed to load model replacements from {}", resource, ex);
		}
		models = loaded;
	}

	public static ModelDefinition get(String name) {
		return name == null ? null : models.get(name);
	}

	public static void clearCachedModels() {
		for (var model : models.values())
			model.clearCachedModels();
	}
}
