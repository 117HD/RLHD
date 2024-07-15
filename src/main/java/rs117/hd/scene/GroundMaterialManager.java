package rs117.hd.scene;

import java.io.IOException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.data.materials.NewGroundMaterial;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class GroundMaterialManager {

	public static NewGroundMaterial[] GROUND_MATERIALS = new NewGroundMaterial[0];

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
				NewGroundMaterial[] groundMaterials = path.loadJson(plugin.getGson(), NewGroundMaterial[].class);
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

	public NewGroundMaterial lookup(String name) {
		for (NewGroundMaterial groundMaterial : GROUND_MATERIALS) {
			if (groundMaterial.getName().equalsIgnoreCase(name)) {
				return groundMaterial;
			}
		}
		return NewGroundMaterial.NONE;
	}

	public void shutDown() {
		if (fileWatcher != null) {
			fileWatcher.unregister();
		}
		fileWatcher = null;
		GROUND_MATERIALS = new NewGroundMaterial[0];
	}

}
