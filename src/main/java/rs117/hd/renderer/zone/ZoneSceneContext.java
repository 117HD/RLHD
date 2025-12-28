package rs117.hd.renderer.zone;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.scene.SceneContext;

import static net.runelite.api.Constants.*;

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

	public int countAnimatedDynamicObjectsInZone(int zx, int zz) {
		int count = 0;
		final Tile[][][] tiles = scene.getExtendedTiles();
		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < 8; ++x) {
				for (int y = 0; y < 8; ++y) {
					Tile t = tiles[z][(zx << 3) + x][(zz << 3) + y];
					if (t != null)
						count += countAnimatedObjects(t);
				}
			}
		}
		return count;
	}

	private int countAnimatedObjects(@Nonnull Tile t) {
		int count = 0;
		for (var gameObject : t.getGameObjects()) {
			if (gameObject == null)
				continue;
			var r = gameObject.getRenderable();
			if (r instanceof DynamicObject && ((DynamicObject) r).getAnimation() != null)
				count++;
		}
		if (t.getBridge() != null)
			count += countAnimatedObjects(t.getBridge());
		return count;
	}
}
