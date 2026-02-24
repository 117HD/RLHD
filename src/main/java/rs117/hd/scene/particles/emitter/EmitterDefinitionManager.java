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
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.particles.ParticleDefinitionLoader;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class EmitterDefinitionManager {

	private static final ResourcePath EMITTERS_CONFIG_PATH = Props.getFile(
		"rlhd.emitters-config-path",
			() -> path(ParticleDefinitionLoader.class, "emitters.json")
	);

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Getter
	private final List<EmitterPlacement> placements = new ArrayList<>();

	@Getter
	private final ListMultimap<Integer, String> objectEmittersByType = ArrayListMultimap.create();

	/** Object id -> (particleId, offsetX, offsetY, offsetZ). Use this when spawning to apply offsets. */
	@Getter
	private final ListMultimap<Integer, ObjectEmitterBinding> objectBindingsByType = ArrayListMultimap.create();

	@Getter
	private final List<ParticleEmitter> definitionEmitters = new ArrayList<>();

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
			objectEmittersByType.clear();
			objectBindingsByType.clear();
			if (entries != null) {
				var objects = gamevalManager.getObjects();
				for (EmitterConfigEntry entry : entries) {
					if (entry.particleId == null || entry.particleId.isEmpty()) continue;
					String pid = entry.particleId.toUpperCase();
					if (entry.placements != null) {
						for (int[] p : entry.placements) {
							if (p != null && p.length >= 3) {
								EmitterPlacement pl = new EmitterPlacement();
								pl.worldX = p[0];
								pl.worldY = p[1];
								pl.plane = p[2];
								pl.particleId = pid;
								placements.add(pl);
							}
						}
					}
					if (objects != null) {
						if (entry.objectEmitters != null) {
							for (String name : entry.objectEmitters) {
								if (name == null || name.isEmpty()) continue;
								Integer id = objects.get(name);
								if (id != null) {
									objectEmittersByType.put(id, pid);
									objectBindingsByType.put(id, new ObjectEmitterBinding(pid, 0, 0, 0));
								} else {
									log.warn("[Particles] Unknown object gameval in emitters.json: {}", name);
								}
							}
						}
						if (entry.objectProperties != null) {
							for (ObjectEmitterProperty prop : entry.objectProperties) {
								if (prop == null || prop.object == null || prop.object.isEmpty()) continue;
								Integer id = objects.get(prop.object);
								if (id != null) {
									objectBindingsByType.put(id, new ObjectEmitterBinding(
										pid, prop.offsetX, prop.offsetY, prop.offsetZ));
								} else {
									log.warn("[Particles] Unknown object gameval in objectProperties: {}", prop.object);
								}
							}
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
			objectEmittersByType.clear();
			objectBindingsByType.clear();
		}
	}

}
