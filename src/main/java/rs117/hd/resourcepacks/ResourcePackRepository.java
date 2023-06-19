package rs117.hd.resourcepacks;

import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import rs117.hd.HdPlugin;
import rs117.hd.gui.panel.InstalledPacksPanel;
import rs117.hd.resourcepacks.data.LocalPackData;
import rs117.hd.resourcepacks.data.Manifest;
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

    public boolean packsLoaded = false;

    public ResourcePackRepository(File dirResourcepacksIn, File dirDevResourcepacksIn, IResourcePack rprDefaultResourcePackIn) {
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

        if (loadManifest()) {
            for (File pack : Objects.requireNonNull(dirResourcepacksIn.listFiles(resourcePackFilter))) {
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

    public boolean presentInLocalManifest(String name) {
        return manifestLocal.stream().anyMatch(it -> it.getInternalName().equalsIgnoreCase(name));
    }

    public LocalPackData localManifest(String name) {
        return manifestLocal.stream().filter(it -> it.getInternalName().equalsIgnoreCase(name)).findFirst().get();
    }

}
