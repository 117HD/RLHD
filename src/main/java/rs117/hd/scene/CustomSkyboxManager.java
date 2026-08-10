package rs117.hd.scene;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

/**
 * Loads user-supplied skybox textures from {@code <RuneLite home>/117hd/custom-skyboxes/},
 * allowing custom skyboxes to be added without rebuilding the plugin. Supports equirectangular
 * images, 6-face cubemaps, and unwrapped horizontal-cross cubemap images.
 * <p>
 * If a {@code manifest.json} is present in that folder, it takes full control over the available
 * skyboxes (name, type, files). Otherwise, every supported image file found directly in the
 * folder is exposed as an equirectangular skybox named after its filename, so that simply
 * dropping a single panorama image in works with no configuration at all.
 */
@Slf4j
@Singleton
public class CustomSkyboxManager {
	private static final String[] SUPPORTED_IMAGE_EXTENSIONS = { "png", "jpg", "jpeg" };
	private static final ResourcePath CUSTOM_SKYBOX_DIR = Props
		.getFolder("rlhd.custom-skyboxes-path", () -> HdPlugin.PLUGIN_DIR.resolve("custom-skyboxes"));

	@Inject
	private HdPlugin plugin;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private TextureManager textureManager;

	private FileWatcher.UnregisterCallback fileWatcher;
	private ScheduledFuture<?> debounce;
	private Map<String, CustomSkyboxEntry> skyboxesByName = new HashMap<>();

	public void startUp() {
		// The directory must exist before a filesystem watcher can be registered on it
		CUSTOM_SKYBOX_DIR.mkdirs();

		fileWatcher = CUSTOM_SKYBOX_DIR.watch((path, first) -> {
			if (first) {
				reload();
				return;
			}

			// Debounce in case several files change at once (e.g. copying in a cubemap's 6 faces)
			if (debounce == null || debounce.cancel(false) || debounce.isDone())
				debounce = executor.schedule(this::reload, 200, TimeUnit.MILLISECONDS);
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		if (debounce != null)
			debounce.cancel(false);
		debounce = null;
		skyboxesByName = new HashMap<>();
	}

	private void reload() {
		CustomSkyboxEntry[] manifestEntries = null;
		try {
			manifestEntries = CUSTOM_SKYBOX_DIR.resolve("manifest.json").loadJson(plugin.getGson(), CustomSkyboxEntry[].class);
		} catch (IOException ignored) {
			// No manifest.json, or it's invalid - fall back to auto-detecting image files below
		}

		var map = new HashMap<String, CustomSkyboxEntry>();
		if (manifestEntries != null && manifestEntries.length > 0) {
			for (var entry : manifestEntries) {
				if (entry.name == null)
					continue;
				if (entry.type == null)
					entry.type = "equirect";
				map.put(entry.name, entry);
			}
		} else {
			for (String filename : listImageFilenames()) {
				var entry = new CustomSkyboxEntry();
				entry.name = stripExtension(filename);
				entry.type = "equirect";
				entry.file = filename;
				map.put(entry.name, entry);
			}
		}

		skyboxesByName = map;
		log.debug("Loaded {} custom skybox(es)", map.size());
	}

	private List<String> listImageFilenames() {
		File[] files = CUSTOM_SKYBOX_DIR.toFile().listFiles();
		if (files == null)
			return List.of();

		var names = new ArrayList<String>();
		outer:
		for (var file : files) {
			if (!file.isFile())
				continue;
			String lowerName = file.getName().toLowerCase();
			for (var ext : SUPPORTED_IMAGE_EXTENSIONS) {
				if (lowerName.endsWith("." + ext)) {
					names.add(file.getName());
					continue outer;
				}
			}
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	private static String stripExtension(String filename) {
		int i = filename.lastIndexOf('.');
		return i < 0 ? filename : filename.substring(0, i);
	}

	public List<String> getAvailableNames() {
		return new ArrayList<>(skyboxesByName.keySet());
	}

	public File getDirectory() {
		return CUSTOM_SKYBOX_DIR.toFile();
	}

	/**
	 * Looks up an entry by name, tolerating names given with or without a file extension (e.g.
	 * both "sunset" and "sunset.png" resolve the same entry), since File Explorer hides
	 * extensions by default on Windows and users commonly copy a name with or without one.
	 */
	public CustomSkyboxEntry getEntry(String name) {
		var entry = skyboxesByName.get(name);
		if (entry != null)
			return entry;
		return skyboxesByName.get(stripExtension(name));
	}

	public BufferedImage loadEquirectImage(CustomSkyboxEntry entry) {
		return textureManager.loadImage(CUSTOM_SKYBOX_DIR, entry.file, SUPPORTED_IMAGE_EXTENSIONS);
	}

	public BufferedImage[] loadCubemapFaceImages(CustomSkyboxEntry entry) {
		var faces = new BufferedImage[6];
		for (int i = 0; i < 6; i++)
			faces[i] = textureManager.loadImage(CUSTOM_SKYBOX_DIR, entry.faces[i], SUPPORTED_IMAGE_EXTENSIONS);
		return faces;
	}

	public BufferedImage[] loadCubemapCrossImage(CustomSkyboxEntry entry) {
		return TextureManager.sliceHorizontalCross(textureManager.loadImage(CUSTOM_SKYBOX_DIR, entry.file, SUPPORTED_IMAGE_EXTENSIONS));
	}
}
