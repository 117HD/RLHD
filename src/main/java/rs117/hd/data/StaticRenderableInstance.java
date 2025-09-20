package rs117.hd.data;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StaticRenderableInstance {
	public final StaticRenderable renderable;
	public final int x;
	public final int y;
	public final int z;
	public final int orientation;
	public final ModelBufferData renderableBuffer;
}
