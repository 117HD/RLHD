package rs117.hd.scene;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.TileObjectImpostorTracker;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.tile_overrides.TileOverrideVariables;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.UV_SIZE;
import static rs117.hd.HdPlugin.VERTEX_SIZE;
import static rs117.hd.utils.MathUtils.*;

public class SceneContext {
	public static final int SCENE_OFFSET = (EXTENDED_SCENE_SIZE - SCENE_SIZE) / 2;

	public final int id = RAND.nextInt() & SceneUploader.SCENE_ID_MASK;
	public final Client client;
	public final Scene scene;
	public final int expandedMapLoadingChunks;

	@Nullable
	public final int[] sceneBase;
	public final AABB sceneBounds;

	public boolean enableAreaHiding;
	public boolean forceDisableAreaHiding;
	public boolean fillGaps;
	public boolean isPrepared;

	@Nullable
	public Area currentArea;
	public Area[] possibleAreas = new Area[0];
	public final ArrayList<Environment> environments = new ArrayList<>();
	public byte[][] filledTiles = new byte[EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE];

	public int staticVertexCount = 0;
	public GpuIntBuffer staticUnorderedModelBuffer;
	public GpuIntBuffer stagingBufferVertices;
	public GpuFloatBuffer stagingBufferUvs;
	public GpuFloatBuffer stagingBufferNormals;

	public int staticGapFillerTilesOffset;
	public int staticGapFillerTilesVertexCount;
	public int staticCustomTilesOffset;
	public int staticCustomTilesVertexCount;

	// Statistics
	public int uniqueModels;

	// Terrain data
	public Map<Integer, Integer> vertexTerrainColor;
	public Map<Integer, Material> vertexTerrainTexture;
	public Map<Integer, float[]> vertexTerrainNormals;
	// Used for overriding potentially low quality vertex colors
	public HashMap<Integer, Boolean> highPriorityColor;

	// Water-related data
	public boolean[][][] tileIsWater;
	public Map<Integer, Boolean> vertexIsWater;
	public Map<Integer, Boolean> vertexIsLand;
	public Map<Integer, Boolean> vertexIsOverlay;
	public Map<Integer, Boolean> vertexIsUnderlay;
	public boolean[][][] skipTile;
	public Map<Integer, Integer> vertexUnderwaterDepth;
	public int[][][] underwaterDepthLevels;

	// Thread safe tile override variables
	public final TileOverrideVariables tileOverrideVars = new TileOverrideVariables();

	public int numVisibleLights = 0;
	public final ArrayList<Light> lights = new ArrayList<>();
	public final HashSet<Projectile> knownProjectiles = new HashSet<>();
	public final HashMap<TileObject, TileObjectImpostorTracker> trackedTileObjects = new HashMap<>();
	public final ListMultimap<Integer, TileObjectImpostorTracker> trackedVarps = ArrayListMultimap.create();
	public final ListMultimap<Integer, TileObjectImpostorTracker> trackedVarbits = ArrayListMultimap.create();

	// Model pusher arrays, to avoid simultaneous usage from different threads
	public final int[] modelFaceVertices = new int[12];
	public final float[] modelFaceNormals = new float[12];
	public final int[] modelPusherResults = new int[2];

