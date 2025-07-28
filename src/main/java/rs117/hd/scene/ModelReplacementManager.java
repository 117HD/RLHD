package rs117.hd.scene;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.model.modelreplaceer.ModelReplacement;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

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

	private final HashMap<Integer, ModelReplacement> modelReplacements = new HashMap<>();

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = MODEL_OVERRIDES_PATH.watch((path, first) -> {
			modelReplacements.clear();

			try {
				ModelReplacement[] entries = path.loadJson(plugin.getGson(), ModelReplacement[].class);
				if (entries == null)
					throw new IOException("Empty or invalid: " + path);
				for (ModelReplacement override : entries) {
					addOverride(override);
				}

				log.debug("Loaded {} model replacements", modelReplacements.size());
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

		modelReplacements.clear();
	}

	private void addOverride(@Nullable ModelReplacement override) {
		if (override == null) return;

		String currentTheme = plugin.config.seasonalTheme().name().toUpperCase();
		Set<String> overrideThemes = override.themes;
		if (overrideThemes != null && !overrideThemes.isEmpty()) {
			boolean match = overrideThemes.stream().anyMatch(t -> t.equalsIgnoreCase(currentTheme));
			if (!match) {
				System.out.println("MATCH SKIPPING");
				return;
			}
		}

		String overrideTime = override.time;
		if (overrideTime != null && !overrideTime.isEmpty()) {
			if (!isCurrentTimeMatching(overrideTime)) {
				return;
			}
		}

		for (int id : override.objectIds) {
			addEntry(id, override);
		}
	}

	private boolean isCurrentTimeMatching(String time) {
		LocalDate today = LocalDate.now();
		if (time.toUpperCase().equals("HALLOWEEN")) {
			LocalDate start = LocalDate.of(today.getYear(), 10, 25);
			LocalDate end = LocalDate.of(today.getYear(), 11, 2);
			return !today.isBefore(start) && !today.isAfter(end);
		}
		return false;
	}

	private void addEntry(int id, ModelReplacement entry) {
		modelReplacements.put(id, entry);
	}

	@Nonnull
	public ModelReplacement getOverride(int id) {
		var override = modelReplacements.get(id);
		if (override == null)
			return ModelReplacement.NONE;
		return override;
	}


}
