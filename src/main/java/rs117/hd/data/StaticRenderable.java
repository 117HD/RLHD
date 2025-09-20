package rs117.hd.data;

import java.util.ArrayList;
import java.util.List;
import rs117.hd.scene.model_overrides.ModelOverride;

public class StaticRenderable {
	public int index;
	public int bottomY;
	public int radius;
	public int height;
	public int uuid;

	public int vertexOffset;
	public int uvOffset;
	public int faceCount;

	public ModelOverride modelOverride;

	// TODO: This should be condensed down into flags to reduce size
	public boolean hillskew;
	public boolean opaque;
	public boolean ignoreRoofRemoval;

	private final List<StaticRenderableInstance> instances = new ArrayList<>();

	public void appendInstanceToTile(StaticTileData staticTile, int x, int y, int z, int orientation) {
		StaticRenderableInstance tileInstance = null;

		for (StaticRenderableInstance instance : instances) {
			if (instance.x == x && instance.y == y && instance.z == z && instance.orientation == orientation) {
				tileInstance = instance;
				break;
			}
		}

		if (tileInstance == null) {
			ModelBufferData bufferData = new ModelBufferData(vertexOffset, uvOffset, faceCount * 3);
			tileInstance = new StaticRenderableInstance(this, x, y, z, orientation, bufferData);
			instances.add(tileInstance);
		}

		staticTile.renderables.add(tileInstance);
	}
}
