package rs117.hd.resourcepacks.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;

@Slf4j
public final class ZipResourcePack extends AbstractResourcePack {
	private ZipFile zipFile;
	private final String rootPrefix;

	public ZipResourcePack(File resourcePackFileIn) {
		super(ResourcePath.path(resourcePackFileIn.getPath()));
		try {
			this.zipFile = new ZipFile(resourcePackFileIn);
			this.rootPrefix = detectRootPrefix();
		} catch (IOException e) {
			throw new RuntimeException("Failed to open zip file: " + resourcePackFileIn, e);
		}
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
		// GitHub zip archives have a root folder, find it by looking for pack.properties.
		String commonRoot = null;
		boolean hasRootFile = false;
		var entries = zipFile.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			if (!entry.isDirectory() && (name.equals("pack.properties") || name.endsWith("/pack.properties"))) {
				int separator = name.lastIndexOf('/');
				return separator < 0 ? "" : name.substring(0, separator + 1);
			}

			int separator = name.indexOf('/');
			if (separator < 0) {
				hasRootFile = true;
				continue;
			}
			String root = name.substring(0, separator);
			if (commonRoot == null) {
				commonRoot = root;
			} else if (!commonRoot.equals(root)) {
				return "";
			}
		}
		return !hasRootFile && commonRoot != null ? commonRoot + "/" : "";
	}

	private String normalizeZipPath(String... parts) {
		return rootPrefix + ResourcePath.path(parts).toPosixPath();
	}

	private InputStream getZipEntryInputStream(String... parts) throws IOException {
		if (zipFile == null)
			throw new IOException("Resource pack is closed: " + path);

		String zipPath = normalizeZipPath(parts);
		ZipEntry entry = zipFile.getEntry(zipPath);
		if (entry == null) {
			throw new IOException("Entry not found in zip: " + zipPath);
		}
		return zipFile.getInputStream(entry);
	}

	@Override
	public ResourcePath getResource(String... parts) {
		return new ZipEntryPath(this, parts);
	}

	@Override
	public InputStream getInputStream(String... parts) throws IOException {
		return getZipEntryInputStream(parts);
	}

	@Override
	public boolean hasResource(String... parts) {
		if (zipFile == null)
			return false;
		String zipPath = normalizeZipPath(parts);
		ZipEntry entry = zipFile.getEntry(zipPath);
		return entry != null && !entry.isDirectory();
	}

	@Override
	public List<ResourcePath> listJsonFiles(String directory) {
		if (zipFile == null)
			return List.of();

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
				jsonFiles.add(new ZipEntryPath(this, directory, filename));
			}
		}

		return jsonFiles;
	}

	@Override
	protected boolean hasPackContent() {
		if (zipFile == null) {
			return false;
		}

		var entries = zipFile.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			if (entry.isDirectory()) {
				continue;
			}
			String name = entry.getName();
			if (!rootPrefix.isEmpty() && name.startsWith(rootPrefix)) {
				name = name.substring(rootPrefix.length());
			}
			if (!isDisplayMetadata(name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isDisplayMetadata(String path) {
		return path.equals("pack.properties") || path.equals("icon.png") || path.equals("compact-icon.png");
	}

	/**
	 * A path inside this archive. Keeping this implementation here makes the
	 * archive lifetime and its non-filesystem semantics impossible to use without
	 * the owning pack.
	 */
	private static final class ZipEntryPath extends ResourcePath {
		private final ZipResourcePack pack;
		private final String[] parts;

		private ZipEntryPath(@Nonnull ZipResourcePack pack, String... parts) {
			super(pack.path, parts);
			this.pack = pack;
			this.parts = parts;
		}

		@Override
		public ResourcePath resolve(String... additionalParts) {
			String[] combined = new String[parts.length + additionalParts.length];
			System.arraycopy(parts, 0, combined, 0, parts.length);
			System.arraycopy(additionalParts, 0, combined, parts.length, additionalParts.length);
			return new ZipEntryPath(pack, combined);
		}

		@Override
		public String toPosixPath() {
			return pack.path.toPosixPath() + "!/" + path;
		}

		@Override
		public boolean exists() {
			return pack.hasResource(parts);
		}

		@Override
		public InputStream toInputStream() throws IOException {
			return pack.getInputStream(parts);
		}

		@Override
		public boolean isFileSystemResource() {
			return false;
		}
	}
}
