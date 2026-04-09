/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.core;

import java.awt.Graphics2D;
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
 * Particle textures: single 2D textures (getTextureId) plus optional texture array for single-draw batching.
 * Texture array: layer 0 = white fallback; layers 1..N = lazily loaded, scaled to LAYER_SIZE.
 */
@Slf4j
@Singleton
public class ParticleTextureLoader {

	private static final ResourcePath PARTICLE_TEXTURES_PATH = Props.getFolder(
		"rlhd.particle-texture-path",
		() -> path(ParticleTextureLoader.class, "..", "..", "textures", "particles")
	);
	private static final int LAYER_SIZE = 256;
	private static final int MAX_LAYERS = 32;

	@Getter
	@Setter
	private String activeTextureName;

	private final Map<String, Integer> textureIds = new HashMap<>();
	private int texArrayId;
	private final Map<String, Integer> nameToLayer = new HashMap<>();
	private int nextLayer = 1;

	@Getter
	private int lastTextureCount;
	@Getter
	private long lastLoadTimeMs;

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

	public int getTextureArrayId() {
		ensureArrayCreated();
		return texArrayId;
	}

	public int getTextureLayer(String textureName) {
		if (textureName == null || textureName.isEmpty()) return 0;
		Integer layer = nameToLayer.get(textureName);
		if (layer != null) return layer;
		ensureArrayCreated();
		if (nextLayer >= MAX_LAYERS) {
			log.warn("[Particles] Texture array full, using layer 0 for: {}", textureName);
			return 0;
		}
		try {
			ResourcePath resPath = PARTICLE_TEXTURES_PATH.resolve(textureName);
			BufferedImage img = loadImageExact(resPath);
			if (img == null) return 0;
			int layerIdx = nextLayer++;
			uploadToLayer(layerIdx, img);
			nameToLayer.put(textureName, layerIdx);
			lastTextureCount = nameToLayer.size();
			return layerIdx;
		} catch (IOException e) {
			log.warn("[Particles] Failed to load texture: {}", textureName, e);
			return 0;
		}
	}

	private void ensureArrayCreated() {
		if (texArrayId != 0) return;
		texArrayId = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texArrayId);
		ByteBuffer white = BufferUtils.createByteBuffer(LAYER_SIZE * LAYER_SIZE * 4);
		for (int i = 0; i < LAYER_SIZE * LAYER_SIZE; i++)
			white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
		white.flip();
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA, LAYER_SIZE, LAYER_SIZE, MAX_LAYERS, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, LAYER_SIZE, LAYER_SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, white);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
	}

	private void uploadToLayer(int layer, BufferedImage img) {
		BufferedImage scaled = img;
		if (img.getWidth() != LAYER_SIZE || img.getHeight() != LAYER_SIZE) {
			scaled = new BufferedImage(LAYER_SIZE, LAYER_SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = scaled.createGraphics();
			g.drawImage(img, 0, 0, LAYER_SIZE, LAYER_SIZE, null);
			g.dispose();
		}
		ByteBuffer pixels = BufferUtils.createByteBuffer(LAYER_SIZE * LAYER_SIZE * 4);
		for (int y = LAYER_SIZE - 1; y >= 0; y--)
			for (int x = 0; x < LAYER_SIZE; x++) {
				int argb = scaled.getRGB(x, y);
				pixels.put((byte) ((argb >> 16) & 0xFF));
				pixels.put((byte) ((argb >> 8) & 0xFF));
				pixels.put((byte) (argb & 0xFF));
				pixels.put((byte) ((argb >> 24) & 0xFF));
			}
		pixels.flip();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texArrayId);
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, LAYER_SIZE, LAYER_SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
	}

	public void dispose() {
		for (int id : textureIds.values()) {
			if (id != 0) glDeleteTextures(id);
		}
		textureIds.clear();
		if (texArrayId != 0) {
			glDeleteTextures(texArrayId);
			texArrayId = 0;
		}
		nameToLayer.clear();
		nextLayer = 1;
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
