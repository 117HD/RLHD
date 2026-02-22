package rs117.hd.resourcepacks.impl;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.ZipResourcePath;

@Slf4j
public class ZipResourcePack extends AbstractResourcePack {
	private ZipFile zipFile;
	private final String rootPrefix;

	public ZipResourcePack(File resourcePackFileIn) {
		super(ResourcePath.path(resourcePackFileIn.getPath()));
		try {
			this.zipFile = new ZipFile(resourcePackFileIn);
			this.rootPrefix = detectRootPrefix();
			setHasTextures(checkHasTextures());
			setHasEnvironments(!listJsonFiles("environments").isEmpty());
		} catch (IOException e) {
			throw new RuntimeException("Failed to open zip file: " + resourcePackFileIn, e);
		}
	}

	private boolean checkHasTextures() {
		if (zipFile == null) {
			return false;
		}

		String materialsPath = normalizeZipPath("materials");
		if (!materialsPath.endsWith("/")) {
			materialsPath += "/";
		}

		// Check if there are any image files in the materials directory
		var entries = zipFile.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			if (name.startsWith(materialsPath) && !entry.isDirectory()) {
				String lower = name.toLowerCase();
				if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Closes the zip file to release the file lock.
	 * This should be called before deleting the pack file.
	 */
	public void close() {
		if (zipFile != null) {
			try {
				zipFile.close();
				zipFile = null;
			} catch (IOException e) {
				log.warn("Error closing zip file: {}", path, e);
			}
		}
	}

	private String detectRootPrefix() {
		// GitHub zip archives have a root folder, find it by looking for pack.properties
		var entries = zipFile.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			if (name.endsWith("pack.properties") && !entry.isDirectory()) {
				int index = name.indexOf("pack.properties");
				if (index > 0) {
					String prefix = name.substring(0, index);
					return prefix.endsWith("/") ? prefix : prefix + "/";
				}
				return "";
			}
		}
		return "";
	}

	private String normalizeZipPath(String... parts) {
		StringBuilder sb = new StringBuilder();
		if (rootPrefix != null && !rootPrefix.isEmpty()) {
			sb.append(rootPrefix);
		}
		for (String part : parts) {
			String normalizedPart = part.replace('\\', '/');
			if (normalizedPart.startsWith("/")) {
				normalizedPart = normalizedPart.substring(1);
			}
			if (sb.length() > 0 && !sb.toString().endsWith("/")) {
				sb.append('/');
			}
			sb.append(normalizedPart);
		}
		return sb.toString();
	}

	private InputStream getZipEntryInputStream(String... parts) throws IOException {
		String zipPath = normalizeZipPath(parts);
		ZipEntry entry = zipFile.getEntry(zipPath);
		if (entry == null) {
			throw new IOException("Entry not found in zip: " + zipPath);
		}
		return zipFile.getInputStream(entry);
	}

	@Override
	protected InputStream getInputStreamByName(String name) throws IOException {
		return getZipEntryInputStream(name);
	}

	@Override
	public InputStream getInputStream(String... parts) throws IOException {
		return getZipEntryInputStream(parts);
	}

	@Override
	public ResourcePath getResource(String... parts) {
		return new ZipResourcePath(this, parts);
	}

	@Override
	public boolean hasResource(String... parts) {
		String zipPath = normalizeZipPath(parts);
		ZipEntry entry = zipFile.getEntry(zipPath);
		return entry != null && !entry.isDirectory();
	}

	@Override
	public BufferedImage getPackImage() {
		try {
			return getResource("icon.png").loadImage();
		} catch (IOException e) {
			log.warn("Pack: {} has no defined icon", getPackName());
			return null;
		}
	}

	@Override
	public boolean hasPackImage() {
		return hasResource("icon.png");
	}

	@Override
	public List<ResourcePath> listJsonFiles(String directory) {
		List<ResourcePath> jsonFiles = new ArrayList<>();
		String dirPath = normalizeZipPath(directory);
		if (!dirPath.endsWith("/")) {
			dirPath += "/";
		}

		var entries = zipFile.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			if (name.startsWith(dirPath) && name.endsWith(".json") && !entry.isDirectory()) {
				// Extract the filename relative to the directory
				String filename = name.substring(dirPath.length());
				jsonFiles.add(new ZipResourcePath(this, directory, filename));
			}
		}

		return jsonFiles;
	}
}
