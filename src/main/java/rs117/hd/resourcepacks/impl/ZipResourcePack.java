package rs117.hd.resourcepacks.impl;

import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.swing.ImageIcon;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;
import java.io.*;
import java.util.zip.*;

@Slf4j
public class ZipResourcePack extends AbstractResourcePack {

	Map<String, InputStream> filesMap;

    public ZipResourcePack(File resourcePackFileIn) {
        super(ResourcePath.path(resourcePackFileIn.getPath()));
		try {

			// Load ZIP file into memory
			filesMap = loadZipFile(resourcePackFileIn.getPath());

		}catch (Exception e) {
			e.printStackTrace();
		}
    }

    @Override
    protected InputStream getInputStreamByName(String name) throws IOException {
		return filesMap.get(name);
    }

    @Override
    public InputStream getInputStream(String... parts) throws IOException {
		return filesMap.get(parts[0]);
    }

	@Override
    public BufferedImage getPackImage() {
        try {
            return loadImage(filesMap.get("icon.png"));
        } catch (IOException e) {
            log.warn("Pack: {} has no defined icon in {}", getPackName(),path);
            return null;
        }
    }

	public BufferedImage loadImage(InputStream inputStream) throws IOException {
		try (InputStream is = inputStream) {
			byte[] bytes = is.readAllBytes();
			var icon = new ImageIcon(Toolkit.getDefaultToolkit().createImage(bytes));
			var bufferedImage = new BufferedImage(
				icon.getIconWidth(),
				icon.getIconHeight(),
				BufferedImage.TYPE_INT_ARGB
			);
			var g = bufferedImage.createGraphics();
			icon.paintIcon(null, g, 0, 0);
			g.dispose();
			return bufferedImage;
		}
	}

    @Override
    public boolean hasPackImage() {
        return getPackImage() != null;
    }

	public static Map<String, InputStream> loadZipFile(String zipFilePath) throws IOException {
		Map<String, InputStream> filesMap = new HashMap<>();
		FileInputStream fis = new FileInputStream(zipFilePath + ".zip");
		ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fis));

		ZipEntry entry;
		while ((entry = zis.getNextEntry()) != null) {
			// Extract each file from the ZIP
			String fileName = entry.getName();
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int len;
			while ((len = zis.read(buffer)) > 0) {
				baos.write(buffer, 0, len);
			}
			baos.close();
			InputStream inputStream = new ByteArrayInputStream(baos.toByteArray());

			// Put the file into the map
			filesMap.put(fileName, inputStream);
		}

		zis.close();
		fis.close();

		return filesMap;
	}

}
