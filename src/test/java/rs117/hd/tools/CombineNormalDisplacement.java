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
import rs117.hd.TexturePrepareCache;
import rs117.hd.scene.TextureManager;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

/**
 * Produces *_nd textures. Output: RGB = normal, A = displacement (height).
 * - When *_d exists: combines *_n + *_d (RGB from _n, A from _d).
 * - When only *_n exists: creates *_nd with RGB from _n and solid alpha (255).
 *
 * Can read from separate normals/ and displacement/ subdirs; outputs to a target dir.
 * Use --no-delete to keep raw *_n and *_d sources.
 */
@Slf4j
public class CombineNormalDisplacement {
	private static final String[] EXTENSIONS = { "png", "jpg", "jpeg" };

	public static void main(String[] args) throws IOException {
		int i = 0;
		boolean delete = true;
		for (; i < args.length && "--no-delete".equals(args[i]); i++) delete = false;
		Path base = i < args.length ? Paths.get(args[i]).toAbsolutePath() : defaultTexturePath();
		Path out = i + 1 < args.length ? Paths.get(args[i + 1]).toAbsolutePath() : base;
		Path normals = base.resolve("normals");
		Path displacement = base.resolve("displacement");
		run(Files.isDirectory(normals) ? normals : base, Files.isDirectory(displacement) ? displacement : base, out, delete);
	}

	private static Path defaultTexturePath() {
		Props.set("rlhd.resource-path", "src/main/resources");
		var p = path(TextureManager.class, "textures");
		if (!p.isFileSystemResource()) throw new IllegalStateException("Texture path not on filesystem. Pass dir as arg.");
		return p.toPath();
	}

	public static void run(Path normalsDir, Path displacementDir, Path outputDir, boolean deleteSources) throws IOException {
		run(normalsDir, displacementDir, outputDir, deleteSources, null);
	}

	public static void run(Path normalsDir, Path displacementDir, Path outputDir, boolean deleteSources,
		@javax.annotation.Nullable rs117.hd.TexturePrepareCache cache) throws IOException {
		List<Path[]> toProcess = findNormalMaps(normalsDir, displacementDir);
		if (toProcess.isEmpty()) return;

		for (Path[] pair : toProcess) {
			combine(outputDir, pair[0], pair[1], deleteSources, cache);
		}
	}

	private static List<Path[]> findNormalMaps(Path normalsDir, Path displacementDir) throws IOException {
		List<Path> nFiles = new ArrayList<>();
		try (var stream = Files.list(normalsDir)) {
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
				Path candidate = displacementDir.resolve(base + "_d." + ext);
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

	private static void combine(Path outDir, Path nPath, Path dPath, boolean deleteSources,
		@javax.annotation.Nullable rs117.hd.TexturePrepareCache cache) throws IOException {
		String base = baseName(nPath.getFileName().toString(), "_n");
		String outName = base + "_nd.png";
		Path outPath = outDir.resolve(outName);

		long nCrc;
		Long dCrc = null;
		try {
			nCrc = TexturePrepareCache.crc32(nPath);
			if (dPath != null && Files.exists(dPath)) dCrc = TexturePrepareCache.crc32(dPath);
		} catch (IOException e) {
			nCrc = 0;
		}
		if (cache != null && Files.exists(outPath) && !cache.shouldCombine(outName, nCrc, dCrc))
			return;

		BufferedImage nImg = ImageIO.read(nPath.toFile());
		if (nImg == null) {
			log.warn("Failed to load: {}", nPath);
			return;
		}

		int w = nImg.getWidth();
		int h = nImg.getHeight();
		BufferedImage nRgba = nImg.getType() == BufferedImage.TYPE_INT_ARGB ? nImg : toRgba(nImg);

		int alphaFill = 255;
		if (dPath != null) {
			BufferedImage dImg = ImageIO.read(dPath.toFile());
			if (dImg != null) {
				if (dImg.getWidth() != w || dImg.getHeight() != h) {
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
				writeOut(outDir, outPath, out);
				if (cache != null) cache.putCombined(outName, nCrc, dCrc);
				if (deleteSources) deleteSourceFiles(nPath, dPath);
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
		writeOut(outDir, outPath, out);
		if (cache != null) cache.putCombined(outName, nCrc, null);
		if (deleteSources) deleteSourceFiles(nPath, null);
	}

	private static void writeOut(Path outDir, Path outPath, BufferedImage out) throws IOException {
		Files.createDirectories(outDir);
		ImageIO.write(out, "PNG", outPath.toFile());
	}

	private static void deleteSourceFiles(Path nPath, Path dPath) {
		try {
			Files.delete(nPath);
			if (dPath != null)
				Files.delete(dPath);
		} catch (IOException e) {
			log.warn("Failed to delete source: {}", e.getMessage());
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
