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
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.data.environments.Area;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.VariableSupplier;

import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.localToWorld;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class TileOverrideManager {
	private static final ResourcePath TILE_OVERRIDES_PATH = Props.getPathOrDefault(
		"rlhd.tile-overrides-path",
		() -> path(TileOverrideManager.class, "tile_overrides.json")
	);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ModelPusher modelPusher;

	private FileWatcher.UnregisterCallback fileWatcher;
	private ResourcePath tileOverridesPath;
	private boolean trackReplacements;
	private List<Map.Entry<Area, TileOverride>> anyMatchOverrides;
	private ListMultimap<Integer, Map.Entry<Area, TileOverride>> idMatchOverrides;
	private final HslVariables hslVars = new HslVariables();

	public void startUp() {
		fileWatcher = TILE_OVERRIDES_PATH.watch((path, first) -> {
			tileOverridesPath = path;
			reload(!first);
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		anyMatchOverrides = null;
		idMatchOverrides = null;
	}

	public void reload(boolean reloadScene) {
		if (tileOverridesPath == null)
			return;

		try {
			TileOverride[] allOverrides = tileOverridesPath.loadJson(plugin.getGson(), TileOverride[].class);
			if (allOverrides == null)
				throw new IOException("Empty or invalid: " + tileOverridesPath);

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

			// Substitute constants in replacement expressions and simplify
			Map<String, Object> constants = new HashMap<>();
			for (var season : SeasonalTheme.values())
				constants.put(season.name(), season.ordinal());
			constants.put("season", plugin.configSeasonalTheme.ordinal());
			constants.put("blending", plugin.configGroundBlending);
			constants.put("textures", plugin.configGroundTextures);

			for (int i = 0; i < allOverrides.length; i++) {
				var override = allOverrides[i];
				try {
					override.index = i;
					override.normalize(allOverrides, constants);
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

		if (reloadScene) {
			clientThread.invoke(() -> {
				modelPusher.clearModelCache();
				if (client.getGameState() == GameState.LOGGED_IN)
					client.setGameState(GameState.LOADING);
			});
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
		trackReplacements = shouldTrackReplacements;
		if (plugin.isActive())
			reload(true);
	}

	@NonNull
	public TileOverride getOverride(Scene scene, Tile tile) {
		return getOverride(scene, tile, null);
	}

	@NonNull
	public TileOverride getOverride(Scene scene, Tile tile, @Nullable int[] worldPos, int... ids) {
		if (worldPos == null) {
			LocalPoint lp = tile.getLocalLocation();
			worldPos = localToWorld(scene, lp.getX(), lp.getY(), tile.getRenderLevel());
		}
		if (ids.length == 0) {
			var pos = tile.getSceneLocation();
			int x = pos.getX() + SceneUploader.SCENE_OFFSET;
			int y = pos.getY() + SceneUploader.SCENE_OFFSET;
			int z = tile.getRenderLevel();
			int overlayId = OVERLAY_FLAG | scene.getOverlayIds()[z][x][y];
			int underlayId = scene.getUnderlayIds()[z][x][y];
			ids = new int[] { overlayId, underlayId };
		}
		var override = getOverrideBeforeReplacements(worldPos, ids);
		return resolveReplacements(override, tile);
	}

	@NonNull
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
			var override = entry.getValue();
			if (override.index > match.index)
				break;
			var area = entry.getKey();
			if (area.containsPoint(worldPos)) {
				match = override;
				break;
			}
		}

		return match;
	}

	public TileOverride resolveReplacements(TileOverride override, Tile tile) {
		var replacement = resolveNextReplacement(override, tile);
		if (replacement != override)
			replacement = resolveReplacements(replacement, tile);
		return replacement;
	}

	public TileOverride resolveNextReplacement(TileOverride override, Tile tile) {
		if (override.replacements != null) {
			hslVars.setTile(tile);
			for (var exprReplacement : override.replacements) {
				var replacement = override;
				if (exprReplacement.predicate.test(hslVars))
					replacement = exprReplacement.replacement;
				if (replacement == null)
					return TileOverride.NONE;
				if (replacement != override) {
					replacement.queriedAsOverlay = override.queriedAsOverlay;
					return replacement;
				}
			}
			// Avoid accidentally keeping the old scene in memory
			hslVars.setTile(null);
		}

		return override;
	}

	private static class HslVariables implements VariableSupplier {
		private final String[] HSL_VARS = { "h", "s", "l" };
		private final int[] hsl = new int[3];

		private Tile tile;
		private boolean requiresHslUpdate;

		public void setTile(Tile tile) {
			if (tile == this.tile)
				return;
			this.tile = tile;
			requiresHslUpdate = true;
		}

		@Override
		public Object get(String variableName) {
			for (int i = 0; i < HSL_VARS.length; i++) {
				if (HSL_VARS[i].equals(variableName)) {
					if (requiresHslUpdate) {
						HDUtils.getSouthWesternMostTileColor(hsl, tile);
						requiresHslUpdate = false;
					}
					return hsl[i];
				}
			}

			throw new IllegalArgumentException("Undefined variable '" + variableName + "'");
		}
	}
}
