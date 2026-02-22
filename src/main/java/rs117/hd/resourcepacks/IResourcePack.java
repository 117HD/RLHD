package rs117.hd.resourcepacks;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.utils.ResourcePath;

public interface IResourcePack
{
    InputStream getInputStream(String... parts) throws IOException;

	ResourcePath getResource(String... parts);

    Manifest getManifest();

    BufferedImage getPackImage();

    boolean hasPackImage();

    BufferedImage getPackImage(boolean compactView);

    boolean hasPackImage(boolean compactView);

    String getPackName();
}
