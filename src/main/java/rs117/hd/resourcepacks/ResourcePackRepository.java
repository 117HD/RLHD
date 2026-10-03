package rs117.hd.resourcepacks;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import rs117.hd.resourcepacks.impl.FileResourcePack;
import rs117.hd.resourcepacks.impl.ZipResourcePack;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

@Singleton
@Slf4j
final class ResourcePackRepository {
	private static final ResourcePath PACK_DIRECTORY = Props.getFolder("117hd-resource-packs", () -> ResourcePath.path(new File(RuneLite.RUNELITE_DIR, "117hd-resource-packs").getPath()));
	private static final FileFilter PACK_FILTER = file ->
		file.isFile() && file.getName().toLowerCase().endsWith(".zip") || file.isDirectory();

	List<AbstractResourcePack> loadInstalledPacks() {
		LinkedHashMap<String, AbstractResourcePack> packsByName = new LinkedHashMap<>();
		File[] files = PACK_DIRECTORY.exists() ? PACK_DIRECTORY.toFile().listFiles(PACK_FILTER) : null;
		if (files != null) {
			Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
			for (File file : files) {
				try {
					AbstractResourcePack pack = createPack(file);
					close(packsByName.put(pack.getManifest().getInternalName(), pack));
				} catch (RuntimeException ex) {
					log.warn("Ignoring unreadable resource pack: {}", file, ex);
				}
			}
		}

		return new ArrayList<>(packsByName.values());
	}

	boolean ensurePackDirectory() {
		return PACK_DIRECTORY.toFile().mkdirs() || PACK_DIRECTORY.exists();
	}

	boolean isRelevantPackChange(ResourcePath path) {
		File file = path.toFile();
		if (file.isDirectory() || file.getName().toLowerCase().endsWith(".zip"))
			return true;

		File parent = file.getParentFile();
		while (parent != null && !parent.equals(PACK_DIRECTORY.toFile())) {
			if (parent.isDirectory())
				return true;
			parent = parent.getParentFile();
		}
		return false;
	}

	File archiveFile(String internalName) {
		return PACK_DIRECTORY.resolve(internalName + ".zip").toFile();
	}

	File packDirectory() {
		return PACK_DIRECTORY.toFile();
	}

	AbstractResourcePack createPack(File file) {
		AbstractResourcePack pack = file.isDirectory() ? new FileResourcePack(file) : new ZipResourcePack(file);
		pack.setDevelopmentPack(!(pack instanceof ZipResourcePack));
		return pack;
	}

	void close(AbstractResourcePack pack) {
		if (pack instanceof ZipResourcePack)
			((ZipResourcePack) pack).close();
	}

}
