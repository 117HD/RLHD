package rs117.hd.scene;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class GroundMaterialManager {
	private static final ResourcePath GROUND_MATERIALS_PATH = Props
		.getFile("rlhd.ground-materials-path", () -> path(AreaManager.class, "ground_materials.json"));

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	private FileWatcher.UnregisterCallback fileWatcher;

	public static GroundMaterial[] GROUND_MATERIALS = {};

	public void startUp() {
		fileWatcher = GROUND_MATERIALS_PATH.watch((path, first) -> clientThread.invoke(() -> {
			try {
				GroundMaterial[] groundMaterials = path.loadJson(plugin.getGson(), GroundMaterial[].class);
				if (groundMaterials == null)
					throw new IOException("Empty or invalid: " + path);

				for (var g : groundMaterials)
					g.normalize();


				var dirt1 = materialManager.getMaterial("DIRT_1");
				var dirt2 = materialManager.getMaterial("DIRT_2");
				GroundMaterial.DIRT = new GroundMaterial("DIRT", dirt1, dirt2);
				GroundMaterial.UNDERWATER_GENERIC = new GroundMaterial("UNDERWATER_GENERIC", dirt1, dirt2);

				var staticGroundMaterials = List.of(
					GroundMaterial.NONE,
					GroundMaterial.DIRT,
					GroundMaterial.UNDERWATER_GENERIC
				);
				GROUND_MATERIALS = Stream.concat(
					staticGroundMaterials.stream(),
					Arrays.stream(groundMaterials)
				).toArray(GroundMaterial[]::new);

				// Reload everything which depends on ground materials
				if (!first)
					tileOverrideManager.reload(true);
			} catch (IOException ex) {
				log.error("Failed to load ground materials:", ex);
			}
		}));
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		GROUND_MATERIALS = new GroundMaterial[0];
	}

	public void restart() {
		shutDown();
		startUp();
	}
}
