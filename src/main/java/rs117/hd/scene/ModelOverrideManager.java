package rs117.hd.scene;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class ModelOverrideManager {
	private static final ResourcePath MODEL_OVERRIDES_PATH = Props.getPathOrDefault(
		"rlhd.model-overrides-path",
		() -> path(ModelOverrideManager.class, "model_overrides.json")
	);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ModelPusher modelPusher;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	private final HashMap<Integer, ModelOverride> modelOverrides = new HashMap<>();

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = MODEL_OVERRIDES_PATH.watch((path, first) -> {
			modelOverrides.clear();

			try {
				ModelOverride[] entries = path.loadJson(plugin.getGson(), ModelOverride[].class);
				if (entries == null)
					throw new IOException("Empty or invalid: " + path);
				for (ModelOverride override : entries) {
					try {
						override.normalize(plugin.configVanillaShadowMode);
					} catch (IllegalStateException ex) {
						log.error("Invalid model override '{}': {}", override.description, ex.getMessage());
						continue;
					}

					addOverride(override);

					if (override.hideInAreas.length > 0) {
						var hider = override.copy();
						hider.hide = true;
						hider.areas = override.hideInAreas;
						addOverride(hider);
					}
				}

				log.debug("Loaded {} model overrides", modelOverrides.size());
			} catch (IOException ex) {
				log.error("Failed to load model overrides:", ex);
			}

			addOverride(fishingSpotReplacer.getModelOverride());

			if (!first) {
				clientThread.invoke(() -> {
					modelPusher.clearModelCache();
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

	private void addOverride(@Nullable ModelOverride override) {
		if (override == null || override.seasonalTheme != null && override.seasonalTheme != plugin.configSeasonalTheme)
			return;

		for (int id : override.npcIds)
			addEntry(ModelHash.TYPE_NPC, id, override);
		for (int id : override.objectIds)
			addEntry(ModelHash.TYPE_OBJECT, id, override);
		for (int id : override.projectileIds)
			addEntry(ModelHash.TYPE_PROJECTILE, id, override);
		for (int id : override.graphicsObjectIds)
			addEntry(ModelHash.TYPE_GRAPHICS_OBJECT, id, override);
	}

	private void addEntry(int type, int id, ModelOverride entry) {
		int uuid = ModelHash.packUuid(type, id);
		ModelOverride current = modelOverrides.get(uuid);

		if (current != null && !Objects.equals(current.seasonalTheme, entry.seasonalTheme)) {
			// Seasonal theme overrides should take precedence
			if (current.seasonalTheme != null)
				return;
			current = null;
		}

		boolean isDuplicate = false;

		if (entry.areas.length == 0) {
			// Non-area-restricted override, of which there can only be one per UUID

			// A dummy override is used as the base if only area-specific overrides exist
			isDuplicate = current != null && !current.isDummy;

			if (current != null && current.areaOverrides != null && !current.areaOverrides.isEmpty()) {
				var areaOverrides = current.areaOverrides;
				current = entry.copy();
				current.areaOverrides = areaOverrides;
			} else {
				current = entry;
			}

			modelOverrides.put(uuid, current);
		} else {
			if (current == null)
				current = ModelOverride.NONE;

			if (current.areaOverrides == null) {
				// We need to replace the override with a copy that has a separate list of area overrides to avoid conflicts
				current = current.copy();
				current.areaOverrides = new HashMap<>();
				modelOverrides.put(uuid, current);
			}

			for (var area : entry.areas)
				current.areaOverrides.put(area, entry);
		}

		if (isDuplicate && Props.DEVELOPMENT) {
			// This should ideally not be reached, so print helpful warnings in development mode
			if (entry.hideInAreas.length > 0) {
				System.err.printf(
					"Replacing ID %d from '%s' with hideInAreas-override '%s'. This is likely a mistake...\n",
					id, current.description, entry.description
				);
			} else {
				System.err.printf(
					"Replacing ID %d from '%s' with '%s'. The first-mentioned override should be removed.\n",
					id, current.description, entry.description
				);
			}
		}
	}

	@Nonnull
	public ModelOverride getOverride(int uuid, int[] worldPos) {
		var override = modelOverrides.get(uuid);
		if (override == null)
			return ModelOverride.NONE;

		if (override.areaOverrides != null)
			for (var entry : override.areaOverrides.entrySet())
				if (entry.getKey().contains(worldPos))
					return entry.getValue();

		return override;
	}
}
