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

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UBOWaterTypes;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class WaterTypeManager {
	private static final ResourcePath WATER_TYPES_PATH = Props
		.getFile("rlhd.water-types-path", () -> path(WaterTypeManager.class, "water_types.json"));

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	public static WaterType[] WATER_TYPES = {};

	public UBOWaterTypes uboWaterTypes;

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = WATER_TYPES_PATH.watch((path, first) -> clientThread.invoke(() -> {
			try {
				var rawWaterTypes = path.loadJson(plugin.getGson(), WaterType[].class);
				if (rawWaterTypes == null)
					throw new IOException("Empty or invalid: " + path);
				log.debug("Loaded {} water types", rawWaterTypes.length);

				var waterTypes = new WaterType[rawWaterTypes.length + 1];
				waterTypes[0] = WaterType.NONE;
				System.arraycopy(rawWaterTypes, 0, waterTypes, 1, rawWaterTypes.length);

				Material fallbackNormalMap = materialManager.getMaterial("WATER_NORMAL_MAP_1");
				for (int i = 0; i < waterTypes.length; i++)
					waterTypes[i].normalize(i, fallbackNormalMap);

				var oldWaterTypes = WATER_TYPES;
				WATER_TYPES = waterTypes;
				// Update statically accessible water types
				WaterType.WATER = get("WATER");
				WaterType.WATER_FLAT = get("WATER_FLAT");
				WaterType.SWAMP_WATER_FLAT = get("SWAMP_WATER_FLAT");
				WaterType.ICE = get("ICE");

				if (uboWaterTypes != null)
					uboWaterTypes.destroy();
				uboWaterTypes = new UBOWaterTypes(waterTypes);

				if (first)
					return;

				fishingSpotReplacer.despawnRuneLiteObjects();
				fishingSpotReplacer.update();

				boolean indicesChanged = oldWaterTypes == null || oldWaterTypes.length != waterTypes.length;
				if (!indicesChanged) {
					for (int i = 0; i < waterTypes.length; i++) {
						if (!waterTypes[i].name.equals(oldWaterTypes[i].name)) {
							indicesChanged = true;
							break;
						}
					}
				}

				if (indicesChanged) {
					// Reload everything which depends on water type indices
					tileOverrideManager.shutDown();
					tileOverrideManager.startUp();
					plugin.reuploadScene();
				}
			} catch (IOException ex) {
				log.error("Failed to load water types:", ex);
			}
		}));
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;

		if (uboWaterTypes != null)
			uboWaterTypes.destroy();
		uboWaterTypes = null;

		WATER_TYPES = new WaterType[0];
	}

	public void restart() {
		shutDown();
		startUp();
	}

	public WaterType get(String name) {
		for (var type : WATER_TYPES)
			if (name.equals(type.name))
				return type;
		return WaterType.NONE;
	}
}
