/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.particles.emitter.ParticleEmitterDefinition;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

/**
 * Loads and holds particle definitions from particles.json.
 * Uses {@link Props} for config path so hot-reload works with dev resource paths.
 */
@Slf4j
@Singleton
public class ParticleDefinitionLoader {

	public static final ResourcePath PARTICLES_CONFIG_PATH = Props.getFile(
		"rlhd.particles-config-path",
		() -> path(ParticleDefinitionLoader.class, "particles.json")
	);

	@Inject
	HdPlugin plugin;

	@Inject
	ClientThread clientThread;

	@Getter
	private final Map<String, ParticleEmitterDefinition> definitions = new LinkedHashMap<>();

	private FileWatcher.UnregisterCallback watcher;

	private int lastDefinitionCount;
	private long lastLoadTimeMs;

	public int getLastDefinitionCount() { return lastDefinitionCount; }
	public long getLastLoadTimeMs() { return lastLoadTimeMs; }

	/**
	 * Load config and register file watcher for hot-reload. When config changes, reloads then runs {@code onReload} on the client thread.
	 */
	public void startup(Runnable onReload) {
		watcher = PARTICLES_CONFIG_PATH.watch((path, first) -> {
			loadConfig(plugin.getGson(), path);
			clientThread.invoke(onReload);
		});
	}

	public void shutdown() {
		if (watcher != null) {
			watcher.unregister();
			watcher = null;
		}
	}

	public void loadFromDefaultPath() {
		loadConfig(plugin.getGson(), PARTICLES_CONFIG_PATH);
	}

	private void loadConfig(Gson gson, ResourcePath configPath) {
		long start = System.nanoTime();
		ParticleEmitterDefinition[] defs;
		try {
			defs = configPath.loadJson(gson, ParticleEmitterDefinition[].class);
		} catch (IOException ex) {
			log.error("[Particles] Failed to load particles.json from {}", configPath, ex);
			return;
		}
		definitions.clear();
		List<ParticleEmitterDefinition> ordered = defs != null ? new ArrayList<>(defs.length) : new ArrayList<>();
		if (defs != null) {
			for (ParticleEmitterDefinition def : defs) {
				if (def.id != null && !def.id.isEmpty())
					def.id = def.id.toUpperCase();
				def.parseHexColours();
				def.postDecode();
				if (def.id == null || def.id.isEmpty()) {
					log.warn("[Particles] Skipping definition with missing id");
					continue;
				}
				if (definitions.put(def.id, def) != null)
					log.warn("[Particles] Duplicate particle id: {}", def.id);
				ordered.add(def);
			}
			for (ParticleEmitterDefinition def : ordered) {
				def.fallbackDefinition = (def.fallbackEmitterType >= 0 && def.fallbackEmitterType < ordered.size())
					? ordered.get(def.fallbackEmitterType) : null;
			}
		}
		lastDefinitionCount = definitions.size();
		lastLoadTimeMs = (System.nanoTime() - start) / 1_000_000;
	}

	@Nullable
	public ParticleEmitterDefinition getDefinition(String id) {
		return definitions.get(id);
	}

	public List<String> getDefinitionIdsOrdered() {
		List<String> ids = new ArrayList<>(definitions.size());
		ids.addAll(definitions.keySet());
		return ids;
	}

	public List<String> getAvailableTextureNames() {
		Set<String> names = new LinkedHashSet<>();
		names.add("");
		for (ParticleEmitterDefinition def : definitions.values())
			if (def.texture != null && !def.texture.isEmpty())
				names.add(def.texture);
		return new ArrayList<>(names);
	}

	@Nullable
	public String getDefaultTexturePath() {
		for (ParticleEmitterDefinition def : definitions.values())
			if (def.texture != null && !def.texture.isEmpty())
				return def.texture;
		return null;
	}

	public static ResourcePath getParticlesConfigPath() {
		return PARTICLES_CONFIG_PATH;
	}
}
