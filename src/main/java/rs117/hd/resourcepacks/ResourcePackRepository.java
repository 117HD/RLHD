package rs117.hd.resourcepacks;

import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import rs117.hd.HdPlugin;
import rs117.hd.resourcepacks.data.LocalPackData;
import rs117.hd.resourcepacks.data.Manifest;

import javax.inject.Inject;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static rs117.hd.resourcepacks.Constants.*;

@Slf4j
public class ResourcePackRepository {

    private static final FileFilter resourcePackFilter = file -> {
        boolean flag = file.isFile() && file.getName().endsWith(".zip");
        boolean flag1 = file.isDirectory() && (new File(file, "pack.properties")).isFile();
        return flag || flag1;
    };

    public final IResourcePack rprDefaultResourcePackIn;
    List<AbstractResourcePack> repository = new ArrayList<AbstractResourcePack>();
    private final HashMap<String, Manifest> manifestOnline = new HashMap<>();
    List<LocalPackData> manifestLocal = new ArrayList<LocalPackData>();

    public ResourcePackRepository(File dirResourcepacksIn, File dirDevResourcepacksIn, IResourcePack rprDefaultResourcePackIn) {
        this.rprDefaultResourcePackIn = rprDefaultResourcePackIn;

        Type types = new TypeToken<ArrayList<LocalPackData>>() {}.getType();
        File localManifest = new File(PACK_DIR, "117-manifestLocal.json");

        if (localManifest.exists()) {
            try {
                manifestLocal = GSON.fromJson(new FileReader(localManifest), types);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (loadManifest()) {
            for (File pack : dirResourcepacksIn.listFiles(resourcePackFilter)) {
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
            for (File pack : dirDevResourcepacksIn.listFiles(resourcePackFilter)) {
                AbstractResourcePack iResourcePack = new FileResourcePack(pack);
                iResourcePack.setNeedsUpdating(false);
                iResourcePack.setDevelopmentPack(true);
                repository.add(iResourcePack);
            }
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

    public boolean presentInLocalManifest(String name) {
        return manifestLocal.stream().anyMatch(it -> it.getInternalName().equalsIgnoreCase(name));
    }

    public LocalPackData localManifest(String name) {
        return manifestLocal.stream().filter(it -> it.getInternalName().equalsIgnoreCase(name)).findFirst().get();
    }

}
