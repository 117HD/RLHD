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
import rs117.hd.gui.panel.ResourcePackPanel;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.data.PackData;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.*;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static rs117.hd.resourcepacks.Constants.*;

@Slf4j
public class ResourcePackManager {

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
                locateInstalledPacks();
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

    public void locateInstalledPacks() {

        if (!PACK_DIR.exists()) {
            PACK_DIR.mkdirs();
        }

        panel.executor.submit(() -> {
            Arrays.stream(Objects.requireNonNull(PACK_DIR.listFiles())).filter(it -> it.getName().startsWith("pack-") && it.isDirectory()).forEach(this::addPack);
        });

        Arrays.stream(Objects.requireNonNull(PACK_DIR.listFiles())).filter(it -> it.getName().startsWith("devpack-") && it.isDirectory()).forEach( dir -> {
            Manifest manifest = new Manifest();
            String name = dir.getName();
            manifest.setInternalName(toInternalName(name));
            manifest.setDescription("Dev Pack: " + name);
            manifest.setDev(true);
            currentManifest.put(manifest.getInternalName(),manifest);
            installedPacks.put(toInternalName(name), dir);
            panel.installedDropdown.addItem(name);
            log.info("Resource Pack {} has been added", toInternalName(name));
        });

        buildPackPanel();
        loadDropdownItems();
    }

    public void buildPackPanel() {
        panel.packList.removeAll();
        currentManifest.forEach((internalName, manifest) -> {
            panel.packList.add(new ResourcePackComponent(manifest, panel.executor, this));
        });

        panel.messagePanel.setVisible(false);
    }

    public ItemListener dropdownListener() {
        return e -> {
            if(e.getStateChange() == ItemEvent.SELECTED) {
                setCurrentPack(e.getItem().toString());
            }
        };
    }

    public void loadDropdownItems() {
        ItemListener listener = dropdownListener();
        panel.installedDropdown.removeItemListener(listener);
        if(panel.installedDropdown.getItemCount() != 0) {
            panel.installedDropdown.removeAllItems();
        }
        panel.installedDropdown.addItem("Default");
        installedPacks.forEach((internalName, manifest) -> {
            panel.installedDropdown.addItem(fromInternalName(internalName));
        });

        String packedSelected = panel.getPlugin().getConfig().packName();
        if (currentManifest.containsKey(packedSelected)) {
            panel.installedDropdown.setSelectedItem(fromInternalName(packedSelected));
        } else {
            panel.installedDropdown.setSelectedItem("Default");
        }
        panel.installedDropdown.addItemListener(listener);
    }

    public void addPack(File file) {

        if (!new File(file, "pack.properties").exists()) {
            log.info("{} does not contain pack.properties", file.getPath());
            return;
        }

        Properties props = new Properties();

        try {
            props.load(Files.newInputStream(new File(file, "pack.properties").toPath()));
        } catch (IOException e) {
            log.info("{} unable to read pack.properties please add a display name", file.getPath());
            return;
        }

        String name = props.getProperty("displayName");
        installedPacks.put(toInternalName(name), file);
        panel.installedDropdown.addItem(name);
        log.info("Resource Pack {} has been added", toInternalName(name));

    }


    public void downloadResourcePack(Manifest manifest, ScheduledExecutorService executor) {
        if(alreadyDownloading) {
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
                    new File(PACK_DIR,"pack-" + manifest.getInternalName()).mkdirs();

                    try {
                        unZipAll(new File(PACK_DIR, manifest.getInternalName() + ".zip"), new File(PACK_DIR,"pack-" + manifest.getInternalName()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    installedPacks.put(manifest.getInternalName(), new File(PACK_DIR,"pack-" + manifest.getInternalName()));
                    SwingUtilities.invokeLater(() -> {
                        panel.progressBar.setValue(0);
                        panel.dropdownPanel.setVisible(true);
                        panel.progressPanel.setVisible(false);
                        buildPackPanel();
                    });
                    alreadyDownloading = false;
                    loadDropdownItems();
                }

                @Override
                public void progress(long bytesRead, long contentLength) {
                    SwingUtilities.invokeLater(() -> {
                        long progress = (100 * bytesRead) / contentLength;
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
                System.out.println(currentEntry);
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

    public void setCurrentPack(String name) {
        String internalName = toInternalName(name);
        panel.getPlugin().getConfig().setPackName(internalName);
        loadPackData(internalName);
    }

    public void loadPackData(String internalName) {
        if (!internalName.equalsIgnoreCase("Default") && installedPacks.containsKey(internalName) ) {
            if (panel.getPlugin().currentPack != null && panel.getPlugin().currentPack.getInternalName().equals(internalName)) {
                activatePack(panel.getPlugin().currentPack, internalName);
                return;
            }
            PackData pack = new PackData();
            pack.setInternalName(internalName);
            File baseDir = installedPacks.get(internalName);

            for (File texture : Objects.requireNonNull(new File(baseDir, "materials").listFiles())) {
                try {
                    pack.getMaterials().put(texture.getName(), ImageIO.read(texture));
                } catch (IOException e) {
                    log.error("Error loading Resource pack textures {} ", internalName);
                    e.printStackTrace();
                }
            }

            activatePack(pack, internalName);
        } else {
            activatePack(null, internalName);
        }

    }

    public void activatePack(PackData pack, String internalName) {
        if(panel.getPlugin().currentPack == pack) {
            return;
        }
        log.info("Resource pack {} enabled.", internalName);
        panel.getPlugin().currentPack = pack;
        panel.installedDropdown.setSelectedItem(Constants.fromInternalName(internalName));
        panel.getPlugin().getEventBus().post(new PackChangedEvent(internalName));
    }

    public void uninstallPack(File file, String internalName) {
        if(deleteDirectory(file)) {
            String formattedName = fromInternalName(internalName);
            installedPacks.remove(internalName);
            if (formattedName.equalsIgnoreCase(panel.getPlugin().getConfig().packName())) {
                panel.installedDropdown.setSelectedItem("Default");
            }
            panel.installedDropdown.removeItem(formattedName);
            log.info("Pack {} has been removed", file.getName());
        }
    }

    boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }

}
