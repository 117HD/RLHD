package rs117.hd.scene;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

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
			TileOverride[] entries = tileOverridesPath.loadJson(plugin.getGson(), TileOverride[].class);
			if (entries == null)
				throw new IOException("Empty or invalid: " + tileOverridesPath);

			List<Map.Entry<Area, TileOverride>> anyMatch = new ArrayList<>();
			ListMultimap<Integer, Map.Entry<Area, TileOverride>> idMatch = ArrayListMultimap.create();

			// Substitute constants in replacement expressions and simplify
			Map<String, Object> constants = new HashMap<>();
			for (var season : SeasonalTheme.values())
				constants.put(season.name(), season.ordinal());
			constants.put("season", plugin.configSeasonalTheme.ordinal());
			constants.put("blending", plugin.configGroundBlending);
			constants.put("textures", plugin.configGroundTextures);

			for (TileOverride override : entries) {
				override.normalize(entries, constants);
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

			log.debug("Loaded {} tile overrides", entries.length);
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

	public void setTrackReplacements(boolean shouldTrackReplacements) {
		trackReplacements = shouldTrackReplacements;
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
		return getOverrideBeforeReplacements(worldPos, ids)
			.resolveReplacements(scene, tile, plugin);
	}

	@NonNull
	public TileOverride getOverrideBeforeReplacements(@Nonnull int[] worldPos, int... ids) {
		var match = TileOverride.NONE;

		outer:
		for (int id : ids) {
			var entries = idMatchOverrides.get(id);
			if (entries != null) {
				for (var entry : entries) {
					var area = entry.getKey();
					if (area.containsPoint(worldPos)) {
						match = entry.getValue();
						match.queriedAsOverlay = (id & OVERLAY_FLAG) != 0;
						break outer;
					}
				}
			}
		}

		for (var entry : anyMatchOverrides) {
			var area = entry.getKey();
			var override = entry.getValue();
			if (override.index > match.index)
				break;
			if (area.containsPoint(worldPos)) {
				match = override;
				break;
			}
		}

		return match;
	}
}
