package rs117.hd.scene;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.data.environments.Environment;
import rs117.hd.data.materials.Material;
import rs117.hd.scene.lights.SceneLight;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.UV_SIZE;
import static rs117.hd.HdPlugin.VERTEX_SIZE;

public class SceneContext {
	public final int id = HDUtils.rand.nextInt();
	public final Scene scene;
	public final HashSet<Integer> regionIds;
	public final int expandedMapLoadingChunks;

	public final int[] cameraPosition = new int[3];
	public final int[] cameraFocalPoint = new int[2];
	public final int[] cameraShift = new int[2];
	public final int[] cameraOrientation = new int[2];

	public int staticVertexCount = 0;
	public GpuIntBuffer staticUnorderedModelBuffer;
	public GpuIntBuffer stagingBufferVertices;
	public GpuFloatBuffer stagingBufferUvs;
	public GpuFloatBuffer stagingBufferNormals;

	// statistics
	public int uniqueModels;

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

	public int visibleLightCount = 0;
	public final ArrayList<SceneLight> lights = new ArrayList<>();
	public final HashSet<Projectile> projectiles = new HashSet<>();

	public final ArrayList<Environment> environments = new ArrayList<>();

	// model pusher arrays, to avoid simultaneous usage from different threads
	public final int[] modelFaceVertices = new int[12];
	public final float[] modelFaceNormals = new float[12];
	public final int[] modelPusherResults = new int[2];

	public SceneContext(Scene scene, int expandedMapLoadingChunks, boolean reuseBuffers, @Nullable SceneContext previous) {
		this.scene = scene;
		this.regionIds = HDUtils.getSceneRegionIds(scene);
		this.expandedMapLoadingChunks = expandedMapLoadingChunks;

		if (previous == null) {
			staticUnorderedModelBuffer = new GpuIntBuffer();
			stagingBufferVertices = new GpuIntBuffer();
			stagingBufferUvs = new GpuFloatBuffer();
			stagingBufferNormals = new GpuFloatBuffer();
		} else if (reuseBuffers) {
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

	public synchronized void destroy() {
		if (staticUnorderedModelBuffer != null)
			staticUnorderedModelBuffer.destroy();
		staticUnorderedModelBuffer = null;

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
	 * Transform local coordinates into world coordinates.
	 * If the {@link LocalPoint} is not in the scene, this returns untranslated coordinates when in instances.
	 *
	 * @param localPoint to transform
	 * @param plane		 which the local coordinate is on
	 * @return world coordinate
	 */
	public WorldPoint localToWorld(LocalPoint localPoint, int plane)
	{
		int[] pos = HDUtils.localToWorld(scene, localPoint.getX(), localPoint.getY(), plane);
		return new WorldPoint(pos[0], pos[1], pos[2]);

	}

	public int[] localToWorld(int localX, int localY, int plane)
	{
		return HDUtils.localToWorld(scene, localX, localY, plane);
	}

	public int[] sceneToWorld(int sceneX, int sceneY, int plane)
	{
		return HDUtils.localToWorld(scene, sceneX * LOCAL_TILE_SIZE, sceneY * LOCAL_TILE_SIZE, plane);
	}

	public Collection<LocalPoint> worldInstanceToLocals(WorldPoint worldPoint)
	{
		return WorldPoint.toLocalInstance(scene, worldPoint)
			.stream()
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
		return new LocalPoint(
			(worldPoint.getX() - scene.getBaseX()) * LOCAL_TILE_SIZE,
			(worldPoint.getY() - scene.getBaseY()) * LOCAL_TILE_SIZE
		);
	}
}
