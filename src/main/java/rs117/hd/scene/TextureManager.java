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

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.materials.Material;
import rs117.hd.utils.Env;
import rs117.hd.utils.FileUtils;
import rs117.hd.utils.FileWatcher;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;

import static org.lwjgl.opengl.GL43C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;

@Singleton
@Slf4j
public class TextureManager
{
	public static String ENV_TEXTURES = "RLHD_TEXTURES_PATH";

	private static final float PERC_64 = 1f / 64f;
	private static final float PERC_128 = 1f / 128f;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ClientThread clientThread;

	private FileWatcher textureFileWatcher;
	private int textureArray;
	private int textureSize;
	private int[] materialOrdinalToTextureIndex;

	// Temporary buffers for texture loading
	private IntBuffer pixelBuffer;
	private BufferedImage vanillaImage;
	private BufferedImage scaledImage;

	public void startUp()
	{
		Path texturePath = Env.getPath(ENV_TEXTURES);
		if (texturePath != null)
		{
			try
			{
				textureFileWatcher = new FileWatcher(texturePath, path -> {
					log.info("Reloading Textures...");
					freeTextures();
				});
			}
			catch (IOException ex)
			{
				throw new RuntimeException(ex);
			}
		}
	}

	public void shutDown()
	{
		if(textureFileWatcher != null)
		{
			textureFileWatcher.close();
			textureFileWatcher = null;
		}
	}

	public int getTextureIndex(Material material)
	{
		return material == null ? -1 : materialOrdinalToTextureIndex[material.ordinal()];
	}

