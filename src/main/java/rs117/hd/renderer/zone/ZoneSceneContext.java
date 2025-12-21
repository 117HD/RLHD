package rs117.hd.renderer.zone;

import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.scene.SceneContext;

public class ZoneSceneContext extends SceneContext {
	public int totalReused;
	public int totalDeferred;
	public int totalMapZones;

	public ZoneSceneContext(
		Client client,
		WorldView worldView,
		Scene scene,
		int expandedMapLoadingChunks,
		@Nullable SceneContext previous
	) {
		super(client, scene, expandedMapLoadingChunks);
		if (worldView.getId() != -1) {
			sceneOffset = 0;
			sizeX = worldView.getSizeX();
			sizeZ = worldView.getSizeY();
		}
	}
}
