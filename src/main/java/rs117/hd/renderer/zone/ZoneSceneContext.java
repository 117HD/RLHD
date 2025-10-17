package rs117.hd.renderer.zone;

import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.scene.SceneContext;

public class ZoneSceneContext extends SceneContext {
//	public final WorldView worldView;
//	public final Zone[][] zones;
//	public final int numZonesX, numZonesY;

	public ZoneSceneContext(
		Client client,
		WorldView worldView,
		Scene scene,
		int expandedMapLoadingChunks,
		@Nullable SceneContext previous
	) {
		super(client, scene, expandedMapLoadingChunks, worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
//		this.worldView = worldView;
		if (worldView.getId() != -1)
			sceneOffset = 0;
//		numZonesX = worldView.getSizeX();
//		numZonesY = worldView.getSizeY();
//		zones = new Zone[numZonesX][numZonesY];
	}
}
