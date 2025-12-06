package rs117.hd.resourcepacks;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.EventBus;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rs117.hd.HdPlugin;
import rs117.hd.gui.components.MessagePanel;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;
import rs117.hd.resourcepacks.impl.FileResourcePack;
import rs117.hd.resourcepacks.impl.ZipResourcePack;
import rs117.hd.utils.FileDownloader;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class ResourcePackManager {

	public static ResourcePath RESOURCE_PACK_DIR = Props.getFolder("117hd-resource-packs", () -> path(new File(RuneLite.RUNELITE_DIR,"117hd-resource-packs").getPath()));

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

	@Getter
	private ArrayList<AbstractResourcePack> installedPacks = new ArrayList<>();

	@Getter
	private final HashMap<String, Manifest> downloadablePacks = new HashMap<>();

	@Getter
	private MessagePanel statusMessage;

	private long lastCheckForUpdates;

	public void startUp() {
		checkForUpdates();

		if (RESOURCE_PACK_DIR.exists()) {
			for (File path : RESOURCE_PACK_DIR.toFile().listFiles(RESOURCE_PACK_FILTER)) {
				AbstractResourcePack pack = createResourcePack(path);
				pack.setNeedsUpdating(false);

				if (pack.isValid())
					installedPacks.add(pack);
			}
		}

		// Add default pack at the end (it must always be at the bottom)
		installedPacks.add(new DefaultResourcePack(path(HdPlugin.class, "resource-pack")));
	}

	public void shutDown() {
		installedPacks.clear();
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

							var installed = getInstalledPack(manifest.getInternalName());
							if (installed == null || installed.getManifest().getCommit().equals(manifest.getCommit()))
								continue;

							installed.setNeedsUpdating(true);
						}

						setStatus(null, null);
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
		installedPacks.removeIf(p -> p.getManifest().getInternalName().equals(internalName));
		plugin.getSidebar().refresh();
		eventBus.post(new ResourcePackUpdate());
	}

	public void downloadResourcePack(Manifest manifest) {
		if (!RESOURCE_PACK_DIR.exists()) {
			RESOURCE_PACK_DIR.toFile().mkdir();
		}

		URL url = HttpUrl.parse(manifest.getLink())
			.newBuilder()
			.addPathSegment("archive")
			.addPathSegment(manifest.getCommit() + ".zip")
			.build()
			.url();


		FileDownloader downloader = new FileDownloader();
		downloader.downloadFile(
			url.toString(),
			RESOURCE_PACK_DIR.resolve(manifest.getInternalName() + ".zip").toFile(),
			new FileDownloader.DownloadListener() {
				@Override
				public void onStarted() {
					log.info("Downloading resource pack '{}' from {}", manifest.getInternalName(), url);
				}

				@Override
				public void onFailure(Call call, IOException e) {
					log.info("Error while downloading resource pack '{}' from {}:", manifest.getInternalName(), url, e);
				}

				@Override
				public void onProgress(int progress) {
					System.out.println("Progress: " + progress);

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
						}

						plugin.getSidebar().refresh();
						eventBus.post(new ResourcePackUpdate());
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
