/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.effector;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.particles.definition.ParticleDefinition;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class EffectorDefinitionManager {
	public static final ResourcePath EFFECTORS_CONFIG_PATH = Props.getFile(
		"rlhd.effectors-config-path",
		() -> path(ParticleDefinition.class, "..", "effectors.json")
	);

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Getter
	private final Map<String, EffectorDefinition> definitions = new LinkedHashMap<>();
	@Getter
	private final List<EffectorPlacement> placements = new ArrayList<>();

	private FileWatcher.UnregisterCallback watcher;

	@Getter
	private int lastDefinitionCount;
	@Getter
	private long lastLoadTimeMs;

	public void startup(Runnable onReload) {
		watcher = EFFECTORS_CONFIG_PATH.watch((p, first) -> {
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

	public void loadConfig() {
		long start = System.nanoTime();
		Gson gson = plugin.getGson();
		EffectorDefinition[] defs;
		try {
			defs = EFFECTORS_CONFIG_PATH.loadJson(gson, EffectorDefinition[].class);
		} catch (IOException ex) {
			log.error("[Particles] Failed to load effectors.json from {}", EFFECTORS_CONFIG_PATH, ex);
			return;
		}

		definitions.clear();
		placements.clear();
		if (defs != null) {
			for (EffectorDefinition def : defs) {
				if (def == null) {
					continue;
				}
				if (def.id != null && !def.id.isEmpty()) {
					def.id = def.id.toUpperCase();
				}
				def.postDecode();
				if (def.id == null || def.id.isEmpty()) {
					log.warn("[Particles] Skipping effector with missing id");
					continue;
				}
				if (definitions.put(def.id, def) != null) {
					log.warn("[Particles] Duplicate effector id: {}", def.id);
				}
				if (def.placements != null) {
					for (int[] p : def.placements) {
						if (p == null || p.length < 3) {
							continue;
						}
						placements.add(new EffectorPlacement(p[0], p[1], p[2], def.id));
					}
				}
			}
		}

		lastDefinitionCount = definitions.size();
		lastLoadTimeMs = (System.nanoTime() - start) / 1_000_000;
	}

	@Nullable
	public EffectorDefinition getDefinition(String id) {
		if (id == null) {
			return null;
		}
		return definitions.get(id.toUpperCase());
	}
}