	public void ensureTexturesLoaded(TextureProvider textureProvider)
	{
		if (textureArray != 0)
		{
			return;
		}

		if (!allTexturesLoaded(textureProvider))
		{
			return;
		}

		Texture[] textures = textureProvider.getTextures();

		HashSet<Integer> diffuseIds = new HashSet<>();

		// Count the required number of texture array layers
		for (int i = 0; i < textures.length; i++) {
			Texture texture = textures[i];
			if (texture != null)
			{
				diffuseIds.add(i);
			}
		}

		int textureCount = diffuseIds.size();

		for (Material material : Material.values())
		{
			if (material.getVanillaTextureIndex() == -1 || diffuseIds.add(material.getVanillaTextureIndex()))
			{
				textureCount++;
			}
		}

		textureSize = config.textureResolution().getSize();
		textureArray = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_GAME);
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArray);
		if (GL.getCapabilities().glTexStorage3D != 0)
		{
			glTexStorage3D(GL_TEXTURE_2D_ARRAY, 8, GL_SRGB8_ALPHA8, textureSize, textureSize, textureCount);
		}
		else
		{
			int size = textureSize;
			int i = 0;
			while (size >= 1)
			{
				glTexImage3D(GL_TEXTURE_2D_ARRAY, i++, GL_SRGB8_ALPHA8, size, size, textureCount,
					0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
				size /= 2;
			}
		}

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

		setAnisotropicFilteringLevel();

		// Set brightness to 1.0d to upload unmodified textures to GPU
		double save = textureProvider.getBrightness();
		textureProvider.setBrightness(1.0d);

		pixelBuffer = BufferUtils.createIntBuffer(textureSize * textureSize);
		vanillaImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		int[] vanillaPixels = new int[128 * 128];
		scaledImage = new BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_ARGB);

		materialOrdinalToTextureIndex = new int[Material.values().length];
		Arrays.fill(materialOrdinalToTextureIndex, -1);

		// Load vanilla textures to texture array layers
		ArrayDeque<Integer> unusedIndices = new ArrayDeque<>();
		int i = 0;
		for (; i < textures.length; i++)
		{
			Texture texture = textures[i];
			if (texture == null)
			{
				unusedIndices.addLast(i);
				continue;
			}

			Material material = Material.getTexture(i);
			String textureName = material == Material.NONE ? "" + i : material.name().toLowerCase();

			BufferedImage image = loadTextureImage(textureName);
			if (image == null)
			{
				// Load vanilla texture
				int[] pixels = textureProvider.load(i);
				if (pixels == null)
				{
					log.warn("No pixels for texture index {}", i);
					unusedIndices.addLast(i);
					continue;
				}
				if (pixels.length != 128 * 128)
				{
					// The texture storage is 128x128 bytes, and will only work correctly with the
					// 128x128 textures from high detail mode
					log.warn("Texture size for index {} is {}!", i, pixels.length);
					unusedIndices.addLast(i);
					continue;
				}

				for (int j = 0; j < pixels.length; j++) {
					int p = pixels[j];
					vanillaPixels[j] = p == 0 ? 0 : 0xFF << 24 | p & 0xFFFFFF;
				}

				vanillaImage.setRGB(0, 0, 128, 128, vanillaPixels, 0, 128);
				image = vanillaImage;
			}

			uploadTexture(i, image);
			if (material != Material.NONE)
			{
				materialOrdinalToTextureIndex[material.ordinal()] = i;
			}
		}

		int vanillaCount = i - unusedIndices.size();
		log.debug("Loaded {} vanilla textures", vanillaCount);

		for (Material material : Material.values())
		{
			if (material == Material.NONE)
			{
				continue;
			}

			Material parent = material.getParent();
			if (parent != null)
			{
				// Point this material to pre-existing texture from parent material
				materialOrdinalToTextureIndex[material.ordinal()] = materialOrdinalToTextureIndex[parent.ordinal()];
				continue;
			}

			String textureName = material.name().toLowerCase();
			BufferedImage image = loadTextureImage(textureName);
			if (image == null)
			{
				log.trace("No texture override for: {}", textureName);
				continue;
			}

			Integer index = unusedIndices.pollFirst();
			if (index == null)
			{
				index = i++;
			}

			uploadTexture(index, image);
			materialOrdinalToTextureIndex[material.ordinal()] = index;
		}

		int hdCount = i - unusedIndices.size() - vanillaCount;
		log.debug("Loaded {} HD textures", hdCount);

		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);

		// Reset
		pixelBuffer = null;
		vanillaImage = null;
		scaledImage = null;
		textureProvider.setBrightness(save);
		glActiveTexture(TEXTURE_UNIT_UI);

		plugin.updateMaterialUniformBuffer();
		plugin.updateWaterTypeUniformBuffer();
	}

	private BufferedImage loadTextureImage(String textureName)
	{
		Path path = Paths.get("textures").resolve(textureName + ".png");
		try (InputStream is = FileUtils.getResource(TextureManager.class, path))
		{
			synchronized (ImageIO.class) {
				return ImageIO.read(is);
			}
		} catch (IOException ex) {
			log.trace("Missing texture override: {}", path);
			return null;
		}
	}

	int c = 0;
	private void uploadTexture(int index, BufferedImage image)
	{
		AffineTransform t = new AffineTransform();
		t.scale((double) textureSize / image.getWidth(), (double) textureSize / image.getHeight());
		AffineTransformOp scaleOp = new AffineTransformOp(t, AffineTransformOp.TYPE_BICUBIC);
		scaleOp.filter(image, scaledImage);
		image = scaledImage;

		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		pixelBuffer.put(pixels).flip();

		// Go from TYPE_4BYTE_ABGR in the BufferedImage to RGBA
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, index, textureSize, textureSize, 1,
			GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
	}

	private void setAnisotropicFilteringLevel()
	{
		int level = config.anisotropicFilteringLevel();
		//level = 0 means no mipmaps and no anisotropic filtering
		if (level == 0)
		{
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		}
		//level = 1 means with mipmaps but without anisotropic filtering GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT defaults to 1.0 which is off
		//level > 1 enables anisotropic filtering. It's up to the vendor what the values mean
		//Even if anisotropic filtering isn't supported, mipmaps will be enabled with any level >= 1
		else
		{
			if (true) // TODO: decide
			{
				// Trilinear filtering is used for HD textures as linear filtering produces noisy textures
				// that are very noticeable on terrain
				glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
			}
			else
			{
				// Set on GL_NEAREST_MIPMAP_LINEAR (bilinear filtering with mipmaps) since the pixel nature of the game means that nearest filtering
				// looks best for objects up close but allows linear filtering to resolve possible aliasing and noise with mipmaps from far away objects.
				glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
			}
		}

		if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic)
		{
			final float maxSamples = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
			//Clamp from 1 to max GL says it supports.
			final float anisoLevel = Math.max(1, Math.min(maxSamples, level));
			glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisoLevel);
		}
	}

	public void freeTextures()
	{
		clientThread.invoke(() ->
		{
			glDeleteTextures(textureArray);
			textureArray = 0;
		});
	}

	/**
	 * Check if all textures have been loaded and cached yet.
	 *
	 * @param textureProvider
	 * @return
	 */
	private boolean allTexturesLoaded(TextureProvider textureProvider)
	{
		Texture[] textures = textureProvider.getTextures();
		if (textures == null || textures.length == 0)
		{
			return false;
		}

		for (int textureId = 0; textureId < textures.length; textureId++)
		{
			Texture texture = textures[textureId];
			if (texture != null)
			{
				int[] pixels = textureProvider.load(textureId);
				if (pixels == null)
				{
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Animate the given texture
	 *
	 * @param texture
	 * @param diff    Number of elapsed client ticks since last animation
	 */
	public void animate(Texture texture, int diff)
	{
		final int[] pixels = texture.getPixels();
		if (pixels == null)
		{
			return;
		}

		final int animationSpeed = texture.getAnimationSpeed();
		final float uvdiff = pixels.length == 4096 ? PERC_64 : PERC_128;

		float u = texture.getU();
		float v = texture.getV();

		int offset = animationSpeed * diff;
		float d = (float) offset * uvdiff;

		switch (texture.getAnimationDirection())
		{
			case 1:
				v -= d;
				if (v < 0f)
				{
					v += 1f;
				}
				break;
			case 3:
				v += d;
				if (v > 1f)
				{
					v -= 1f;
				}
				break;
			case 2:
				u -= d;
				if (u < 0f)
				{
					u += 1f;
				}
				break;
			case 4:
				u += d;
				if (u > 1f)
				{
					u -= 1f;
				}
				break;
			default:
				return;
		}

		texture.setU(u);
		texture.setV(v);
	}
}
