/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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
package rs117.hd.data.materials.groundMaterial;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import javax.inject.Inject;
import java.io.IOException;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class GroundMaterialManager {

	public static GroundMaterial[] GROUND_MATERIALS = new GroundMaterial[0];

	private FileWatcher.UnregisterCallback fileWatcher;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private Client client;

	private static final ResourcePath GROUNDMATERIALS_PATH = Props.getPathOrDefault(
		"rlhd.groundMaterials-path",
		() -> path(AreaManager.class, "groundMaterials.json")
	);

	public void startUp() {
		fileWatcher = GROUNDMATERIALS_PATH.watch((path, first) -> {
			try {
				GroundMaterial[] groundMaterials = path.loadJson(plugin.getGson(), GroundMaterial[].class);
				if (groundMaterials == null) {
					throw new IOException("Empty or invalid: " + path);
				}
				GROUND_MATERIALS = groundMaterials;
				if (!first) {
					clientThread.invoke(() -> {
						// Reload everything which depends on area definitions
						tileOverrideManager.shutDown();
						tileOverrideManager.startUp();


						// Force reload the scene to reapply area hiding
						if (client.getGameState() == GameState.LOGGED_IN) {
							client.setGameState(GameState.LOADING);
						}
					});
				}
			} catch (IOException ex) {
				log.error("Failed to load ground materials:", ex);
			}
		});
	}

	public GroundMaterial lookup(String name) {
		for (GroundMaterial groundMaterial : GROUND_MATERIALS) {
			if (groundMaterial.getName().equalsIgnoreCase(name)) {
				return groundMaterial;
			}
		}
		return GroundMaterial.NONE;
	}

	public void shutDown() {
		if (fileWatcher != null) {
			fileWatcher.unregister();
		}
		fileWatcher = null;
		GROUND_MATERIALS = new GroundMaterial[0];
	}

}