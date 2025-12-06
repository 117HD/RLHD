package rs117.hd.resourcepacks;

import com.google.common.base.Charsets;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.utils.ResourcePath;

@Slf4j
public abstract class AbstractResourcePack implements IResourcePack {
	private Manifest manifest;
	public final ResourcePath path;
	@Getter
	@Setter
	private boolean needsUpdating = false;

	@Getter
	@Setter
	private boolean developmentPack = false;

	public AbstractResourcePack(ResourcePath resourcePackFileIn) {
		this.path = resourcePackFileIn;
	}

	protected abstract InputStream getInputStreamByName(String name) throws IOException;

	/**
	 * Check if a resource exists in this pack.
	 * @param parts The path parts to the resource
	 * @return true if the resource exists, false otherwise
	 */
	public boolean hasResource(String... parts) {
		var path = this.path.resolve(parts);
		return path.exists();
	}

	/**
	 * Lists all JSON files in the specified directory.
	 * @param directory The directory path (e.g., "environments")
	 * @return List of ResourcePath objects pointing to JSON files
	 */
	public abstract List<ResourcePath> listJsonFiles(String directory);

	public Manifest getManifest() {
		try {
			if (manifest == null) {
				manifest = readMetadata(this.getInputStreamByName("pack.properties"));
			}
			return manifest;
		} catch (IOException e) {
			log.warn("COULD NOT LOAD");
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
			metadata = new Manifest(props.getProperty("displayName"), props.getProperty("description"), props.getProperty("author"));

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
		return getManifest().getInternalName();
	}

	public boolean isValid() {
		return getManifest() != null;
	}
}
