package rs117.hd.scene;

import java.io.IOException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
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
				GROUND_MATERIALS = new NewGroundMaterial[groundMaterials.length + 2];
				GROUND_MATERIALS[0] = NewGroundMaterial.NONE;
				GROUND_MATERIALS[1] = NewGroundMaterial.DIRT;
				System.arraycopy(groundMaterials, 0, GROUND_MATERIALS, 2, groundMaterials.length);
				if (!first) {
					clientThread.invoke(() -> {
						// Reload everything which depends on ground materials
						tileOverrideManager.shutDown();
						tileOverrideManager.startUp();
						plugin.reuploadScene();
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
