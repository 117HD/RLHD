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