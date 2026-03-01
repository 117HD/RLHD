package rs117.hd;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * CRC-based cache for texture preparation. Skips combine/copy when sources unchanged.
 * Stored as minified JSON in test resources textures dir.
 */
@Slf4j
public class TexturePrepareCache {
	private static final Gson GSON = new Gson();
	private static final String CACHE_FILE = ".texture-cache.json";

	private final Path cachePath;
	private JsonObject cache = new JsonObject();

	public TexturePrepareCache(Path testTexturesDir) {
		this.cachePath = testTexturesDir.resolve(CACHE_FILE);
		load();
	}

	private void load() {
		if (!Files.isRegularFile(cachePath)) return;
		try {
			String json = Files.readString(cachePath);
			var root = GSON.fromJson(json, JsonObject.class);
			if (root != null) cache = root;
		} catch (Exception e) {
			log.debug("Cache load failed: {}", e.getMessage());
		}
	}

	public void save() {
		try {
			Files.writeString(cachePath, GSON.toJson(cache));
		} catch (IOException e) {
			log.warn("Cache save failed: {}", e.getMessage());
		}
	}

	public boolean shouldCombine(String outName, long nCrc, @Nullable Long dCrc) {
		if (!cache.has(outName)) return true;
		var e = cache.getAsJsonObject(outName);
		if (e == null || !e.has("n")) return true;
		String nHex = Long.toHexString(nCrc);
		if (!e.get("n").getAsString().equals(nHex)) return true;
		var d = e.get("d");
		boolean cachedNull = d == null || d.isJsonNull();
		if (cachedNull && dCrc == null) return false;
		if (!cachedNull && dCrc != null) return !d.getAsString().equals(Long.toHexString(dCrc));
		return true;
	}

	public void putCombined(String outName, long nCrc, @Nullable Long dCrc) {
		var e = new JsonObject();
		e.addProperty("n", Long.toHexString(nCrc));
		e.addProperty("d", dCrc != null ? Long.toHexString(dCrc) : null);
		cache.add(outName, e);
	}

	public boolean shouldCopy(String name, long crc) {
		if (!cache.has(name)) return true;
		var v = cache.get(name);
		return v == null || !v.isJsonPrimitive() || !v.getAsString().equals(Long.toHexString(crc));
	}

	public void putCopied(String name, long crc) {
		cache.addProperty(name, Long.toHexString(crc));
	}

	public static long crc32(Path path) throws IOException {
		CRC32 crc = new CRC32();
		try (var in = Files.newInputStream(path)) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) >= 0) crc.update(buf, 0, n);
		}
		return crc.getValue();
	}
}
