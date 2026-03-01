package rs117.hd.tools;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.TextureManager;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

/**
 * Produces *_nd textures for all normal maps. Output: RGB = normal, A = displacement (height).
 * - When *_d exists: combines *_n + *_d into *_nd (RGB from _n, A from _d), then deletes *_n and *_d.
 * - When only *_n exists: creates *_nd with RGB from _n and solid alpha (255), then deletes *_n.
 *
 * Run from project root. Set rlhd.resource-path to point at resources (e.g. src/main/resources).
 */
@Slf4j
public class CombineNormalDisplacement {
	private static final String[] EXTENSIONS = { "png", "jpg", "jpeg" };

	public static void main(String[] args) throws IOException {
		Path texturesDir;
		if (args.length > 0) {
			texturesDir = Paths.get(args[0]).toAbsolutePath();
		} else {
			Props.set("rlhd.resource-path", "src/main/resources");
			ResourcePath texturePath = path(TextureManager.class, "textures");
			if (!texturePath.isFileSystemResource()) {
				log.error("Textures path is not on filesystem (e.g. inside JAR). Pass textures dir as argument.");
				return;
			}
			texturesDir = texturePath.toPath();
		}

		if (!Files.isDirectory(texturesDir)) {
			log.error("Not a directory: {}", texturesDir);
			return;
		}

		List<Path[]> toProcess = findNormalMaps(texturesDir);
		if (toProcess.isEmpty()) {
			log.info("No _n textures found in {}", texturesDir);
			return;
		}

		log.info("Processing {} normal maps...", toProcess.size());
		for (Path[] pair : toProcess) {
			combine(texturesDir, pair[0], pair[1]);
		}
		log.info("Done.");
	}

	private static List<Path[]> findNormalMaps(Path dir) throws IOException {
		List<Path> nFiles = new ArrayList<>();
		try (var stream = Files.list(dir)) {
			stream.filter(Files::isRegularFile).forEach(p -> {
				String name = p.getFileName().toString().toLowerCase();
				for (String ext : EXTENSIONS) {
					if (name.endsWith("_n." + ext)) {
						nFiles.add(p);
						break;
					}
				}
			});
		}

		List<Path[]> result = new ArrayList<>();
		for (Path nPath : nFiles) {
			String base = baseName(nPath.getFileName().toString(), "_n");
			Path dPath = null;
			for (String ext : EXTENSIONS) {
				Path candidate = dir.resolve(base + "_d." + ext);
				if (Files.exists(candidate)) {
					dPath = candidate;
					break;
				}
			}
			result.add(new Path[] { nPath, dPath });
		}
		return result;
	}

	private static String baseName(String filename, String suffix) {
		String lower = filename.toLowerCase();
		for (String ext : EXTENSIONS) {
			String ending = suffix + "." + ext;
			if (lower.endsWith(ending)) {
				return filename.substring(0, filename.length() - ending.length());
			}
		}
		return filename;
	}

	private static void combine(Path outDir, Path nPath, Path dPath) throws IOException {
		String base = baseName(nPath.getFileName().toString(), "_n");
		Path outPath = outDir.resolve(base + "_nd.png");

		BufferedImage nImg = ImageIO.read(nPath.toFile());
		if (nImg == null) {
			log.warn("Failed to load: {}", nPath);
			return;
		}

		int w = nImg.getWidth();
		int h = nImg.getHeight();
		BufferedImage nRgba = nImg.getType() == BufferedImage.TYPE_INT_ARGB ? nImg : toRgba(nImg);

		int alphaFill = 255; // solid alpha when no displacement
		if (dPath != null) {
			BufferedImage dImg = ImageIO.read(dPath.toFile());
			if (dImg != null) {
				if (dImg.getWidth() != w || dImg.getHeight() != h) {
					log.info("Resizing {} to match {}", dPath.getFileName(), nPath.getFileName());
					BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g = resized.createGraphics();
					g.drawImage(dImg, 0, 0, w, h, null);
					g.dispose();
					dImg = resized;
				}
				BufferedImage dGray = toGrayscale(dImg);
				BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int nArgb = nRgba.getRGB(x, y);
						int dVal = dGray.getRGB(x, y) & 0xFF;
						int r = (nArgb >> 16) & 0xFF;
						int g = (nArgb >> 8) & 0xFF;
						int b = nArgb & 0xFF;
						out.setRGB(x, y, (dVal << 24) | (r << 16) | (g << 8) | b);
					}
				}
				ImageIO.write(out, "PNG", outPath.toFile());
				log.info("Created {} (n+d)", outPath.getFileName());
				deleteSourceFiles(nPath, dPath);
				return;
			}
		}

		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int nArgb = nRgba.getRGB(x, y);
				int r = (nArgb >> 16) & 0xFF;
				int g = (nArgb >> 8) & 0xFF;
				int b = nArgb & 0xFF;
				out.setRGB(x, y, (alphaFill << 24) | (r << 16) | (g << 8) | b);
			}
		}
		ImageIO.write(out, "PNG", outPath.toFile());
		log.info("Created {} (n only)", outPath.getFileName());
		deleteSourceFiles(nPath, null);
	}

	private static void deleteSourceFiles(Path nPath, Path dPath) {
		try {
			Files.delete(nPath);
			log.info("Deleted {}", nPath.getFileName());
			if (dPath != null) {
				Files.delete(dPath);
				log.info("Deleted {}", dPath.getFileName());
			}
		} catch (IOException e) {
			log.warn("Failed to delete source files: {}", e.getMessage());
		}
	}

	private static BufferedImage toRgba(BufferedImage img) {
		BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		out.getGraphics().drawImage(img, 0, 0, null);
		return out;
	}

	private static BufferedImage toGrayscale(BufferedImage img) {
		int w = img.getWidth();
		int h = img.getHeight();
		BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int rgb = img.getRGB(x, y);
				int r = (rgb >> 16) & 0xFF;
				int g = (rgb >> 8) & 0xFF;
				int b = rgb & 0xFF;
				int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
				gray.setRGB(x, y, (255 << 24) | (lum << 16) | (lum << 8) | lum);
			}
		}
		return gray;
	}
}
