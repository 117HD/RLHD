package rs117.hd.renderer.zone;

import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.opengl.uniforms.UBOWorldViews.WorldViewStruct;

import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;

public class WorldViewContext {
	final int worldViewId;
	final int sizeX, sizeZ;
	@Nullable
	WorldViewStruct uboWorldViewStruct;
	ZoneSceneContext sceneContext;
	Zone[][] zones;
	VBO vboM;
	boolean isLoading = true;

	WorldViewContext(@Nullable WorldView worldView, @Nullable ZoneSceneContext sceneContext, UBOWorldViews uboWorldViews) {
		this.worldViewId = worldView == null ? -1 : worldView.getId();
		this.sceneContext = sceneContext;
		this.sizeX = worldView == null ? ZoneRenderer.NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? ZoneRenderer.NUM_ZONES : worldView.getSizeY() >> 3;
		if (worldView != null)
			uboWorldViewStruct = uboWorldViews.acquire(worldView);
		zones = new Zone[sizeX][sizeZ];
		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = new Zone();
	}

	void initMetadata() {
		if (vboM != null || uboWorldViewStruct == null)
			return;

		vboM = new VBO(VAO.METADATA_SIZE);
		vboM.initialize(GL_STATIC_DRAW);
		vboM.map();
		vboM.vb.put(uboWorldViewStruct.worldViewIdx + 1);
		vboM.unmap();
	}

	void free() {
		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = null;

		if (uboWorldViewStruct != null)
			uboWorldViewStruct.free();
		uboWorldViewStruct = null;

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z].free();

		if (vboM != null)
			vboM.destroy();
		vboM = null;

		isLoading = true;
	}

	void invalidate() {
		for (Zone[] column : zones)
			for (Zone zone : column)
				zone.invalidate = true;
	}
}
