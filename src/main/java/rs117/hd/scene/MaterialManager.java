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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
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

	public static class TextureLayer {
		Material material;
		boolean needsUpload = true;
	}

	public static final Map<String, Material> MATERIAL_MAP = new HashMap<>();
	public static Material[] MATERIALS;
	public static Material[] VANILLA_TEXTURE_MAPPING;

	private int texMaterialTextureArray;
	private int[] textureResolution;
	public final List<TextureLayer> textureLayers = new ArrayList<>();

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = MATERIALS_PATH.watch((path, first) -> reload(first));
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;

		if (texMaterialTextureArray != 0)
			glDeleteTextures(texMaterialTextureArray);
		texMaterialTextureArray = 0;
		textureLayers.clear();

		if (uboMaterials != null)
			uboMaterials.destroy();
		uboMaterials = null;

		MATERIAL_MAP.clear();
		invalidateMaterials(MATERIALS);
		MATERIALS = VANILLA_TEXTURE_MAPPING = null;
	}

	public Material getMaterial(String name) {
		var mat = MATERIAL_MAP.get(name);
		if (mat != null)
			return mat;
		log.warn("Couldn't find material '{}', falling back to NONE", name);
		return Material.NONE;
	}

	public Material fromVanillaTexture(int vanillaTextureId) {
		if (vanillaTextureId < 0 || vanillaTextureId >= VANILLA_TEXTURE_MAPPING.length)
			return Material.NONE;
		return VANILLA_TEXTURE_MAPPING[vanillaTextureId];
	}

	public void reload(boolean throwOnFailure) {
		clientThread.invoke(() -> {
			try {
				Material[] materials = loadMaterials(MATERIALS_PATH);
				log.debug("Loaded {} materials", materials.length);
				clientThread.invoke(() -> swapMaterials(materials));
			} catch (IOException ex) {
				log.error("Failed to load materials:", ex);
				if (throwOnFailure)
					throw new IllegalStateException(ex);
			}
		});
	}

	private Material[] loadMaterials(ResourcePath path) throws IOException {
		// Gson provides no simple way to let one object inherit defaults from another object,
		// so we parse the JSON manually into a JsonArray, which we can process to copy default
		// values from parent materials in the correct order.
		var gson = plugin.getGson();
		var rawMaterials = path.loadJson(gson, JsonArray.class);
		if (rawMaterials == null)
			throw new IOException("Empty or invalid: " + path);

		var rawMaterialMap = new HashMap<String, JsonObject>();
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
				String parentName = parentField.getAsString();
				// Don't allow inheriting from NONE. Those defaults will be inherited anyway
				if (parentName.equals(Material.NONE.name))
					break;

				var nextParent = rawMaterialMap.get(parentName);
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
		int unnamedCounter = 1;
		for (var mat : materials) {
			if (mat.name == null)
				mat.name = "UNNAMED_" + unnamedCounter++;
			materialMap.put(mat.name, mat);

			if (Props.DEVELOPMENT && mat.replacementCondition instanceof ExpressionParser.SerializableExpressionPredicate) {
				// Ensure the variables are defined
				var expr = ((ExpressionParser.SerializableExpressionPredicate) mat.replacementCondition).expression;
				for (var variable : expr.variables)
					if (vars.get(variable) == null)
						throw new IllegalStateException(String.format("Unknown variable '%s' in expression: '%s'", variable, expr));
			}
		}

		for (var mat : materials)
			mat.normalize(materialMap);

		checkForReplacementLoops(materials);
		return materials;
	}

	private void swapMaterials(Material[] parsedMaterials) {
		assert client.isClientThread();
		assert textureManager.vanillaTexturesAvailable();

		boolean isFirstLoad = MATERIALS == null;
		var textureProvider = client.getTextureProvider();
		var vanillaTextures = textureProvider.getTextures();
		VANILLA_TEXTURE_MAPPING = new Material[vanillaTextures.length];

		// Assemble the material map, accounting for replacements
		MATERIAL_MAP.clear();
		for (var original : parsedMaterials) {
			// If the material is a conditional replacement material, and the condition is not met,
			// the material shouldn't be loaded and can be mapped to NONE
			Material replacement = original;
			if (original.isInactiveReplacement(plugin.vars)) {
				replacement = Material.NONE;
			} else {
				// Apply material replacements from top to bottom
				for (var other : parsedMaterials)
					if (other.replaces(replacement.name, plugin.vars))
						replacement = other;
			}

			MATERIAL_MAP.put(original.name, replacement);

			// Add to vanilla texture mappings if the original was a vanilla replacement
			if (original.isVanillaReplacement()) {
				int i = original.vanillaTextureIndex;
				assert VANILLA_TEXTURE_MAPPING[i] == null || VANILLA_TEXTURE_MAPPING[i] == replacement : String.format(
					"Material %s conflicts with vanilla ID %s of material %s", replacement, i, VANILLA_TEXTURE_MAPPING[i]);
				VANILLA_TEXTURE_MAPPING[i] = replacement;
			}
		}

		// Add dummy materials for any vanilla textures lacking one
		for (int i = 0; i < VANILLA_TEXTURE_MAPPING.length; i++) {
			if (vanillaTextures[i] == null || VANILLA_TEXTURE_MAPPING[i] != null)
				continue;

			var m = new Material()
				.name("VANILLA_" + i)
				.vanillaTextureIndex(i)
				.isFallbackVanillaMaterial(true)
				.hasTransparency(true);
			MATERIAL_MAP.put(m.name, m);
			VANILLA_TEXTURE_MAPPING[i] = m;
		}

		// Gather all unique materials after displacements into an array
		invalidateMaterials(MATERIALS);
		MATERIALS = MATERIAL_MAP.values().stream().distinct().toArray(Material[]::new);
		// Ensure that NONE is the first material
		for (int i = 0; i < MATERIALS.length; i++) {
			if (MATERIALS[i] == Material.NONE) {
				MATERIALS[i] = MATERIALS[0];
				MATERIALS[0] = Material.NONE;
				break;
			}
		}

		// Resolve all texture-owning materials, and update the list of texture layers
		var textureMaterials = Arrays.stream(MATERIALS)
			.map(Material::resolveTextureOwner)
			.distinct()
			.filter(m -> m != Material.NONE)
			.toArray(Material[]::new);
		int previousLayerCount = textureLayers.size();
		int textureLayerIndex = 0;
		for (var mat : textureMaterials) {
			TextureLayer layer;
			if (textureLayerIndex == textureLayers.size()) {
				layer = new TextureLayer();
				textureLayers.add(layer);
			} else {
				layer = textureLayers.get(textureLayerIndex);
				layer.needsUpload = !Objects.equals(mat.getTextureName(), layer.material.getTextureName());
			}
			layer.material = mat;
			mat.textureLayer = textureLayerIndex++;
		}
		// Delete unused layers
		textureLayers.subList(textureLayerIndex, textureLayers.size()).clear();
		// Update texture layers for materials which inherit their texture
		for (var mat : MATERIALS)
			mat.textureLayer = mat.resolveTextureOwner().textureLayer;

		int textureSize = config.textureResolution().getSize();
		textureResolution = ivec(textureSize, textureSize);
		glActiveTexture(TEXTURE_UNIT_GAME);
		if (texMaterialTextureArray == 0 || previousLayerCount != textureLayers.size()) {
			if (texMaterialTextureArray != 0)
				glDeleteTextures(texMaterialTextureArray);
			texMaterialTextureArray = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_ARRAY, texMaterialTextureArray);

			// Since we're reallocating the texture array, all layers need to be reuploaded
			for (var layer : textureLayers)
				layer.needsUpload = true;

			log.debug("Allocating {}x{} texture array with {} layers", textureSize, textureSize, textureLayers.size());
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
		}
		textureManager.setAnisotropicFilteringLevel();

		uploadTextures();

		boolean materialOrderChanged = true;
		if (uboMaterials != null && uboMaterials.materials.length == MATERIALS.length) {
			materialOrderChanged = false;
			for (int i = 0; i < MATERIALS.length; i++) {
				var a = MATERIALS[i];
				var b = uboMaterials.materials[i];
				if (a.vanillaTextureIndex != b.vanillaTextureIndex ||
					a.modifiesVanillaTexture != b.modifiesVanillaTexture ||
					!a.name.equals(b.name)
				) {
					materialOrderChanged = true;
					break;
				}
			}
		} else {
			if (uboMaterials != null)
				uboMaterials.destroy();
			uboMaterials = new UBOMaterials(MATERIALS.length);
		}
		uboMaterials.update(MATERIALS, vanillaTextures);

		if (isFirstLoad)
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

	private void invalidateMaterials(Material[] materials) {
		// Invalidate old materials to highlight issues with keeping them around accidentally
		if (materials != null)
			for (var mat : materials)
				if (mat != Material.NONE)
					mat.isValid = false;
	}

	public void uploadTextures() {
		assert client.isClientThread();
		if (texMaterialTextureArray == 0)
			return;

		// Set brightness to 1 to upload unmodified vanilla textures
		var textureProvider = client.getTextureProvider();
		double vanillaBrightness = textureProvider.getBrightness();
		textureProvider.setBrightness(1);

		boolean uploadedAnything = false;
		for (var layer : textureLayers) {
			if (!layer.needsUpload)
				continue;

			var material = layer.material;
			var image = textureManager.loadTexture(material.getTextureName(), material.vanillaTextureIndex);
			if (image == null)
				continue;

			try {
				if (!uploadedAnything) {
					glActiveTexture(TEXTURE_UNIT_GAME);
					glBindTexture(GL_TEXTURE_2D_ARRAY, texMaterialTextureArray);
					uploadedAnything = true;
				}
				textureManager.uploadTexture(GL_TEXTURE_2D_ARRAY, material.textureLayer, textureResolution, image);
			} catch (Exception ex) {
				log.error("Failed to upload texture {}:", material, ex);
			}
		}

		// Reset the texture brightness
		textureProvider.setBrightness(vanillaBrightness);

		if (uploadedAnything)
			glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
	}

	private static void checkForReplacementLoops(Material[] materials) {
		Map<String, Material> map = new HashMap<>();
		for (var mat : materials)
			if (!mat.materialsToReplace.isEmpty())
				map.put(mat.name, mat);

		Set<String> alreadyChecked = new HashSet<>();
		for (var mat : map.values())
			checkForReplacementLoops(alreadyChecked, map, mat);
	}

	private static void checkForReplacementLoops(Set<String> alreadyChecked, Map<String, Material> map, Material entryMaterial) {
		if (alreadyChecked.add(entryMaterial.name))
			checkForReplacementLoops(alreadyChecked, map, new ArrayDeque<>(), entryMaterial.name, entryMaterial);
	}

	private static void checkForReplacementLoops(
		Set<String> alreadyChecked,
		Map<String, Material> map,
		ArrayDeque<String> loop,
		String entryPointName,
		Material toCheck
	) {
		loop.addLast(toCheck.findParent(m -> m.materialsToReplace).name);

		for (int i = toCheck.materialsToReplace.size() - 1; i >= 0; i--) {
			String nameToReplace = toCheck.materialsToReplace.get(i);
			// Check if the replacement introduces a loop
			if (entryPointName.equals(nameToReplace)) {
				var original = map.get(nameToReplace).findParent(m -> m.materialsToReplace).name;
				if (!nameToReplace.equals(original))
					nameToReplace += " (parent=" + original + ")";
				log.warn("Materials contain replacement loop: {} -> {}", String.join(" -> ", loop), nameToReplace);
				// Remove the loop
				toCheck.materialsToReplace.remove(i);
				continue;
			}

			var toReplace = map.get(nameToReplace);
			if (toReplace == null)
				continue;

			// Before continuing to check for loops back to the entrypoint name,
			// we need to rule out any loops within the next material to check,
			// so we don't get stuck in a loop there
			checkForReplacementLoops(alreadyChecked, map, toReplace);

			// The replacement might've already been removed to prevent a loop in the step above
			if (!toCheck.materialsToReplace.contains(nameToReplace))
				continue;

			// Check if any further replacements result in a loop
			checkForReplacementLoops(alreadyChecked, map, loop, entryPointName, toReplace);
		}

		loop.removeLast();
	}
}
