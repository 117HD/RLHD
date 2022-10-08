package rs117.hd.resourcepacks;

import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.gui.panel.ResourcePackPanel;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.data.PackData;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.io.*;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static rs117.hd.resourcepacks.Constants.*;

@Slf4j
public class ResourcePackManager {

    @Inject
    HdPlugin plugin;

    @Getter
    public ResourcePackPanel panel;

    private boolean alreadyDownloading = false;

    private final HashMap<String, Manifest> currentManifest = new HashMap<>();
    public HashMap<String, File> installedPacks = new HashMap<>();

    public ResourcePackManager(ResourcePackPanel panel) {
        this.panel = panel;
    }

    public void startup() {
        loadManifest();
    }

    public void shutdown() {

    }

    public void loadManifest() {
        panel.messagePanel.setContent("Loading..", "Loading Manifest");

        HttpUrl url = Objects.requireNonNull(RAW_GITHUB).newBuilder()
                .addPathSegment("manifest")
                .addPathSegment("manifest.json")
        .build();

        try (Response res = CLIENT.newCall(new Request.Builder().url(url).build()).execute()) {
            String content;
            if (res.body() != null) {
                content = res.body().string();
                Type types = new TypeToken<ArrayList<Manifest>>() {}.getType();
                ArrayList<Manifest> manifestList = GSON.fromJson(content, types);
                manifestList.forEach(manifest -> currentManifest.put(manifest.getInternalName(), manifest));
                loadPacks();
            } else {
                panel.messagePanel.setContent("Error", "Unable to get manifest.json content");
                panel.installedDropdown.setVisible(false);
            }

        } catch (IOException e) {
            panel.messagePanel.setContent("Error", "Unable to load manifest.json");
            log.error("Unable to download manifest.json ", e);
            panel.installedDropdown.setVisible(false);
        }

    }

    public void loadPacks() {

        panel.executor.submit(() -> {
            if (PACK_DIR.exists()) {
                for (File file : Objects.requireNonNull(PACK_DIR.listFiles())) {
                    addPackZip(file, false);
                }
            }
        });

        buildPackList();

        if(getActivePack() != null) {
            System.out.println("Active Pack: " + getActivePack());
            panel.installedDropdown.setSelectedItem(fromInternalName(getActivePack()));
        }

    }

    public void buildPackList() {
        panel.packList.removeAll();
        currentManifest.forEach((internalName, manifest) -> {
            panel.packList.add(new ResourcePackComponent(manifest, panel.executor, this));
        });

        panel.messagePanel.setVisible(false);
    }

    public void addPackZip(File file, boolean updatePanel) {
        boolean validPack = validZip(file);
        if (validPack) {
            String name = file.getName().replace(".zip", "");
            Manifest manifest = currentManifest.get(name);
            installedPacks.put(manifest.getInternalName(), file);
            panel.installedDropdown.removeItem(fromInternalName(name));
            panel.installedDropdown.addItem(fromInternalName(name));
            if (updatePanel) {
                buildPackList();
            }
        }
    }


    public boolean validZip(File file) {
        if (!currentManifest.containsKey(file.getName().replace(".zip", ""))) {
            return false;
        }

        if (file.isDirectory()) {
            return false;
        }
        if (!file.getName().toLowerCase().contains(".zip")) {
            return false;
        }

        try (ZipFile zipFile = new ZipFile(file)) {
            boolean foundFile = zipFile.stream().anyMatch(it -> it.getName().contains("pack.properties"));
            zipFile.close();
            return foundFile;
        } catch (IOException e) {
            log.info("Unable to add Resource Pack {}", file.getPath());
            e.printStackTrace();
            return false;
        }
    }



    public void downloadResourcePack(Manifest manifest, ScheduledExecutorService executor) {
        if(alreadyDownloading) {
            return;
        }
        if (!PACK_DIR.exists()) {
            PACK_DIR.mkdirs();
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
                    SwingUtilities.invokeLater(() -> {
                        installedPacks.put(manifest.getInternalName(), null);
                        addPackZip(new File(PACK_DIR, manifest.getInternalName() + ".zip"), true);
                        panel.progressBar.setValue(0);
                        panel.dropdownPanel.setVisible(true);
                        panel.progressPanel.setVisible(false);
                        alreadyDownloading = false;
                    });
                }

                @Override
                public void progress(long bytesRead, long contentLength) {
                    SwingUtilities.invokeLater(() -> {
                        long progress = (100 * bytesRead) / contentLength;
                        System.out.println(progress);
                        panel.progressBar.setValue((int) progress);
                    });
                }

                @Override
                public void started() {
                    alreadyDownloading = true;
                    SwingUtilities.invokeLater(() -> {
                        panel.progressBar.setValue(0);
                        panel.dropdownPanel.setVisible(false);
                        panel.progressPanel.setVisible(true);
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
                    currentManifest.remove(manifest.getInternalName());
                }
                File outputFile = new File(PACK_DIR, manifest.getInternalName() + ".zip");
                try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                    if (response.body() != null) {
                        outputStream.write(response.body().bytes());
                    } else {
                        log.info("Error Downloading Pack {} at {}", manifest.getInternalName(), url.getPath());
                        currentManifest.remove(manifest.getInternalName());
                    }
                }
                progressListener.finishedDownloading();
            } catch (IOException e) {
                log.info("Error Downloading Pack {} at {}", manifest.getInternalName(), url.getPath());
                currentManifest.remove(manifest.getInternalName());
            }
        });

    }

    public void setActivePack(String name) {
        String internalName = toInternalName(name);
        panel.getPlugin().getConfigManager().setConfiguration(HdPluginConfig.RESOURCE_PACK_GROUP_NAME, "selectedHubPack",internalName);
        loadPackData(internalName);
    }

    public void loadPackData(String internalName) {
        if (!internalName.equalsIgnoreCase("Default") && installedPacks.containsKey(internalName)) {
            try {
                ZipFile zipFile = new ZipFile(installedPacks.get(internalName));
                PackData pack = new PackData();
                Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
                while (zipEntries.hasMoreElements()) {
                    ZipEntry enrty = zipEntries.nextElement();
                    if (enrty.getName().contains("/materials/")) {
                        String name = StringUtils.substringAfterLast(enrty.getName(), "/");
                        pack.getMaterials().put(name, ImageIO.read(zipFile.getInputStream(enrty)));
                    }
                }
                reloadResourcePack(pack, internalName);
            } catch (IOException e) {
                reloadResourcePack(null, internalName);
                log.info("Error loading Resource pack data {} ", internalName);
                e.printStackTrace();
            }
        } else {
            reloadResourcePack(null, internalName);
        }
    }

    public void reloadResourcePack(PackData pack, String internalName) {
        if(panel.getPlugin().currentPack == pack) {
            return;
        }
        panel.getPlugin().currentPack = pack;
        panel.installedDropdown.setSelectedItem(Constants.fromInternalName(internalName));
        panel.getPlugin().getEventBus().post(new PackChangedEvent(internalName));
    }

    public void uninstallPack(File file) {
        if (file.delete()) {
            String name = file.getName().replace(".zip", "");
            String formattedName = fromInternalName(name);
            installedPacks.remove(name);
            if (formattedName.equalsIgnoreCase(getActivePack())) {
                panel.installedDropdown.setSelectedItem("Default");
            }
            panel.installedDropdown.removeItem(formattedName);
            log.info("Pack {} has been removed", file.getName());
        }
    }

    public String getActivePack() {
        return panel.getPlugin().getConfigManager().getConfiguration(HdPluginConfig.RESOURCE_PACK_GROUP_NAME, "selectedHubPack");
    }

}
