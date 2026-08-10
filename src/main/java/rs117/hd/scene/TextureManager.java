/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.scene;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import rs117.hd.HdPluginConfig;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class TextureManager {
	private static final String[] SUPPORTED_IMAGE_EXTENSIONS = { "png", "jpg" };
	private static final ResourcePath TEXTURE_PATH = Props
		.getFolder("rlhd.texture-path", () -> path(TextureManager.class, "textures"));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private HdPluginConfig config;

	@Inject
	private MaterialManager materialManager;

	// Temporary variables for texture loading and generating material uniforms
	private IntBuffer pixelBuffer;
	private BufferedImage scaledImage;
	private BufferedImage vanillaImage;

	private ScheduledFuture<?> debounce;

	public void startUp() {
		assert vanillaTexturesAvailable();
		vanillaImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);

		TEXTURE_PATH.watch((path, first) -> {
			if (first) return;

			// Mark texture layers that need to be reloaded
			String textureName = path.setExtension(null).getFilename();
			if (!textureName.isEmpty()) {
				for (var layer : materialManager.textureLayers) {
					if (textureName.equals(layer.material.getTextureName())) {
						log.debug("Texture changed: {}", path);
						layer.needsUpload = true;
						break;
					}
				}
			}

			// Debounce texture loading in case the same file change is triggered multiple times
			if (debounce == null || debounce.cancel(false) || debounce.isDone())
				debounce = executor.schedule(() -> clientThread.invoke(materialManager::uploadTextures), 100, TimeUnit.MILLISECONDS);
		});
	}

	public void shutDown() {
		pixelBuffer = null;
		scaledImage = null;
		vanillaImage = null;
	}

	public boolean vanillaTexturesAvailable() {
		var textureProvider = client.getTextureProvider();
		if (textureProvider == null)
			return false;

		Texture[] vanillaTextures = textureProvider.getTextures();
		if (vanillaTextures == null || vanillaTextures.length == 0)
			return false;

		// Ensure all textures are available
		for (int i = 0; i < vanillaTextures.length; i++) {
			var texture = vanillaTextures[i];
			if (texture != null) {
				int[] pixels = textureProvider.load(i);
				if (pixels == null)
					return false;
			}
		}

		return true;
	}

	@Nullable
	public BufferedImage loadTexture(@Nullable String filename, int fallbackVanillaIndex) {
		if (filename != null) {
			var image = loadTexture(filename);
			if (image != null)
				return image;
			if (fallbackVanillaIndex == -1) {
				log.warn("Missing texture: '{}'", filename);
				return null;
			}
		}

		if (fallbackVanillaIndex == -1)
			return null;

		var textureProvider = client.getTextureProvider();
		Texture[] vanillaTextures = textureProvider.getTextures();
		var texture = vanillaTextures[fallbackVanillaIndex];
		if (texture == null) {
			log.warn("Missing vanilla texture index {}", fallbackVanillaIndex);
			return null;
		}

		int[] pixels = textureProvider.load(fallbackVanillaIndex);
		if (pixels == null) {
			log.warn("No pixels for vanilla texture index {}", fallbackVanillaIndex);
			return null;
		}

		if (pixels.length != 128 * 128) {
			log.warn("Unknown dimensions for vanilla texture at index {} ({} pixels)", fallbackVanillaIndex, pixels.length);
			return null;
		}

		for (int j = 0; j < pixels.length; j++) {
			int rgb = pixels[j];
			// Black is considered transparent in vanilla, with anything else being fully opaque
			int alpha = rgb == 0 ? 0 : 0xFF;
			vanillaImage.setRGB(j % 128, j / 128, alpha << 24 | rgb & 0xFFFFFF);
		}

		return vanillaImage;
	}

	@Nullable
	public BufferedImage loadTexture(String filename) {
		return loadImage(TEXTURE_PATH, filename, SUPPORTED_IMAGE_EXTENSIONS);
	}

	/**
	 * Resolves {@code filename} against {@code basePath} and loads it as an image, tolerating
	 * filenames given both with and without an extension: the filename is tried as-is first (in
	 * case it already ends in a valid extension), then with each of {@code extensions} appended.
	 */
	@Nullable
	public BufferedImage loadImage(ResourcePath basePath, String filename, String[] extensions) {
		try {
			return basePath.resolve(filename).loadImage();
		} catch (Exception ignored) {
			// Not a valid path as given - fall through to trying each supported extension below
		}

		for (String ext : extensions) {
			ResourcePath path = basePath.resolve(filename + "." + ext);
			try {
				return path.loadImage();
			} catch (Exception ex) {
				log.trace("Unable to load image: {}", path, ex);
			}
		}

		return null;
	}

	/**
	 * Slices a standard unwrapped horizontal-cross cubemap layout (4 columns x 3 rows) into
	 * the 6 individual cube faces, in +X,-X,+Y,-Y,+Z,-Z order:
	 * <pre>
	 *        [+Y]
	 * [-X] [+Z] [+X] [-Z]
	 *        [-Y]
	 * </pre>
	 */
	public static BufferedImage[] sliceHorizontalCross(BufferedImage cross) {
		int faceSize = cross.getWidth() / 4;
		if (faceSize <= 0 || cross.getHeight() / 3 != faceSize)
			throw new IllegalArgumentException(
				"Cubemap cross image must have a 4:3 aspect ratio (width / 4 == height / 3)");

		return new BufferedImage[] {
			cross.getSubimage(2 * faceSize, faceSize, faceSize, faceSize), // +X
			cross.getSubimage(0, faceSize, faceSize, faceSize), // -X
			cross.getSubimage(faceSize, 0, faceSize, faceSize), // +Y
			cross.getSubimage(faceSize, 2 * faceSize, faceSize, faceSize), // -Y
			cross.getSubimage(faceSize, faceSize, faceSize, faceSize), // +Z
			cross.getSubimage(3 * faceSize, faceSize, faceSize, faceSize), // -Z
		};
	}

	public void uploadTexture(int target, int textureLayer, int[] textureSize, BufferedImage image) {
		assert client.isClientThread() : "Not thread safe";

		// Allocate resources for storing temporary image data
		int numPixels = product(textureSize);
		if (pixelBuffer == null || pixelBuffer.capacity() < numPixels)
			pixelBuffer = BufferUtils.createIntBuffer(numPixels);
		if (scaledImage == null || scaledImage.getWidth() != textureSize[0] || scaledImage.getHeight() != textureSize[1])
			scaledImage = new BufferedImage(textureSize[0], textureSize[1], BufferedImage.TYPE_INT_ARGB);

		// TODO: scale and transform on the GPU for better performance (would save 400+ ms)
		AffineTransform t = new AffineTransform();
		if (image != vanillaImage) {
			// Flip non-vanilla textures horizontally to match vanilla UV orientation
			t.translate(textureSize[1], 0);
			t.scale(-1, 1);
		}
		t.scale((double) textureSize[0] / image.getWidth(), (double) textureSize[1] / image.getHeight());
		AffineTransformOp scaleOp = new AffineTransformOp(t, AffineTransformOp.TYPE_BICUBIC);
		scaleOp.filter(image, scaledImage);

		int[] pixels = ((DataBufferInt) scaledImage.getRaster().getDataBuffer()).getData();
		pixelBuffer.clear().put(pixels).flip();

		// Go from TYPE_4BYTE_ABGR in the BufferedImage to RGBA
		glTexSubImage3D(
			target, 0, 0, 0,
			textureLayer, textureSize[0], textureSize[1], 1,
			GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer
		);
	}

	public void setAnisotropicFilteringLevel() {
		int level = config.anisotropicFilteringLevel();
		if (level == 0) {
			//level = 0 means no mipmaps and no anisotropic filtering
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		} else {
			// level = 1 means with mipmaps but without anisotropic filtering GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT defaults to 1.0 which is off
			// level > 1 enables anisotropic filtering. It's up to the vendor what the values mean
			// Even if anisotropic filtering isn't supported, mipmaps will be enabled with any level >= 1
			// Trilinear filtering is used for HD textures as linear filtering produces noisy textures
			// that are very noticeable on terrain
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		}

		if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
			final float maxSamples = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
			glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, clamp(level, 1, maxSamples));
		}
	}

	/**
	 * Packs a BufferedImage's ARGB pixels into a tightly-packed RGBA ByteBuffer suitable for
	 * uploading via glTexImage2D.
	 */
	private static ByteBuffer packRgba(BufferedImage img) {
		int width = img.getWidth();
		int height = img.getHeight();
		int[] pixels = new int[width * height];
		img.getRGB(0, 0, width, height, pixels, 0, width);

		ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int pixel = pixels[y * width + x];
				buffer.put((byte) ((pixel >> 16) & 0xFF));
				buffer.put((byte) ((pixel >> 8) & 0xFF));
				buffer.put((byte) (pixel & 0xFF));
				buffer.put((byte) ((pixel >> 24) & 0xFF));
			}
		}
		buffer.flip();
		return buffer;
	}

	/**
	 * Uploads a single BufferedImage as a standalone GL_TEXTURE_2D and returns its texture id.
	 */
	public int createTexture2D(BufferedImage img) {
		int width = img.getWidth();
		int height = img.getHeight();
		ByteBuffer buffer = packRgba(img);

		int texId = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texId);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
		return texId;
	}

	/**
	 * Uploads 6 face images (in +X,-X,+Y,-Y,+Z,-Z order) as a GL_TEXTURE_CUBE_MAP and returns its texture id.
	 */
	public int createCubemapTexture(BufferedImage[] faces) {
		int texId = glGenTextures();
		glBindTexture(GL_TEXTURE_CUBE_MAP, texId);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

		for (int i = 0; i < 6; i++) {
			var face = faces[i];
			glTexImage2D(
				GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, GL_RGBA8,
				face.getWidth(), face.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, packRgba(face));
		}
		return texId;
	}
}
