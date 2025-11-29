package rs117.hd.renderer.legacy;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.util.HashMap;
import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.lights.TileObjectImpostorTracker;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static rs117.hd.utils.MathUtils.*;

public class LegacySceneContext extends SceneContext {
	public final int id = RAND.nextInt() & LegacySceneUploader.SCENE_ID_MASK;

	public final HashMap<TileObject, TileObjectImpostorTracker> trackedTileObjects = new HashMap<>();
	public final ListMultimap<Integer, TileObjectImpostorTracker> trackedVarps = ArrayListMultimap.create();
	public final ListMultimap<Integer, TileObjectImpostorTracker> trackedVarbits = ArrayListMultimap.create();

	public boolean forceDisableAreaHiding;

	public GpuIntBuffer staticUnorderedModelBuffer;
	public GpuIntBuffer stagingBufferVertices;
	public GpuFloatBuffer stagingBufferUvs;
	public GpuFloatBuffer stagingBufferNormals;

	public LegacySceneContext(
		Client client,
		Scene scene,
		int expandedMapLoadingChunks,
		@Nullable LegacySceneContext previous
	) {
		super(client, scene, expandedMapLoadingChunks);

		if (previous == null) {
			staticUnorderedModelBuffer = new GpuIntBuffer();
			stagingBufferVertices = new GpuIntBuffer();
			stagingBufferUvs = new GpuFloatBuffer();
			stagingBufferNormals = new GpuFloatBuffer();
		} else {
			// Because scene contexts are always swapped on the client thread, it is guaranteed to only be
			// in use by the client thread, meaning we can reuse all of its buffers if we are loading the
			// next scene also on the client thread
			if (client.isClientThread()) {
				// Avoid reallocating buffers whenever possible
				staticUnorderedModelBuffer = previous.staticUnorderedModelBuffer.clear();
				stagingBufferVertices = previous.stagingBufferVertices.clear();
				stagingBufferUvs = previous.stagingBufferUvs.clear();
				stagingBufferNormals = previous.stagingBufferNormals.clear();
				previous.staticUnorderedModelBuffer = null;
				previous.stagingBufferVertices = null;
				previous.stagingBufferUvs = null;
				previous.stagingBufferNormals = null;
			} else {
				staticUnorderedModelBuffer = new GpuIntBuffer(previous.staticUnorderedModelBuffer.capacity());
				stagingBufferVertices = new GpuIntBuffer(previous.stagingBufferVertices.capacity());
				stagingBufferUvs = new GpuFloatBuffer(previous.stagingBufferUvs.capacity());
				stagingBufferNormals = new GpuFloatBuffer(previous.stagingBufferNormals.capacity());
			}
		}
	}

	@Override
	public synchronized void destroy() {
		super.destroy();

		if (stagingBufferVertices != null)
			stagingBufferVertices.destroy();
		stagingBufferVertices = null;

		if (stagingBufferUvs != null)
			stagingBufferUvs.destroy();
		stagingBufferUvs = null;

		if (stagingBufferNormals != null)
			stagingBufferNormals.destroy();
		stagingBufferNormals = null;
	}

	public int getVertexOffset() {
		return stagingBufferVertices.position() / LegacyRenderer.VERTEX_SIZE;
	}

	public int getUvOffset() {
		return stagingBufferUvs.position() / LegacyRenderer.UV_SIZE;
	}
}
