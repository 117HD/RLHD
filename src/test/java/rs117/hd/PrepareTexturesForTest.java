package rs117.hd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.tools.CombineNormalDisplacement;
import rs117.hd.utils.Props;

/**
 * Prepares textures before HdPluginTest. Raw *_n and *_d stay in test normals/displacement.
 * Combines them into *_nd in main; copies color maps and other root files to main.
 * Override: -Drlhd.texture-output=/path/to/main/textures
 */
@Slf4j
public class PrepareTexturesForTest {
	private static final String TEST_TEXTURES = "src/test/resources/rs117/hd/scene/textures";
	private static final String MAIN_TEXTURES_REL = "rs117/hd/scene/textures";

	public static void run() {
		Path root = Paths.get("").toAbsolutePath();
		Path test = root.resolve(TEST_TEXTURES);
		if (!Files.isDirectory(test)) return;

		Path main = Props.get("rlhd.texture-output") != null
			? Paths.get(Props.get("rlhd.texture-output"))
			: root.resolve(Props.getOrDefault("rlhd.resource-path", "src/main/resources")).resolve(MAIN_TEXTURES_REL);

		try {
			Files.createDirectories(main);
			var cache = new TexturePrepareCache(test);
			CombineNormalDisplacement.run(
				Files.isDirectory(test.resolve("normals")) ? test.resolve("normals") : test,
				Files.isDirectory(test.resolve("displacement")) ? test.resolve("displacement") : test,
				main,
				false,
				cache
			);

			int[] n = { 0 };
			try (var stream = Files.list(test)) {
				stream.filter(Files::isRegularFile)
					.filter(p -> !isRawNormalOrDisplacement(p.getFileName().toString()))
					.forEach(p -> {
						try {
							String name = p.getFileName().toString();
							Path dest = main.resolve(name);
							long crc = TexturePrepareCache.crc32(p);
							if (Files.exists(dest) && !cache.shouldCopy(name, crc)) return;
							Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
							cache.putCopied(name, crc);
							n[0]++;
						} catch (IOException ex) {
							log.warn("Copy failed {}: {}", p.getFileName(), ex.getMessage());
						}
					});
			}
			cache.save();
			if (n[0] > 0) log.info("Prepared {} textures -> {}", n[0], main);
		} catch (IOException e) {
			log.warn("Prepare textures: {}", e.getMessage());
		}
	}

	private static boolean isRawNormalOrDisplacement(String name) {
		String l = name.toLowerCase();
		return l.endsWith("_n.png") || l.endsWith("_n.jpg") || l.endsWith("_n.jpeg")
			|| l.endsWith("_d.png") || l.endsWith("_d.jpg") || l.endsWith("_d.jpeg");
	}
}
