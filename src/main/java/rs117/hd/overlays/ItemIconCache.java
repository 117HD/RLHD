package rs117.hd.overlays;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
class ItemIconCache {
	// Bump when icons are drawn differently
	private static final int VERSION = 2;
	private static final int MAX_FOLDERS = 3;
	private static final Path ROOT = HdPlugin.PLUGIN_DIR.resolve("item-icons").toPath();

	private final Path folder;
	private final int width;
	private final int height;

	ItemIconCache(float scaleX, float scaleY, double brightness, int width, int height) {
		folder = ROOT.resolve(String.format(Locale.ROOT, "v%d-%.4fx%.4f-%.3f", VERSION, scaleX, scaleY, brightness));
		this.width = width;
		this.height = height;
		try {
			if (Files.isDirectory(folder))
				Files.setLastModifiedTime(folder, FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException ex) {
			log.debug("Unable to mark item icons as used:", ex);
		}
	}

	boolean load(long key, NativeItemIcons.Icon icon) {
		try {
			byte[] file = Files.readAllBytes(folder.resolve(fileName(key)));
			if (file.length == 0) {
				icon.failed = true;
				return true;
			}

			byte[] data;
			try (var in = new InflaterInputStream(new ByteArrayInputStream(file))) {
				data = in.readAllBytes();
			}
			var buffer = ByteBuffer.wrap(data);
			if (buffer.getInt() != width || buffer.getInt() != height || buffer.remaining() != width * height * 5)
				return false;

			int[] pixels = new int[width * height];
			buffer.asIntBuffer().get(pixels);
			buffer.position(buffer.position() + pixels.length * Integer.BYTES);
			float[] surroundings = new float[pixels.length];
			for (int i = 0; i < surroundings.length; i++)
				surroundings[i] = (buffer.get() & 0xFF) / 255f;

			icon.surroundings = surroundings;
			icon.pixels = pixels;
			return true;
		} catch (NoSuchFileException ex) {
			return false;
		} catch (IOException ex) {
			log.debug("Unable to load item icon {}:", fileName(key), ex);
			return false;
		}
	}

	void save(long key, @Nullable int[] pixels, @Nullable float[] surroundings) {
		try {
			byte[] file = new byte[0];
			if (pixels != null && surroundings != null) {
				var buffer = ByteBuffer.allocate(2 * Integer.BYTES + pixels.length * 5);
				buffer.putInt(width).putInt(height);
				buffer.asIntBuffer().put(pixels);
				buffer.position(buffer.position() + pixels.length * Integer.BYTES);
				for (float surrounding : surroundings)
					buffer.put((byte) round(surrounding * 255));

				var out = new ByteArrayOutputStream();
				try (var deflater = new DeflaterOutputStream(out)) {
					deflater.write(buffer.array());
				}
				file = out.toByteArray();
			}

			// Moved into place, since other clients may be reading it
			Files.createDirectories(folder);
			var temporary = Files.createTempFile(folder, fileName(key), ".tmp");
			Files.write(temporary, file);
			Files.move(temporary, folder.resolve(fileName(key)), REPLACE_EXISTING, ATOMIC_MOVE);
		} catch (IOException ex) {
			log.debug("Unable to keep item icon {}:", fileName(key), ex);
		}
	}

	static void removeUnused() {
		if (!Files.isDirectory(ROOT))
			return;

		try {
			List<Path> folders;
			try (var list = Files.list(ROOT)) {
				folders = list
					.filter(Files::isDirectory)
					.sorted(Comparator.comparing(ItemIconCache::lastModified).reversed())
					.collect(Collectors.toList());
			}
			for (var unused : folders.subList(min(MAX_FOLDERS, folders.size()), folders.size())) {
				try (Stream<Path> files = Files.walk(unused)) {
					for (var path : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
						Files.delete(path);
				}
			}
		} catch (IOException ex) {
			log.debug("Unable to remove unused item icons:", ex);
		}
	}

	private static FileTime lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path);
		} catch (IOException ex) {
			return FileTime.fromMillis(0);
		}
	}

	private static String fileName(long key) {
		return String.format("%016x", key);
	}
}
