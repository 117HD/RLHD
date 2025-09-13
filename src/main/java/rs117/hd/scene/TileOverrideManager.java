package rs117.hd.scene;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.localToWorld;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class TileOverrideManager {
	private static final ResourcePath TILE_OVERRIDES_PATH = Props
		.getFile("rlhd.tile-overrides-path", () -> path(TileOverrideManager.class, "tile_overrides.json"));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ModelPusher modelPusher;

	private FileWatcher.UnregisterCallback fileWatcher;
	private boolean trackReplacements;
	private List<Map.Entry<Area, TileOverride>> anyMatchOverrides;
	private ListMultimap<Integer, Map.Entry<Area, TileOverride>> idMatchOverrides;

	public void startUp() {
		fileWatcher = TILE_OVERRIDES_PATH.watch((path, first) -> clientThread.invoke(() -> reload(!first)));
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		anyMatchOverrides = null;
		idMatchOverrides = null;
	}

	public void reload(boolean reloadScene) {
		assert client.isClientThread();

		try {
			TileOverride[] allOverrides = TILE_OVERRIDES_PATH.loadJson(plugin.getGson(), TileOverride[].class);
			if (allOverrides == null)
				throw new IOException("Empty or invalid: " + TILE_OVERRIDES_PATH);

			HashSet<String> names = new HashSet<>();
			for (var override : allOverrides) {
				if (override.name != null) {
					if (!names.add(override.name)) {
						log.warn("Removing duplicate tile override name: {}", override.name);
						override.name = null;
					}
				}
			}

			checkForReplacementLoops(allOverrides);

			List<Map.Entry<Area, TileOverride>> anyMatch = new ArrayList<>();
			ListMultimap<Integer, Map.Entry<Area, TileOverride>> idMatch = ArrayListMultimap.create();

			var tileOverrideVars = plugin.vars.aliases(Map.of(
				"textures", "groundTextures"
			));

			for (int i = 0; i < allOverrides.length; i++) {
				var override = allOverrides[i];
				try {
					override.index = i;
					override.normalize(allOverrides, tileOverrideVars);
				} catch (Exception ex) {
					log.warn("Skipping invalid tile override '{}':", override.name, ex);
					continue;
				}

				if (override.area == Area.NONE)
					continue;

				var replacement = trackReplacements ? override : override.resolveConstantReplacements();
				var entry = Map.entry(override.area, replacement);
				if (override.ids != null) {
					for (int id : override.ids)
						idMatch.put(id, entry);
				} else {
					anyMatch.add(entry);
				}
			}

			anyMatchOverrides = anyMatch;
			idMatchOverrides = idMatch;

			log.debug("Loaded {} tile overrides", allOverrides.length);
		} catch (IOException ex) {
			log.error("Failed to load tile overrides:", ex);
		}

		// Update the reference, since the underlying dirt materials may have changed
		TileOverride.NONE.groundMaterial = GroundMaterial.DIRT;

		if (reloadScene) {
			modelPusher.clearModelCache();
			plugin.reuploadScene();
		}
	}

	private void checkForReplacementLoops(TileOverride[] allOverrides) {
		Map<String, TileOverride> relevantOverrides = new HashMap<>();
		for (var override : allOverrides)
			if (override.name != null && override.rawReplacements != null)
				relevantOverrides.put(override.name, override);

		Set<String> alreadyChecked = new HashSet<>();
		for (var override : relevantOverrides.values())
			checkForReplacementLoops(relevantOverrides, alreadyChecked, override);
	}

	private static void checkForReplacementLoops(
		Map<String, TileOverride> map,
		Set<String> alreadyChecked,
		TileOverride topLevelOverride
	) {
		String name = topLevelOverride.name;
		// Only check each top-level override once
		if (alreadyChecked.add(name))
			checkForReplacementLoops(map, alreadyChecked, new ArrayDeque<>(), name, topLevelOverride);
	}

	private static void checkForReplacementLoops(
		Map<String, TileOverride> map,
		Set<String> alreadyChecked,
		ArrayDeque<String> loop,
		String topLevelOverrideName,
		TileOverride overrideToCheck
	) {
		assert overrideToCheck.name != null : "There's no point in checking overrides without names, since they can't be referenced";
		loop.addLast(overrideToCheck.name);

		for (String replacementName : overrideToCheck.rawReplacements.keySet()) {
			// Check if the replacement introduces a loop
			if (topLevelOverrideName.equals(replacementName)) {
				log.warn(
					"Tile override contains replacement loop: {} -> {}",
					String.join(" -> ", loop),
					replacementName
				);
				// Remove the loop
				overrideToCheck.rawReplacements.put(replacementName, null);
				continue;
			}

			var replacement = map.get(replacementName);
			if (replacement == null)
				continue;

			// Before continuing to check for loops back to the top-level override,
			// we need to rule out any loops within the replacement override itself,
			// so we don't get stuck in a loop there
			checkForReplacementLoops(map, alreadyChecked, replacement);

			// The replacement might've already been removed to prevent a loop in the step above
			if (overrideToCheck.rawReplacements.get(replacementName) == null)
				continue;

			// Check if any further replacements result in a loop
			checkForReplacementLoops(map, alreadyChecked, loop, topLevelOverrideName, replacement);
		}

		loop.removeLast();
	}

	public void setTrackReplacements(boolean shouldTrackReplacements) {
		clientThread.invoke(() -> {
			trackReplacements = shouldTrackReplacements;
			if (plugin.isActive())
				reload(true);
		});
	}

	@Nonnull
	public TileOverride getOverride(SceneContext sceneContext, Tile tile) {
		LocalPoint lp = tile.getLocalLocation();
		var worldPos = localToWorld(sceneContext.scene, lp.getX(), lp.getY(), tile.getRenderLevel());
		return getOverride(sceneContext, tile, worldPos);
	}

	@Nonnull
	public TileOverride getOverride(SceneContext sceneContext, @Nonnull Tile tile, @Nonnull int[] worldPos, int... ids) {
		if (ids.length == 0) {
			var pos = tile.getSceneLocation();
			int x = pos.getX() + SCENE_OFFSET;
			int y = pos.getY() + SCENE_OFFSET;
			int z = tile.getRenderLevel();
			int overlayId = OVERLAY_FLAG | sceneContext.scene.getOverlayIds()[z][x][y];
			int underlayId = sceneContext.scene.getUnderlayIds()[z][x][y];
			ids = new int[] { overlayId, underlayId };
		}
		var override = getOverrideBeforeReplacements(worldPos, ids);
		if (override.isConstant())
			return override;

		sceneContext.tileOverrideVars.setTile(tile);
		var replacement = override.resolveReplacements(sceneContext.tileOverrideVars);
		sceneContext.tileOverrideVars.setTile(null); // Avoid accidentally keeping the old scene in memory
		return replacement;
	}

	@Nonnull
	public TileOverride getOverrideBeforeReplacements(@Nonnull int[] worldPos, int... ids) {
		var match = TileOverride.NONE;

		outer:
		for (int id : ids) {
			var entries = idMatchOverrides.get(id);
			for (var entry : entries) {
				var area = entry.getKey();
				if (area.containsPoint(worldPos)) {
					match = entry.getValue();
					match.queriedAsOverlay = (id & OVERLAY_FLAG) != 0;
					break outer;
				}
			}
		}

		for (var entry : anyMatchOverrides) {
			var anyMatch = entry.getValue();
			if (anyMatch.index > match.index)
				break;
			var area = entry.getKey();
			if (area.containsPoint(worldPos)) {
				match = anyMatch;
				break;
			}
		}

		return match;
	}

}
