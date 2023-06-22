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
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.materials.Material;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL43C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class TextureManager
{
	private static final String[] SUPPORTED_IMAGE_EXTENSIONS = { "png", "jpg" };
	private static final float HALF_PI = (float) (Math.PI / 2);
	private static final ResourcePath TEXTURE_PATH = Props.getPathOrDefault("rlhd.texture-path",
		() -> path(TextureManager.class,"textures"));

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ClientThread clientThread;

	private int textureArray;
	private int textureSize;
	private int[] materialOrdinalToTextureIndex;
	private int[] materialReplacements;

	// Temporary buffers for texture loading
	private IntBuffer pixelBuffer;
	private BufferedImage scaledImage;
	private BufferedImage vanillaImage;

	public void startUp()
	{
		TEXTURE_PATH.watch(path -> {
			log.debug("Loading Textures...");
			freeTextures();
		});
	}

	public void shutDown()
	{
		freeTextures();
	}

	public int getTextureIndex(Material material)
	{
		return material == null ? -1 : materialOrdinalToTextureIndex[material.ordinal()];
	}

	public Material getEffectiveMaterial(Material material)
	{
		int replacement = materialReplacements[material.ordinal()];
		return replacement == -1 ? material : Material.values()[replacement];
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
			if (material.vanillaTextureIndex == -1 || diffuseIds.add(material.vanillaTextureIndex))
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

		int[] vanillaPixels = new int[128 * 128];
		pixelBuffer = BufferUtils.createIntBuffer(textureSize * textureSize);
		scaledImage = new BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_ARGB);
		vanillaImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);

		int materialCount = Material.values().length;
		materialOrdinalToTextureIndex = new int[materialCount];
		materialReplacements = new int[materialCount];
		Arrays.fill(materialOrdinalToTextureIndex, -1);
		Arrays.fill(materialReplacements, -1);

		float[] textureAnimations = new float[textureCount * 2];

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
			if (material.parent != null)
			{
				// Point this material to pre-existing texture from parent material
				materialOrdinalToTextureIndex[material.ordinal()] = materialOrdinalToTextureIndex[material.parent.ordinal()];
				continue;
			}

			String textureName = material == Material.NONE ? String.valueOf(i) : material.name().toLowerCase();

			BufferedImage image = loadTextureImage(textureName);
			if (image == null)
			{
				// Load vanilla texture
				int[] pixels = textureProvider.load(i);
				if (pixels == null)
				{
					log.warn("No vanilla pixels for texture index {}", i);
					unusedIndices.addLast(i);
					continue;
				}
				if (pixels.length != 128 * 128)
				{
					log.warn("Unknown dimensions for vanilla texture at index {} ({} pixels)", i, pixels.length);
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

			// Convert texture animations to the same format as Material scrolling
			int direction = texture.getAnimationDirection();
			if (direction != 0) {
				float speed = texture.getAnimationSpeed() * 50 / 128.f;
				float radians = direction * -HALF_PI;
				textureAnimations[i * 2] = (float) Math.cos(radians) * speed;
				textureAnimations[i * 2 + 1] = (float) Math.sin(radians) * speed;
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

			Integer index = -1;
			if (material.replacementCondition != null)
			{
				if (material.replacementCondition.apply(config))
				{
					for (Material toReplace : material.materialsToReplace) {
						index = materialOrdinalToTextureIndex[toReplace.ordinal()];
						materialReplacements[toReplace.ordinal()] = material.ordinal();
					}
				}
				else
				{
					continue;
				}
			}

			if (material.parent != null)
			{
				// Point this material to pre-existing texture from parent material
				materialOrdinalToTextureIndex[material.ordinal()] = materialOrdinalToTextureIndex[material.parent.ordinal()];
				continue;
			}

			String textureName = material.name().toLowerCase();
			BufferedImage image = loadTextureImage(textureName);
			if (image == null) {
				if (material.vanillaTextureIndex == -1)
					System.err.println("No texture found for material: " + material);
				continue;
			}

			if (index == -1)
			{
				index = unusedIndices.pollFirst();
				if (index == null)
				{
					index = i++;
				}
			}

			uploadTexture(index, image);
			materialOrdinalToTextureIndex[material.ordinal()] = index;
		}

		int hdCount = i - unusedIndices.size() - vanillaCount;
		log.debug("Loaded {} HD textures", hdCount);

		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);

		// Reset
		pixelBuffer = null;
		scaledImage = null;
		vanillaImage = null;
		textureProvider.setBrightness(save);
		glActiveTexture(TEXTURE_UNIT_UI);

		plugin.updateMaterialUniformBuffer(textureAnimations);
		plugin.updateWaterTypeUniformBuffer();
	}

	private BufferedImage loadTextureImage(String textureName)
	{
		for (String ext : SUPPORTED_IMAGE_EXTENSIONS)
		{
			ResourcePath path = TEXTURE_PATH.resolve(textureName + "." + ext);
			try {
				return path.loadImage();
			} catch (Exception ex) {
				log.trace("Unable to load texture: {}", path, ex);
			}
		}

		return null;
	}

	private void uploadTexture(int index, BufferedImage image)
	{
		// TODO: scale and transform on the GPU for better performance
		AffineTransform t = new AffineTransform();
		if (image != vanillaImage)
		{
			// Flip non-vanilla textures horizontally to match vanilla UV orientation
			t.translate(textureSize, 0);
			t.scale(-1, 1);
		}
		t.scale((double) textureSize / image.getWidth(), (double) textureSize / image.getHeight());
		AffineTransformOp scaleOp = new AffineTransformOp(t, AffineTransformOp.TYPE_BICUBIC);
		scaleOp.filter(image, scaledImage);

		int[] pixels = ((DataBufferInt) scaledImage.getRaster().getDataBuffer()).getData();
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
			// Trilinear filtering is used for HD textures as linear filtering produces noisy textures
			// that are very noticeable on terrain
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
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
}
