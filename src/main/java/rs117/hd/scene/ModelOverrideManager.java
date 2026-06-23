package rs117.hd.scene;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.scene.lights.LightTimeOfDay;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.collections.Int2ObjectHashMap;
import rs117.hd.utils.collections.IntHashSet;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class ModelOverrideManager {
	private static final ResourcePath MODEL_OVERRIDES_PATH = Props
		.getFile("rlhd.model-overrides-path", () -> path(ModelOverrideManager.class, "model_overrides.json"));

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private Client client;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	private final Int2ObjectHashMap<ModelOverride> modelOverrides = new Int2ObjectHashMap<>();
	private final IntHashSet detailCullingBlacklist = new IntHashSet();
	private boolean hasTimeOfDayOverrides;
	private boolean dayNightWasActive;
	@Nullable
	private LightTimeOfDay currentTimeOfDayPhase;

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = MODEL_OVERRIDES_PATH.watch((path, first) -> clientThread.invoke(() -> {
			try (var gamevals = gamevalManager.obtainHandle()) {
				sceneManager.getLoadingLock().lock();
				sceneManager.completeAllStreaming();

				ModelOverride[] parsedOverrides = path.loadJson(plugin.getGson(), ModelOverride[].class);
				if (parsedOverrides == null)
					throw new IOException("Empty or invalid: " + path);

				modelOverrides.clear();
				hasTimeOfDayOverrides = false;
				for (ModelOverride override : parsedOverrides) {
					try {
						override.normalize(plugin);
					} catch (IllegalStateException ex) {
						log.error("Invalid model override '{}': {}", override.description, ex.getMessage());
						continue;
					}

					addOverride(override, gamevals);

					if (!hasTimeOfDayOverrides && ModelOverride.hasTimeOfDaySchedule(override))
						hasTimeOfDayOverrides = true;

					if (override.hideInAreas.length > 0) {
						var hider = override.copy();
						hider.hide = true;
						hider.areas = override.hideInAreas;
						addOverride(hider, gamevals);
					}
				}

				addOverride(fishingSpotReplacer.getModelOverride(), gamevals);
				addSailingCullingOverrides(gamevals);

				detailCullingBlacklist.clear();
				for (var entry : modelOverrides) {
					final ModelOverride override = entry.getValue();
					if (entry.getValue().disableDetailCulling)
						detailCullingBlacklist.add(entry.getKey());
					override.clearIds();
				}

				log.debug("Loaded {} model overrides", modelOverrides.size());

				if (first)
					return;

				plugin.renderer.clearCaches();
				plugin.renderer.reloadScene();
			} catch (Exception ex) {
				log.error("Failed to load model overrides:", ex);
			} finally {
				sceneManager.getLoadingLock().unlock();
				log.trace("loadingLock unlocked - holdCount: {}", sceneManager.getLoadingLock().getHoldCount());
			}
		}));
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;

		modelOverrides.clear();
		modelOverrides.trimToSize();

		detailCullingBlacklist.clear();
		detailCullingBlacklist.trimToSize();
		hasTimeOfDayOverrides = false;
		dayNightWasActive = false;
		currentTimeOfDayPhase = null;
	}

	public void reload() {
		shutDown();
		startUp();
	}

	private void addOverride(@Nullable ModelOverride override, GamevalManager.Handle gamevals) {
		if (override == null || override.seasonalTheme != null && override.seasonalTheme != plugin.configSeasonalTheme)
			return;

		for (int id : override.npcIds)
			addEntry(ModelHash.TYPE_NPC, id, override, gamevals);
		for (int id : override.objectIds)
			addEntry(ModelHash.TYPE_OBJECT, id, override, gamevals);
		for (int id : override.projectileIds)
			addEntry(ModelHash.TYPE_PROJECTILE, id, override, gamevals);
		for (int id : override.graphicsObjectIds)
			addEntry(ModelHash.TYPE_GRAPHICS_OBJECT, id, override, gamevals);
	}

	private void addEntry(int type, int id, ModelOverride entry, GamevalManager.Handle gamevals) {
		int uuid = ModelHash.packUuid(type, id);
		ModelOverride current = modelOverrides.get(uuid);

		if (current != null && !Objects.equals(current.seasonalTheme, entry.seasonalTheme)) {
			// Seasonal theme overrides should take precedence
			if (current.seasonalTheme != null)
				return;
			current = null;
		}

		if (entry.areas.length == 0) {
			// Non-area-restricted override, of which there can only be one per UUID

			// A dummy override is used as the base if only area-specific overrides exist
			boolean isDuplicate = current != null && !current.isDummy;

			if (isDuplicate && entry.isGenerated)
				return; // Manually specified model overrides should take precedence over generated ones

			if (isDuplicate && Props.DEVELOPMENT) {
				String name = null;
				switch (type) {
					case ModelHash.TYPE_NPC:
						name = gamevals.getNpcName(id);
						break;
					case ModelHash.TYPE_OBJECT:
						name = gamevals.getObjectName(id);
						break;
					case ModelHash.TYPE_PROJECTILE:
					case ModelHash.TYPE_GRAPHICS_OBJECT:
						name = gamevals.getSpotanimName(id);
						break;
				}

				// This should ideally not be reached, so print helpful warnings in development mode
				if (entry.hideInAreas.length > 0) {
					log.error(
						"Replacing {} ({}) from '{}' with hideInAreas-override '{}'. This is likely a mistake...",
						name, id, current.description, entry.description
					);
				} else {
					log.error(
						"Replacing {} ({}) from '{}' with '{}'. The first-mentioned override should be removed.",
						name, id, current.description, entry.description
					);
				}
			}

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
	}

	private void addSailingCullingOverrides(GamevalManager.Handle gamevals) {
		try {
			for (Integer row : client.getDBTableRows(DBTableID.SailingBoatSail.ID)) {
				Integer sailId = (Integer) client.getDBTableField(row, DBTableID.SailingBoatSail.COL_LOC, 0)[0];
				if (sailId == null)
					continue;
				ModelOverride sailOverride = new ModelOverride();
				sailOverride.isGenerated = true;
				sailOverride.description = "Disable detail culling of boat sails";
				sailOverride.objectIds = Set.of(sailId);
				sailOverride.disableDetailCulling = true;
				sailOverride.normalize(plugin);
				addOverride(sailOverride, gamevals);
			}
		} catch (Exception ex) {
			log.error("Error while setting up model overrides for disabling detail culling of sails:", ex);
		}
	}

	public boolean allowDetailCulling(int uuid) {
		return !detailCullingBlacklist.contains(uuid);
	}

	@Nonnull
	public ModelOverride getOverride(int uuid, int[] worldPos) {
		var override = modelOverrides.get(ModelHash.getUuidWithoutSubType(uuid));
		if (override == null)
			return ModelOverride.NONE;

		if (override.areaOverrides != null)
			for (var entry : override.areaOverrides.entrySet())
				if (entry.getKey().contains(worldPos))
					return resolveForTimeOfDay(entry.getValue());

		return resolveForTimeOfDay(override);
	}

	@Nonnull
	private ModelOverride resolveForTimeOfDay(ModelOverride override) {
		if (override.isDummy || !plugin.isDayNightCycleActive())
			return override;
		if (override.timeOfDay == null || override.timeOfDay.length == 0)
			return override;
		return override.resolveTimeOfDay(plugin.getNightLightFactor());
	}

	@Nonnull
	public ModelOverride getOverride(TileObject tileObject, int[] worldPos) {
		return getOverride(ModelHash.packUuid(ModelHash.TYPE_OBJECT, tileObject.getId()), worldPos);
	}

	public void updateTimeOfDayPhase() {
		if (!hasTimeOfDayOverrides || !plugin.isDayNightCycleActive()) {
			if (dayNightWasActive) {
				dayNightWasActive = false;
				currentTimeOfDayPhase = null;
				sceneManager.invalidateAllZones();
			} else {
				currentTimeOfDayPhase = null;
			}
			return;
		}

		LightTimeOfDay phase = LightTimeOfDay.fromNightLightFactor(plugin.getNightLightFactor());
		if (!dayNightWasActive || phase != currentTimeOfDayPhase)
			sceneManager.invalidateAllZones();
		dayNightWasActive = true;
		currentTimeOfDayPhase = phase;
	}
}
