package rs117.hd.data;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import rs117.hd.scene.SceneContext;

import static rs117.hd.HdPlugin.MAX_FACE_COUNT;
import static rs117.hd.utils.MathUtils.*;

@RequiredArgsConstructor
public class StaticTileData {
	private final SceneContext owner;

	public final int plane;
	public final int tileExX;
	public final int tileExY;

	public int tileModel_VertexOffset;
	public int tileModel_UVOffset;
	public int tileModel_VertexCount;

	public int scenePaint_VertexOffset;
	public int scenePaint_UVOffset;
	public int scenePaint_VertexCount;

	// Transient
	public int tileModel_RenderBufferOffset;
	public int scenePaint_RenderBufferOffset;

	public StaticRenderable getStaticRenderable(int x, int y, int z, Model model) {
		for(int idx : renderables) {
			StaticRenderable existingRenderable = owner.staticRenderableData.get(idx);
			if (existingRenderable.x == x && existingRenderable.y == y && existingRenderable.z == z &&
				existingRenderable.vertexOffset == model.getBufferOffset() && existingRenderable.uvOffset == model.getUvBufferOffset() &&
				existingRenderable.sceneId == model.getSceneId()) {
				return existingRenderable;
			}
		}
		return null;
	}

	public void addStaticRenderable(int x, int y, int z, boolean hillskew, int orientation, Model model) {
		// Probably Quite Slow ... But ok for now
		for (int idx = 0; idx < owner.staticRenderableData.size(); idx++) {
			StaticRenderable existingRenderable = owner.staticRenderableData.get(idx);
			if (existingRenderable.x == x && existingRenderable.y == y && existingRenderable.z == z &&
				existingRenderable.vertexOffset == model.getBufferOffset() && existingRenderable.uvOffset == model.getUvBufferOffset() &&
				existingRenderable.sceneId == model.getSceneId()) {
				renderables.add(idx);
				return;
			}
		}

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

		renderables.add(owner.staticRenderableData.size());
		owner.staticRenderableData.add(newStaticRenderable);
	}

	public List<Integer> renderables = new ArrayList<>();

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

		// Transient
		public int renderBufferOffset;

		public boolean hillskew;
	}

	public boolean isEmpty() {
		return tileModel_VertexCount <= 0 && scenePaint_VertexCount <= 0 && renderables.isEmpty();
	}
}
