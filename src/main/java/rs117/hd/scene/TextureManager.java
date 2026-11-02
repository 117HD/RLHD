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
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
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
import rs117.hd.utils.collections.IntHashSet;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class TextureManager {
	private static final String[] SUPPORTED_IMAGE_EXTENSIONS = { "png", "jpg" };
	private static final boolean DEBUG_DUMP_TEXTURES = false;
	private static final ResourcePath DEBUG_TEXTURE_DUMP_PATH = path("textures_dump");
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
	private BufferedImage nearestNeighborImage;
	private BufferedImage vanillaImage;
	private int[] alphaMaskNearestOpaque;
	private int[] alphaMaskFloodQueue;
	private final HashSet<String> alphaMaskTextures = new HashSet<>();
	private final IntHashSet alphaMaskVanillaTextures = new IntHashSet();
	private boolean currentTextureIsAlphaMask;

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
		nearestNeighborImage = null;
		vanillaImage = null;
		alphaMaskNearestOpaque = null;
		alphaMaskFloodQueue = null;
		alphaMaskVanillaTextures.clear();
		alphaMaskTextures.clear();
		currentTextureIsAlphaMask = false;
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

	public boolean isAlphasMaskTexture(@Nullable String filename, int fallbackVanillaIndex) {
		return (filename != null && alphaMaskTextures.contains(filename)) || alphaMaskVanillaTextures.contains(fallbackVanillaIndex);
	}

	@Nullable
	public BufferedImage loadTexture(@Nullable String filename, int fallbackVanillaIndex, boolean forcedAlphaDiscard) {
		if (filename != null) {
			var image = loadTexture(filename);
			if (image != null) {
				currentTextureIsAlphaMask = forcedAlphaDiscard || isAlphaMaskTexture(image);
				if(currentTextureIsAlphaMask) {
					alphaMaskTextures.add(filename);
				} else {
					alphaMaskTextures.remove(filename);
				}
				return image;
			}
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

		boolean hasTransparentPixel = false;
		boolean hasOpaquePixel = false;
		for (int j = 0; j < pixels.length; j++) {
			int rgb = pixels[j];
			// Black is considered transparent in vanilla, with anything else being fully opaque
			int alpha = rgb == 0 ? 0 : 0xFF;
			vanillaImage.setRGB(j % 128, j / 128, alpha << 24 | rgb & 0xFFFFFF);
			hasTransparentPixel |= alpha == 0;
			hasOpaquePixel |= alpha == 0xFF;
		}

		currentTextureIsAlphaMask = hasTransparentPixel && hasOpaquePixel;
		if (currentTextureIsAlphaMask) {
			alphaMaskVanillaTextures.add(fallbackVanillaIndex);
		} else {
			alphaMaskVanillaTextures.remove(fallbackVanillaIndex);
		}

		return vanillaImage;
	}

	@Nullable
	public BufferedImage loadTexture(String filename) {
		for (String ext : SUPPORTED_IMAGE_EXTENSIONS) {
			ResourcePath path = TEXTURE_PATH.resolve(filename + "." + ext);
			try {
				return path.loadImage();
			} catch (Exception ex) {
				log.trace("Unable to load texture: {}", path, ex);
			}
		}

		return null;
	}

	public void uploadTexture(int target, int textureLayer, int[] textureSize, BufferedImage image) {
		assert client.isClientThread() : "Not thread safe";

		// Allocate resources for storing temporary image data
		int numPixels = product(textureSize);
		if (pixelBuffer == null || pixelBuffer.capacity() < numPixels)
			pixelBuffer = BufferUtils.createIntBuffer(numPixels);

		if (scaledImage == null || scaledImage.getWidth() != textureSize[0] || scaledImage.getHeight() != textureSize[1]) {
			scaledImage = new BufferedImage(textureSize[0], textureSize[1], BufferedImage.TYPE_INT_ARGB);
			nearestNeighborImage = new BufferedImage(textureSize[0], textureSize[1], BufferedImage.TYPE_INT_ARGB);
		}

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

		final int[] scaledPixels = ((DataBufferInt) scaledImage.getRaster().getDataBuffer()).getData();
		if (currentTextureIsAlphaMask) {
			AffineTransformOp alphaScaleOp = new AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
			alphaScaleOp.filter(image, nearestNeighborImage);

			int[] nearestNeighborPixels = ((DataBufferInt) nearestNeighborImage.getRaster().getDataBuffer()).getData();
			for (int i = 0; i < scaledPixels.length; i++)
				scaledPixels[i] = (scaledPixels[i] & 0x00FFFFFF) | (nearestNeighborPixels[i] & 0xFF000000);

			bleedAlphaMaskColors(scaledPixels, textureSize[0], textureSize[1]);
		}
		if (DEBUG_DUMP_TEXTURES)
			dumpTexture(textureLayer, scaledImage);

		pixelBuffer.clear().put(scaledPixels).flip();

		// Go from TYPE_4BYTE_ABGR in the BufferedImage to RGBA
		glTexSubImage3D(
			target, 0, 0, 0,
			textureLayer, textureSize[0], textureSize[1], 1,
			GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer
		);
	}

	private void dumpTexture(int textureLayer, BufferedImage image) {
		try {
			ResourcePath outputPath = DEBUG_TEXTURE_DUMP_PATH
				.mkdirs()
				.resolve(String.format("texture-%04d.png", textureLayer));
			if (!ImageIO.write(image, "png", outputPath.toFile()))
				log.warn("No PNG writer available for texture layer {}", textureLayer);
		} catch (IOException ex) {
			log.warn("Failed to dump texture layer {}", textureLayer, ex);
		}
	}

	/**
	 * Fill transparent texels with nearby opaque RGB while preserving their alpha. This prevents
	 * bilinear filtering and generated mipmaps from pulling black RGB into alpha-tested edges.
	 * The flood wraps at the texture boundaries to match GL_TEXTURE_WRAP_{S,T} = GL_REPEAT.
	 */
	private void bleedAlphaMaskColors(int[] pixels, int width, int height) {
		final int pixelCount = width * height;
		if (alphaMaskNearestOpaque == null || alphaMaskNearestOpaque.length < pixelCount)
			alphaMaskNearestOpaque = new int[pixelCount];
		if (alphaMaskFloodQueue == null || alphaMaskFloodQueue.length < pixelCount)
			alphaMaskFloodQueue = new int[pixelCount];

		Arrays.fill(alphaMaskNearestOpaque, 0, pixelCount, -1);
		int queueHead = 0;
		int queueTail = 0;
		for (int i = 0; i < pixelCount; i++) {
			if ((pixels[i] >>> 24) != 0) {
				alphaMaskNearestOpaque[i] = i;
				alphaMaskFloodQueue[queueTail++] = i;
			}
		}

		// A completely transparent image has no color to bleed from.
		if (queueTail == 0)
			return;

		while (queueHead < queueTail) {
			final int index = alphaMaskFloodQueue[queueHead++];
			final int x = index % width;
			final int y = index / width;
			final int source = alphaMaskNearestOpaque[index];

			for (int dy = -1; dy <= 1; dy++) {
				final int neighborY = (y + dy + height) % height;
				for (int dx = -1; dx <= 1; dx++) {
					if (dx == 0 && dy == 0)
						continue;

					final int neighborX = (x + dx + width) % width;
					final int neighbor = neighborY * width + neighborX;
					if (alphaMaskNearestOpaque[neighbor] != -1)
						continue;

					alphaMaskNearestOpaque[neighbor] = source;
					alphaMaskFloodQueue[queueTail++] = neighbor;
				}
			}
		}

		for (int i = 0; i < pixelCount; i++) {
			if ((pixels[i] >>> 24) == 0)
				pixels[i] = (pixels[i] & 0xFF000000) | (pixels[alphaMaskNearestOpaque[i]] & 0x00FFFFFF);
		}
	}

	private static boolean isAlphaMaskTexture(BufferedImage image) {
		boolean hasTransparentPixel = false;
		boolean hasOpaquePixel = false;

		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int alpha = image.getRGB(x, y) >>> 24;
				if (alpha == 0)
					hasTransparentPixel = true;
				else if (alpha == 0xFF)
					hasOpaquePixel = true;
				else
					return false;
			}
		}

		return hasTransparentPixel && hasOpaquePixel;
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
}
