package rs117.hd.data;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.*;

import static rs117.hd.HdPlugin.MAX_FACE_COUNT;
import static rs117.hd.utils.MathUtils.*;

public class StaticTileData {
	public int tileModel_VertexOffset;
	public int tileModel_UVOffset;
	public int tileModel_VertexCount;

	public int scenePaint_VertexOffset;
	public int scenePaint_UVOffset;
	public int scenePaint_VertexCount;

	public void addStaticRenderable(int x, int y, int z, boolean hillskew, int orientation, Model model) {
		StaticRenderable newStaticRenderable = new StaticRenderable();

		newStaticRenderable.x = x;
		newStaticRenderable.y = y;
		newStaticRenderable.z = z;
		newStaticRenderable.hillskew = hillskew;
		newStaticRenderable.orientation = orientation;

		newStaticRenderable.bottomY = model.getBottomY();
		newStaticRenderable.radius = model.getRadius();
		newStaticRenderable.height = model.getModelHeight();
		newStaticRenderable.vertexOffset = model.getBufferOffset();
		newStaticRenderable.uvOffset = model.getUvBufferOffset();
		newStaticRenderable.vertexCount = model.getVerticesCount();
		newStaticRenderable.sceneId = model.getSceneId();
		newStaticRenderable.faceCount = min(MAX_FACE_COUNT, model.getFaceCount());

		renderables.add(newStaticRenderable);
	}

	public List<StaticRenderable> renderables = new ArrayList<>();

	public static class StaticRenderable {
		public int x;
		public int y;
		public int z;
		public int bottomY;
		public int radius;
		public int height;
		public int orientation;
		public int vertexOffset;
		public int uvOffset;
		public int vertexCount;
		public int faceCount;
		public int sceneId;

		public boolean hillskew;
	}

	public boolean isEmpty() {
		return tileModel_VertexCount <= 0 && scenePaint_VertexCount <= 0 && renderables.isEmpty();
	}
}
