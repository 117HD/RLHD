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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.HDVariables;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class MaterialManager {
	private static final ResourcePath MATERIALS_PATH = Props
		.getFile("rlhd.materials-path", () -> path(MaterialManager.class, "materials.json"));

	@Inject
	private Gson unmodifiedGson;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

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

	public static final Map<String, Material> MATERIAL_MAP = new HashMap<>();
	public static Material[] MATERIALS = {};
	public static Material[] REPLACEMENT_MAPPING = {};
	public static Material[] VANILLA_TEXTURE_MAPPING = {};

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = MATERIALS_PATH.watch((path, first) -> {
			try {
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

				checkForReplacementLoops(materials);

				log.debug("Loaded {} materials", materials.length);

				clientThread.invoke(() -> {
					plugin.waitUntilIdle();

					MATERIALS = materials;
					MATERIAL_MAP.clear();
					for (var m : materials)
						MATERIAL_MAP.put(m.name, m);

					// Update statically accessible materials
					Material.UNLIT = get("UNLIT");
					Material.BLACK = get("BLACK");
					Material.WATER_FLAT = get("WATER_FLAT");
					Material.WATER_FLAT_2 = get("WATER_FLAT_2");
					Material.SWAMP_WATER_FLAT = get("SWAMP_WATER_FLAT");
					Material.WATER_FOAM = get("WATER_FOAM");
					Material.WATER_FLOW_MAP = get("WATER_FLOW_MAP");
					Material.WATER_NORMAL_MAP_1 = get("WATER_NORMAL_MAP_1");
					Material.DIRT_1 = get("DIRT_1");
					Material.DIRT_2 = get("DIRT_2");

					if (first)
						return;

					// TODO: Manage material UBO here and only reload what's necessary
					textureManager.reloadTextures();

					waterTypeManager.shutDown();
					waterTypeManager.startUp();

					groundMaterialManager.shutDown();
					groundMaterialManager.startUp();

					tileOverrideManager.reload(false); // Let the ModelOverrideManager reload the scene

					modelOverrideManager.reload();

					modelPusher.clearModelCache();
					plugin.reuploadScene();
				});
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

		MATERIALS = new Material[0];
		MATERIAL_MAP.clear();
	}

	public Material get(String name) {
		for (var type : MATERIALS)
			if (name.equals(type.name))
				return type;
		return Material.NONE;
	}

	public void updateMappings(Texture[] textures) {
		var materials = MATERIALS;
		REPLACEMENT_MAPPING = new Material[materials.length];
		for (int i = 0; i < materials.length; ++i) {
			var material = materials[i];

			// If the material is a conditional replacement material, and the condition is not met,
			// the material shouldn't be loaded and can be mapped to NONE
			if (material.replacementCondition != null && !material.replacementCondition.test(plugin.vars)) {
				material = Material.NONE;
			} else {
				// Apply material replacements from top to bottom
				for (var replacement : materials) {
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
		for (int i = 0; i < materials.length; i++) {
			var replacement = REPLACEMENT_MAPPING[i];
			Material next;
			while ((next = REPLACEMENT_MAPPING[replacement.index]) != replacement) {
				if (next.index == i) {
					// This should never happen, since loops should have been removed already
					assert false : String.format("Material loop: %s -> ... -> %s -> %s", materials[i], replacement, next);
					continue outer;
				}
				replacement = next;
			}
			REPLACEMENT_MAPPING[i] = replacement;
		}

		VANILLA_TEXTURE_MAPPING = new Material[textures.length];
		Arrays.fill(VANILLA_TEXTURE_MAPPING, Material.VANILLA);
		for (int i = 0; i < textures.length; i++) {
			for (var material : materials) {
				if (material.vanillaTextureIndex == i && material.parent == null) {
					assert VANILLA_TEXTURE_MAPPING[i] == Material.VANILLA :
						"Material " + material + " conflicts with vanilla ID " + material.vanillaTextureIndex + " of material "
						+ VANILLA_TEXTURE_MAPPING[i];
					VANILLA_TEXTURE_MAPPING[i] = material.resolveReplacements();
				}
			}
		}
	}

	public Material fromVanillaTexture(int vanillaTextureId) {
		if (vanillaTextureId < 0 || vanillaTextureId >= VANILLA_TEXTURE_MAPPING.length)
			return Material.NONE;
		return VANILLA_TEXTURE_MAPPING[vanillaTextureId];
	}

	/**
	 * @return an array of all unique materials in use after all replacements have been accounted for, including NONE.
	 */
	public Material[] getActiveMaterials() {
		return Arrays.stream(REPLACEMENT_MAPPING)
			.filter(m -> m != Material.VANILLA) // The VANILLA material is used for vanilla textures lacking a material definition
			.distinct()
			.toArray(Material[]::new);
	}

	/**
	 * @return an array of all unique materials in use after all replacements have been accounted for, except NONE.
	 */
	public Material[] getTextureMaterials() {
		return Arrays.stream(REPLACEMENT_MAPPING)
			.map(Material::resolveTextureMaterial)
			.filter(m -> m != Material.NONE)
			.distinct()
			.toArray(Material[]::new);
	}

	private void checkForReplacementLoops(Material[] materials) {
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
