/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
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
 * Holds the particle textures folder path, the current texture filename used for rendering,
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
	private String texturePath;

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
				BufferedImage img = resPath.loadImage();
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
		if (textureFileName == null || textureFileName.isEmpty()) return null;
		return textureIds.get(textureFileName);
	}

	public void dispose() {
		for (int id : textureIds.values()) {
			if (id != 0) glDeleteTextures(id);
		}
		textureIds.clear();
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
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		return id;
	}

	@Nullable
	public ResourcePath getTextureResourcePath() {
		if (texturePath == null || texturePath.isEmpty())
			return null;
		return PARTICLE_TEXTURES_PATH.resolve(texturePath);
	}

	public static ResourcePath getParticleTexturesPath() {
		return PARTICLE_TEXTURES_PATH;
	}
}
