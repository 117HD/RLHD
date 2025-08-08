/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.model.ModelPusher;
import rs117.hd.opengl.uniforms.UBOMaterials;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.HDVariables;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class MaterialManager {
	private static final ResourcePath MATERIALS_PATH = Props
		.getFile("rlhd.materials-path", () -> path(MaterialManager.class, "materials.json"));

	@Inject
	private Gson unmodifiedGson;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private HDVariables vars;

	@Inject
	private TextureManager textureManager;

	@Inject
	private WaterTypeManager waterTypeManager;

	@Inject
	private GroundMaterialManager groundMaterialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private ModelPusher modelPusher;

	public UBOMaterials uboMaterials;

	@RequiredArgsConstructor
	private static class TextureLayer {
		final Material material;
		final int vanillaIndex;
		final int index;
	}

	public static final Map<String, Material> MATERIAL_MAP = new HashMap<>();
	public static Material[] MATERIALS;
	public static Material[] REPLACEMENT_MAPPING;
	public static Material[] VANILLA_TEXTURE_MAPPING;

	private int texMaterialTextureArray;

	public int[] materialIndexToTextureLayer;
	public int[] vanillaTextureIndexToTextureLayer;

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = MATERIALS_PATH.watch((path, first) -> {
			try {
				Material[] materials = parseMaterials(path);
				log.debug("Loaded {} materials", materials.length);
				clientThread.invoke(() -> loadMaterialTextures(materials));
			} catch (IOException ex) {
				log.error("Failed to load materials:", ex);
				if (first)
					throw new IllegalStateException(ex);
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;

		freeTextures();

		MATERIAL_MAP.clear();
		MATERIALS = REPLACEMENT_MAPPING = VANILLA_TEXTURE_MAPPING = null;
		materialIndexToTextureLayer = vanillaTextureIndexToTextureLayer = null;
	}

	public Material getMaterial(String name) {
		return MATERIAL_MAP.getOrDefault(name, Material.NONE);
	}

	public void reload() {
		clientThread.invoke(() -> {
			freeTextures();
			loadMaterialTextures(MATERIALS);
			modelOverrideManager.reload();
		});
	}

	public int getMaterialIndex(@Nonnull Material material, int vanillaTextureIndex) {
		assert uboMaterials != null;
		if (material == Material.VANILLA &&
			vanillaTextureIndex >= 0 &&
			vanillaTextureIndex < uboMaterials.vanillaTextureIndexToMaterialUniformIndex.length)
			return uboMaterials.vanillaTextureIndexToMaterialUniformIndex[vanillaTextureIndex];
		return uboMaterials.materialOrdinalToMaterialUniformIndex[material.index];
	}

	public int getTextureLayer(@Nullable Material material) {
		if (material == null)
			return -1;
		assert materialIndexToTextureLayer != null : "Textures must be loaded";
		material = material.resolveTextureMaterial();
		return materialIndexToTextureLayer[material.index];
	}

	private void freeTextures() {
		if (texMaterialTextureArray != 0)
			glDeleteTextures(texMaterialTextureArray);
		texMaterialTextureArray = 0;

		if (uboMaterials != null)
			uboMaterials.destroy();
		uboMaterials = null;

		materialIndexToTextureLayer = null;
	}

	private Material[] parseMaterials(ResourcePath path) throws IOException {
		// Gson provides no simple way to let one object inherit defaults from another object,
		// so we parse the JSON manually into a JsonArray, which we can process to copy default
		// values from parent materials in the correct order.
		var gson = plugin.getGson();
		var rawMaterials = path.loadJson(gson, JsonArray.class);
		if (rawMaterials == null)
			throw new IOException("Empty or invalid: " + path);

		var rawMaterialMap = new HashMap<String, JsonObject>();

		// Add static materials
		for (var mat : Material.REQUIRED_MATERIALS)
			rawMaterialMap.put(mat.name, unmodifiedGson.toJsonTree(mat, Material.class).getAsJsonObject());

		var materialToParentMap = new HashMap<String, String>();

		var validMaterials = new ArrayList<JsonObject>();
		for (var element : rawMaterials) {
			if (!element.isJsonObject()) {
				log.error("Invalid material. Expected an object. Got: '{}'", element);
				continue;
			}

			var mat = element.getAsJsonObject();
			var name = mat.get("name");
			if (name == null) {
				log.error("Material missing name: '{}'", mat);
				continue;
			}
			if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
				log.error("Material name is not a string: '{}'", mat);
				continue;
			}

			var parent = mat.get("parent");
			if (parent != null && (!parent.isJsonPrimitive() || !parent.getAsJsonPrimitive().isString())) {
				log.error("Error in material '{}': Invalid parent name: '{}'", name.getAsString(), parent);
				continue;
			}

			if (rawMaterialMap.putIfAbsent(name.getAsString(), mat) != null) {
				log.error("Duplicate material name: '{}'", name);
				continue;
			}

			validMaterials.add(mat);
			if (parent != null)
				materialToParentMap.put(name.getAsString(), parent.getAsString());
		}

		// Check for parent loops
		var iter = materialToParentMap.entrySet().iterator();
		while (iter.hasNext()) {
			var entry = iter.next();
			var original = entry.getKey();
			var current = entry.getValue();
			while ((current = materialToParentMap.get(current)) != null) {
				if (current.equals(original)) {
					log.error("Material '{}' contains a parent loop. Removing its parent...", original);
					rawMaterialMap.get(original).remove("parent"); // Remove parent in-place
					iter.remove(); // No longer has a parent
				}
			}
		}

		// Recursively resolve parents and apply default values from them
		for (var mat : rawMaterialMap.values()) {
			var parent = mat;
			JsonElement parentField;
			while ((parentField = parent.get("parent")) != null) {
				if (!parentField.isJsonPrimitive() || !parentField.getAsJsonPrimitive().isString()) {
					log.error("Error in material '{}': Invalid parent name '{}'", parent.get("name").getAsString(), parentField);
					break;
				}
				var nextParent = rawMaterialMap.get(parentField.getAsString());
				if (nextParent == null) {
					log.error(
						"Error in material '{}': Unknown parent name '{}'",
						parent.get("name").getAsString(),
						parentField.getAsString()
					);
					break;
				}

				for (var entry : nextParent.entrySet())
					if (!mat.has(entry.getKey()))
						mat.add(entry.getKey(), entry.getValue());

				parent = nextParent;
			}
		}

		var materialsToParse = new JsonArray();
		for (var mat : validMaterials)
			materialsToParse.add(mat);

		var materials = Stream.concat(
			Arrays.stream(Material.REQUIRED_MATERIALS),
			Arrays.stream(gson.fromJson(materialsToParse, Material.Definition[].class))
		).toArray(Material[]::new);

		var materialMap = new HashMap<String, Material>();
		for (var mat : materials) {
			materialMap.put(mat.name, mat);

			if (Props.DEVELOPMENT && mat.replacementCondition instanceof ExpressionParser.SerializableExpressionPredicate) {
				// Ensure the variables are defined
				var expr = ((ExpressionParser.SerializableExpressionPredicate) mat.replacementCondition).expression;
				for (var variable : expr.variables)
					if (vars.get(variable) == null)
						throw new IllegalStateException(String.format("Unknown variable '%s' in expression: '%s'", variable, expr));
			}
		}

		for (int i = 0; i < materials.length; i++)
			materials[i].normalize(i, materialMap);

		Material.checkForReplacementLoops(materials);
		return materials;
	}

	private Material[] getActiveMaterials() {
		return Arrays.stream(REPLACEMENT_MAPPING)
			.filter(m -> m != Material.VANILLA) // The VANILLA material is used for vanilla textures lacking a material definition
			.distinct()
			.toArray(Material[]::new);
	}

	private Material[] getTextureMaterials() {
		return Arrays.stream(REPLACEMENT_MAPPING)
			.map(Material::resolveTextureMaterial)
			.filter(m -> m != Material.NONE)
			.distinct()
			.toArray(Material[]::new);
	}

	private void loadMaterialTextures(Material[] materials) {
		assert client.isClientThread();
		assert textureManager.vanillaTexturesAvailable();

		boolean isFirst = MATERIALS == null;
		MATERIALS = materials;
		MATERIAL_MAP.clear();
		for (var m : materials)
			MATERIAL_MAP.put(m.name, m);

		// Update statically accessible materials
		Material.BLACK = getMaterial("BLACK");
		Material.WATER_FLAT = getMaterial("WATER_FLAT");
		Material.WATER_FLAT_2 = getMaterial("WATER_FLAT_2");
		Material.SWAMP_WATER_FLAT = getMaterial("SWAMP_WATER_FLAT");
		Material.WATER_NORMAL_MAP_1 = getMaterial("WATER_NORMAL_MAP_1");
		Material.WATER_FOAM = getMaterial("WATER_FOAM");
		Material.WATER_FLOW_MAP = getMaterial("WATER_FLOW_MAP");
		Material.DIRT_1 = getMaterial("DIRT_1");
		Material.DIRT_2 = getMaterial("DIRT_2");

		REPLACEMENT_MAPPING = new Material[MATERIALS.length];
		for (int i = 0; i < MATERIALS.length; ++i) {
			var material = MATERIALS[i];

			// If the material is a conditional replacement material, and the condition is not met,
			// the material shouldn't be loaded and can be mapped to NONE
			if (material.replacementCondition != null && !material.replacementCondition.test(plugin.vars)) {
				material = Material.NONE;
			} else {
				// Apply material replacements from top to bottom
				for (var replacement : MATERIALS) {
					if (replacement.replacementCondition != null &&
						replacement.materialsToReplace.contains(material.name) &&
						replacement.replacementCondition.test(plugin.vars)
					) {
						material = replacement;
						break;
					}
				}
			}

			REPLACEMENT_MAPPING[i] = material;
		}

		// Resolve replacements completely
		outer:
		for (int i = 0; i < MATERIALS.length; i++) {
			var replacement = REPLACEMENT_MAPPING[i];
			Material next;
			while ((next = REPLACEMENT_MAPPING[replacement.index]) != replacement) {
				if (next.index == i) {
					// This should never happen, since loops should have been removed already
					assert false : String.format("Material loop: %s -> ... -> %s -> %s", MATERIALS[i], replacement, next);
					continue outer;
				}
				replacement = next;
			}
			REPLACEMENT_MAPPING[i] = replacement;
		}

		var textureProvider = client.getTextureProvider();
		Texture[] vanillaTextures = textureProvider.getTextures();
		VANILLA_TEXTURE_MAPPING = new Material[vanillaTextures.length];
		Arrays.fill(VANILLA_TEXTURE_MAPPING, Material.VANILLA);
		for (int i = 0; i < vanillaTextures.length; i++) {
			for (var material : MATERIALS) {
				if (material.vanillaTextureIndex == i && material.parent == null) {
					assert VANILLA_TEXTURE_MAPPING[i] == Material.VANILLA :
						"Material " + material + " conflicts with vanilla ID " + material.vanillaTextureIndex + " of material "
						+ VANILLA_TEXTURE_MAPPING[i];
					VANILLA_TEXTURE_MAPPING[i] = material.resolveReplacements();
				}
			}
		}

		// Add material uniforms for all active material definitions
		// Temporary variables for texture loading and generating material uniforms
		var uboEntries = new ArrayList<UBOMaterials.MaterialEntry>();
		for (var material : getActiveMaterials())
			uboEntries.add(new UBOMaterials.MaterialEntry(material, material.vanillaTextureIndex));

		// Add texture layers for each material that adds its own texture, after resolving replacements
		ArrayList<TextureLayer> textureLayers = new ArrayList<>();
		materialIndexToTextureLayer = new int[MaterialManager.MATERIALS.length];
		Arrays.fill(materialIndexToTextureLayer, -1);
		for (var textureMaterial : getTextureMaterials()) {
			int layerIndex = textureLayers.size();
			textureLayers.add(new TextureLayer(textureMaterial, textureMaterial.vanillaTextureIndex, layerIndex));
			materialIndexToTextureLayer[textureMaterial.index] = layerIndex;
		}

		// Prepare mappings for materials that don't provide their own textures
		for (var material : MaterialManager.MATERIALS)
			if (materialIndexToTextureLayer[material.index] == -1)
				materialIndexToTextureLayer[material.index] =
					materialIndexToTextureLayer[material.resolveTextureMaterial().index];

		// Add material uniforms and texture layers for any vanilla textures lacking a material definition
		vanillaTextureIndexToTextureLayer = new int[vanillaTextures.length];
		Arrays.fill(vanillaTextureIndexToTextureLayer, -1);
		for (int i = 0; i < vanillaTextures.length; i++) {
			if (vanillaTextures[i] != null && Material.fromVanillaTexture(i) == Material.VANILLA) {
				uboEntries.add(new UBOMaterials.MaterialEntry(Material.VANILLA, i));
				int layerIndex = textureLayers.size();
				textureLayers.add(new TextureLayer(Material.VANILLA, i, layerIndex));
				vanillaTextureIndexToTextureLayer[i] = layerIndex;
			}
		}

		// Allocate texture array
		glActiveTexture(TEXTURE_UNIT_GAME);
		texMaterialTextureArray = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texMaterialTextureArray);

		int textureSize = config.textureResolution().getSize();
		int mipLevels = 1 + floor(log2(textureSize));
		int format = GL_SRGB8_ALPHA8;
		if (HdPlugin.GL_CAPS.glTexStorage3D != 0) {
			ARBTextureStorage.glTexStorage3D(GL_TEXTURE_2D_ARRAY, mipLevels, format, textureSize, textureSize, textureLayers.size());
		} else {
			// Allocate each mip level separately
			for (int i = 0; i < mipLevels; i++) {
				int size = textureSize >> i;
				glTexImage3D(GL_TEXTURE_2D_ARRAY, i, format, size, size, textureLayers.size(), 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
			}
		}

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
		textureManager.setAnisotropicFilteringLevel();

		log.debug("Allocated {}x{} texture array with {} layers", textureSize, textureSize, textureLayers.size());

		// Set brightness to 1 to upload unmodified vanilla textures
		double vanillaBrightness = textureProvider.getBrightness();
		textureProvider.setBrightness(1);
		for (var textureLayer : textureLayers) {
			var material = textureLayer.material;
			var image = textureManager.loadTexture(material.getTextureName(), textureLayer.vanillaIndex);
			if (image == null)
				continue;

			try {
				textureManager.uploadTexture(GL_TEXTURE_2D_ARRAY, textureLayer.index, ivec(textureSize, textureSize), image);
			} catch (Exception ex) {
				log.error("Failed to upload texture {}:", material, ex);
			}
		}
		textureProvider.setBrightness(vanillaBrightness);

		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);

		boolean materialOrderChanged = true;
		if (uboMaterials != null && uboMaterials.entries.size() == uboEntries.size()) {
			materialOrderChanged = false;
			for (int i = 0; i < uboEntries.size(); i++) {
				var a = uboEntries.get(i);
				var b = uboMaterials.entries.get(i);
				if (a.vanillaIndex != b.vanillaIndex ||
					a.material.index != b.material.index ||
					a.material.modifiesVanillaTexture != b.material.modifiesVanillaTexture ||
					!a.material.name.equals(b.material.name)
				) {
					materialOrderChanged = true;
					break;
				}
			}
		} else {
			if (uboMaterials != null)
				uboMaterials.destroy();
			uboMaterials = new UBOMaterials(uboEntries.size());
		}
		uboMaterials.update(this, uboEntries, vanillaTextures);

		if (isFirst)
			return;

		// Reload anything which depends on Material instances
		waterTypeManager.restart();
		groundMaterialManager.restart();
		tileOverrideManager.reload(false);
		modelOverrideManager.reload();

		if (materialOrderChanged) {
			modelPusher.clearModelCache();
			plugin.reuploadScene();
			plugin.recompilePrograms();
		}
	}
}
