package rs117.hd.renderer.zone;

import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.opengl.uniforms.UBOWorldViews;

public class WorldViewContext {
	final int worldViewId;
	final int sizeX, sizeZ;
	ZoneSceneContext sceneContext;
	Zone[][] zones;
	VBO vboM;

	WorldViewContext(@Nullable WorldView worldView, @Nullable ZoneSceneContext sceneContext) {
		this.worldViewId = worldView == null ? -1 : worldView.getId();
		this.sceneContext = sceneContext;
		this.sizeX = worldView == null ? ZoneRenderer.NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? ZoneRenderer.NUM_ZONES : worldView.getSizeY() >> 3;
		zones = new Zone[sizeX][sizeZ];
		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = new Zone();
	}

	void updateWorldViewIndex(UBOWorldViews uboWorldViews) {
		if (vboM == null)
			return;
		vboM.map();
		vboM.vb.put(uboWorldViews.getIndex(worldViewId) + 1);
		vboM.unmap();
	}

	void free() {
		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = null;

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z].free();

		if (vboM != null)
			vboM.destroy();
		vboM = null;
	}

	void invalidate() {
		for (Zone[] column : zones)
			for (Zone zone : column)
				zone.invalidate = true;
	}
}
