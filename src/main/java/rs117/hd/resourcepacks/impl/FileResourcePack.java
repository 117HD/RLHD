package rs117.hd.resourcepacks.impl;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;

@Slf4j
public class FileResourcePack extends AbstractResourcePack {
    public FileResourcePack(File resourcePackFileIn) {
        super(ResourcePath.path(resourcePackFileIn.getPath()));
    }

    @Override
    protected InputStream getInputStreamByName(String name) throws IOException {
		return this.path.resolve(name).toInputStream();
    }

    @Override
    public InputStream getInputStream(String... parts) throws IOException {
		return this.path.resolve(parts).toInputStream();
    }

	@Override
	public ResourcePath getResource(String... parts) {
		return this.path.resolve(parts);
	}

	@Override
    public BufferedImage getPackImage() {
		ResourcePath path = this.path.resolve("icon.png");
        try {
            return path.loadImage();
        } catch (IOException e) {
            log.warn("Pack: {} has no defined icon in {}", getPackName(),path);
            return null;
        }
    }

    @Override
    public boolean hasPackImage() {
        return getPackImage() != null;
    }

	@Override
	public List<ResourcePath> listJsonFiles(String directory) {
		List<ResourcePath> jsonFiles = new ArrayList<>();
		ResourcePath dirPath = this.path.resolve(directory);
		if (!dirPath.exists()) {
			return jsonFiles;
		}

		File dir = dirPath.toFile();
		if (!dir.isDirectory()) {
			return jsonFiles;
		}

		File[] files = dir.listFiles((file, name) -> name.toLowerCase().endsWith(".json"));
		if (files != null) {
			for (File file : files) {
				if (file.isFile()) {
					jsonFiles.add(this.path.resolve(directory, file.getName()));
				}
			}
		}

		return jsonFiles;
	}
}
