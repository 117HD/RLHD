package rs117.hd.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.Scene;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.data.environments.Environment;
import rs117.hd.data.materials.Material;
import rs117.hd.scene.lights.SceneLight;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Constants.CHUNK_SIZE;
import static rs117.hd.HdPlugin.UV_SIZE;
import static rs117.hd.HdPlugin.VERTEX_SIZE;

public class SceneContext
{
	public final int id = HDUtils.rand.nextInt();
	public final Scene scene;
	public final HashSet<Integer> regionIds;

	public GpuIntBuffer stagingBufferVertices;
	public GpuFloatBuffer stagingBufferUvs;
	public GpuFloatBuffer stagingBufferNormals;

	// terrain data
	public Map<Integer, Integer> vertexTerrainColor;
	public Map<Integer, Material> vertexTerrainTexture;
	public Map<Integer, float[]> vertexTerrainNormals;
	// used for overriding potentially low quality vertex colors
	public HashMap<Integer, Boolean> highPriorityColor;

	// water-related data
	public boolean[][][] tileIsWater;
	public Map<Integer, Boolean> vertexIsWater;
	public Map<Integer, Boolean> vertexIsLand;
	public Map<Integer, Boolean> vertexIsOverlay;
	public Map<Integer, Boolean> vertexIsUnderlay;
	public boolean[][][] skipTile;
	public Map<Integer, Integer> vertexUnderwaterDepth;
	public int[][][] underwaterDepthLevels;

	public final ArrayList<SceneLight> lights = new ArrayList<>();
	public int visibleLightCount = 0;

	public final ArrayList<Environment> environments = new ArrayList<>();

	public int[][][] instanceTemplateChunks = new int[4][13][13];

	// model pusher arrays, to avoid simultaneous usage from different threads
	public final int[] modelFaceVertices = new int[12];
	public final float[] modelFaceNormals = new float[12];
	public final int[] modelPusherResults = new int[2];

	public SceneContext(Scene scene, @Nullable SceneContext previousSceneContext)
	{
		this.scene = scene;
		this.regionIds = HDUtils.getSceneRegionIds(scene);

		// This works around an issue which will be fixed after 1.10.0.6
		int[][][] mutableTemplateChunks = scene.getInstanceTemplateChunks();
		for (int i = 0; i < 4; i++)
			for (int j = 0; j < 13; j++)
				for (int k = 0; k < 13; k++)
					instanceTemplateChunks[i][j][k] = mutableTemplateChunks[i][j][k];

		if (previousSceneContext == null)
		{
			stagingBufferVertices = new GpuIntBuffer();
			stagingBufferUvs = new GpuFloatBuffer();
			stagingBufferNormals = new GpuFloatBuffer();
		}
		else
		{
			stagingBufferVertices = new GpuIntBuffer(previousSceneContext.stagingBufferVertices.getBuffer().capacity());
			stagingBufferUvs = new GpuFloatBuffer(previousSceneContext.stagingBufferUvs.getBuffer().capacity());
			stagingBufferNormals = new GpuFloatBuffer(previousSceneContext.stagingBufferNormals.getBuffer().capacity());
		}
	}

	public void destroy()
	{
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

	public int getVertexOffset()
	{
		return stagingBufferVertices.position() / VERTEX_SIZE;
	}

	public int getUvOffset()
	{
		return stagingBufferUvs.position() / UV_SIZE;
	}

	/**
	 * Gets the coordinate of the tile that contains the passed local point,
	 * accounting for instances.
	 *
	 * @param localPoint the local coordinate
	 * @param plane the plane for the returned point, if it is not an instance
	 * @return the tile coordinate containing the local point
	 */
	public WorldPoint fromLocalInstance(LocalPoint localPoint, int plane)
	{
		if (scene.isInstance())
		{
			// get position in the scene
			int sceneX = localPoint.getSceneX();
			int sceneY = localPoint.getSceneY();

			// get chunk from scene
			int chunkX = sceneX / CHUNK_SIZE;
			int chunkY = sceneY / CHUNK_SIZE;

			// get the template chunk for the chunk
			int templateChunk = instanceTemplateChunks[plane][chunkX][chunkY];

			int rotation = templateChunk >> 1 & 0x3;
			int templateChunkY = (templateChunk >> 3 & 0x7FF) * CHUNK_SIZE;
			int templateChunkX = (templateChunk >> 14 & 0x3FF) * CHUNK_SIZE;
			int templateChunkPlane = templateChunk >> 24 & 0x3;

			// calculate world point of the template
			int x = templateChunkX + (sceneX & (CHUNK_SIZE - 1));
			int y = templateChunkY + (sceneY & (CHUNK_SIZE - 1));

			// create and rotate point back to 0, to match with template
			return rotate(new WorldPoint(x, y, templateChunkPlane), 4 - rotation);
		}
		else
		{
			return WorldPoint.fromLocal(scene, localPoint.getX(), localPoint.getY(), plane);
		}
	}

	/**
	 * Rotate the coordinates in the chunk according to chunk rotation
	 *
	 * @param point point
	 * @param rotation rotation
	 * @return world point
	 */
	private static WorldPoint rotate(WorldPoint point, int rotation)
	{
		int chunkX = point.getX() & ~(CHUNK_SIZE - 1);
		int chunkY = point.getY() & ~(CHUNK_SIZE - 1);
		int x = point.getX() & (CHUNK_SIZE - 1);
		int y = point.getY() & (CHUNK_SIZE - 1);
		switch (rotation)
		{
			case 1:
				return new WorldPoint(chunkX + y, chunkY + (CHUNK_SIZE - 1 - x), point.getPlane());
			case 2:
				return new WorldPoint(chunkX + (CHUNK_SIZE - 1 - x), chunkY + (CHUNK_SIZE - 1 - y), point.getPlane());
			case 3:
				return new WorldPoint(chunkX + (CHUNK_SIZE - 1 - y), chunkY + x, point.getPlane());
		}
		return point;
	}
}
