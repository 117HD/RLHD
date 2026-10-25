package rs117.hd.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
import rs117.hd.scene.areas.AABB;
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
	private final List<ModelOverride> modelOverrideDefinitions = new ArrayList<>();
	private final IntHashSet detailCullingBlacklist = new IntHashSet();
	private final AtomicBoolean suppressNextFileWatchReload = new AtomicBoolean();

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = MODEL_OVERRIDES_PATH.watch((path, first) -> {
			if (!first && suppressNextFileWatchReload.compareAndSet(true, false))
				return;
			clientThread.invoke(() -> {
				try (var gamevals = gamevalManager.obtainHandle()) {
					sceneManager.getLoadingLock().lock();
					sceneManager.completeAllStreaming();

					ModelOverride[] parsedOverrides = path.loadJson(plugin.getGson(), ModelOverride[].class);
					if (parsedOverrides == null)
						throw new IOException("Empty or invalid: " + path);

					synchronized (modelOverrideDefinitions) {
						modelOverrideDefinitions.clear();
						modelOverrideDefinitions.addAll(Arrays.asList(parsedOverrides));
					}
					modelOverrides.clear();
					for (ModelOverride override : parsedOverrides) {
						try {
							override.normalize(plugin);
						} catch (IllegalStateException ex) {
							log.error("Invalid model override '{}': {}", override.description, ex.getMessage());
							continue;
						}

						addOverride(override, gamevals);

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
						if (!Props.DEVELOPMENT)
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
			});
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;

		modelOverrides.clear();
		modelOverrides.trimToSize();
		synchronized (modelOverrideDefinitions) {
			modelOverrideDefinitions.clear();
		}

		detailCullingBlacklist.clear();
		detailCullingBlacklist.trimToSize();
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
	public ModelOverride getOverride(int uuid) {
		var override = modelOverrides.get(ModelHash.getUuidWithoutSubType(uuid));
		return override == null ? ModelOverride.NONE : override;
	}

	public boolean isSharedOverride(int uuid) {
		int baseUuid = ModelHash.getUuidWithoutSubType(uuid);
		ModelOverride override = modelOverrides.get(baseUuid);
		if (override == null || override == ModelOverride.NONE || override.isDummy || override.isGenerated)
			return false;

		int matches = 0;
		for (var entry : modelOverrides) {
			if (entry.getValue() == override && ++matches > 1)
				return true;
		}
		return false;
	}

	public void replaceOverride(int uuid, ModelOverride override) {
		modelOverrides.put(ModelHash.getUuidWithoutSubType(uuid), override);
	}

	public Map<Integer, ModelOverride> getObjectOverrideMappings(Set<Integer> objectIds) {
		Map<Integer, ModelOverride> mappings = new HashMap<>();
		for (int id : objectIds) {
			int uuid = ModelHash.packUuid(ModelHash.TYPE_OBJECT, id);
			mappings.put(id, modelOverrides.get(uuid));
		}
		return mappings;
	}

	public void setObjectOverrideMappings(Map<Integer, ModelOverride> mappings) {
		for (var entry : mappings.entrySet()) {
			int uuid = ModelHash.packUuid(ModelHash.TYPE_OBJECT, entry.getKey());
			if (entry.getValue() == null)
				modelOverrides.remove(uuid);
			else
				modelOverrides.put(uuid, entry.getValue());
		}
	}

	public List<ModelOverride> getAreaOverrideOwners(ModelOverride areaOverride) {
		List<ModelOverride> owners = new ArrayList<>();
		var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<ModelOverride, Boolean>());
		for (var entry : modelOverrides) {
			ModelOverride root = entry.getValue();
			if (root.areaOverrides != null && root.areaOverrides.containsValue(areaOverride) && seen.add(root))
				owners.add(root);
		}
		return owners;
	}

	public void updateAreaOverrideMappings(List<ModelOverride> owners, ModelOverride areaOverride) {
		for (ModelOverride root : owners) {
			if (root.areaOverrides == null) {
				if (areaOverride.areas == null || areaOverride.areas.length == 0) continue;
				root.areaOverrides = new HashMap<>();
			}

			root.areaOverrides.entrySet().removeIf(entry -> entry.getValue() == areaOverride);
			if (areaOverride.areas != null)
				for (var area : areaOverride.areas)
					root.areaOverrides.put(area, areaOverride);

			if (root.areaOverrides.isEmpty()) root.areaOverrides = null;
		}
	}

	public void addDefinition(ModelOverride override) {
		synchronized (modelOverrideDefinitions) {
			if (!modelOverrideDefinitions.contains(override))
				modelOverrideDefinitions.add(override);
		}
	}

	public void removeDefinition(ModelOverride override) {
		synchronized (modelOverrideDefinitions) {
			modelOverrideDefinitions.remove(override);
		}
	}

	public void save() throws IOException {
		if (!MODEL_OVERRIDES_PATH.isFileSystemResource())
			throw new IOException("Model overrides are loaded from a classpath resource; set rlhd.model-overrides-path to a writable file to save.");

		List<ModelOverride> sourceDefinitions;
		synchronized (modelOverrideDefinitions) {
			sourceDefinitions = new ArrayList<>(modelOverrideDefinitions);
		}
		String json = plugin.getGson().toJson(getSaveDefinitions(sourceDefinitions));
		MODEL_OVERRIDES_PATH.mkdirs();
		suppressNextFileWatchReload.set(true);
		try (var writer = MODEL_OVERRIDES_PATH.toWriter()) {
			writer.write(json);
		} catch (IOException | RuntimeException ex) {
			suppressNextFileWatchReload.set(false);
			throw ex;
		}
	}

	private ModelOverride[] getSaveDefinitions(List<ModelOverride> sourceDefinitions) {
		List<ModelOverride> definitions = new ArrayList<>();
		for (ModelOverride definition : sourceDefinitions) {
			if (definition.areas == null || definition.areas.length == 0) {
				definitions.add(definition);
				continue;
			}

			for (var area : definition.areas) {
				Set<Integer> npcIds = new HashSet<>();
				Set<Integer> objectIds = new HashSet<>();
				Set<Integer> projectileIds = new HashSet<>();
				Set<Integer> graphicsObjectIds = new HashSet<>();
				for (var entry : modelOverrides) {
					var root = entry.getValue();
					if (root.areaOverrides == null || root.areaOverrides.get(area) != definition)
						continue;

					int id = ModelHash.getUuidId(entry.getKey());
					switch (ModelHash.getUuidType(entry.getKey())) {
						case ModelHash.TYPE_NPC:
							npcIds.add(id);
							break;
						case ModelHash.TYPE_OBJECT:
							objectIds.add(id);
							break;
						case ModelHash.TYPE_PROJECTILE:
							projectileIds.add(id);
							break;
						case ModelHash.TYPE_GRAPHICS_OBJECT:
							graphicsObjectIds.add(id);
							break;
					}
				}

				if (npcIds.isEmpty() && objectIds.isEmpty() && projectileIds.isEmpty() && graphicsObjectIds.isEmpty())
					continue;

				ModelOverride savedDefinition = definition.copy();
				savedDefinition.areas = new AABB[]{area};
				savedDefinition.npcIds = npcIds;
				savedDefinition.objectIds = objectIds;
				savedDefinition.projectileIds = projectileIds;
				savedDefinition.graphicsObjectIds = graphicsObjectIds;
				definitions.add(savedDefinition);
			}
		}
		return definitions.toArray(ModelOverride[]::new);
	}

	@Nonnull
	public ModelOverride getOverride(int uuid, int[] worldPos) {
		var override = getOverride(uuid);

		if (worldPos != null && override.areaOverrides != null)
			for (var entry : override.areaOverrides.entrySet())
				if (entry.getKey().contains(worldPos))
					return entry.getValue();

		return override;
	}

	@Nonnull
	public ModelOverride getOverride(TileObject tileObject, int[] worldPos) {
		return getOverride(ModelHash.packUuid(ModelHash.TYPE_OBJECT, tileObject.getId()), worldPos);
	}
}
