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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.WaterType;
import rs117.hd.data.materials.Material;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL43C.*;
import static rs117.hd.HdPlugin.SCALAR_BYTES;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class TextureManager {
	private static final String[] SUPPORTED_IMAGE_EXTENSIONS = { "png", "jpg" };
	private static final float HALF_PI = (float) (Math.PI / 2);
	private static final ResourcePath TEXTURE_PATH = Props.getPathOrDefault(
		"rlhd.texture-path",
		() -> path(TextureManager.class, "textures")
	);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executorService;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	private int textureArray;
	private int textureSize;

	// Temporary variables for texture loading and generating material uniforms
	private IntBuffer pixelBuffer;
	private BufferedImage scaledImage;
	private BufferedImage vanillaImage;
	private float[] vanillaTextureAnimations;
	private ArrayList<MaterialEntry> materialUniformEntries;
	private int[] materialOrdinalToTextureLayer;
	private int[] vanillaTextureIndexToTextureLayer;
	private ScheduledFuture<?> pendingReload;

	public void startUp() {
		clientThread.invoke(this::ensureMaterialsAreLoaded);

		TEXTURE_PATH.watch((path, first) -> {
			if (first) return;
			log.debug("Texture changed: {}", path);

			if (pendingReload == null || pendingReload.cancel(false) || pendingReload.isDone())
				pendingReload = executorService.schedule(this::reloadTextures, 100, TimeUnit.MILLISECONDS);
		});
	}

	public void shutDown() {
		clientThread.invoke(this::freeTextures);
	}

	public void reloadTextures() {
		clientThread.invoke(() -> {
			freeTextures();
			ensureMaterialsAreLoaded();
			modelOverrideManager.reload();
		});
	}

	private void freeTextures() {
		if (textureArray != 0)
			glDeleteTextures(textureArray);
		textureArray = 0;
	}

	@RequiredArgsConstructor
	private static class MaterialEntry {
		final Material material;
		final int vanillaIndex;
	}

	@RequiredArgsConstructor
	private static class TextureLayer {
		final Material material;
		final int vanillaIndex;
		final int index;
	}

	private final int[] materialOrdinalToMaterialUniformIndex = new int[Material.values().length];
	private int[] vanillaTextureIndexToMaterialUniformIndex = {};

	public int getMaterialIndex(@Nonnull Material material, int vanillaTextureIndex) {
		if (material == Material.VANILLA &&
			vanillaTextureIndex >= 0 &&
			vanillaTextureIndex < vanillaTextureIndexToMaterialUniformIndex.length)
			return vanillaTextureIndexToMaterialUniformIndex[vanillaTextureIndex];
		return materialOrdinalToMaterialUniformIndex[material.ordinal()];
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

	private void ensureMaterialsAreLoaded() {
		if (textureArray != 0)
			return;

		assert vanillaTexturesAvailable();
		var textureProvider = client.getTextureProvider();
		Texture[] vanillaTextures = textureProvider.getTextures();
		Material.updateMappings(vanillaTextures, plugin);

		// Add material uniforms for all active material definitions
		materialUniformEntries = new ArrayList<>();
		for (var material : Material.getActiveMaterials())
			materialUniformEntries.add(new MaterialEntry(material, material.vanillaTextureIndex));

		// Add texture layers for each material that adds its own texture, after resolving replacements
		ArrayList<TextureLayer> textureLayers = new ArrayList<>();
		materialOrdinalToTextureLayer = new int[Material.values().length];
		Arrays.fill(materialOrdinalToTextureLayer, -1);
		for (var textureMaterial : Material.getTextureMaterials()) {
			int layerIndex = textureLayers.size();
			textureLayers.add(new TextureLayer(textureMaterial, textureMaterial.vanillaTextureIndex, layerIndex));
			materialOrdinalToTextureLayer[textureMaterial.ordinal()] = layerIndex;
		}

		// Prepare mappings for materials that don't provide their own textures
		for (var material : Material.values())
			if (materialOrdinalToTextureLayer[material.ordinal()] == -1)
				materialOrdinalToTextureLayer[material.ordinal()] =
					materialOrdinalToTextureLayer[material.resolveTextureMaterial().ordinal()];

		// Add material uniforms and texture layers for any vanilla textures lacking a material definition
		vanillaTextureIndexToTextureLayer = new int[vanillaTextures.length];
		Arrays.fill(vanillaTextureIndexToTextureLayer, -1);
		for (int i = 0; i < vanillaTextures.length; i++) {
			if (Material.fromVanillaTexture(i) == Material.VANILLA) {
				materialUniformEntries.add(new MaterialEntry(Material.VANILLA, i));
				int layerIndex = textureLayers.size();
				textureLayers.add(new TextureLayer(Material.VANILLA, i, layerIndex));
				vanillaTextureIndexToTextureLayer[i] = layerIndex;
			}
		}

		// Allocate texture array
		textureSize = config.textureResolution().getSize();
		textureArray = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_GAME);
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArray);
		if (plugin.glCaps.glTexStorage3D != 0) {
			glTexStorage3D(GL_TEXTURE_2D_ARRAY, 8, GL_SRGB8_ALPHA8,
				textureSize, textureSize, textureLayers.size()
			);
		} else {
			// Allocate each mip level separately
			for (int i = 0, size = textureSize; size >= 1; i++, size /= 2) {
				glTexImage3D(GL_TEXTURE_2D_ARRAY, i, GL_SRGB8_ALPHA8,
					size, size, textureLayers.size(),
					0, GL_RGBA, GL_UNSIGNED_BYTE, 0
				);
			}
		}

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
		setAnisotropicFilteringLevel();

		log.debug("Allocated {}x{} texture array with {} layers", textureSize, textureSize, textureLayers.size());

		// Begin loading textures

		// Set brightness to 1.0d to upload unmodified textures to GPU
		double vanillaBrightness = textureProvider.getBrightness();
		textureProvider.setBrightness(1.0d);

		// Allocate resources for storing temporary image data
		pixelBuffer = BufferUtils.createIntBuffer(textureSize * textureSize);
		scaledImage = new BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_ARGB);
		vanillaImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		vanillaTextureAnimations = new float[vanillaTextures.length * 2];

		int vanillaTextureCount = 0;
		int hdTextureCount = 0;
		for (var textureLayer : textureLayers) {
			var material = textureLayer.material;

			BufferedImage image = null;
			// Check if HD provides a texture for the material
			if (material != Material.VANILLA) {
				image = loadTextureImage(material);
				if (image == null && material.vanillaTextureIndex == -1) {
					log.warn("No texture found for material: {}", material);
					continue;
				}
			}

			// Fallback to loading a vanilla image
			if (image == null) {
				int vanillaIndex = textureLayer.vanillaIndex;
				var texture = vanillaTextures[vanillaIndex];
				if (texture == null)
					continue;

				int[] pixels = textureProvider.load(vanillaIndex);
				if (pixels == null) {
					log.warn("No pixels for vanilla texture at index {}", vanillaIndex);
					continue;
				}
				int resolution = (int) Math.round(Math.sqrt(pixels.length));
				if (resolution * resolution != pixels.length) {
					log.warn("Unknown dimensions for vanilla texture at index {} ({} pixels)", vanillaIndex, pixels.length);
					continue;
				}

				for (int j = 0; j < pixels.length; j++) {
					int rgb = pixels[j];
					// Black is considered transparent in vanilla, with anything else being fully opaque
					int alpha = rgb == 0 ? 0 : 0xFF;
					vanillaImage.setRGB(j % 128, j / 128, alpha << 24 | rgb & 0xFFFFFF);
				}

				image = vanillaImage;
				vanillaTextureCount++;
			} else {
				hdTextureCount++;
			}

			try {
				uploadTexture(textureLayer, image);
			} catch (Exception ex) {
				log.error("Failed to load texture {}:", textureLayer.material, ex);
			}
		}

		// Convert vanilla texture animations to the same format as Material scroll parameters
		for (int i = 0; i < vanillaTextures.length; i++) {
			var texture = vanillaTextures[i];
			if (texture == null)
				continue;

			int direction = texture.getAnimationDirection();
			if (direction != 0) {
				float speed = texture.getAnimationSpeed() * 50 / 128.f;
				float radians = direction * -HALF_PI;
				vanillaTextureAnimations[i * 2] = (float) Math.cos(radians) * speed;
				vanillaTextureAnimations[i * 2 + 1] = (float) Math.sin(radians) * speed;
			}
		}

		log.debug("Loaded {} HD & {} vanilla textures", hdTextureCount, vanillaTextureCount);

		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);

		vanillaTextureIndexToMaterialUniformIndex = new int[vanillaTextures.length];
		plugin.updateMaterialUniformBuffer(generateMaterialUniformBuffer());
		plugin.updateWaterTypeUniformBuffer(generateWaterTypeUniformBuffer());

		// Reset
		pixelBuffer = null;
		scaledImage = null;
		vanillaImage = null;
		vanillaTextureAnimations = null;
		materialUniformEntries = null;
		materialOrdinalToTextureLayer = null;
		vanillaTextureIndexToTextureLayer = null;
		textureProvider.setBrightness(vanillaBrightness);
		glActiveTexture(TEXTURE_UNIT_UI);
	}

	private BufferedImage loadTextureImage(Material material) {
		String textureName = material.name().toLowerCase();
		for (String ext : SUPPORTED_IMAGE_EXTENSIONS) {
			ResourcePath path = TEXTURE_PATH.resolve(textureName + "." + ext);
			try {
				return path.loadImage();
			} catch (Exception ex) {
				log.trace("Unable to load texture: {}", path, ex);
			}
		}

		return null;
	}

	private void uploadTexture(TextureLayer layer, BufferedImage image) {
		// TODO: scale and transform on the GPU for better performance
		AffineTransform t = new AffineTransform();
		if (image != vanillaImage) {
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
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0,
			layer.index, textureSize, textureSize, 1,
			GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer
		);
	}

	private void setAnisotropicFilteringLevel() {
		int level = config.anisotropicFilteringLevel();
		if (level == 0) {
			//level = 0 means no mipmaps and no anisotropic filtering
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		} else {
			// level = 1 means with mipmaps but without anisotropic filtering GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT defaults to 1.0 which is off
			// level > 1 enables anisotropic filtering. It's up to the vendor what the values mean
			// Even if anisotropic filtering isn't supported, mipmaps will be enabled with any level >= 1
			// Trilinear filtering is used for HD textures as linear filtering produces noisy textures
			// that are very noticeable on terrain
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		}

		if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
			final float maxSamples = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
			//Clamp from 1 to max GL says it supports.
			final float anisoLevel = Math.max(1, Math.min(maxSamples, level));
			glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisoLevel);
		}
	}

	private ByteBuffer generateMaterialUniformBuffer() {
		final int materialBytes = 20 * SCALAR_BYTES;
		ByteBuffer buffer = BufferUtils.createByteBuffer(materialUniformEntries.size() * materialBytes);
		for (int i = 0; i < materialUniformEntries.size(); i++) {
			MaterialEntry entry = materialUniformEntries.get(i);
			materialOrdinalToMaterialUniformIndex[entry.material.ordinal()] = i;
			if (entry.vanillaIndex != -1)
				vanillaTextureIndexToMaterialUniformIndex[entry.vanillaIndex] = i;
			writeMaterialData(buffer, entry);
		}
		for (var material : Material.values())
			materialOrdinalToMaterialUniformIndex[material.ordinal()] =
				materialOrdinalToMaterialUniformIndex[material.resolveReplacements().ordinal()];
		return buffer.flip();
	}

	private int getTextureLayer(@Nullable Material material) {
		if (material == null)
			return -1;
		material = material.resolveTextureMaterial();
		return materialOrdinalToTextureLayer[material.ordinal()];
	}

	private void writeMaterialData(ByteBuffer buffer, MaterialEntry entry) {
		var m = entry.material;
		var vanillaIndex = entry.vanillaIndex;

		float scrollSpeedX = m.scrollSpeed[0];
		float scrollSpeedY = m.scrollSpeed[1];
		if (vanillaIndex != -1) {
			scrollSpeedX += vanillaTextureAnimations[vanillaIndex * 2];
			scrollSpeedY += vanillaTextureAnimations[vanillaIndex * 2 + 1];
		}

		int baseColorTextureIndex = -1;
		if (m == Material.VANILLA) {
			baseColorTextureIndex = vanillaTextureIndexToTextureLayer[vanillaIndex];
		} else if (m != Material.NONE) {
			baseColorTextureIndex = materialOrdinalToTextureLayer[m.ordinal()];
		}

		buffer
			.putInt(baseColorTextureIndex)
			.putInt(getTextureLayer(m.normalMap))
			.putInt(getTextureLayer(m.displacementMap))
			.putInt(getTextureLayer(m.roughnessMap))
			.putInt(getTextureLayer(m.ambientOcclusionMap))
			.putInt(getTextureLayer(m.flowMap))
			.putInt(
				(m.overrideBaseColor ? 1 : 0) << 2 |
				(m.unlit ? 1 : 0) << 1 |
				(m.hasTransparency ? 1 : 0)
			)
			.putFloat(m.brightness)
			.putFloat(m.displacementScale)
			.putFloat(m.specularStrength)
			.putFloat(m.specularGloss)
			.putFloat(m.flowMapStrength)
			.putFloat(m.flowMapDuration[0])
			.putFloat(m.flowMapDuration[1])
			.putFloat(scrollSpeedX)
			.putFloat(scrollSpeedY)
			.putFloat(1 / m.textureScale[0])
			.putFloat(1 / m.textureScale[1])
			.putFloat(1 / m.textureScale[2])
			.putFloat(0); // align vec4
	}

	private ByteBuffer generateWaterTypeUniformBuffer() {
		ByteBuffer buffer = BufferUtils.createByteBuffer(WaterType.values().length * 28 * SCALAR_BYTES);
		for (WaterType type : WaterType.values())
			buffer
				.putInt(type.flat ? 1 : 0)
				.putFloat(type.specularStrength)
				.putFloat(type.specularGloss)
				.putFloat(type.normalStrength)
				.putFloat(type.baseOpacity)
				.putInt(type.hasFoam ? 1 : 0)
				.putFloat(type.duration)
				.putFloat(type.fresnelAmount)
				.putFloat(type.surfaceColor[0])
				.putFloat(type.surfaceColor[1])
				.putFloat(type.surfaceColor[2])
				.putFloat(0) // pad vec4
				.putFloat(type.foamColor[0])
				.putFloat(type.foamColor[1])
				.putFloat(type.foamColor[2])
				.putFloat(0) // pad vec4
				.putFloat(type.depthColor[0])
				.putFloat(type.depthColor[1])
				.putFloat(type.depthColor[2])
				.putFloat(0) // pad vec4
				.putFloat(type.causticsStrength)
				.putInt(getTextureLayer(type.normalMap))
				.putInt(getTextureLayer(Material.WATER_FOAM))
				.putInt(getTextureLayer(Material.WATER_FLOW_MAP))
				.putInt(getTextureLayer(Material.UNDERWATER_FLOW_MAP))
				.putFloat(0).putFloat(0).putFloat(0); // pad vec4
		return buffer.flip();
	}
}
