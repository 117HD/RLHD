package rs117.hd.renderer.zone;

import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.collections.Int2IntHashMap;
import rs117.hd.utils.collections.IntHashSet;

public class ZoneSceneContext extends SceneContext {
	public int totalReused;
	public int totalDeferred;
	public int totalMapZones;

	public final IntHashSet animatedDynamicObjectIds = new IntHashSet();
	public final Int2IntHashMap animatedDynamicObjectImpostors;

	public ZoneSceneContext(
		Client client,
		WorldView worldView,
		Scene scene,
		int expandedMapLoadingChunks,
		@Nullable SceneContext previous
	) {
		super(client, scene, expandedMapLoadingChunks);
		if (!worldView.isTopLevel()) {
			sceneOffset = 0;
			sizeX = worldView.getSizeX();
			sizeZ = worldView.getSizeY();
			animatedDynamicObjectImpostors = new Int2IntHashMap(0);
		} else {
			animatedDynamicObjectImpostors = new Int2IntHashMap();
		}
	}
}
