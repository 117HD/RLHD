package rs117.hd.resourcepacks;

import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import rs117.hd.HdPlugin;
import rs117.hd.gui.panel.InstalledPacksPanel;
import rs117.hd.resourcepacks.data.LocalPackData;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;
import rs117.hd.resourcepacks.impl.FileResourcePack;

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.resourcepacks.Constants.*;

@Slf4j
public class ResourcePackRepository {

    private static final FileFilter resourcePackFilter = file -> {
        boolean flag = file.isFile();
        boolean flag1 = file.isDirectory() && (new File(file, "pack.properties")).isFile();
        return flag || flag1;
    };

    public final IResourcePack rprDefaultResourcePackIn;
    @Getter
    List<AbstractResourcePack> repository = new ArrayList<>();
    @Getter
    private final HashMap<String, Manifest> manifestOnline = new HashMap<>();
    @Getter
    List<LocalPackData> manifestLocal = new ArrayList<>();

    @Setter
    @Getter
    InstalledPacksPanel packPanel;

	private boolean alreadyDownloading = false;

    public boolean packsLoaded = false;

	File dirResourcepacksIn;
	File dirDevResourcepacksIn;
    public ResourcePackRepository(File dirResourcepacksIn, File dirDevResourcepacksIn, DefaultResourcePack rprDefaultResourcePackIn) {
        this.rprDefaultResourcePackIn = rprDefaultResourcePackIn;
		this.dirResourcepacksIn = dirResourcepacksIn;
		this.dirDevResourcepacksIn = dirDevResourcepacksIn;
        List<AbstractResourcePack> repositoryTemp = new ArrayList<AbstractResourcePack>();
        File localManifest = new File(PACK_DIR_ROOT, "117-manifestLocal.json");

        if (localManifest.exists()) {
            try {
                manifestLocal = GSON.fromJson(new FileReader(localManifest), new TypeToken<ArrayList<LocalPackData>>() {}.getType());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

		repository.add(rprDefaultResourcePackIn);

		loadPacks();

		packsLoaded = true;

    }

	public void loadPacks() {
		if (loadManifest()) {
			for (File pack : Objects.requireNonNull(dirResourcepacksIn.listFiles(resourcePackFilter))) {
				verifyPack(pack);
			}
		}

		if (DEV_PACK_DIR.exists()) {
			for (File pack : Objects.requireNonNull(dirDevResourcepacksIn.listFiles(resourcePackFilter))) {
				AbstractResourcePack iResourcePack = new FileResourcePack(pack);
				iResourcePack.setNeedsUpdating(false);
				iResourcePack.setDevelopmentPack(true);
				repository.add(iResourcePack);
			}
		}

		repository.stream().filter(AbstractResourcePack::isNeedsUpdating).forEach(pack -> {
			//UPDATE ANY PACK HERE THAT NEEDS UPDATING AND THE USER HAS INSTALLED
		});

		Collections.reverse(repository);
	}

	public void verifyPack(File pack) {
		AbstractResourcePack iResourcePack = new FileResourcePack(pack);
		Manifest packManifest = iResourcePack.getPackMetadata();
		String internalName = packManifest.getInternalName();
		if (presentInLocalManifest(packManifest.getInternalName())) {
			LocalPackData localPackData = localManifest(packManifest.getInternalName());
			if (manifestOnline.containsKey(packManifest.getInternalName())) {
				if (manifestOnline.get(internalName).getCommit() != localPackData.getCommitHash()) {
					iResourcePack.setNeedsUpdating(true);
				}
				repository.add(iResourcePack);
			}
		}
		Collections.reverse(repository);
		if (packPanel != null) {
			packPanel.populatePacks();
		}
	}

    public boolean loadManifest() {
        HttpUrl url = Objects.requireNonNull(RAW_GITHUB).newBuilder()
                .addPathSegment("manifest")
                .addPathSegment("manifest.json")
                .build();

        try (Response res = CLIENT.newCall(new Request.Builder().url(url).build()).execute()) {
            if (res.body() != null) {
                Type types = new TypeToken<ArrayList<Manifest>>() {}.getType();
                ArrayList<Manifest> manifestList = GSON.fromJson(res.body().string(), types);
                manifestList.forEach(manifest -> manifestOnline.put(manifest.getInternalName(), manifest));
            }
            return res.body() != null;
        } catch (IOException e) {
            log.error("Unable to download manifest.json ", e);
            return false;
        }
    }

	public void downloadResourcePack(Manifest manifest, ScheduledExecutorService executor) {
		if (alreadyDownloading) {
			return;
		}

		executor.submit(() -> {
			URL url = Objects.requireNonNull(HttpUrl.parse(manifest.getLink())).newBuilder()
				.addPathSegment("archive")
				.addPathSegment(manifest.getCommit() + ".zip")
				.build().url();

			log.info("Downloading Pack {} at {}", manifest.getInternalName(), url.getPath());

			Request request = new Request.Builder().url(url).build();
			final ProgressListener progressListener = new ProgressListener() {

				@Override
				public void finishedDownloading() {
					File pack = new File(PACK_DIR,"pack-" + manifest.getInternalName());
					if (pack.mkdirs()) {
						try {
							unZipAll(new File(PACK_DIR, manifest.getInternalName() + ".zip"),pack);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
						manifestLocal.add(new LocalPackData(manifest.getCommit(), manifest.getInternalName(), 0));

						verifyPack(pack);

						alreadyDownloading = false;
						//SwingUtilities.invokeLater(() -> {
							//buildPackPanel();
						//});
					}
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
					alreadyDownloading = true;
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

			OkHttpClient client = new OkHttpClient.Builder().addNetworkInterceptor(chain -> {
				Response originalResponse = chain.proceed(chain.request());
				return originalResponse.newBuilder()
					.body(new ProgressManager(originalResponse.body(), progressListener))
					.build();
			}).build();

			try (Response response = client.newCall(request).execute()) {
				if (!response.isSuccessful()) {
					log.info("Error Downloading Pack {} at {}", manifest.getInternalName(), url.getPath());
					manifestLocal.remove(manifest);
				}
				File outputFile = new File(PACK_DIR, manifest.getInternalName() + ".zip");
				try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
					if (response.body() != null) {
						outputStream.write(response.body().bytes());
					} else {
						log.info("Error Downloading Pack {} at {}", manifest.getInternalName(), url.getPath());
						manifestLocal.remove(manifest);
					}
				}
				progressListener.finishedDownloading();
			} catch (IOException e) {
				log.info("Error Downloading Pack {} at {}", manifest.getInternalName(), url.getPath());
				manifestLocal.remove(manifest);
			}
		});
	}

	public static void unZipAll(File source, File destination) throws IOException {
		System.out.println("Unzipping - " + source.getName());
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
						System.out.println("unable to extract entry:" + entry.getName());
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
			System.out.println("Failed to successfully unzip:" + source.getName());
		} finally {
			zip.close();
		}
		source.delete();
		System.out.println("Done Unzipping:" + source.getName());
	}

    public boolean presentInLocalManifest(String name) {
        return manifestLocal.stream().anyMatch(it -> it.getInternalName().equalsIgnoreCase(name));
    }

	public ResourcePath locateFile(String... parts) {
		for (AbstractResourcePack pack : repository) {
			boolean exists = pack.getResource(parts).exists();
			if (exists) {
				System.out.println(pack.getPackName());
				return pack.getResource(parts);
			}
		}

		return null;
	}

    public LocalPackData localManifest(String name) {
        return manifestLocal.stream().filter(it -> it.getInternalName().equalsIgnoreCase(name)).findFirst().get();
    }

}
