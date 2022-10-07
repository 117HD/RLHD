package rs117.hd.resourcepacks;

import com.google.gson.Gson;
import net.runelite.client.RuneLite;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.text.WordUtils;
import rs117.hd.resourcepacks.data.Manifest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

public class Constants {

    public static OkHttpClient CLIENT = new OkHttpClient();
    public static Gson GSON = new Gson();

    public static final HttpUrl GITHUB = HttpUrl.parse("https://github.com/117HD/resource-packs");
    public static final HttpUrl RAW_GITHUB = HttpUrl.parse("https://raw.githubusercontent.com/117HD/resource-packs");
    public static final HttpUrl RAW_GITHUB_URL = HttpUrl.parse("https://raw.githubusercontent.com/");
    public static final HttpUrl API_GITHUB = HttpUrl.parse("https://api.github.com/repos/117HD/resource-packs");
    public static File PACK_DIR = new File(RuneLite.RUNELITE_DIR, "117-resource-packs");

    public static String toInternalName(String name) {
        return name.toLowerCase().replace(" ", "_");
    }

    public static String fromInternalName(String name) {
        return WordUtils.capitalizeFully(name.replace("_", " ").toLowerCase());
    }


    public static BufferedImage downloadIcon(Manifest plugin) throws IOException {
        if (!plugin.isHasIcon()) {
            return null;
        }

        HttpUrl url = RAW_GITHUB_URL.newBuilder().addPathSegment(plugin.getLink().replace("https://github.com/", ""))
           .addPathSegment(plugin.getCommit())
           .addPathSegment("icon.png")
        .build();

        try (Response res = CLIENT.newCall(new Request.Builder().url(url).build()).execute()) {
            byte[] bytes = res.body().bytes();
            // We don't stream so the lock doesn't block the edt trying to load something at the same time
            synchronized (ImageIO.class) {
                return ImageIO.read(new ByteArrayInputStream(bytes));
            }
        }
    }

}
