package rs117.hd.resourcepacks;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import rs117.hd.HdPlugin;
import rs117.hd.gui.components.MessagePanel;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;
import rs117.hd.resourcepacks.impl.FileResourcePack;
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
				AbstractResourcePack pack = new FileResourcePack(path);
				pack.setNeedsUpdating(false);
				pack.setDevelopmentPack(true);

				if (pack.isValid())
					installedPacks.add(pack);
			}
		}

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
					var path = RESOURCE_PACK_DIR.resolve("pack-" + manifest.getInternalName());
					try {
						path.mkdirs();
						unZipAll(RESOURCE_PACK_DIR.resolve(manifest.getInternalName() + ".zip").toFile(), path.toFile());
					} catch (IOException ex) {
						log.error("Unable to unzip resource pack to the following path: {}", path, ex);
						return;
					}

					SwingUtilities.invokeLater(() -> {
						var pack = new FileResourcePack(path.toFile());
						String internalName = pack.getManifest().getInternalName();

						var localPack = getInstalledPack(internalName);
						if (localPack == null)
							installedPacks.add(pack);

						plugin.getSidebar().refresh();
					});
				}
			}
		);
	}

	public static void unZipAll(File source, File destination) throws IOException {
		log.debug("Unzipping - " + source.getName());
		int BUFFER = 2048;

		if (!destination.exists()) {
			destination.mkdir();
		}

		ZipFile zip = new ZipFile(source);
		try {
			destination.getParentFile().mkdirs();
			Enumeration zipFileEntries = zip.entries();

			// Process each entry
			while (zipFileEntries.hasMoreElements()) {

				// grab a zip file entry
				ZipEntry entry = (ZipEntry) zipFileEntries.nextElement();
				String currentEntry = StringUtils.substringAfter(entry.getName(),"/");
				File destFile = new File(destination, currentEntry);
				//destFile = new File(newPath, destFile.getName());
				File destinationParent = destFile.getParentFile();

				// create the parent directory structure if needed
				destinationParent.mkdirs();

				if (!entry.isDirectory()) {
					BufferedInputStream is = null;
					FileOutputStream fos = null;
					BufferedOutputStream dest = null;
					try {
						is = new BufferedInputStream(zip.getInputStream(entry));
						int currentByte;
						// establish buffer for writing file
						byte data[] = new byte[BUFFER];

						// write the current file to disk
						fos = new FileOutputStream(destFile);
						dest = new BufferedOutputStream(fos, BUFFER);

						// read and write until last byte is encountered
						while ((currentByte = is.read(data, 0, BUFFER)) != -1) {
							dest.write(data, 0, currentByte);
						}
					} catch (Exception e) {
						log.error("unable to extract entry:" + entry.getName());
						throw e;
					} finally {
						if (dest != null) {
							dest.close();
						}
						if (fos != null) {
							fos.close();
						}
						if (is != null) {
							is.close();
						}
					}
				} else {
					//Create directory
					destFile.mkdirs();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.error("Failed to successfully unzip:" + source.getName());
		} finally {
			zip.close();
		}
		source.delete();
		log.debug("Done Unzipping:" + source.getName());
	}

	public AbstractResourcePack getInstalledPack(String internalName) {
		for (var pack : installedPacks)
			if (pack.getManifest().getInternalName().equals(internalName))
				return pack;
		return null;
	}

	public ResourcePath locateFile(String... parts) {
		for (AbstractResourcePack pack : installedPacks) {
			var path = pack.path.resolve(parts);
			if (path.exists())
				return path;
		}

		return null;
	}
}
