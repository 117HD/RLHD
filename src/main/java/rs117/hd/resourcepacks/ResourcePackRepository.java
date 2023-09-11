package rs117.hd.resourcepacks;

import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
import java.util.concurrent.atomic.AtomicInteger;

import static rs117.hd.resourcepacks.Constants.*;

@Slf4j
public class ResourcePackRepository {

    private static final FileFilter resourcePackFilter = file -> {
        boolean flag = file.isFile() && file.getName().endsWith(".zip");
        boolean flag1 = file.isDirectory() && (new File(file, "pack.properties")).isFile();
        return flag || flag1;
    };

    public final IResourcePack rprDefaultResourcePackIn;
    @Getter
    List<AbstractResourcePack> repository = new ArrayList<AbstractResourcePack>();
    @Getter
    private final HashMap<String, Manifest> manifestOnline = new HashMap<>();
    @Getter
    List<LocalPackData> manifestLocal = new ArrayList<LocalPackData>();

    @Setter
    @Getter
    InstalledPacksPanel packPanel;

    @Inject
    HdPlugin plugin;

	private boolean alreadyDownloading = false;

    public boolean packsLoaded = false;

    public ResourcePackRepository(File dirResourcepacksIn, File dirDevResourcepacksIn, DefaultResourcePack rprDefaultResourcePackIn) {
        this.rprDefaultResourcePackIn = rprDefaultResourcePackIn;

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

        if (loadManifest()) {
            for (File pack : Objects.requireNonNull(dirResourcepacksIn.listFiles(resourcePackFilter))) {
                AbstractResourcePack iResourcePack = new FileResourcePack(pack);
                Manifest packManifest = iResourcePack.getPackMetadata();
                String internalName = packManifest.getInternalName();
				System.out.println(internalName);
                if (presentInLocalManifest(packManifest.getInternalName())) {
                    LocalPackData localPackData = localManifest(packManifest.getInternalName());
                    if (manifestOnline.containsKey(packManifest.getInternalName())) {
                        if (manifestOnline.get(internalName).getCommit() != localPackData.getCommitHash()) {
                            iResourcePack.setNeedsUpdating(true);
                        }
                        repository.add(iResourcePack);
                    }
                }
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

        packsLoaded = true;

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
					if (new File(PACK_DIR,"pack-" + manifest.getInternalName()).mkdirs()) {
						manifestLocal.add(new LocalPackData(manifest.getCommit(), manifest.getInternalName(), manifestLocal.size() + 1));
						AbstractResourcePack iResourcePack = new FileResourcePack(new File(PACK_DIR, manifest.getInternalName() + ".zip"));
						Manifest packManifest = iResourcePack.getPackMetadata();
						String internalName = packManifest.getInternalName();
						if (presentInLocalManifest(packManifest.getInternalName())) {
							LocalPackData localPackData = localManifest(packManifest.getInternalName());
							if (manifestOnline.containsKey(packManifest.getInternalName())) {
								if (!Objects.equals(manifestOnline.get(internalName).getCommit(), localPackData.getCommitHash())) {
									iResourcePack.setNeedsUpdating(true);
								}
								repository.add(iResourcePack);
							}
						}

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

    public boolean presentInLocalManifest(String name) {
        return manifestLocal.stream().anyMatch(it -> it.getInternalName().equalsIgnoreCase(name));
    }

    public LocalPackData localManifest(String name) {
        return manifestLocal.stream().filter(it -> it.getInternalName().equalsIgnoreCase(name)).findFirst().get();
    }

}