	public SceneContext(Client client, Scene scene, int expandedMapLoadingChunks, boolean reuseBuffers, @Nullable SceneContext previous) {
		this.client = client;
		this.scene = scene;
		this.expandedMapLoadingChunks = expandedMapLoadingChunks;
		sceneBase = findSceneBase();
		sceneBounds = findSceneBounds(sceneBase);

		if (previous == null) {
			staticUnorderedModelBuffer = new GpuIntBuffer();
			stagingBufferVertices = new GpuIntBuffer();
			stagingBufferUvs = new GpuFloatBuffer();
			stagingBufferNormals = new GpuFloatBuffer();
		} else {
			// If area hiding was determined to be incorrect previously, keep it disabled
			forceDisableAreaHiding = previous.forceDisableAreaHiding;

			if (reuseBuffers) {
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

	public int getVertexOffset() {
		return stagingBufferVertices.position() / VERTEX_SIZE;
	}

	public int getUvOffset() {
		return stagingBufferUvs.position() / UV_SIZE;
	}

	/**
	 * Transform local coordinates into world coordinates.
	 * If the {@link LocalPoint} is not in the scene, this returns untranslated coordinates when in instances.
	 *
	 * @param localPoint to transform
	 * @param plane      which the local coordinate is on
	 * @return world coordinate
	 */
	public int[] localToWorld(LocalPoint localPoint, int plane) {
		return localToWorld(localPoint.getX(), localPoint.getY(), plane);
	}

	public int[] localToWorld(LocalPoint localPoint) {
		return localToWorld(localPoint, client.getPlane());
	}

	public int[] localToWorld(int localX, int localY) {
		return localToWorld(localX, localY, client.getPlane());
	}

	public int[] localToWorld(int localX, int localY, int plane) {
		return sceneToWorld(localX >> LOCAL_COORD_BITS, localY >> LOCAL_COORD_BITS, plane);
	}

	public int[] sceneToWorld(int sceneX, int sceneY, int plane) {
		if (sceneBase == null)
			return HDUtils.sceneToWorld(scene, sceneX, sceneY, plane);
		return ivec(
			sceneBase[0] + sceneX,
			sceneBase[1] + sceneY,
			sceneBase[2] + plane
		);
	}

	public int[] extendedSceneToWorld(int sceneExX, int sceneExY, int plane) {
		return sceneToWorld(sceneExX - SCENE_OFFSET, sceneExY - SCENE_OFFSET, plane);
	}

	public Stream<int[]> worldToLocals(WorldPoint worldPoint) {
		if (sceneBase != null)
			return Stream.of(worldToLocal(worldPoint));
		// If the scene is not contiguous, convert the world point to world points within the instance, then to local coords
		return WorldPoint.toLocalInstance(scene, worldPoint)
			.stream()
			.filter(Objects::nonNull)
			.map(instancePoint -> ivec(
				(instancePoint.getX() - scene.getBaseX()) * LOCAL_TILE_SIZE,
				(instancePoint.getY() - scene.getBaseY()) * LOCAL_TILE_SIZE,
				instancePoint.getPlane()
			));
	}

	/**
	 * Gets the local coordinate at the south-western corner of the tile, if the scene is contiguous, otherwise null
	 */
	@Nullable
	public int[] worldToLocal(WorldPoint worldPoint) {
		if (sceneBase == null)
			return null;
		return ivec(
			(worldPoint.getX() - sceneBase[0]) * LOCAL_TILE_SIZE,
			(worldPoint.getY() - sceneBase[1]) * LOCAL_TILE_SIZE,
			worldPoint.getPlane()
		);
	}

	public boolean intersects(Area area) {
		return intersects(area.aabbs);
	}

	public boolean intersects(AABB... aabbs) {
		return HDUtils.sceneIntersects(scene, expandedMapLoadingChunks, aabbs);
	}

	public AABB getNonInstancedSceneBounds() {
		return HDUtils.getNonInstancedSceneBounds(scene, expandedMapLoadingChunks);
	}

	public int getObjectConfig(Tile tile, long hash) {
		if (tile.getWallObject() != null && tile.getWallObject().getHash() == hash)
			return tile.getWallObject().getConfig();
		if (tile.getDecorativeObject() != null && tile.getDecorativeObject().getHash() == hash)
			return tile.getDecorativeObject().getConfig();
		if (tile.getGroundObject() != null && tile.getGroundObject().getHash() == hash)
			return tile.getGroundObject().getConfig();
		for (GameObject gameObject : tile.getGameObjects())
			if (gameObject != null && gameObject.getHash() == hash)
				return gameObject.getConfig();
		return -1;
	}

	/**
	 * Returns the south-west coordinate of the scene in world coordinates, after resolving instance template chunks
	 * to their original world coordinates. If the scene is instanced, this returns null when the chunks aren't contiguous.
	 */
	@Nullable
	private int[] findSceneBase() {
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();
		int basePlane = 0;

		if (scene.isInstance()) {
			boolean foundChunk = false;

			int[][][] chunks = scene.getInstanceTemplateChunks();
			for (int plane = 0; plane < chunks.length; plane++) {
				for (int x = 0; x < chunks[plane].length; x++) {
					for (int y = 0; y < chunks[plane][x].length; y++) {
						int chunk = chunks[plane][x][y];
						if (chunk == -1)
							continue; // Ignore unfilled chunks

						// Ensure the chunk isn't rotated (although we technically could handle consistent rotation)
						int rotation = chunk >> 1 & 0x3;
						if (rotation != 0)
							return null;

						int chunkX = chunk >> 14 & 0x3FF;
						int chunkY = chunk >> 3 & 0x7FF;
						int chunkPlane = chunk >> 24 & 0x3;

						if (foundChunk) {
							int expectedX = baseX + x;
							int expectedY = baseY + y;
							int expectedPlane = basePlane + plane;
							if (chunkX != expectedX || chunkY != expectedY || chunkPlane != expectedPlane)
								return null; // Not contiguous
						} else {
							// Calculate the expected unextended scene base chunk
							baseX = chunkX - x;
							baseY = chunkY - y;
							basePlane = chunkPlane - plane;
							foundChunk = true;
						}
					}
				}
			}

			if (!foundChunk)
				return null;

			// Transform chunk to world coordinates
			baseX <<= 3;
			baseY <<= 3;
		}

		return ivec(baseX, baseY, basePlane);
	}

	/**
	 * Works for non-instanced scenes & contiguous instanced scenes.
	 * Returns a best attempt for non-contiguous instanced scenes, which may be
	 * significantly larger than necessary, but will always include all tiles.
	 */
	private AABB findSceneBounds(@Nullable int[] sceneBase) {
		if (sceneBase != null) {
			int x = sceneBase[0] - SCENE_OFFSET;
			int y = sceneBase[1] - SCENE_OFFSET;
			return new AABB(x, y, x + EXTENDED_SCENE_SIZE - 1, y + EXTENDED_SCENE_SIZE - 1);
		}

		// Assume instances are assembled from approximately adjacent chunks on the map
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = MAX_Z;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = 0;

		int[][][] chunks = scene.getInstanceTemplateChunks();
		for (int[][] plane : chunks) {
			for (int[] column : plane) {
				for (int chunk : column) {
					if (chunk == -1)
						continue;

					// Extract chunk coordinates
					int x = chunk >> 14 & 0x3FF;
					int y = chunk >> 3 & 0x7FF;
					int z = chunk >> 24 & 0x3;
					minX = min(minX, x);
					minY = min(minY, y);
					minZ = min(minZ, z);
					maxX = max(maxX, x);
					maxY = max(maxY, y);
					maxZ = max(maxZ, z);
				}
			}
		}

		// Return an AABB representing no match, if there are no chunks
		if (maxX < minX)
			return new AABB(-1, -1);

		// Transform from chunk to world coordinates
		return new AABB(minX << 3, minY << 3, minZ, (maxX << 3) + CHUNK_SIZE - 1, (maxY << 3) + CHUNK_SIZE - 1, maxZ);
	}
}
