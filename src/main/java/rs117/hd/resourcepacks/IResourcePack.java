package rs117.hd.resourcepacks;

import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.utils.ResourcePath;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

public interface IResourcePack
{
    InputStream getInputStream(String... parts) throws IOException;

    Manifest getPackMetadata();

    BufferedImage getPackImage() throws IOException;

    String getPackName();
}