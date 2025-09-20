package rs117.hd.data;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StaticTileData {
	public final int index;
	public final int plane;
	public final int tileExX;
	public final int tileExY;

	public boolean isWater;

	public ModelBufferData modelBuffer;
	public ModelBufferData underwaterBuffer;
	public ModelBufferData paintBuffer;

	public final List<StaticRenderableInstance> renderables = new ArrayList<>();

	public void reset() {
		if (modelBuffer != null) {
			modelBuffer.reset();
		}

		if (underwaterBuffer != null) {
			underwaterBuffer.reset();
		}

		if (paintBuffer != null) {
			paintBuffer.reset();
		}

		for (StaticRenderableInstance instance : renderables) {
			instance.renderableBuffer.reset();
		}
	}

	public StaticRenderableInstance getStaticRenderableInstance(StaticRenderable renderable, int x, int y, int z, int orientation) {
		for (int i = 0; i < renderables.size(); i++) {
			final StaticRenderableInstance instance = renderables.get(i);
			if (instance.renderable == renderable &&
				instance.x == x &&
				instance.y == y &&
				instance.z == z &&
				instance.orientation == orientation) {
				return instance;
			}
		}
		return null;
	}

	public void merge(StaticTileData other) {
		for (StaticRenderableInstance instance : renderables) {
			if (!other.renderables.contains(instance)) {
				other.renderables.add(instance);
			}
		}

		for (StaticRenderableInstance instance : other.renderables) {
			if (!renderables.contains(instance)) {
				renderables.add(instance);
			}
		}
	}

	public boolean isEmpty() {
		return paintBuffer == null && modelBuffer == null && underwaterBuffer == null && renderables.isEmpty();
	}
}
