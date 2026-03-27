package rs117.hd.renderer.zone;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.scene.SceneContext;

public class ZoneSceneContext extends SceneContext {
	public int totalReused;
	public int totalDeferred;
	public int totalMapZones;

	public final Set<Integer> animatedDynamicObjectIds = new HashSet<>();
	public final Map<Integer, Integer> animatedDynamicObjectImpostors;

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
			animatedDynamicObjectImpostors = Collections.emptyMap();
		} else {
			animatedDynamicObjectImpostors = new HashMap<>();
		}
	}
}
