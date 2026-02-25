/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Holds the particle textures folder path, the active texture name used for rendering,
 * and a preloaded map of texture name → GL texture id so textures are not loaded on demand during draw.
 */
@Slf4j
@Singleton
public class ParticleTextureLoader {

	private static final ResourcePath PARTICLE_TEXTURES_PATH = Props.getFolder(
		"rlhd.particle-texture-path",
		() -> path(ParticleTextureLoader.class, "..", "textures", "particles")
	);

	@Getter
	@Setter
	private String activeTextureName;

	private final Map<String, Integer> textureIds = new HashMap<>();

	private int lastTextureCount;
	private long lastLoadTimeMs;

	public int getLastTextureCount() { return lastTextureCount; }
	public long getLastLoadTimeMs() { return lastLoadTimeMs; }

	/**
	 * Preload all given texture names: load from disk and upload to GL. Call when particle config is loaded.
	 * Existing preloaded textures are disposed first. Safe to call on client/GL thread.
	 */
	public void preload(List<String> textureNames) {
		long start = System.nanoTime();
		dispose();
		lastTextureCount = 0;
		lastLoadTimeMs = (System.nanoTime() - start) / 1_000_000;
		if (textureNames == null) return;
		for (String name : textureNames) {
			if (name == null || name.isEmpty() || textureIds.containsKey(name)) continue;
			try {
				ResourcePath resPath = PARTICLE_TEXTURES_PATH.resolve(name);
				BufferedImage img = loadImageExact(resPath);
				if (img == null) continue;
				int id = uploadToGl(img);
				textureIds.put(name, id);
			} catch (IOException e) {
				log.warn("[Particles] Failed to preload texture: {}", name, e);
			}
		}
		lastTextureCount = textureIds.size();
		lastLoadTimeMs = (System.nanoTime() - start) / 1_000_000;
	}

	@Nullable
	public Integer getTextureId(String textureFileName) {
		if (textureFileName == null || textureFileName.isEmpty())
			return null;

		Integer existing = textureIds.get(textureFileName);
		if (existing != null && existing != 0)
			return existing;

		long start = System.nanoTime();
		try {
			ResourcePath resPath = PARTICLE_TEXTURES_PATH.resolve(textureFileName);
			BufferedImage img = loadImageExact(resPath);
			if (img == null)
				return null;
			int id = uploadToGl(img);
			textureIds.put(textureFileName, id);
			lastTextureCount = textureIds.size();
			lastLoadTimeMs = (System.nanoTime() - start) / 1_000_000;
			return id;
		} catch (IOException e) {
			log.warn("[Particles] Failed to lazily load texture: {}", textureFileName, e);
			return null;
		}
	}

	public void dispose() {
		for (int id : textureIds.values()) {
			if (id != 0) glDeleteTextures(id);
		}
		textureIds.clear();
	}

	/**
	 * Load image at exact file resolution (no Toolkit scaling). Returns null if read fails.
	 */
	@Nullable
	private BufferedImage loadImageExact(ResourcePath path) throws IOException {
		try (InputStream is = path.toInputStream()) {
			BufferedImage img = ImageIO.read(is);
			if (img == null) {
				log.warn("[Particles] ImageIO.read returned null for: {}", path);
				return null;
			}
			return img;
		}
	}

	private int uploadToGl(BufferedImage img) {
		int w = img.getWidth();
		int h = img.getHeight();
		int id = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, id);
		ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
		for (int y = h - 1; y >= 0; y--)
			for (int x = 0; x < w; x++) {
				int argb = img.getRGB(x, y);
				pixels.put((byte) ((argb >> 16) & 0xFF));
				pixels.put((byte) ((argb >> 8) & 0xFF));
				pixels.put((byte) (argb & 0xFF));
				pixels.put((byte) ((argb >> 24) & 0xFF));
			}
		pixels.flip();
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
		glGenerateMipmap(GL_TEXTURE_2D);
		// NEAREST keeps sharp edges; mipmaps improve quality when particle is drawn small
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		return id;
	}

	@Nullable
	public ResourcePath getTextureResourcePath() {
		if (activeTextureName == null || activeTextureName.isEmpty())
			return null;
		return PARTICLE_TEXTURES_PATH.resolve(activeTextureName);
	}

	public static ResourcePath getParticleTexturesPath() {
		return PARTICLE_TEXTURES_PATH;
	}
}
