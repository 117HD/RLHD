package rs117.hd.scene;

import java.io.IOException;
import java.util.HashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.model.modelreplaceer.ModelReplacement;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class ModelReplacementManager {
	private static final ResourcePath MODEL_OVERRIDES_PATH = Props
		.getFile("rlhd.model-replacement-path", () -> path(ModelReplacementManager.class, "model_replacement.json"));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private GamevalManager gamevalManager;

	private final HashMap<Integer, ModelReplacement> modelOverrides = new HashMap<>();

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = MODEL_OVERRIDES_PATH.watch((path, first) -> {
			modelOverrides.clear();

			try {
				ModelReplacement[] entries = path.loadJson(plugin.getGson(), ModelReplacement[].class);
				if (entries == null)
					throw new IOException("Empty or invalid: " + path);
				for (ModelReplacement override : entries) {
					addOverride(override);
				}

				log.debug("Loaded {} model replacements", modelOverrides.size());
			} catch (IOException ex) {
				log.error("Failed to load model replacements:", ex);
			}

			if (!first) {
				clientThread.invoke(() -> {
					if (client.getGameState() == GameState.LOGGED_IN)
						client.setGameState(GameState.LOADING);
				});
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;

		modelOverrides.clear();
	}

	public void reload() {
		shutDown();
		startUp();
	}

	private void addOverride(@Nullable ModelReplacement override) {
		for (int id : override.objectIds) {
			addEntry(id, override);
		}
	}

	private void addEntry(int id, ModelReplacement entry) {
		modelOverrides.put(id, entry);
	}

	@Nonnull
	public ModelReplacement getOverride(int id) {
		var override = modelOverrides.get(id);
		if (override == null)
			return ModelReplacement.NONE;
		return override;
	}


}
