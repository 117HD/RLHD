package rs117.hd.resourcepacks;

import rs117.hd.utils.ResourcePath;

import java.awt.image.BufferedImage;
import java.io.*;

public class DefaultResourcePack extends AbstractResourcePack {

    public DefaultResourcePack(ResourcePath resourcePackFileIn) {
        super(resourcePackFileIn);
    }

    @Override
    protected boolean hasResourceName(String name) {
        return (new File(this.resourcePackFile.toFile(), name)).isFile();
    }

    @Override
    protected InputStream getInputStreamByName(String name) throws IOException {
        System.out.println(this.resourcePackFile.resolve(name).toString());
        return this.resourcePackFile.resolve(name).toInputStream();
    }

    @Override
    public InputStream getInputStream(String... parts) throws IOException {
        return this.resourcePackFile.resolve(parts).toInputStream();
    }

    @Override
    public BufferedImage getPackImage() throws IOException {
        return resourcePackFile.resolve("icon.png").loadImage();
    }

}