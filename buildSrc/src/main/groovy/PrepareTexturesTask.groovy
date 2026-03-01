import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.*

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

class PrepareTexturesTask extends DefaultTask {
	@InputDirectory
	File testTexturesDir

	@OutputDirectory
	File mainTexturesDir

	@TaskAction
	void prepare() {
		def test = testTexturesDir.toPath()
		def main = mainTexturesDir.toPath()
		if (!Files.isDirectory(test)) return

		def cache = loadCache(test)
		def normals = Files.isDirectory(test.resolve("normals")) ? test.resolve("normals") : test
		def displacement = Files.isDirectory(test.resolve("displacement")) ? test.resolve("displacement") : test

		Files.createDirectories(main)

		// Combine _n + _d -> _nd
		Files.list(normals).withCloseable { stream ->
		stream.filter { Files.isRegularFile(it) && it.fileName.toString().toLowerCase().matches(".*_n\\.(png|jpg|jpeg)") }.each { nPath ->
			def base = baseName(nPath.fileName.toString(), "_n")
			def dPath = ["png", "jpg", "jpeg"].collect { displacement.resolve("${base}_d.${it}") }.find { Files.exists(it) }
			def outName = "${base}_nd.png"
			def outPath = main.resolve(outName)

			def nCrc = crc32(nPath)
			def dCrc = dPath ? crc32(dPath) : null
			if (Files.exists(outPath) && !shouldCombine(cache, outName, nCrc, dCrc)) return

			def nImg = ImageIO.read(nPath.toFile())
			if (!nImg) return

			def w = nImg.width
			def h = nImg.height
			def nRgba = nImg.type == BufferedImage.TYPE_INT_ARGB ? nImg : toRgba(nImg)

			def out
			if (dPath) {
				def dImg = ImageIO.read(dPath.toFile())
				if (dImg) {
					if (dImg.width != w || dImg.height != h) {
						def resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
						resized.createGraphics().with { g ->
							g.drawImage(dImg, 0, 0, w, h, null)
							g.dispose()
						}
						dImg = resized
					}
					def dGray = toGrayscale(dImg)
					out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
					for (y in 0..<h)
						for (x in 0..<w) {
							def nArgb = nRgba.getRGB(x, y)
							def dVal = dGray.getRGB(x, y) & 0xFF
							out.setRGB(x, y, (dVal << 24) | (nArgb & 0xFFFFFF))
						}
					putCombined(cache, outName, nCrc, dCrc)
				}
			}
			if (out == null) {
				out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
				for (y in 0..<h)
					for (x in 0..<w) {
						def nArgb = nRgba.getRGB(x, y)
						out.setRGB(x, y, (0xFF << 24) | (nArgb & 0xFFFFFF))
					}
				putCombined(cache, outName, nCrc, null)
			}
			if (out) ImageIO.write(out, "PNG", outPath.toFile())
		}
		}

		// Copy root image files only (excl _n, _d)
		def copied = 0
		def imageExt = ~/.*\.(png|jpg|jpeg)$/
		Files.list(test).withCloseable { stream ->
		stream.filter { Files.isRegularFile(it) }.each { p ->
			def name = p.fileName.toString().toLowerCase()
			if (!(name ==~ imageExt)) return
			if (name.matches(".*_n\\.(png|jpg|jpeg)") || name.matches(".*_d\\.(png|jpg|jpeg)")) return
			def dest = main.resolve(p.fileName)
			def crc = crc32(p)
			if (Files.exists(dest) && !shouldCopy(cache, p.fileName.toString(), crc)) return
			Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING)
			putCopied(cache, p.fileName.toString(), crc)
			copied++
		}
		}

		saveCache(test, cache)
		if (copied > 0) logger.lifecycle("Prepared {} textures -> {}", copied, main)
	}

	static def baseName(filename, suffix) {
		def l = filename.toLowerCase()
		for (ext in ["png", "jpg", "jpeg"])
			if (l.endsWith("${suffix}.${ext}"))
				return filename.substring(0, filename.length() - suffix.length() - ext.length() - 1)
		return filename
	}

	static def crc32(Path p) {
		def crc = new CRC32()
		Files.newInputStream(p).withCloseable { ins ->
			def buf = new byte[8192]
			int n
			while ((n = ins.read(buf)) >= 0) crc.update(buf, 0, n)
		}
		return crc.value
	}

	static def toRgba(BufferedImage img) {
		def out = new BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
		out.graphics.drawImage(img, 0, 0, null)
		return out
	}

	static def toGrayscale(BufferedImage img) {
		def gray = new BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
		for (y in 0..<img.height)
			for (x in 0..<img.width) {
				def rgb = img.getRGB(x, y)
				def r = (rgb >> 16) & 0xFF
				def g = (rgb >> 8) & 0xFF
				def b = rgb & 0xFF
				def lum = (int)(0.299 * r + 0.587 * g + 0.114 * b)
				gray.setRGB(x, y, (255 << 24) | (lum << 16) | (lum << 8) | lum)
			}
		return gray
	}

	def loadCache(Path test) {
		def cache = [:]
		def cacheFile = test.resolve(".texture-cache.json")
		if (Files.isRegularFile(cacheFile)) {
			try {
				def json = new groovy.json.JsonSlurper().parseText(Files.readString(cacheFile))
				if (json instanceof Map) cache = json
			} catch (e) {}
		}
		return cache
	}

	def saveCache(Path test, Map cache) {
		try {
			Files.writeString(test.resolve(".texture-cache.json"), new groovy.json.JsonBuilder(cache).toPrettyString().replaceAll("\\s+", " ").trim())
		} catch (e) {}
	}

	static def shouldCombine(cache, outName, nCrc, dCrc) {
		if (!cache[outName] || !(cache[outName] instanceof Map)) return true
		def e = cache[outName]
		if (e.n != Long.toHexString(nCrc)) return true
		def cachedNull = e.d == null
		if (cachedNull && dCrc == null) return false
		if (!cachedNull && dCrc != null) return e.d != Long.toHexString(dCrc)
		return true
	}

	static def putCombined(cache, outName, nCrc, dCrc) {
		cache[outName] = [n: Long.toHexString(nCrc), d: dCrc != null ? Long.toHexString(dCrc) : null]
	}

	static def shouldCopy(cache, name, crc) {
		return !cache[name] || cache[name] != Long.toHexString(crc)
	}

	static def putCopied(cache, name, crc) {
		cache[name] = Long.toHexString(crc)
	}
}
