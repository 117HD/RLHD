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

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
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
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.resourcepacks.PackEventType;
import rs117.hd.resourcepacks.ResourcePackManager;
import rs117.hd.resourcepacks.ResourcePackUpdate;
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
		.getFolder("rlhd.texture-path", () -> path(HdPlugin.class, "resource-pack", "materials"));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ResourcePackManager resourcePackManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EventBus eventBus;

	@Inject
	private MaterialManager materialManager;

	// Temporary variables for texture loading and generating material uniforms
	private IntBuffer pixelBuffer;
	private BufferedImage scaledImage;
	private BufferedImage uploadImage;
	private BufferedImage vanillaImage;
	private int uploadTexture;
	private int uploadReadFramebuffer;
	private int uploadDrawFramebuffer;
	private int[] uploadResolution;

	private ScheduledFuture<?> debounce;

	public void startUp() {
		assert vanillaTexturesAvailable();
		vanillaImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		eventBus.register(this);
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
		uploadImage = null;
		vanillaImage = null;
		uploadResolution = null;
		if (uploadTexture != 0)
			glDeleteTextures(uploadTexture);
		uploadTexture = 0;
		if (uploadReadFramebuffer != 0)
			glDeleteFramebuffers(uploadReadFramebuffer);
		uploadReadFramebuffer = 0;
		if (uploadDrawFramebuffer != 0)
			glDeleteFramebuffers(uploadDrawFramebuffer);
		uploadDrawFramebuffer = 0;
		eventBus.unregister(this);
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
		for (var pack : resourcePackManager.getEnabledPacks()) {
			for (String ext : SUPPORTED_IMAGE_EXTENSIONS) {
				ResourcePath path = pack.getResource("materials", filename + "." + ext);
				if (!path.exists())
					continue;

				try {
					return path.loadImage();
				} catch (Exception ex) {
					log.trace("Unable to load texture: {}", path, ex);
				}
			}
		}

		return null;
	}

	public void uploadTexture(int target, int textureLayer, int[] textureSize, BufferedImage image) {
		assert client.isClientThread() : "Not thread safe";
		assert target == GL_TEXTURE_2D_ARRAY : "Material textures must use a texture array";
		if (config.gpuTextureResizing())
			uploadTextureGpu(target, textureLayer, textureSize, image);
		else
			uploadTextureCpu(target, textureLayer, textureSize, image);
	}

	private void uploadTextureCpu(int target, int textureLayer, int[] textureSize, BufferedImage image) {
		int numPixels = product(textureSize);
		if (pixelBuffer == null || pixelBuffer.capacity() < numPixels)
			pixelBuffer = BufferUtils.createIntBuffer(numPixels);
		if (scaledImage == null || scaledImage.getWidth() != textureSize[0] || scaledImage.getHeight() != textureSize[1])
			scaledImage = new BufferedImage(textureSize[0], textureSize[1], BufferedImage.TYPE_INT_ARGB);

		AffineTransform transform = new AffineTransform();
		if (image != vanillaImage) {
			// Flip non-vanilla textures horizontally to match vanilla UV orientation
			transform.translate(textureSize[1], 0);
			transform.scale(-1, 1);
		}
		transform.scale((double) textureSize[0] / image.getWidth(), (double) textureSize[1] / image.getHeight());
		new AffineTransformOp(transform, AffineTransformOp.TYPE_BICUBIC).filter(image, scaledImage);

		int[] pixels = ((DataBufferInt) scaledImage.getRaster().getDataBuffer()).getData();
		pixelBuffer.clear().put(pixels).flip();
		glTexSubImage3D(target, 0, 0, 0, textureLayer, textureSize[0], textureSize[1], 1,
			GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
	}

	private void uploadTextureGpu(int target, int textureLayer, int[] textureSize, BufferedImage image) {
		int sourceWidth = image.getWidth();
		int sourceHeight = image.getHeight();
		int numPixels = sourceWidth * sourceHeight;
		if (pixelBuffer == null || pixelBuffer.capacity() < numPixels)
			pixelBuffer = BufferUtils.createIntBuffer(numPixels);
		if (uploadImage == null || uploadImage.getWidth() != sourceWidth || uploadImage.getHeight() != sourceHeight)
			uploadImage = new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_ARGB);

		Graphics2D graphics = uploadImage.createGraphics();
		graphics.setComposite(AlphaComposite.Src);
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();

		int[] pixels = ((DataBufferInt) uploadImage.getRaster().getDataBuffer()).getData();
		pixelBuffer.clear().put(pixels).flip();

		int destinationTexture = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
		if (destinationTexture == 0)
			throw new IllegalStateException("No material texture array is bound");

		int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
		int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		int previousReadBuffer = glGetInteger(GL_READ_BUFFER);
		int previousDrawBuffer = glGetInteger(GL_DRAW_BUFFER);
		boolean framebufferSrgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
		try {
			ensureUploadResources(sourceWidth, sourceHeight);
			glBindTexture(GL_TEXTURE_2D, uploadTexture);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, sourceWidth, sourceHeight,
				GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);

			glBindFramebuffer(GL_READ_FRAMEBUFFER, uploadReadFramebuffer);
			glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, uploadTexture, 0);
			glReadBuffer(GL_COLOR_ATTACHMENT0);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, uploadDrawFramebuffer);
			glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, destinationTexture, 0, textureLayer);
			glDrawBuffer(GL_COLOR_ATTACHMENT0);

			if (framebufferSrgb)
				glDisable(GL_FRAMEBUFFER_SRGB);
			int sourceLeft = image == vanillaImage ? 0 : sourceWidth;
			int sourceRight = image == vanillaImage ? sourceWidth : 0;
			glBlitFramebuffer(sourceLeft, 0, sourceRight, sourceHeight,
				0, 0, textureSize[0], textureSize[1], GL_COLOR_BUFFER_BIT, GL_LINEAR);
		} finally {
			if (framebufferSrgb)
				glEnable(GL_FRAMEBUFFER_SRGB);
			glBindTexture(GL_TEXTURE_2D, previousTexture);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
			glReadBuffer(previousReadBuffer);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
			glDrawBuffer(previousDrawBuffer);
		}
	}

	private void ensureUploadResources(int width, int height) {
		if (uploadTexture == 0) {
			uploadTexture = glGenTextures();
			uploadReadFramebuffer = glGenFramebuffers();
			uploadDrawFramebuffer = glGenFramebuffers();
		}
		if (uploadResolution != null && uploadResolution[0] == width && uploadResolution[1] == height)
			return;

		uploadResolution = ivec(width, height);
		glBindTexture(GL_TEXTURE_2D, uploadTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
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

	@Subscribe
	public void onResourcePackUpdate(ResourcePackUpdate event) {
		if (event.stateIs(PackEventType.UI_CHANGED))
			return;

		for (var layer : materialManager.textureLayers) {
			layer.needsUpload = true;
		}

		if (debounce == null || debounce.cancel(false) || debounce.isDone())
			debounce = executor.schedule(() -> clientThread.invoke(materialManager::uploadTextures), 100, TimeUnit.MILLISECONDS);
	}

}
