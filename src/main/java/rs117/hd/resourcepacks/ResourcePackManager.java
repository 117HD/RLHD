package rs117.hd.resourcepacks;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;
import rs117.hd.HdPlugin;
import rs117.hd.gui.components.MessagePanel;
import rs117.hd.resourcepacks.data.LocalPackData;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;
import rs117.hd.resourcepacks.impl.FileResourcePack;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class ResourcePackManager {
	//	private static final int UPDATE_CHECK_INTERVAL = 600000; // 10 minutes
	private static final int UPDATE_CHECK_INTERVAL = 1000; // 10 minutes

	public static ResourcePath RESOURCE_PACK_DIR = path(RuneLite.RUNELITE_DIR, "117hd-resource-packs");
	public static ResourcePath RESOURCE_PACK_MANIFEST = RESOURCE_PACK_DIR.resolve("manifest.json");
	public static ResourcePath RESOURCE_PACK_DEVELOPMENT_DIR = RESOURCE_PACK_DIR.resolve("development");

	public static final HttpUrl GITHUB = HttpUrl.parse("https://github.com/117HD/resource-packs");
	public static final HttpUrl RAW_GITHUB = HttpUrl.parse("https://raw.githubusercontent.com/117HD/resource-packs");
	public static final HttpUrl RAW_GITHUB_URL = HttpUrl.parse("https://raw.githubusercontent.com/");
	public static final HttpUrl API_GITHUB = HttpUrl.parse("https://api.github.com/repos/117HD/resource-packs");

	private static final FileFilter RESOURCE_PACK_FILTER = file ->
		file.isFile() || file.isDirectory() && (new File(file, "pack.properties")).isFile();

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Getter
	private ArrayList<AbstractResourcePack> installedPacks = new ArrayList<>();

	@Getter
	private final HashMap<String, Manifest> downloadablePacks = new HashMap<>();

	@Getter
	private MessagePanel statusMessage;

	private long lastCheckForUpdates;

	public static String toInternalName(String name) {
		return name.toLowerCase().replace(" ", "_");
	}

	public static String fromInternalName(String name) {
		return WordUtils.capitalizeFully(name.replace("_", " "));
	}

	public void startUp() {
		checkForUpdates();

		if (RESOURCE_PACK_MANIFEST.exists()) {
			try {
				LocalPackData[] manifest = RESOURCE_PACK_MANIFEST.loadJson(plugin.getGson(), LocalPackData[].class);
				for (var pack : manifest) {

				}
			} catch (IOException ex) {
				log.error("Error while loading manifest:", ex);
			}
		}

		if (RESOURCE_PACK_DEVELOPMENT_DIR.exists()) {
			for (File pack : RESOURCE_PACK_DEVELOPMENT_DIR.toFile().listFiles(RESOURCE_PACK_FILTER)) {
				AbstractResourcePack iResourcePack = new FileResourcePack(pack);
				iResourcePack.setNeedsUpdating(false);
				iResourcePack.setDevelopmentPack(true);
				installedPacks.add(iResourcePack);
			}
		}

		log.info("Adding default pack");
		installedPacks.add(new DefaultResourcePack(path(HdPlugin.class, "resource-pack")));
	}

	public void shutDown() {
		installedPacks.clear();
	}

	public void verifyAndLoadPack(FileResourcePack pack) {
		String internalName = pack.getManifest().getInternalName();
		var localPack = getInstalledPack(internalName);
		if (localPack != null) {
			var downloadable = downloadablePacks.get(internalName);
			if (downloadable != null && !downloadable.getCommit().equals(localPack.getManifest().getCommit())) {
				pack.setNeedsUpdating(true);
			}
			installedPacks.add(pack);
		}
	}

	public void checkForUpdates() {
		if (System.currentTimeMillis() - lastCheckForUpdates < UPDATE_CHECK_INTERVAL)
			return;
		lastCheckForUpdates = System.currentTimeMillis();

		setStatus("Loading...", "Fetching list of resource packs...");

		okHttpClient.newCall(new Request.Builder()
				.url(Objects.requireNonNull(RAW_GITHUB).newBuilder()
					.addPathSegment("manifest")
					.addPathSegment("manifest.json")
					.build())
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

						downloadablePacks.clear();
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

					clientThread.invokeLater(() -> {
						for (var manifest : manifests)
							downloadablePacks.put(manifest.getInternalName(), manifest);

						for (File pack : RESOURCE_PACK_DIR.toFile().listFiles(RESOURCE_PACK_FILTER)) {
							try {
								verifyAndLoadPack(new FileResourcePack(pack));
							} catch (Exception ex) {
								log.warn("Unable to verify pack at '{}':", pack, ex);
							}
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

	public void downloadResourcePack(Manifest manifest) {
		final ProgressListener progressListener = new ProgressListener() {

			@Override
			public void finishedDownloading() {
				var pack = RESOURCE_PACK_DIR.resolve("pack-" + manifest.getInternalName());
				try {
					pack.mkdirs();
					unZipAll(RESOURCE_PACK_DIR.resolve(manifest.getInternalName() + ".zip").toFile(), pack.toFile());
				} catch (IOException ex) {
					log.error("Unable to unzip resource pack to the following path: {}", pack, ex);
					return;
				}

				// TODO: add to list of installed packs
//				new LocalPackData(manifest.getCommit(), manifest.getInternalName(), 0);

				verifyAndLoadPack(new FileResourcePack(pack.toFile()));

				SwingUtilities.invokeLater(() -> plugin.getSidebar().refresh());

				//SwingUtilities.invokeLater(() -> {
				//buildPackPanel();
				//});
			}

			@Override
			public void progress(long bytesRead, long contentLength) {
				SwingUtilities.invokeLater(() -> {
					long progress = (100 * bytesRead) / contentLength;
					//if (panel != null)
					//panel.progressBar.setValue((int) progress);
				});
			}

			@Override
			public void started() {
				SwingUtilities.invokeLater(() -> {
					//if (panel != null)
					//panel.progressBar.setValue(0);
					//if (panel != null)
					//panel.dropdownPanel.setVisible(false);
					//if (panel != null)
					//panel.progressPanel.setVisible(true);
				});
			}
		};

		OkHttpClient client = okHttpClient.newBuilder()
			.addNetworkInterceptor(chain -> {
				try (var res = chain.proceed(chain.request())) {
					return res.newBuilder()
						.body(new ProgressManager(res.body(), progressListener))
						.build();
				}
			})
			.build();

		URL url = HttpUrl.parse(manifest.getLink())
			.newBuilder()
			.addPathSegment("archive")
			.addPathSegment(manifest.getCommit() + ".zip")
			.build()
			.url();
		log.info("Downloading resource pack '{}' from {}", manifest.getInternalName(), url);

		client
			.newCall(new Request.Builder()
				.url(url)
				.build())
			.enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException ex) {
					log.info("Error resource pack '{}' from {}:", manifest.getInternalName(), url, ex);
				}

				@Override
				public void onResponse(Call call, Response res) throws IOException {
					File outputFile = RESOURCE_PACK_DIR.resolve(manifest.getInternalName() + ".zip").toFile();
					try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
						if (res.body() != null) {
							outputStream.write(res.body().bytes());
						} else {
							log.info("Error resource pack '{}' from {}: empty body", manifest.getInternalName(), url);
						}
					}
					progressListener.finishedDownloading();
				}
			});
	}

	public static void unZipAll(File source, File destination) throws IOException {
		log.debug("Unzipping - " + source.getName());
		int BUFFER = 2048;

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
