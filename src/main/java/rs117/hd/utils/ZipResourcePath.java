package rs117.hd.utils;

import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nonnull;
import rs117.hd.resourcepacks.impl.ZipResourcePack;

/**
 * A ResourcePath that reads from a zip file through ZipResourcePack.
 * Overrides toInputStream() and exists() to read from zip entries instead of the file system.
 */
public class ZipResourcePath extends ResourcePath {
	private final ZipResourcePack zipPack;
	private final String[] parts;

	public ZipResourcePath(@Nonnull ZipResourcePack zipPack, String... parts) {
		super(zipPack.path, parts);
		this.zipPack = zipPack;
		this.parts = parts;
	}

	@Override
	public ResourcePath resolve(String... additionalParts) {
		String[] combined = new String[parts.length + additionalParts.length];
		System.arraycopy(parts, 0, combined, 0, parts.length);
		System.arraycopy(additionalParts, 0, combined, parts.length, additionalParts.length);
		return new ZipResourcePath(zipPack, combined);
	}

	@Override
	public boolean exists() {
		return zipPack.hasResource(parts);
	}

	@Override
	public InputStream toInputStream() throws IOException {
		return zipPack.getInputStream(parts);
	}

	@Override
	public boolean isFileSystemResource() {
		return false;
	}
}

