package rs117.hd.resourcepacks;

import com.google.common.base.Charsets;
import com.google.gson.JsonParseException;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ResourcePath;
import sun.jvm.hotspot.oops.Metadata;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Properties;

@Slf4j
public abstract class AbstractResourcePack implements IResourcePack {

    public Manifest manifest;
    public final ResourcePath resourcePackFile;

    public void setNeedsUpdating(boolean needsUpdating) {
        this.needsUpdating = needsUpdating;
    }

    private boolean needsUpdating = false;

    public void setDevelopmentPack(boolean developmentPack) {
        this.developmentPack = developmentPack;
    }

    private boolean developmentPack = false;

    public AbstractResourcePack(ResourcePath resourcePackFileIn) {
        this.resourcePackFile = resourcePackFileIn;
    }

    protected abstract boolean hasResourceName(String name);


    protected abstract InputStream getInputStreamByName(String name) throws IOException;

    protected void logNameNotLowercase(String p_110594_1_) {
        log.warn("ResourcePack: ignored non-lowercase namespace: {} in {}", new Object[]{p_110594_1_, this.resourcePackFile});
    }

    public Manifest getPackMetadata() {
        try {
            if (manifest == null) {
                manifest = readMetadata(this.getInputStreamByName("pack.properties"));
            }
            return manifest;
        } catch (IOException e) {
            System.out.println("COULD NOT LOAD");
            return null;
        }
    }

    public static Manifest readMetadata(InputStream inputStream) {
        BufferedReader bufferedreader = new BufferedReader(new InputStreamReader(inputStream, Charsets.UTF_8));
        Manifest metadata = null;
        try {
            bufferedreader = new BufferedReader(new InputStreamReader(inputStream, Charsets.UTF_8));
            Properties props = new Properties();
            props.load(bufferedreader);
            metadata = new Manifest(props.getProperty("displayName"),props.getProperty("description"),props.getProperty("author"));
        } catch (Exception exception) {
            exception.printStackTrace();
        } finally {
            try {
                bufferedreader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return metadata;
    }

    public String getPackName() {
        return getPackMetadata().getInternalName();
    }
}