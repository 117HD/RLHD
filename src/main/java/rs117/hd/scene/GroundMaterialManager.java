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
	private static final ResourcePath GROUND_MATERIALS_PATH = Props.getPathOrDefault(
		"rlhd.ground-materials-path",
		() -> path(AreaManager.class, "ground_materials.json")
	);

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TileOverrideManager tileOverrideManager;

	private FileWatcher.UnregisterCallback fileWatcher;

	public static NewGroundMaterial[] GROUND_MATERIALS = new NewGroundMaterial[0];

	public void startUp() {
		fileWatcher = GROUND_MATERIALS_PATH.watch((path, first) -> {
			try {
				NewGroundMaterial[] groundMaterials = path.loadJson(plugin.getGson(), NewGroundMaterial[].class);
				if (groundMaterials == null)
					throw new IOException("Empty or invalid: " + path);

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

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		GROUND_MATERIALS = new NewGroundMaterial[0];
	}
}
