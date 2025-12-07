package rs117.hd.resourcepacks;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.gui.HdSidebar;
import rs117.hd.gui.components.MessagePanel;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;
import rs117.hd.resourcepacks.impl.FileResourcePack;
import rs117.hd.resourcepacks.impl.ZipResourcePack;
import rs117.hd.utils.FileDownloader;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class ResourcePackManager {

	public static ResourcePath RESOURCE_PACK_DIR = Props.getFolder("117hd-resource-packs", () -> path(new File(RuneLite.RUNELITE_DIR,"117hd-resource-packs").getPath()));
	public static ResourcePath RESOURCE_PACK_PROPS = Props.getFile("117hd-resource-pack-commits", () -> path(new File(RuneLite.RUNELITE_DIR,"117hd-resource-packs").getPath(), "commits.properties"));

	public static final HttpUrl GITHUB = HttpUrl.get("https://github.com/117HD/resource-packs");
	public static final HttpUrl RESOURCE_PACKS_MANIFEST_URL = HttpUrl.get(
		"https://raw.githubusercontent.com/117HD/resource-packs/manifest/manifest.json");
	public static final HttpUrl RAW_GITHUB_URL = HttpUrl.get("https://raw.githubusercontent.com/");
	public static final HttpUrl API_GITHUB = HttpUrl.get("https://api.github.com/repos/117HD/resource-packs");

	private static final int MAX_UPDATE_CHECK_INTERVAL = 600000; // 10 minutes
	private static final FileFilter RESOURCE_PACK_FILTER = file ->
		file.isFile() || file.isDirectory() && (new File(file, "pack.properties")).isFile();

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private HdPlugin plugin;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ConfigManager configManager;

	@Getter
	private ArrayList<AbstractResourcePack> installedPacks = new ArrayList<>();

	@Getter
	private final HashMap<String, Manifest> downloadablePacks = new HashMap<>();

	@Getter
	private MessagePanel statusMessage;

	private long lastCheckForUpdates;

	public void startUp() {

		if (!config.enableResourcePacks()) {
			installedPacks.add(new DefaultResourcePack(path(HdPlugin.class, "resource-pack")));
			return;
		}
		SwingUtilities.invokeLater(() -> plugin.sidebar = plugin.getInjector().getInstance(HdSidebar.class));
		
		Properties commitHashes = loadCommitHashes();

		if (RESOURCE_PACK_DIR.exists()) {
			if (RESOURCE_PACK_DIR.exists()) {
				File[] files = RESOURCE_PACK_DIR.toFile().listFiles(RESOURCE_PACK_FILTER);
				if (files != null) {
					for (File file : files) {
						boolean isZip = file.isFile() && file.getName().endsWith(".zip");
						boolean hasPackProperties = file.isDirectory() && new File(file, "pack.properties").exists();

						if (!isZip && !hasPackProperties) {
							continue;
						}

						if (isOrphanedZipPack(file, commitHashes)) {
							deleteOrphanedZipPack(file);
							continue;
						}

						AbstractResourcePack pack = createResourcePack(file);

						if (pack.isValid()) {
							installedPacks.add(pack);
						}
					}
				}
			}
		}
		installedPacks.add(new DefaultResourcePack(path(HdPlugin.class, "resource-pack")));

		checkForUpdates();
		plugin.configLegacyTzHaarReskin = isEnabled("tzhaar_reskin");
	}

	private void migrateLegacyConfigsInternal() {
		if (config.legacyTzHaarReskin()) {
			downloadLegacyPackIfNeeded("tzhaar_reskin", "TzHaar Reskin",KEY_LEGACY_TZHAAR_RESKIN);
		}

		if (config.legacyTobEnvironment()) {
			downloadLegacyPackIfNeeded("legacy_theatre_of_blood", "Theatre of Blood",KEY_LEGACY_TOB_ENVIRONMENT);
		}
	}

	private void downloadLegacyPackIfNeeded(String internalName, String displayName, String keyName) {
		Manifest manifest = downloadablePacks.get(internalName);
		if (manifest != null) {
			if (getInstalledPack(internalName) == null) {
				log.info("Auto-downloading legacy pack '{}' due to legacy config being enabled", internalName);
				downloadResourcePack(manifest);
				configManager.setConfiguration("hd",keyName,false);
			}
		} else {
			log.warn("Could not find legacy pack '{}' ({}) in downloadable packs", internalName, displayName);
		}
	}

	public void shutDown() {
		installedPacks.clear();
		if (plugin.sidebar != null)
			plugin.sidebar.destroy();
		plugin.sidebar = null;
	}

	public void checkForUpdates() {
		if (System.currentTimeMillis() - lastCheckForUpdates < MAX_UPDATE_CHECK_INTERVAL)
			return;
		lastCheckForUpdates = System.currentTimeMillis();

		setStatus("Loading...", "Fetching list of resource packs...");

		okHttpClient
			.newCall(new Request.Builder()
				.url(RESOURCE_PACKS_MANIFEST_URL)
				.build())
			.enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException ex) {
					log.error("Unable to download manifest:", ex);
					// Allow retrying without delay
					lastCheckForUpdates = 0;

					setStatus(
						"Network Error",
						"Check your network connection.<br>"
						+ "Join our Discord server if the issue persists."
					);
				}

				@Override
				public void onResponse(Call call, Response res) {
					Manifest[] manifests;
					try {
						if (res.body() == null)
							throw new IllegalStateException("Manifest is null");

						manifests = plugin.getGson().fromJson(res.body().string(), Manifest[].class);
					} catch (Exception ex) {
						log.error("Error while reading downloaded manifest:", ex);
						setStatus(
							"Malformed Manifest",
							"Something went wrong with our system...<br>"
							+ "Join our Discord server for further information."
						);
						return;
					}

					if (manifests.length == 0) {
						setStatus(
							"No packs available",
							"There are currently no packs available for download."
						);
						return;
					}

					SwingUtilities.invokeLater(() -> {
						downloadablePacks.clear();

						for (var manifest : manifests) {
							downloadablePacks.put(manifest.getInternalName(), manifest);
						}

						checkAndUpdateOutdatedPacks();

						setStatus(null, null);

						migrateLegacyConfigsInternal();
					});
				}
			});
	}

	private void setStatus(String title, String description) {
		SwingUtilities.invokeLater(() -> {
			if (title == null) {
				statusMessage = null;
			} else {
				statusMessage = new MessagePanel(title, description);
			}
			var sidebar = plugin.getSidebar();
			if (sidebar != null)
				sidebar.refresh();
		});
	}

	public void removeResourcePack(String internalName) {
		// Find the pack before removing it
		AbstractResourcePack packToRemove = null;
		for (var pack : installedPacks) {
			if (pack.getManifest().getInternalName().equals(internalName)) {
				packToRemove = pack;
				break;
			}
		}
		
		if (packToRemove == null) {
			log.warn("Attempted to remove pack '{}' but it was not found", internalName);
			return;
		}
		
		// Don't delete the default pack
		if (packToRemove instanceof DefaultResourcePack) {
			log.warn("Attempted to remove default pack, ignoring");
			return;
		}
		
		// Store file path before removing from list
		File fileToDelete = null;
		if (packToRemove.path.isFileSystemResource()) {
			fileToDelete = packToRemove.path.toFile();
		}
		
		// Remove from the list first
		installedPacks.remove(packToRemove);
		
		// Close the zip file if it's a ZipResourcePack to release the file lock
		if (packToRemove instanceof ZipResourcePack) {
			((ZipResourcePack) packToRemove).close();
		}
		
		// Delete the zip file from disk
		if (fileToDelete != null && fileToDelete.exists() && fileToDelete.isFile()) {
			try {
				fileToDelete.delete();
			} catch (Exception e) {
				log.error("Error deleting resource pack file for '{}':", internalName, e);
			}
		}
		
		removeCommitHash(internalName);
		
		plugin.getSidebar().refresh();
		eventBus.post(new ResourcePackUpdate(PackEventType.REMOVED, packToRemove));
	}

	public void downloadResourcePack(Manifest manifest) {
		downloadResourcePack(manifest, null, null, null);
	}

	public void downloadResourcePack(Manifest manifest, java.util.function.Consumer<Integer> onProgress, Runnable onSuccess, Runnable onFailure) {
		if (!RESOURCE_PACK_DIR.exists()) {
			RESOURCE_PACK_DIR.toFile().mkdir();
		}

		URL url = HttpUrl.parse(manifest.getLink())
			.newBuilder()
			.addPathSegment("archive")
			.addPathSegment(manifest.getCommit() + ".zip")
			.build()
			.url();

		// Use file size from manifest if available
		Long expectedFileSize = manifest.getFileSize();

		FileDownloader downloader = new FileDownloader();
		downloader.downloadFile(
			url.toString(),
			RESOURCE_PACK_DIR.resolve(manifest.getInternalName() + ".zip").toFile(),
			expectedFileSize,
			new FileDownloader.DownloadListener() {
				@Override
				public void onStarted() {
					log.info("Downloading resource pack '{}' from {}", manifest.getInternalName(), url);
				}

				@Override
				public void onFailure(Call call, IOException e) {
					log.info("Error while downloading resource pack '{}' from {}:", manifest.getInternalName(), url, e);
					if (onFailure != null) {
						onFailure.run();
					}
				}

				@Override
				public void onProgress(int progress) {
					if (onProgress != null) {
						onProgress.accept(progress);
					}
				}

				@Override
				public void onFinished() {
					File zipFile = RESOURCE_PACK_DIR.resolve(manifest.getInternalName() + ".zip").toFile();

					SwingUtilities.invokeLater(() -> {
						AbstractResourcePack pack = createResourcePack(zipFile);

						String internalName = pack.getManifest().getInternalName();

						var localPack = getInstalledPack(internalName);
						if (localPack == null) {
							// Add before the default pack (which is always at the last index)
							int lastIndex = installedPacks.size() - 1;
							installedPacks.add(lastIndex, pack);
						} else {
							// Replace existing pack if updating
							int index = installedPacks.indexOf(localPack);
							if (index >= 0) {
								// Close old pack if it's a zip
								if (localPack instanceof ZipResourcePack) {
									((ZipResourcePack) localPack).close();
								}
								installedPacks.set(index, pack);
							}
						}

						saveCommitHash(internalName, manifest.getCommit());

						plugin.getSidebar().refresh();
						eventBus.post(new ResourcePackUpdate(PackEventType.ADDED, pack, manifest));
						
						if (onSuccess != null) {
							onSuccess.run();
						}
					});
				}
			}
		);
	}

	public AbstractResourcePack getInstalledPack(String internalName) {
		for (var pack : installedPacks)
			if (pack.getManifest().getInternalName().equals(internalName))
				return pack;
		return null;
	}

	public boolean isEnabled(String internalName) {
		return getInstalledPack(internalName) != null;
	}

	public ResourcePath locateFile(String... parts) {
		AbstractResourcePack pack = locatePack(parts);
		return pack != null ? pack.getResource(parts) : null;
	}

	public AbstractResourcePack locatePack(String... parts) {
		for (AbstractResourcePack pack : installedPacks) {
			if (pack.hasResource(parts)) {
				return pack;
			}
		}
		return null;
	}

	/**
	 * Checks if a file is an orphaned zip pack (not tracked in commit hashes).
	 */
	private boolean isOrphanedZipPack(File file, Properties commitHashes) {
		if (!file.isFile() || !file.getName().toLowerCase().endsWith(".zip")) {
			return false;
		}
		
		String internalName = file.getName().substring(0, file.getName().length() - 4);
		return !commitHashes.containsKey(internalName);
	}

	/**
	 * Deletes an orphaned zip pack file.
	 */
	private void deleteOrphanedZipPack(File file) {
		String internalName = file.getName().substring(0, file.getName().length() - 4);
		log.info("Deleting orphaned zip pack '{}' (not found in commit hashes)", internalName);
		
		if (file.delete()) {
			log.debug("Deleted orphaned zip pack: {}", file.getAbsolutePath());
		} else {
			log.warn("Failed to delete orphaned zip pack: {}", file.getAbsolutePath());
		}
	}

	/**
	 * Checks for outdated packs by comparing stored commit hashes with manifest,
	 * and automatically re-downloads any outdated packs.
	 */
	private void checkAndUpdateOutdatedPacks() {
		Properties commitHashes = loadCommitHashes();
		ArrayList<Manifest> packsToUpdate = new ArrayList<>();

		for (var pack : installedPacks) {
			if (pack instanceof DefaultResourcePack) {
				continue;
			}

			String internalName = pack.getManifest().getInternalName();
			Manifest manifest = downloadablePacks.get(internalName);

			if (manifest == null) {
				continue;
			}

			String storedCommit = commitHashes.getProperty(internalName);
			String manifestCommit = manifest.getCommit();

			if (storedCommit == null || !storedCommit.equals(manifestCommit)) {
				packsToUpdate.add(manifest);
			}
		}

		if (!packsToUpdate.isEmpty()) {
			List<String> namesList = packsToUpdate.stream()
				.map(Manifest::getInternalName)
				.collect(Collectors.toList());

			String names = String.join(", ", namesList);
			log.info("{} | Packs outdated: {}", namesList.size(), names);
		}

		for (var manifest : packsToUpdate) {
			removeResourcePack(manifest.getInternalName());
			downloadResourcePack(manifest);
		}
	}
	/**
	 * Loads commit hashes from the properties file.
	 * @return Properties object containing internalName -> commitHash mappings
	 */
	private Properties loadCommitHashes() {
		Properties props = new Properties();
		File propsFile = RESOURCE_PACK_PROPS.toFile();
		
		if (propsFile.exists() && propsFile.isFile()) {
			try (FileInputStream fis = new FileInputStream(propsFile)) {
				props.load(fis);
			} catch (IOException e) {
				log.warn("Failed to load commit hashes from {}: {}", propsFile, e.getMessage());
			}
		}
		
		return props;
	}

	/**
	 * Saves a commit hash for a pack to the properties file.
	 * @param internalName The internal name of the pack
	 * @param commitHash The commit hash to save
	 */
	private void saveCommitHash(String internalName, String commitHash) {
		Properties props = loadCommitHashes();
		props.setProperty(internalName, commitHash);
		
		File propsFile = RESOURCE_PACK_PROPS.toFile();
		try {
			// Ensure parent directory exists
			propsFile.getParentFile().mkdirs();
			
			try (FileOutputStream fos = new FileOutputStream(propsFile)) {
				props.store(fos, "Resource pack commit hashes - internalName:commitHash");
			}
		} catch (IOException e) {
			log.warn("Failed to save commit hash for pack '{}': {}", internalName, e.getMessage());
		}
	}

	/**
	 * Removes a commit hash from the properties file.
	 * @param internalName The internal name of the pack to remove
	 */
	private void removeCommitHash(String internalName) {
		Properties props = loadCommitHashes();
		if (props.remove(internalName) != null) {
			File propsFile = RESOURCE_PACK_PROPS.toFile();
			try {
				try (FileOutputStream fos = new FileOutputStream(propsFile)) {
					props.store(fos, "Resource pack commit hashes - internalName:commitHash");
				}
			} catch (IOException e) {
				log.warn("Failed to remove commit hash for pack '{}': {}", internalName, e.getMessage());
			}
		}
	}

	/**
	 * Creates an appropriate ResourcePack instance based on the file type.
	 * @param file The file or directory representing the resource pack
	 * @return ZipResourcePack if the file is a .zip, otherwise FileResourcePack
	 */
	private AbstractResourcePack createResourcePack(File file) {
		String fileName = file.getName().toLowerCase();
		AbstractResourcePack pack;
		if (fileName.endsWith(".zip")) {
			pack = new ZipResourcePack(file);
			pack.setDevelopmentPack(false);
		} else {
			pack = new FileResourcePack(file);
			pack.setDevelopmentPack(true);
		}
		return pack;
	}
}
