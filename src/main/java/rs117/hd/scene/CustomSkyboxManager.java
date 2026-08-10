package rs117.hd.scene;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.scene.customskybox.CubemapUtils;
import rs117.hd.scene.customskybox.CustomSkyboxEntry;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

/**
 * Loads user-supplied skybox textures from a manifest file under
 * {@code <RuneLite home>/117hd/custom-skyboxes/manifest.json}, allowing custom skyboxes to be
 * added without rebuilding the plugin. Supports equirectangular images, 6-face cubemaps, and
 * unwrapped horizontal-cross cubemap images.
 */
@Slf4j
@Singleton
public class CustomSkyboxManager {
	private static final ResourcePath CUSTOM_SKYBOX_DIR = Props
		.getFolder("rlhd.custom-skyboxes-path", () -> HdPlugin.PLUGIN_DIR.resolve("custom-skyboxes"));

	@Inject
	private HdPlugin plugin;

	private FileWatcher.UnregisterCallback fileWatcher;
	private Map<String, CustomSkyboxEntry> skyboxesByName = new HashMap<>();

	public void startUp() {
		var manifestPath = CUSTOM_SKYBOX_DIR.resolve("manifest.json");
		// The directory must exist before a filesystem watcher can be registered on it
		manifestPath.mkdirs();

		fileWatcher = manifestPath.watch((path, first) -> {
			try {
				var entries = path.loadJson(plugin.getGson(), CustomSkyboxEntry[].class);
				var map = new HashMap<String, CustomSkyboxEntry>();
				if (entries != null)
					for (var entry : entries)
						if (entry.name != null)
							map.put(entry.name, entry);
				skyboxesByName = map;
				log.debug("Loaded {} custom skybox(es)", map.size());
			} catch (IOException ex) {
				if (!first)
					log.warn("Failed to load custom skybox manifest: {}", path, ex);
				skyboxesByName = new HashMap<>();
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		skyboxesByName = new HashMap<>();
	}

	public List<String> getAvailableNames() {
		return new ArrayList<>(skyboxesByName.keySet());
	}

	public CustomSkyboxEntry getEntry(String name) {
		return skyboxesByName.get(name);
	}

	public BufferedImage loadEquirectImage(CustomSkyboxEntry entry) throws IOException {
		return CUSTOM_SKYBOX_DIR.resolve(entry.file).loadImage();
	}

	public BufferedImage[] loadCubemapFaceImages(CustomSkyboxEntry entry) throws IOException {
		var faces = new BufferedImage[6];
		for (int i = 0; i < 6; i++)
			faces[i] = CUSTOM_SKYBOX_DIR.resolve(entry.faces[i]).loadImage();
		return faces;
	}

	public BufferedImage[] loadCubemapCrossImage(CustomSkyboxEntry entry) throws IOException {
		return CubemapUtils.sliceHorizontalCross(CUSTOM_SKYBOX_DIR.resolve(entry.file).loadImage());
	}
}
