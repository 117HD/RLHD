package rs117.hd.scene;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
import static net.runelite.api.Perspective.LOCAL_HALF_TILE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
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

	public Collection<LocalPoint> worldInstanceToLocals(WorldPoint worldPoint)
	{
		return instanceToWorld(worldPoint).stream()
			.map(this::worldToLocal)
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	/**
	 * Gets the local coordinate at the center of the passed tile.
	 *
	 * @param worldPoint the passed tile
	 * @return coordinate if the tile is in the current scene, otherwise null
	 */
	@Nullable
	public LocalPoint worldToLocal(WorldPoint worldPoint)
	{
		LocalPoint localPoint = new LocalPoint(
			(worldPoint.getX() - scene.getBaseX()) * LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE,
			(worldPoint.getY() - scene.getBaseY()) * LOCAL_TILE_SIZE + LOCAL_HALF_TILE_SIZE);

		if (!localPoint.isInScene())
		{
			return null;
		}

		return localPoint;
	}

	/**
	 * Get occurrences of a tile on the scene, accounting for instances. There may be
	 * more than one if the same template chunk occurs more than once on the scene.
	 * @param worldPoint
	 * @return
	 */
	public Collection<WorldPoint> instanceToWorld(WorldPoint worldPoint)
	{
		if (!scene.isInstance())
		{
			return Collections.singleton(worldPoint);
		}

		// find instance chunks using the template point. there might be more than one.
		List<WorldPoint> worldPoints = new ArrayList<>();
		for (int z = 0; z < instanceTemplateChunks.length; z++)
		{
			for (int x = 0; x < instanceTemplateChunks[z].length; ++x)
			{
				for (int y = 0; y < instanceTemplateChunks[z][x].length; ++y)
				{
					int chunkData = instanceTemplateChunks[z][x][y];
					int rotation = chunkData >> 1 & 0x3;
					int templateChunkY = (chunkData >> 3 & 0x7FF) * CHUNK_SIZE;
					int templateChunkX = (chunkData >> 14 & 0x3FF) * CHUNK_SIZE;
					int plane = chunkData >> 24 & 0x3;
					if (worldPoint.getX() >= templateChunkX && worldPoint.getX() < templateChunkX + CHUNK_SIZE
						&& worldPoint.getY() >= templateChunkY && worldPoint.getY() < templateChunkY + CHUNK_SIZE
						&& plane == worldPoint.getPlane())
					{
						WorldPoint p = new WorldPoint(
							scene.getBaseX() + x * CHUNK_SIZE + (worldPoint.getX() & (CHUNK_SIZE - 1)),
							scene.getBaseY() + y * CHUNK_SIZE + (worldPoint.getY() & (CHUNK_SIZE - 1)),
							z);
						p = rotate(p, rotation);
						worldPoints.add(p);
					}
				}
			}
		}
		return worldPoints;
	}

	/**
	 * Gets the coordinate of the tile that contains the passed local point,
	 * accounting for instances.
	 *
	 * @param localPoint the local coordinate
	 * @param plane the plane for the returned point, if it is not an instance
	 * @return the tile coordinate containing the local point
	 */
	public WorldPoint localToWorldInstance(LocalPoint localPoint, int plane)
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
