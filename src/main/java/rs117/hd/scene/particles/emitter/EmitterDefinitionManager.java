/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.particles.definition.ParticleDefinition;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class EmitterDefinitionManager {

	private static final ResourcePath EMITTERS_CONFIG_PATH = Props.getFile(
		"rlhd.emitters-config-path",
			() -> path(ParticleDefinition.class, "..", "emitters.json")
	);

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private AreaManager areaManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Getter
	private final List<EmitterPlacement> placements = new ArrayList<>();

	@Getter
	private final ListMultimap<Integer, EmitterConfigEntry.ObjectBinding> objectBindingsByType = ArrayListMultimap.create();

	@Getter
	private final List<ParticleEmitter> definitionEmitters = new ArrayList<>();

	@Getter
	private final List<WeatherAreaConfig> weatherAreaConfigs = new ArrayList<>();

	/** Placements at center tile of each weather AABB, for spawning like regular tile emitters. */
	@Getter
	private final List<EmitterPlacement> weatherPlacements = new ArrayList<>();

	private FileWatcher.UnregisterCallback watcher;

	@Getter
	private int lastPlacements;
	@Getter
	private int lastObjectBindings;
	@Getter
	private long lastLoadTimeMs;

	/**
	 * Load config and register file watcher for hot-reload. When config changes, reloads then runs {@code onReload} on the client thread.
	 */
	public void startup(Runnable onReload) {
		watcher = EMITTERS_CONFIG_PATH.watch((path, first) -> {
			loadConfig();
			clientThread.invoke(onReload);
		});
	}

	public void shutdown() {
		if (watcher != null) {
			watcher.unregister();
			watcher = null;
		}
	}

	/**
	 * Load from the given path (e.g. for tests or custom paths).
	 */
	public void loadConfig() {
		long start = System.nanoTime();
		try {
			EmitterConfigEntry[] entries = EMITTERS_CONFIG_PATH.loadJson(plugin.getGson(), EmitterConfigEntry[].class);
			placements.clear();
			objectBindingsByType.clear();
			weatherAreaConfigs.clear();
			weatherPlacements.clear();
			if (entries != null) {
				var objects = gamevalManager.getObjects();
				for (EmitterConfigEntry entry : entries) {
					List<String> pids = getParticleIds(entry);
					if (pids.isEmpty()) continue;
					String pid = pids.get(0);
					if (entry.placements != null) {
						for (int[] p : entry.placements) {
							if (p != null && p.length >= 3) {
								for (String pid2 : pids) {
									placements.add(new EmitterPlacement(p[0], p[1], p[2], pid2, 1f));
								}
							}
						}
					}
					if (objects != null && entry.objectEmitters != null) {
						for (EmitterConfigEntry.ObjectBinding b : entry.objectEmitters) {
							if (b == null || b.object == null || b.object.isEmpty()) continue;
							Integer id = objects.get(b.object);
							if (id != null) {
								for (String pid2 : pids) {
									var binding = new EmitterConfigEntry.ObjectBinding();
									binding.object = b.object;
									binding.offsetX = b.offsetX;
									binding.offsetY = b.offsetY;
									binding.offsetZ = b.offsetZ;
									binding.alignment = b.alignment;
									binding.particleId = pid2;
									objectBindingsByType.put(id, binding);
								}
							} else {
								log.warn("[Particles] Unknown object gameval in emitters.json: {}", b.object);
							}
						}
					}
					if (entry.weatherAreas != null && !entry.weatherAreas.isEmpty()) {
						List<AABB> aabbs = new ArrayList<>();
						for (String areaName : entry.weatherAreas) {
							var area = areaManager.getArea(areaName);
							if (area != null && !"NONE".equals(area.name) && area.aabbs != null) {
								Collections.addAll(aabbs, area.aabbs);
							} else {
								log.warn("[Particles] Unknown area in weatherAreas: {}", areaName);
							}
						}
						if (!aabbs.isEmpty()) {
							int padding = Math.max(0, entry.edgeFadeInside);
							int paddingOutside = Math.max(0, entry.edgeFadeOutside);
							int everyNTiles = Math.max(0, entry.weatherEveryNTiles);
							int spacing = Math.max(1, entry.weatherSpacing);
							weatherAreaConfigs.add(new WeatherAreaConfig(aabbs, new ArrayList<>(pids), padding, paddingOutside));
							addWeatherPlacementsStandard(aabbs, pids, padding, paddingOutside, everyNTiles, spacing, ThreadLocalRandom.current());
						}
					}
				}
			}
			lastPlacements = placements.size();
			lastObjectBindings = objectBindingsByType.size();
			lastLoadTimeMs = (System.nanoTime() - start) / 1_000_000;
		} catch (IOException ex) {
			log.error("[Particles] Failed to load emitters.json from {}", EMITTERS_CONFIG_PATH, ex);
			placements.clear();
			objectBindingsByType.clear();
			weatherAreaConfigs.clear();
			weatherPlacements.clear();
		}
	}

	private void addWeatherPlacementsStandard(List<AABB> aabbs, List<String> pids, int padding, int paddingOutside, int everyNTiles, int spacing, ThreadLocalRandom rng) {
		for (AABB aabb : aabbs) {
			List<EmitterPlacement> aabbPlacements = new ArrayList<>();
			int plane = aabbPlane(aabb);
			int width = aabb.maxX - aabb.minX + 1;
			int height = aabb.maxY - aabb.minY + 1;
			int tileCount = width * height;

			for (int x = aabb.minX; x <= aabb.maxX; x += spacing) {
				for (int y = aabb.minY; y <= aabb.maxY; y += spacing) {
					int jitterX = rng.nextInt(-spacing, spacing + 1);
					int jitterY = rng.nextInt(-spacing, spacing + 1);
					int wx = Math.max(aabb.minX, Math.min(aabb.maxX, x + jitterX));
					int wy = Math.max(aabb.minY, Math.min(aabb.maxY, y + jitterY));
					String pid2 = pids.get(rng.nextInt(pids.size()));
					aabbPlacements.add(new EmitterPlacement(wx, wy, plane, pid2, 1f));
				}
			}
			int extraCount = rng.nextInt(width * height / (spacing * spacing) + 1);
			for (int i = 0; i < extraCount; i++) {
				int wx = rng.nextInt(aabb.minX, aabb.maxX + 1);
				int wy = rng.nextInt(aabb.minY, aabb.maxY + 1);
				String pid2 = pids.get(rng.nextInt(pids.size()));
				aabbPlacements.add(new EmitterPlacement(wx, wy, plane, pid2, 1f));
			}
			if (padding > 0) {
				int edgeZoneTiles = Math.max(4, (width + height) * padding);
				for (int i = 0; i < edgeZoneTiles; i++) {
					int wx = rng.nextInt(aabb.minX, aabb.maxX + 1);
					int wy = rng.nextInt(aabb.minY, aabb.maxY + 1);
					int distToEdge = distToEdge(wx, wy, aabb);
					if (distToEdge >= padding) continue;
					float t = (float) distToEdge / padding;
					if (rng.nextFloat() > t) continue;
					String pid2 = pids.get(rng.nextInt(pids.size()));
					aabbPlacements.add(new EmitterPlacement(wx, wy, plane, pid2, t));
				}
				if (paddingOutside > 0) {
					int outsideTiles = Math.max(4, (width + height) * paddingOutside);
					for (int i = 0; i < outsideTiles; i++) {
						int wx = rng.nextInt(aabb.minX - paddingOutside, aabb.maxX + paddingOutside + 1);
						int wy = rng.nextInt(aabb.minY - paddingOutside, aabb.maxY + paddingOutside + 1);
						if (wx >= aabb.minX && wx <= aabb.maxX && wy >= aabb.minY && wy <= aabb.maxY) continue;
						int distOutside = distOutside(wx, wy, aabb);
						if (distOutside > paddingOutside) continue;
						float t = 1f - (float) distOutside / paddingOutside;
						if (rng.nextFloat() > t * t) continue;
						String pid2 = pids.get(rng.nextInt(pids.size()));
						aabbPlacements.add(new EmitterPlacement(wx, wy, plane, pid2, t));
					}
				}
			}

			if (everyNTiles > 0 && tileCount > 0 && aabbPlacements.size() > 0) {
				int maxPlacements = Math.max(1, tileCount / everyNTiles);
				if (aabbPlacements.size() > maxPlacements) {
					// Stratified sampling: divide area into cells, pick one per cell to avoid gaps
					int n = Math.max(1, (int) Math.sqrt(maxPlacements));
					int numCellsX = Math.max(1, Math.min(n, width));
					int numCellsY = Math.max(1, Math.min(n, height));
					List<List<EmitterPlacement>> cells = new ArrayList<>(numCellsX * numCellsY);
					for (int c = 0; c < numCellsX * numCellsY; c++)
						cells.add(new ArrayList<>());
					for (EmitterPlacement p : aabbPlacements) {
						int cx = (p.getWorldX() - aabb.minX) * numCellsX / width;
						int cy = (p.getWorldY() - aabb.minY) * numCellsY / height;
						cx = Math.max(0, Math.min(numCellsX - 1, cx));
						cy = Math.max(0, Math.min(numCellsY - 1, cy));
						cells.get(cy * numCellsX + cx).add(p);
					}
					aabbPlacements = new ArrayList<>();
					for (List<EmitterPlacement> cell : cells) {
						if (!cell.isEmpty())
							aabbPlacements.add(cell.get(rng.nextInt(cell.size())));
					}
					if (aabbPlacements.size() > maxPlacements) {
						Collections.shuffle(aabbPlacements, rng);
						aabbPlacements = aabbPlacements.subList(0, maxPlacements);
					}
				}
			}
			weatherPlacements.addAll(aabbPlacements);
		}
	}

	private static int distToEdge(int wx, int wy, AABB aabb) {
		int distX = Math.min(wx - aabb.minX, aabb.maxX - wx);
		int distY = Math.min(wy - aabb.minY, aabb.maxY - wy);
		return Math.min(distX, distY);
	}

	private static int distOutside(int wx, int wy, AABB aabb) {
		int d = Integer.MAX_VALUE;
		if (wx < aabb.minX) d = Math.min(d, aabb.minX - wx);
		if (wx > aabb.maxX) d = Math.min(d, wx - aabb.maxX);
		if (wy < aabb.minY) d = Math.min(d, aabb.minY - wy);
		if (wy > aabb.maxY) d = Math.min(d, wy - aabb.maxY);
		return d == Integer.MAX_VALUE ? 0 : d;
	}

	private static int aabbPlane(AABB aabb) {
		if (aabb.hasZ()) return Math.max(0, Math.min(2, aabb.minZ));
		return 0;
	}

	private static List<String> getParticleIds(EmitterConfigEntry entry) {
		if (entry.particleIds != null && !entry.particleIds.isEmpty()) {
			return entry.particleIds.stream().map(String::toUpperCase).toList();
		}
		if (entry.particleId != null && !entry.particleId.isEmpty()) {
			return List.of(entry.particleId.toUpperCase());
		}
		return List.of();
	}

}
