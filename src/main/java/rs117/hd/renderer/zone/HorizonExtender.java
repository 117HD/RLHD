package rs117.hd.renderer.zone;

import java.util.HashMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import rs117.hd.HdPlugin;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static java.lang.Math.max;
import static net.runelite.api.Constants.CHUNK_SIZE;
import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.scene.SceneContext.TILE_WATER_FLAG;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.HDUtils.UNDERWATER_HSL;
import static rs117.hd.utils.HDUtils.tileVertexHash;
import static rs117.hd.utils.MathUtils.fract;

@Slf4j
@Singleton
public class HorizonExtender {
	private static final int EDGE_INSET = 4;
	private static final int GPU_EXTRA_CHUNKS = 4;
	private static final int[][] CORNER_TILES = {
		{ 0, 0, 0 },
		{ -1, 0, 1 },
		{ 0, -1, 2 },
		{ -1, -1, 3 },
	};
	private static final ThreadLocal<UploadScratch> UPLOAD_SCRATCH = ThreadLocal.withInitial(UploadScratch::new);

	@Inject
	private HdPlugin plugin;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	private enum ZoneUploadMode {
		WATER_SURFACE,
		WATER_UNDERWATER,
		TERRAIN
	}

	private static final class UploadScratch {
		final VertexWriteCache.Collection writeCache = new VertexWriteCache.Collection();
		final int[][] cornerHeights = new int[CHUNK_SIZE + 1][CHUNK_SIZE + 1];
		final int[] clipBounds = new int[4];

		void begin(GpuIntBuffer vb, GpuIntBuffer fb) {
			writeCache.setOutputBuffers(vb, vb, fb);
		}

		void end() {
			writeCache.release();
		}
	}

	@FunctionalInterface
	private interface ShellStripFn {
		void apply(int minLocalX, int minLocalZ, int maxLocalX, int maxLocalZ);
	}

	public static final class Sample {
		public final int plane;
		public final int flatHeight;
		public final WaterType waterType;
		public final HashMap<Long, Integer> boundarySurfaceHeights;
		public final int flatUnderwaterHeight;
		@Nullable
		public final HashMap<Long, Integer> boundaryUnderwaterHeights;
		public final Material material;
		public final GroundMaterial groundMaterial;
		public final int swColor;
		public final int seColor;
		public final int neColor;
		public final int nwColor;

		private Sample(
			int plane,
			int flatHeight,
			WaterType waterType,
			HashMap<Long, Integer> boundarySurfaceHeights,
			int flatUnderwaterHeight,
			@Nullable HashMap<Long, Integer> boundaryUnderwaterHeights,
			Material material,
			GroundMaterial groundMaterial,
			int swColor,
			int seColor,
			int neColor,
			int nwColor
		) {
			this.plane = plane;
			this.flatHeight = flatHeight;
			this.waterType = waterType;
			this.boundarySurfaceHeights = boundarySurfaceHeights;
			this.flatUnderwaterHeight = flatUnderwaterHeight;
			this.boundaryUnderwaterHeights = boundaryUnderwaterHeights;
			this.material = material;
			this.groundMaterial = groundMaterial;
			this.swColor = swColor;
			this.seColor = seColor;
			this.neColor = neColor;
			this.nwColor = nwColor;
		}

		public static Sample water(
			WaterType waterType,
			int plane,
			int flatHeight,
			int flatUnderwaterHeight,
			HashMap<Long, Integer> boundarySurfaceHeights,
			HashMap<Long, Integer> boundaryUnderwaterHeights
		) {
			return new Sample(
				plane, flatHeight, waterType, boundarySurfaceHeights, flatUnderwaterHeight,
				boundaryUnderwaterHeights, Material.NONE, null, 127, 127, 127, 127
			);
		}

		public static Sample terrain(
			int plane,
			int flatHeight,
			HashMap<Long, Integer> boundarySurfaceHeights,
			WaterType waterType,
			Material material,
			GroundMaterial groundMaterial,
			int swColor,
			int seColor,
			int neColor,
			int nwColor
		) {
			return new Sample(
				plane, flatHeight, waterType, boundarySurfaceHeights, flatHeight, null,
				material, groundMaterial, swColor, seColor, neColor, nwColor
			);
		}

		public boolean isWaterSurface() {
			return waterType != WaterType.NONE;
		}

		public static long packWorldVertex(int worldX, int worldY) {
			return ((long) worldX << 32) | (worldY & 0xffffffffL);
		}
	}

	private static final class ZonePlan {
		public long tileMask;
		public int tileCount;
		public boolean fullZone;

		public boolean hasTile(int xoff, int zoff) {
			return (tileMask & (1L << (xoff * CHUNK_SIZE + zoff))) != 0;
		}
	}

	private static final class ShellEdges {
		public final int extra;
		public final int extent;
		public final boolean west;
		public final boolean east;
		public final boolean north;
		public final boolean south;

		ShellEdges(ZoneSceneContext ctx, int mzx, int mzz) {
			int zoneMinSceneX = (mzx << 3) - ctx.sceneOffset;
			int zoneMaxSceneX = zoneMinSceneX + CHUNK_SIZE - 1;
			int zoneMinSceneY = (mzz << 3) - ctx.sceneOffset;
			int zoneMaxSceneY = zoneMinSceneY + CHUNK_SIZE - 1;
			int mapMin = loadedSceneMin(ctx);
			int mapMax = loadedSceneMax(ctx);

			extra = gpuExtraLocalExtent(ctx);
			extent = CHUNK_SIZE * LOCAL_TILE_SIZE;
			west = zoneMinSceneX == mapMin;
			east = zoneMaxSceneX == mapMax - 1;
			north = zoneMinSceneY == mapMin;
			south = zoneMaxSceneY == mapMax - 1;
		}

		public boolean any() {
			return west || east || north || south;
		}
	}

	private static final class Reference {
		public final int worldX;
		public final int worldY;
		public final int plane;
		public final int flatHeight;
		public final int textureId;
		public TileOverride override;
		public WaterType waterType;
		public Material material = Material.NONE;
		public GroundMaterial groundMaterial;
		public int swColor = 127;
		public int seColor = 127;
		public int neColor = 127;
		public int nwColor = 127;

		private Reference(int worldX, int worldY, int plane, int flatHeight, int textureId) {
			this.worldX = worldX;
			this.worldY = worldY;
			this.plane = plane;
			this.flatHeight = flatHeight;
			this.textureId = textureId;
		}
	}

	public static boolean isEnabled(SceneContext ctx) {
		if (!ctx.enableHorizonTiles)
			return false;
		Area area = getArea(ctx);
		return ctx.sceneBase != null && area != null && area.hasHorizonTiles();
	}

	public static boolean shouldPatchWaterAtTile(SceneContext ctx, int tileExX, int tileExY, int plane) {
		Area area = getArea(ctx);
		if (area != null && area.horizonFlatTerrain)
			return false;
		if (!isEnabled(ctx) || plane != getPlane(ctx))
			return false;
		if (tileExX < 0 || tileExY < 0 || tileExX >= ctx.sizeX || tileExY >= ctx.sizeZ)
			return false;

		boolean[][] mask = ctx.horizonTileMask;
		if (mask != null)
			return mask[tileExX][tileExY];

		Tile tile = ctx.scene.getExtendedTiles()[plane][tileExX][tileExY];
		return isWithinLoadedRange(ctx, tileExX, tileExY) &&
			isOutsideAreaBounds(ctx, tileExX, tileExY, plane) &&
			isEligiblePatchTile(tile);
	}

	private static boolean isFlatTerrain(SceneContext ctx) {
		Area area = getArea(ctx);
		return area != null && area.horizonFlatTerrain;
	}

	@Nullable
	private static Area getArea(SceneContext ctx) {
		if (!ctx.enableHorizonTiles)
			return null;
		if (ctx.horizonTileArea != null)
			return ctx.horizonTileArea;
		if (ctx.currentArea != null && ctx.currentArea.hasHorizonTiles())
			return ctx.currentArea;
		return null;
	}

	private static int getPlane(SceneContext ctx) {
		Area area = getArea(ctx);
		if (area == null || area.horizonTileReference == null)
			return 0;
		int[] ref = area.horizonTileReference;
		return ref.length > 2 ? ref[2] : 0;
	}

	private static int loadedSceneMin(SceneContext ctx) {
		return -ctx.expandedMapLoadingChunks * CHUNK_SIZE;
	}

	private static int loadedSceneMax(SceneContext ctx) {
		return SCENE_SIZE + ctx.expandedMapLoadingChunks * CHUNK_SIZE;
	}

	private static int gpuSceneMin(SceneContext ctx) {
		return loadedSceneMin(ctx) - GPU_EXTRA_CHUNKS * CHUNK_SIZE;
	}

	private static int gpuSceneMax(SceneContext ctx) {
		return loadedSceneMax(ctx) + GPU_EXTRA_CHUNKS * CHUNK_SIZE;
	}

	private static int gpuExtraLocalExtent(SceneContext ctx) {
		return GPU_EXTRA_CHUNKS * CHUNK_SIZE * LOCAL_TILE_SIZE;
	}

	private static boolean isWithinLoadedRange(SceneContext ctx, int tileExX, int tileExY) {
		if (ctx.sceneBase == null)
			return true;
		int tileX = tileExX - ctx.sceneOffset;
		int tileY = tileExY - ctx.sceneOffset;
		int min = loadedSceneMin(ctx);
		int max = loadedSceneMax(ctx);
		return tileX > min && tileY > min && tileX < max && tileY < max;
	}

	private static boolean isWithinGpuRange(SceneContext ctx, int tileExX, int tileExY) {
		if (ctx.sceneBase == null)
			return true;
		int tileX = tileExX - ctx.sceneOffset;
		int tileY = tileExY - ctx.sceneOffset;
		int min = gpuSceneMin(ctx);
		int max = gpuSceneMax(ctx);
		return tileX >= min && tileY >= min && tileX < max && tileY < max;
	}

	private static boolean needsGpuShell(SceneContext ctx) {
		if (ctx.sceneBase == null)
			return false;
		int gpuMinEx = gpuSceneMin(ctx) + ctx.sceneOffset;
		int gpuMaxEx = gpuSceneMax(ctx) + ctx.sceneOffset;
		return gpuMinEx < 0 || gpuMaxEx > EXTENDED_SCENE_SIZE;
	}

	private static boolean isHiddenGroundTile(Tile tile) {
		SceneTilePaint paint = tile.getSceneTilePaint();
		SceneTileModel model = tile.getSceneTileModel();

		if (model == null) {
			Tile bridge = tile.getBridge();
			if (bridge != null) {
				if (bridge.getSceneTileModel() != null) {
					model = bridge.getSceneTileModel();
					paint = null;
				} else {
					paint = bridge.getSceneTilePaint();
				}
			}
		}

		if (model != null) {
			for (int color : model.getTriangleColorA()) {
				if (color != HIDDEN_HSL)
					return false;
			}
			return true;
		}

		return paint == null || paint.getNeColor() == HIDDEN_HSL;
	}

	private static boolean isEligiblePatchTile(@Nullable Tile tile) {
		return tile == null || isHiddenGroundTile(tile);
	}

	private static boolean isOutsideAreaBounds(SceneContext ctx, int tileExX, int tileExY, int plane) {
		Area area = getArea(ctx);
		if (area == null)
			return true;
		int[] worldPos = ctx.extendedSceneToWorld(tileExX, tileExY, plane);
		return !area.containsPoint(false, worldPos[0], worldPos[1], worldPos[2]);
	}

	private static void buildPatchMask(SceneContext ctx, Tile[][][] tiles) {
		if (!isEnabled(ctx)) {
			ctx.horizonTileMask = null;
			return;
		}

		int plane = getPlane(ctx);
		boolean[][] mask = new boolean[ctx.sizeX][ctx.sizeZ];
		Tile[][] planeTiles = tiles[plane];
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int y = 0; y < ctx.sizeZ; ++y) {
				mask[x][y] = isWithinLoadedRange(ctx, x, y) &&
					isOutsideAreaBounds(ctx, x, y, plane) &&
					isEligiblePatchTile(planeTiles[x][y]);
			}
		}
		ctx.horizonTileMask = mask;
	}

	private static boolean shouldRenderAtTile(SceneContext ctx, int tileExX, int tileExY, int plane) {
		if (!isEnabled(ctx) || plane != getPlane(ctx))
			return false;
		if (!isWithinGpuRange(ctx, tileExX, tileExY))
			return false;
		return isOutsideAreaBounds(ctx, tileExX, tileExY, plane);
	}

	private static boolean shouldExtendFlatTerrainTile(
		ZoneSceneContext ctx,
		int tileExX,
		int tileExY,
		int plane
	) {
		if (!isEnabled(ctx) || plane != getPlane(ctx))
			return false;

		if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE) {
			if (!isOutsideAreaBounds(ctx, tileExX, tileExY, plane))
				return false;
			return isWithinGpuRange(ctx, tileExX, tileExY);
		}

		if (isOutsideAreaBounds(ctx, tileExX, tileExY, plane))
			return true;

		Tile tile = ctx.scene.getExtendedTiles()[plane][tileExX][tileExY];
		return tile == null || isHiddenGroundTile(tile);
	}

	private static boolean clipLocal(
		ZoneSceneContext ctx,
		Area area,
		int mzx,
		int mzz,
		int plane,
		int minLocalX,
		int minLocalZ,
		int maxLocalX,
		int maxLocalZ,
		int[] out
	) {
		if (ctx.sceneBase == null)
			return false;

		int zoneBaseTileX = (mzx << 3) - ctx.sceneOffset;
		int zoneBaseTileY = (mzz << 3) - ctx.sceneOffset;
		int zoneMinWx = ctx.sceneBase[0] + zoneBaseTileX;
		int zoneMaxWx = ctx.sceneBase[0] + zoneBaseTileX + CHUNK_SIZE - 1;
		int zoneMinWy = ctx.sceneBase[1] + zoneBaseTileY;
		int zoneMaxWy = ctx.sceneBase[1] + zoneBaseTileY + CHUNK_SIZE - 1;

		long clipMinX = minLocalX;
		long clipMaxX = maxLocalX;
		long clipMinZ = minLocalZ;
		long clipMaxZ = maxLocalZ;

		area.normalize();
		for (AABB aabb : area.aabbs) {
			if (aabb.minZ != Integer.MIN_VALUE && aabb.maxZ != Integer.MAX_VALUE &&
				(plane < aabb.minZ || plane > aabb.maxZ))
				continue;

			if (zoneMaxWx < aabb.minX) {
				clipMaxX = Math.min(clipMaxX, (long) (aabb.minX - zoneMinWx) * LOCAL_TILE_SIZE);
			} else if (zoneMinWx > aabb.maxX) {
				clipMinX = Math.max(clipMinX, (long) (aabb.maxX + 1 - zoneMinWx) * LOCAL_TILE_SIZE);
			}

			if (zoneMaxWy < aabb.minY) {
				clipMaxZ = Math.min(clipMaxZ, (long) (aabb.minY - zoneMinWy) * LOCAL_TILE_SIZE);
			} else if (zoneMinWy > aabb.maxY) {
				clipMinZ = Math.max(clipMinZ, (long) (aabb.maxY + 1 - zoneMinWy) * LOCAL_TILE_SIZE);
			}
		}

		if (clipMinX >= clipMaxX || clipMinZ >= clipMaxZ)
			return false;

		out[0] = (int) clipMinX;
		out[1] = (int) clipMinZ;
		out[2] = (int) clipMaxX;
		out[3] = (int) clipMaxZ;
		return true;
	}

	private static int countTiles(int minX, int minZ, int maxX, int maxZ) {
		int tiles = 0;
		int startTileX = floorDiv(minX, LOCAL_TILE_SIZE);
		int startTileZ = floorDiv(minZ, LOCAL_TILE_SIZE);
		int endTileX = floorDiv(maxX - 1, LOCAL_TILE_SIZE);
		int endTileZ = floorDiv(maxZ - 1, LOCAL_TILE_SIZE);

		for (int tx = startTileX; tx <= endTileX; ++tx) {
			for (int tz = startTileZ; tz <= endTileZ; ++tz) {
				int baseX = tx * LOCAL_TILE_SIZE;
				int baseZ = tz * LOCAL_TILE_SIZE;
				if (baseX + LOCAL_TILE_SIZE <= minX || baseZ + LOCAL_TILE_SIZE <= minZ ||
					baseX >= maxX || baseZ >= maxZ)
					continue;
				tiles++;
			}
		}
		return tiles;
	}

	private static int floorDiv(int a, int b) {
		if (a >= 0)
			return a / b;
		return (a - b + 1) / b;
	}

	private static AABB nearestAabb(Area area, int worldX, int worldY, int plane) {
		area.normalize();
		AABB nearest = null;
		int nearestDist = Integer.MAX_VALUE;

		for (AABB aabb : area.aabbs) {
			if (aabb.minZ != Integer.MIN_VALUE && aabb.maxZ != Integer.MAX_VALUE &&
				(plane < aabb.minZ || plane > aabb.maxZ))
				continue;

			int dx = 0;
			if (worldX < aabb.minX)
				dx = aabb.minX - worldX;
			else if (worldX > aabb.maxX)
				dx = worldX - aabb.maxX;

			int dy = 0;
			if (worldY < aabb.minY)
				dy = aabb.minY - worldY;
			else if (worldY > aabb.maxY)
				dy = worldY - aabb.maxY;

			int dist = dx + dy;
			if (nearest == null || dist < nearestDist) {
				nearest = aabb;
				nearestDist = dist;
			}
		}
		return nearest;
	}

	private static int clampVertexX(AABB aabb, int worldX) {
		return Math.min(Math.max(worldX, aabb.minX), aabb.maxX + 1);
	}

	private static int clampVertexY(AABB aabb, int worldY) {
		return Math.min(Math.max(worldY, aabb.minY), aabb.maxY + 1);
	}

	private static void putFlatQuad(
		VertexWriteCache.Collection writeCache,
		WaterType waterType,
		int plane,
		int minX,
		int minZ,
		int maxX,
		int maxZ,
		int height,
		int color,
		int depth
	) {
		int terrainData = HDUtils.packTerrainData(true, depth, waterType, plane);
		int packedMaterial = Material.NONE.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);

		putTriangle(
			writeCache,
			color, color, color,
			packedMaterial, packedMaterial, packedMaterial,
			terrainData, terrainData, terrainData,
			maxX, height, maxZ, 0, 0,
			minX, height, maxZ, 0, 0,
			maxX, height, minZ, 0, 0
		);
		putTriangle(
			writeCache,
			color, color, color,
			packedMaterial, packedMaterial, packedMaterial,
			terrainData, terrainData, terrainData,
			minX, height, minZ, 0, 0,
			maxX, height, minZ, 0, 0,
			minX, height, maxZ, 0, 0
		);
	}

	private static void putTriangle(
		VertexWriteCache.Collection writeCache,
		int colorA,
		int colorB,
		int colorC,
		int packedMaterialA,
		int packedMaterialB,
		int packedMaterialC,
		int terrainDataA,
		int terrainDataB,
		int terrainDataC,
		int x0, int y0, int z0, float u0, float v0,
		int x1, int y1, int z1, float u1, float v1,
		int x2, int y2, int z2, float u2, float v2
	) {
		var vb = writeCache.getVertexBuffer();
		var tb = writeCache.getTextureBuffer();
		int faceIdx = tb.putFace(
			colorA, colorB, colorC,
			packedMaterialA, packedMaterialB, packedMaterialC,
			terrainDataA, terrainDataB, terrainDataC
		);
		vb.putStaticVertex(x0, y0, z0, u0, v0, 0, 0, 0, -1, faceIdx);
		vb.putStaticVertex(x1, y1, z1, u1, v1, 0, 0, 0, -1, faceIdx);
		vb.putStaticVertex(x2, y2, z2, u2, v2, 0, 0, 0, -1, faceIdx);
	}

	private static void forEachShellStrip(@Nullable ShellEdges shell, ShellStripFn fn) {
		if (shell == null)
			return;

		int x = shell.extra;
		int z = shell.extent;
		if (shell.west)
			fn.apply(-x, 0, 0, z);
		if (shell.east)
			fn.apply(z, 0, z + x, z);
		if (shell.north)
			fn.apply(0, -x, z, 0);
		if (shell.south)
			fn.apply(0, z, z, z + x);
		if (shell.west && shell.north)
			fn.apply(-x, -x, 0, 0);
		if (shell.east && shell.north)
			fn.apply(z, -x, z + x, 0);
		if (shell.west && shell.south)
			fn.apply(-x, z, 0, z + x);
		if (shell.east && shell.south)
			fn.apply(z, z, z + x, z + x);
	}

	private Reference resolveReference(ZoneSceneContext ctx, Area area) {
		int[] ref = area.horizonTileReference;
		int worldX = ref[0];
		int worldY = ref[1];
		int plane = ref.length > 2 ? ref[2] : 0;

		int textureId = -1;
		int flatHeight = 0;
		TileOverride override = TileOverride.NONE;
		int swColor = 127;
		int seColor = 127;
		int neColor = 127;
		int nwColor = 127;

		if (ctx.sceneBase != null) {
			int sceneX = worldX - ctx.sceneBase[0];
			int sceneY = worldY - ctx.sceneBase[1];
			int tileExX = sceneX + ctx.sceneOffset;
			int tileExY = sceneY + ctx.sceneOffset;

			if (tileExX >= 0 && tileExY >= 0 && tileExX < EXTENDED_SCENE_SIZE && tileExY < EXTENDED_SCENE_SIZE) {
				Tile[][][] extendedTiles = ctx.scene.getExtendedTiles();
				int[][][] tileHeights = ctx.scene.getTileHeights();
				Tile tile = extendedTiles[plane][tileExX][tileExY];
				if (tile != null) {
					int[] worldPos = ctx.sceneToWorld(sceneX, sceneY, plane);
					override = tileOverrideManager.getOverride(ctx, tile, worldPos);
					SceneTilePaint paint = tile.getSceneTilePaint();
					if (paint != null) {
						textureId = paint.getTexture();
						flatHeight = averageHeight(tileHeights, plane, tileExX, tileExY);
						swColor = paint.getSwColor();
						seColor = paint.getSeColor();
						neColor = paint.getNeColor();
						nwColor = paint.getNwColor();
					} else {
						SceneTileModel model = tile.getSceneTileModel();
						if (model != null && model.getTriangleTextureId() != null) {
							for (int faceTexture : model.getTriangleTextureId()) {
								if (faceTexture != -1) {
									textureId = faceTexture;
									break;
								}
							}
							for (int c : model.getTriangleColorA()) {
								if (c != HIDDEN_HSL) {
									swColor = seColor = neColor = nwColor = c;
									break;
								}
							}
						}
						flatHeight = averageHeight(tileHeights, plane, tileExX, tileExY);
					}
				}
			}
		}

		Reference result = new Reference(worldX, worldY, plane, flatHeight, textureId);
		result.override = override;
		result.waterType = proceduralGenerator.seasonalWaterType(override, textureId);
		result.swColor = swColor;
		result.seColor = seColor;
		result.neColor = neColor;
		result.nwColor = nwColor;

		if (override == TileOverride.NONE) {
			int[] worldPos = { worldX, worldY, plane };
			override = tileOverrideManager.getOverrideBeforeReplacements(worldPos);
			result.override = override;
			if (result.waterType == WaterType.NONE)
				result.waterType = proceduralGenerator.seasonalWaterType(override, textureId);
		}

		if (result.waterType == WaterType.NONE) {
			if (textureId != -1) {
				result.material = materialManager.fromVanillaTexture(textureId);
				if (result.material.isFallbackVanillaMaterial)
					override = TileOverride.NONE;
			}

			if (override != TileOverride.NONE) {
				result.groundMaterial = override.groundMaterial;
				result.swColor = override.modifyColor(result.swColor);
				result.seColor = override.modifyColor(result.seColor);
				result.neColor = override.modifyColor(result.neColor);
				result.nwColor = override.modifyColor(result.nwColor);
			} else if (textureId == -1) {
				result.groundMaterial = override.groundMaterial;
			}
		}

		return result;
	}

	private static int averageHeight(int[][][] tileHeights, int plane, int tileExX, int tileExY) {
		int sw = tileHeights[plane][tileExX][tileExY];
		int se = tileHeights[plane][tileExX + 1][tileExY];
		int ne = tileHeights[plane][tileExX + 1][tileExY + 1];
		int nw = tileHeights[plane][tileExX][tileExY + 1];
		return (sw + se + ne + nw) / 4;
	}

	@Nullable
	private static ShellEdges shellEdges(ZoneSceneContext ctx, int mzx, int mzz) {
		if (!needsGpuShell(ctx))
			return null;
		return new ShellEdges(ctx, mzx, mzz);
	}

	private static int countShellQuads(ShellEdges shell) {
		if (shell == null || !shell.any())
			return 0;

		int[] quads = { 0 };
		forEachShellStrip(shell, (minX, minZ, maxX, maxZ) -> quads[0]++);
		return quads[0];
	}

	private static int countShellStripTiles(
		ZoneSceneContext ctx,
		Area area,
		int mzx,
		int mzz,
		int plane,
		int minLocalX,
		int minLocalZ,
		int maxLocalX,
		int maxLocalZ,
		int[] clipBounds
	) {
		if (!clipLocal(ctx, area, mzx, mzz, plane, minLocalX, minLocalZ, maxLocalX, maxLocalZ, clipBounds))
			return 0;
		return countTiles(clipBounds[0], clipBounds[1], clipBounds[2], clipBounds[3]);
	}

	private ZonePlan planZone(ZoneSceneContext ctx, int mzx, int mzz, Sample sample) {
		ZonePlan plan = new ZonePlan();
		boolean flatTerrain = isFlatTerrain(ctx);
		for (int xoff = 0; xoff < CHUNK_SIZE; ++xoff) {
			for (int zoff = 0; zoff < CHUNK_SIZE; ++zoff) {
				int tileExX = (mzx << 3) + xoff;
				int tileExY = (mzz << 3) + zoff;
				boolean extend = flatTerrain ?
					shouldExtendFlatTerrainTile(ctx, tileExX, tileExY, sample.plane) :
					shouldExtendTile(ctx, sample, tileExX, tileExY) > 0;
				if (extend) {
					plan.tileMask |= 1L << (xoff * CHUNK_SIZE + zoff);
					plan.tileCount++;
				}
			}
		}
		plan.fullZone = plan.tileCount == CHUNK_SIZE * CHUNK_SIZE;
		return plan;
	}

	private static boolean isUniformHeightGrid(int[][] heights) {
		int h = heights[0][0];
		for (int x = 0; x <= CHUNK_SIZE; ++x) {
			for (int z = 0; z <= CHUNK_SIZE; ++z) {
				if (heights[x][z] != h)
					return false;
			}
		}
		return true;
	}

	private static int resolveCornerSurfaceHeight(
		ZoneSceneContext ctx,
		Sample sample,
		AABB aabb,
		int worldX,
		int worldY
	) {
		int boundaryX = clampVertexX(aabb, worldX);
		int boundaryY = clampVertexY(aabb, worldY);

		Integer live = liveSurfaceHeightAtWorldVertex(ctx, sample.plane, boundaryX, boundaryY);
		if (live != null)
			return live;

		long key = Sample.packWorldVertex(boundaryX, boundaryY);
		Integer height = sample.boundarySurfaceHeights.get(key);
		return height != null ? height : sample.flatHeight;
	}

	@Nullable
	private static Integer liveSurfaceHeightAtWorldVertex(
		ZoneSceneContext ctx,
		int plane,
		int worldX,
		int worldY
	) {
		if (ctx.sceneBase == null)
			return null;

		int sceneX = worldX - ctx.sceneBase[0];
		int sceneY = worldY - ctx.sceneBase[1];
		int tileExX = sceneX + ctx.sceneOffset;
		int tileExY = sceneY + ctx.sceneOffset;
		int[][][] tileHeights = ctx.scene.getTileHeights();
		if (tileExX < 0 || tileExY < 0 ||
			tileExX >= tileHeights[plane].length ||
			tileExY >= tileHeights[plane][tileExX].length)
			return null;

		return tileHeights[plane][tileExX][tileExY];
	}

	private static int cornerSurfaceHeightAtWorldVertex(
		ZoneSceneContext ctx,
		int plane,
		int worldX,
		int worldY,
		int fallback
	) {
		Integer live = liveSurfaceHeightAtWorldVertex(ctx, plane, worldX, worldY);
		return live != null ? live : fallback;
	}

	public void prepareSceneTerrain(SceneContext ctx, Tile[][][] tiles) {
		Area area = getArea(ctx);
		if (isFlatTerrain(ctx))
			return;
		if (!isEnabled(ctx))
			return;

		buildPatchMask(ctx, tiles);
		boolean[][] mask = ctx.horizonTileMask;
		if (mask == null || ctx.underwaterDepthLevels == null)
			return;

		int z = getPlane(ctx);
		int sizeX = ctx.sizeX;
		int sizeY = ctx.sizeZ;

		for (int x = 0; x < sizeX; ++x) {
			for (int y = 0; y < sizeY; ++y) {
				if (!mask[x][y] || ctx.isTileFlagSet(z, x, y, TILE_WATER_FLAG))
					continue;

				ctx.setTileFlag(z, x, y, TILE_WATER_FLAG);
				Tile tile = tiles[z][x][y];
				if (tile == null || isHiddenGroundTile(tile)) {
					ctx.underwaterDepthLevels[z][x][y] = 1;
					ctx.underwaterDepthLevels[z][x + 1][y] = 1;
					ctx.underwaterDepthLevels[z][x][y + 1] = 1;
					ctx.underwaterDepthLevels[z][x + 1][y + 1] = 1;
				}
			}
		}

		for (int x = 0; x < sizeX; ++x) {
			for (int y = 0; y < sizeY; ++y) {
				if (!mask[x][y])
					continue;

				Tile tile = tiles[z][x][y];
				if (tile != null && !isHiddenGroundTile(tile))
					continue;

				for (int nx = x - 1; nx <= x + 1; ++nx) {
					for (int ny = y - 1; ny <= y + 1; ++ny) {
						if (nx == x && ny == y)
							continue;
						if (nx < 0 || ny < 0 || nx >= sizeX || ny >= sizeY)
							continue;
						if (!ctx.isTileFlagSet(z, nx, ny, TILE_WATER_FLAG))
							continue;

						Tile neighbor = tiles[z][nx][ny];
						if (neighbor == null)
							continue;

						clearLandFlagsOnWaterFaces(ctx, neighbor, nx, ny, z);
					}
				}
			}
		}
	}

	private void clearLandFlagsOnWaterFaces(SceneContext ctx, Tile tile, int x, int y, int z) {
		if (tile.getBridge() != null)
			tile = tile.getBridge();

		int[] worldPos = ctx.extendedSceneToWorld(x, y, z);
		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null) {
			var override = tileOverrideManager.getOverride(ctx, tile, worldPos);
			if (proceduralGenerator.seasonalWaterType(override, paint.getTexture()) != WaterType.NONE) {
				for (int key : ProceduralGenerator.tileVertexKeys(ctx, tile))
					ctx.clearVertexIsLand(key);
			}
			return;
		}

		SceneTileModel model = tile.getSceneTileModel();
		if (model == null)
			return;

		int overlayId = OVERLAY_FLAG | ctx.scene.getOverlayIds()[z][x][y];
		int underlayId = ctx.scene.getUnderlayIds()[z][x][y];
		var overlayOverride = tileOverrideManager.getOverride(ctx, tile, worldPos, overlayId);
		var underlayOverride = tileOverrideManager.getOverride(ctx, tile, worldPos, underlayId);

		final int[] triangleTextures = model.getTriangleTextureId();
		final int[] triangleColorA = model.getTriangleColorA();
		for (int face = 0; face < triangleColorA.length; face++) {
			if (triangleColorA[face] == HIDDEN_HSL)
				continue;

			int textureId = triangleTextures == null ? -1 : triangleTextures[face];
			boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, face);
			var override = isOverlay ? overlayOverride : underlayOverride;
			if (proceduralGenerator.seasonalWaterType(override, textureId) == WaterType.NONE)
				continue;

			for (int key : ProceduralGenerator.faceVertexKeys(tile, face))
				ctx.clearVertexIsLand(key);
		}
	}

	public void estimateForZone(ZoneSceneContext ctx, Zone zone, int mzx, int mzz) {
		if (!isEnabled(ctx))
			return;

		Sample sample = resolveSample(ctx);
		if (sample == null)
			return;

		boolean terrain = isFlatTerrain(ctx);
		ZonePlan plan = planZone(ctx, mzx, mzz, sample);
		ShellEdges shell = shellEdges(ctx, mzx, mzz);
		int shellFaces = terrain ?
			countTerrainShellFaces(ctx, getArea(ctx), sample, mzx, mzz, shell, UPLOAD_SCRATCH.get().clipBounds) :
			countShellQuads(shell) * 2;
		if (plan.tileCount == 0 && shellFaces == 0)
			return;

		int faces = plan.tileCount * 2 + shellFaces;
		if (terrain) {
			zone.sizeO += faces;
			zone.sizeF += faces;
			if (faces > 0)
				zone.hasWater = true;
		} else {
			zone.hasWater = true;
			zone.sizeO += faces * 2;
			zone.sizeF += faces * 2;
		}
	}

	public void uploadUnderwaterForZone(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		if (!isEnabled(ctx) || isFlatTerrain(ctx))
			return;
		uploadForZone(ctx, zone, mzx, mzz, vb, fb, ZoneUploadMode.WATER_UNDERWATER);
	}

	public void uploadSurfaceForZone(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		if (!isEnabled(ctx))
			return;
		uploadForZone(
			ctx, zone, mzx, mzz, vb, fb,
			isFlatTerrain(ctx) ? ZoneUploadMode.TERRAIN : ZoneUploadMode.WATER_SURFACE
		);
	}

	private void uploadForZone(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		GpuIntBuffer vb,
		GpuIntBuffer fb,
		ZoneUploadMode mode
	) {
		if (!isEnabled(ctx) || vb == null)
			return;

		var scratch = UPLOAD_SCRATCH.get();
		scratch.begin(vb, fb);
		try {
			Sample sample = resolveSample(ctx);
			if (sample == null)
				return;

			Area area = getArea(ctx);
			assert area != null;

			int posBefore = vb.position();
			ZonePlan plan = planZone(ctx, mzx, mzz, sample);
			if (plan.tileCount > 0) {
				boolean underwater = mode == ZoneUploadMode.WATER_UNDERWATER;
				fillZoneCornerHeights(ctx, area, sample, mzx, mzz, scratch.cornerHeights, underwater);
				if (mode == ZoneUploadMode.TERRAIN)
					uploadTerrainZoneTiles(scratch.writeCache, ctx, sample, plan, scratch.cornerHeights, mzx, mzz);
				else if (plan.fullZone && isUniformHeightGrid(scratch.cornerHeights))
					uploadZoneQuad(scratch.writeCache, ctx, sample, scratch.cornerHeights, mzx, mzz, underwater);
				else
					uploadWaterZoneTiles(scratch.writeCache, ctx, sample, plan, scratch.cornerHeights, mzx, mzz, underwater);
			}

			uploadShell(scratch.writeCache, ctx, area, sample, mzx, mzz, mode);

			if (vb.position() > posBefore)
				zone.hasWater = true;
		} finally {
			scratch.end();
		}
	}

	private void uploadShell(
		VertexWriteCache.Collection writeCache,
		ZoneSceneContext ctx,
		Area area,
		Sample sample,
		int mzx,
		int mzz,
		ZoneUploadMode mode
	) {
		int[] clipBounds = UPLOAD_SCRATCH.get().clipBounds;
		forEachShellStrip(shellEdges(ctx, mzx, mzz), (minLocalX, minLocalZ, maxLocalX, maxLocalZ) ->
			uploadShellStrip(writeCache, ctx, area, sample, mzx, mzz, minLocalX, minLocalZ, maxLocalX, maxLocalZ, mode, clipBounds));
	}

	private void uploadShellStrip(
		VertexWriteCache.Collection writeCache,
		ZoneSceneContext ctx,
		Area area,
		Sample sample,
		int mzx,
		int mzz,
		int minLocalX,
		int minLocalZ,
		int maxLocalX,
		int maxLocalZ,
		ZoneUploadMode mode,
		int[] clipBounds
	) {
		if (!clipLocal(ctx, area, mzx, mzz, sample.plane, minLocalX, minLocalZ, maxLocalX, maxLocalZ, clipBounds))
			return;

		switch (mode) {
			case TERRAIN:
				uploadFlatTilesInBounds(
					writeCache, ctx, sample, mzx, mzz,
					clipBounds[0], clipBounds[1], clipBounds[2], clipBounds[3], sample.flatHeight
				);
				break;
			case WATER_UNDERWATER:
				putFlatQuad(
					writeCache,
					sample.waterType, sample.plane,
					clipBounds[0], clipBounds[1], clipBounds[2], clipBounds[3],
					sample.flatUnderwaterHeight, UNDERWATER_HSL, max(1, sample.flatUnderwaterHeight - sample.flatHeight)
				);
				break;
			case WATER_SURFACE:
				putFlatQuad(
					writeCache,
					sample.waterType, sample.plane,
					clipBounds[0], clipBounds[1], clipBounds[2], clipBounds[3],
					sample.flatHeight, 127, 0
				);
				break;
		}
	}

	private static void fillZoneCornerHeights(
		ZoneSceneContext ctx,
		Area area,
		Sample sample,
		int mzx,
		int mzz,
		int[][] heights,
		boolean underwater
	) {
		int plane = sample.plane;
		for (int vx = 0; vx <= CHUNK_SIZE; ++vx) {
			for (int vz = 0; vz <= CHUNK_SIZE; ++vz) {
				int tileX = (mzx << 3) + vx - ctx.sceneOffset;
				int tileY = (mzz << 3) + vz - ctx.sceneOffset;
				int[] worldPos = ctx.sceneToWorld(tileX, tileY, plane);
				AABB aabb = nearestAabb(area, worldPos[0], worldPos[1], plane);
				if (aabb == null) {
					heights[vx][vz] = underwater ? sample.flatUnderwaterHeight : sample.flatHeight;
					continue;
				}
				heights[vx][vz] = underwater ?
					resolveCornerUnderwaterHeight(ctx, sample, aabb, worldPos[0], worldPos[1]) :
					resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0], worldPos[1]);
			}
		}
	}

	private void uploadZoneQuad(
		VertexWriteCache.Collection writeCache,
		ZoneSceneContext ctx,
		Sample sample,
		int[][] cornerHeights,
		int mzx,
		int mzz,
		boolean underwater
	) {
		int sw = cornerHeights[0][0];
		int se = cornerHeights[CHUNK_SIZE][0];
		int nw = cornerHeights[0][CHUNK_SIZE];
		int ne = cornerHeights[CHUNK_SIZE][CHUNK_SIZE];
		int extent = CHUNK_SIZE * LOCAL_TILE_SIZE;

		if (underwater)
			uploadUnderwaterTile(writeCache, ctx, sample, 0, 0, extent, extent, sw, se, nw, ne, mzx, mzz);
		else
			putFlatQuad(writeCache, sample.waterType, sample.plane, 0, 0, extent, extent, sw, 127, 0);
	}

	private void uploadWaterZoneTiles(
		VertexWriteCache.Collection writeCache,
		ZoneSceneContext ctx,
		Sample sample,
		ZonePlan plan,
		int[][] cornerHeights,
		int mzx,
		int mzz,
		boolean underwater
	) {
		for (int xoff = 0; xoff < CHUNK_SIZE; ++xoff) {
			for (int zoff = 0; zoff < CHUNK_SIZE; ++zoff) {
				if (!plan.hasTile(xoff, zoff))
					continue;

				int sw = cornerHeights[xoff][zoff];
				int se = cornerHeights[xoff + 1][zoff];
				int nw = cornerHeights[xoff][zoff + 1];
				int ne = cornerHeights[xoff + 1][zoff + 1];
				int baseX = xoff * LOCAL_TILE_SIZE;
				int baseZ = zoff * LOCAL_TILE_SIZE;

				if (underwater)
					uploadUnderwaterTile(writeCache, ctx, sample, baseX, baseZ, baseX + LOCAL_TILE_SIZE, baseZ + LOCAL_TILE_SIZE, sw, se, nw, ne, mzx, mzz);
				else
					putFlatQuad(writeCache, sample.waterType, sample.plane, baseX, baseZ, baseX + LOCAL_TILE_SIZE, baseZ + LOCAL_TILE_SIZE, sw, 127, 0);
			}
		}
	}

	private void uploadUnderwaterTile(
		VertexWriteCache.Collection writeCache,
		ZoneSceneContext ctx,
		Sample sample,
		int minX,
		int minZ,
		int maxX,
		int maxZ,
		int sw,
		int se,
		int nw,
		int ne,
		int mzx,
		int mzz
	) {
		int tileZ = sample.plane;
		int baseTileX = (mzx << 3) - ctx.sceneOffset;
		int baseTileY = (mzz << 3) - ctx.sceneOffset;
		int[] swWorld = ctx.sceneToWorld(baseTileX + minX / LOCAL_TILE_SIZE, baseTileY + minZ / LOCAL_TILE_SIZE, tileZ);
		int[] seWorld = ctx.sceneToWorld(baseTileX + maxX / LOCAL_TILE_SIZE, baseTileY + minZ / LOCAL_TILE_SIZE, tileZ);
		int[] nwWorld = ctx.sceneToWorld(baseTileX + minX / LOCAL_TILE_SIZE, baseTileY + maxZ / LOCAL_TILE_SIZE, tileZ);
		int[] neWorld = ctx.sceneToWorld(baseTileX + maxX / LOCAL_TILE_SIZE, baseTileY + maxZ / LOCAL_TILE_SIZE, tileZ);

		int swDepth = max(1, sw - resolveSurfaceAt(ctx, sample, swWorld[0], swWorld[1]));
		int seDepth = max(1, se - resolveSurfaceAt(ctx, sample, seWorld[0], seWorld[1]));
		int nwDepth = max(1, nw - resolveSurfaceAt(ctx, sample, nwWorld[0], nwWorld[1]));
		int neDepth = max(1, ne - resolveSurfaceAt(ctx, sample, neWorld[0], neWorld[1]));

		Material swMaterial = Material.NONE;
		Material seMaterial = Material.NONE;
		Material neMaterial = Material.NONE;
		Material nwMaterial = Material.NONE;
		if (plugin.configGroundTextures) {
			GroundMaterial groundMaterial = GroundMaterial.UNDERWATER_GENERIC;
			swMaterial = groundMaterial.getRandomMaterial(swWorld[0], swWorld[1], swWorld[2]);
			seMaterial = groundMaterial.getRandomMaterial(seWorld[0], seWorld[1], seWorld[2]);
			nwMaterial = groundMaterial.getRandomMaterial(nwWorld[0], nwWorld[1], nwWorld[2]);
			neMaterial = groundMaterial.getRandomMaterial(neWorld[0], neWorld[1], neWorld[2]);
		}

		float uvx = fract(swWorld[0]);
		float uvy = fract(swWorld[1]);

		putTriangle(
			writeCache,
			UNDERWATER_HSL, UNDERWATER_HSL, UNDERWATER_HSL,
			neMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			HDUtils.packTerrainData(true, neDepth, sample.waterType, tileZ),
			HDUtils.packTerrainData(true, nwDepth, sample.waterType, tileZ),
			HDUtils.packTerrainData(true, seDepth, sample.waterType, tileZ),
			maxX, ne, maxZ, fract(neWorld[0]), fract(neWorld[1]),
			minX, nw, maxZ, uvx - 1, fract(nwWorld[1]),
			maxX, se, minZ, fract(seWorld[0]), uvy - 1
		);
		putTriangle(
			writeCache,
			UNDERWATER_HSL, UNDERWATER_HSL, UNDERWATER_HSL,
			swMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			HDUtils.packTerrainData(true, swDepth, sample.waterType, tileZ),
			HDUtils.packTerrainData(true, seDepth, sample.waterType, tileZ),
			HDUtils.packTerrainData(true, nwDepth, sample.waterType, tileZ),
			minX, sw, minZ, uvx - 1, uvy - 1,
			maxX, se, minZ, fract(seWorld[0]), uvy - 1,
			minX, nw, maxZ, uvx - 1, fract(nwWorld[1])
		);
	}

	private static int resolveSurfaceAt(ZoneSceneContext ctx, Sample sample, int worldX, int worldY) {
		Area area = getArea(ctx);
		if (area == null)
			return sample.flatHeight;
		AABB aabb = nearestAabb(area, worldX, worldY, sample.plane);
		if (aabb == null)
			return sample.flatHeight;
		return resolveCornerSurfaceHeight(ctx, sample, aabb, worldX, worldY);
	}

	@Nullable
	private Sample resolveSample(ZoneSceneContext ctx) {
		if (!isEnabled(ctx))
			return null;
		if (ctx.horizonTileSample != null)
			return ctx.horizonTileSample;

		Area area = getArea(ctx);
		assert area != null;

		Reference ref = resolveReference(ctx, area);
		HashMap<Long, Integer> boundarySurfaceHeights = new HashMap<>();
		boolean terrain = isFlatTerrain(ctx);

		if (terrain) {
			buildBoundaryHeightMaps(ctx, area, ref.plane, ref.flatHeight, boundarySurfaceHeights, null);
			ctx.horizonTileSample = Sample.terrain(
				ref.plane,
				ref.flatHeight,
				boundarySurfaceHeights,
				ref.waterType,
				ref.material,
				ref.groundMaterial,
				ref.swColor,
				ref.seColor,
				ref.neColor,
				ref.nwColor
			);
			log.info(
				"Horizon terrain enabled for {} using reference tile [{}, {}, {}]: {} at height {}",
				area.name, ref.worldX, ref.worldY, ref.plane, ref.waterType, ref.flatHeight
			);
		} else {
			HashMap<Long, Integer> boundaryUnderwaterHeights = new HashMap<>();
			buildBoundaryHeightMaps(ctx, area, ref.plane, ref.flatHeight, boundarySurfaceHeights, boundaryUnderwaterHeights);

			WaterType waterType = ref.waterType;
			if (waterType == WaterType.NONE)
				waterType = WaterType.WATER;

			int flatUnderwaterHeight = ref.flatHeight + (int) (ProceduralGenerator.MAX_DEPTH * .55f);
			if (!boundaryUnderwaterHeights.isEmpty()) {
				int sum = 0;
				for (int h : boundaryUnderwaterHeights.values())
					sum += h;
				flatUnderwaterHeight = sum / boundaryUnderwaterHeights.size();
			}

			ctx.horizonTileSample = Sample.water(
				waterType,
				ref.plane,
				ref.flatHeight,
				flatUnderwaterHeight,
				boundarySurfaceHeights,
				boundaryUnderwaterHeights
			);
			log.info(
				"Horizon water enabled for {} using reference tile [{}, {}, {}]: {} at height {}, {} boundary vertices sampled",
				area.name, ref.worldX, ref.worldY, ref.plane, waterType, ref.flatHeight, boundaryUnderwaterHeights.size()
			);
		}
		return ctx.horizonTileSample;
	}

	private static void buildBoundaryHeightMaps(
		ZoneSceneContext ctx,
		Area area,
		int plane,
		int flatHeight,
		HashMap<Long, Integer> surfaceHeights,
		HashMap<Long, Integer> underwaterHeights
	) {
		area.normalize();
		for (AABB aabb : area.aabbs) {
			if (aabb.minZ != Integer.MIN_VALUE && aabb.maxZ != Integer.MAX_VALUE &&
				(plane < aabb.minZ || plane > aabb.maxZ))
				continue;

			for (int x = aabb.minX; x <= aabb.maxX + 1; x++) {
				sampleEdgeWithInwardSearch(ctx, plane, aabb, x, aabb.minY, 0, 1, flatHeight, surfaceHeights, underwaterHeights);
				sampleEdgeWithInwardSearch(ctx, plane, aabb, x, aabb.maxY + 1, 0, -1, flatHeight, surfaceHeights, underwaterHeights);
			}
			for (int y = aabb.minY + 1; y <= aabb.maxY; y++) {
				sampleEdgeWithInwardSearch(ctx, plane, aabb, aabb.minX, y, 1, 0, flatHeight, surfaceHeights, underwaterHeights);
				sampleEdgeWithInwardSearch(ctx, plane, aabb, aabb.maxX + 1, y, -1, 0, flatHeight, surfaceHeights, underwaterHeights);
			}
		}
	}

	private static void sampleEdgeWithInwardSearch(
		ZoneSceneContext ctx,
		int plane,
		AABB aabb,
		int boundaryX,
		int boundaryY,
		int stepX,
		int stepY,
		int flatHeight,
		HashMap<Long, Integer> surfaceHeights,
		@Nullable HashMap<Long, Integer> underwaterHeights
	) {
		long boundaryKey = Sample.packWorldVertex(boundaryX, boundaryY);
		if (underwaterHeights != null && underwaterHeights.containsKey(boundaryKey))
			return;

		if (underwaterHeights == null) {
			surfaceHeights.putIfAbsent(
				boundaryKey,
				cornerSurfaceHeightAtWorldVertex(ctx, plane, boundaryX, boundaryY, flatHeight)
			);
			return;
		}

		for (int i = 0; i <= EDGE_INSET; i++) {
			int worldX = boundaryX + stepX * i;
			int worldY = boundaryY + stepY * i;
			if (worldX < aabb.minX || worldX > aabb.maxX + 1 ||
				worldY < aabb.minY || worldY > aabb.maxY + 1)
				break;

			int depth = interiorUnderwaterOffsetAtVertex(ctx, plane, worldX, worldY);
			if (depth <= 0)
				depth = cornerUnderwaterOffsetAtWorldVertex(ctx, plane, worldX, worldY);

			if (depth > 0) {
				int boundarySurface = cornerSurfaceHeightAtWorldVertex(ctx, plane, boundaryX, boundaryY, flatHeight);
				surfaceHeights.put(boundaryKey, boundarySurface);
				underwaterHeights.put(boundaryKey, boundarySurface + depth);
				return;
			}
		}

		surfaceHeights.putIfAbsent(
			boundaryKey,
			cornerSurfaceHeightAtWorldVertex(ctx, plane, boundaryX, boundaryY, flatHeight)
		);
	}

	private static int resolveCornerUnderwaterHeight(
		ZoneSceneContext ctx,
		Sample sample,
		AABB aabb,
		int worldX,
		int worldY
	) {
		int boundaryX = clampVertexX(aabb, worldX);
		int boundaryY = clampVertexY(aabb, worldY);

		Integer live = liveUnderwaterHeightAtWorldVertex(ctx, sample.plane, boundaryX, boundaryY);
		if (live != null)
			return live;

		long key = Sample.packWorldVertex(boundaryX, boundaryY);
		HashMap<Long, Integer> underwaterHeights = sample.boundaryUnderwaterHeights;
		Integer height = underwaterHeights.get(key);
		if (height != null) {
			Integer liveSurface = liveSurfaceHeightAtWorldVertex(ctx, sample.plane, boundaryX, boundaryY);
			Integer cachedSurface = sample.boundarySurfaceHeights.get(key);
			if (liveSurface != null && cachedSurface != null && !liveSurface.equals(cachedSurface))
				return height + (liveSurface - cachedSurface);
			return height;
		}

		Integer surface = sample.boundarySurfaceHeights.get(key);
		if (surface != null)
			return surface + Math.max(1, sample.flatUnderwaterHeight - sample.flatHeight);

		Integer liveSurface = liveSurfaceHeightAtWorldVertex(ctx, sample.plane, boundaryX, boundaryY);
		if (liveSurface != null)
			return liveSurface + Math.max(1, sample.flatUnderwaterHeight - sample.flatHeight);

		return sample.flatUnderwaterHeight;
	}

	@Nullable
	private static Integer liveUnderwaterHeightAtWorldVertex(
		ZoneSceneContext ctx,
		int plane,
		int worldX,
		int worldY
	) {
		Integer surface = liveSurfaceHeightAtWorldVertex(ctx, plane, worldX, worldY);
		if (surface == null)
			return null;

		int depth = interiorUnderwaterOffsetAtVertex(ctx, plane, worldX, worldY);
		if (depth <= 0)
			depth = cornerUnderwaterOffsetAtWorldVertex(ctx, plane, worldX, worldY);
		if (depth <= 0)
			return null;

		return surface + depth;
	}

	private static int interiorUnderwaterOffsetAtVertex(
		ZoneSceneContext ctx,
		int plane,
		int worldX,
		int worldY
	) {
		if (ctx.sceneBase == null || ctx.vertexTerrainData == null)
			return 0;

		int sceneX = worldX - ctx.sceneBase[0];
		int sceneY = worldY - ctx.sceneBase[1];
		int tileExX = sceneX + ctx.sceneOffset;
		int tileExY = sceneY + ctx.sceneOffset;

		Tile[][][] tiles = ctx.scene.getExtendedTiles();
		for (int[] corner : CORNER_TILES) {
			int tx = tileExX + corner[0];
			int ty = tileExY + corner[1];
			int vertexIndex = corner[2];
			if (tx < 0 || ty < 0 || tx >= EXTENDED_SCENE_SIZE || ty >= EXTENDED_SCENE_SIZE)
				continue;

			Tile tile = tiles[plane][tx][ty];
			if (tile == null)
				continue;

			if (tile.getBridge() != null)
				tile = tile.getBridge();

			if (tile.getSceneTilePaint() == null && tile.getSceneTileModel() == null)
				continue;

			int[] keys = ProceduralGenerator.tileVertexKeys(ctx, tile);
			if (ctx.vertexTerrainData.containsKey(keys[vertexIndex])) {
				int depth = ctx.getVertexUnderwaterDepth(keys[vertexIndex]);
				if (depth > 0)
					return depth;
			}
		}

		return 0;
	}

	private static int cornerUnderwaterOffsetAtWorldVertex(
		ZoneSceneContext ctx,
		int plane,
		int worldX,
		int worldY
	) {
		if (ctx.sceneBase == null)
			return 0;

		int sceneX = worldX - ctx.sceneBase[0];
		int sceneY = worldY - ctx.sceneBase[1];
		int tileExX = sceneX + ctx.sceneOffset;
		int tileExY = sceneY + ctx.sceneOffset;
		if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
			return 0;

		if (ctx.underwaterDepthLevels != null &&
			tileExX < ctx.underwaterDepthLevels[plane].length &&
			tileExY < ctx.underwaterDepthLevels[plane][tileExX].length) {
			int level = ctx.underwaterDepthLevels[plane][tileExX][tileExY];
			if (level > 0 && level <= ProceduralGenerator.DEPTH_LEVEL_SLOPE.length) {
				int depth = ProceduralGenerator.DEPTH_LEVEL_SLOPE[level - 1];
				return (int) (depth * .55f);
			}
		}

		if (ctx.vertexTerrainData != null) {
			int height = cornerSurfaceHeightAtWorldVertex(ctx, plane, worldX, worldY, 0);
			int key = tileVertexHash(new int[] {
				sceneX * LOCAL_TILE_SIZE,
				height,
				sceneY * LOCAL_TILE_SIZE
			});
			if (ctx.vertexTerrainData.containsKey(key)) {
				int depth = ctx.getVertexUnderwaterDepth(key);
				if (depth > 0)
					return depth;
			}
		}

		return 0;
	}

	private int shouldExtendTile(ZoneSceneContext ctx, Sample sample, int tileExX, int tileExY) {
		if (isFlatTerrain(ctx))
			return shouldExtendFlatTerrainTile(ctx, tileExX, tileExY, sample.plane) ? 2 : 0;

		int tileZ = sample.plane;
		if (!shouldRenderAtTile(ctx, tileExX, tileExY, tileZ))
			return 0;

		if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
			return 2;

		if (ctx.isTileFlagSet(tileZ, tileExX, tileExY, TILE_WATER_FLAG) &&
			(ctx.filledTiles[tileExX][tileExY] & (1 << tileZ)) != 0)
			return 0;

		Tile tile = ctx.scene.getExtendedTiles()[tileZ][tileExX][tileExY];
		if (tile == null || isHiddenGroundTile(tile))
			return 2;

		if ((ctx.filledTiles[tileExX][tileExY] & (1 << tileZ)) != 0 && hasVisibleLand(ctx, tile, tileExX, tileExY, tileZ))
			return 0;

		return 2;
	}

	private boolean hasVisibleLand(SceneContext ctx, Tile tile, int tileExX, int tileExY, int tileZ) {
		if (tile.getBridge() != null)
			tile = tile.getBridge();

		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null && paint.getNeColor() != HIDDEN_HSL) {
			int[] worldPos = ctx.extendedSceneToWorld(tileExX, tileExY, tileZ);
			var override = tileOverrideManager.getOverride(ctx, tile, worldPos);
			if (proceduralGenerator.seasonalWaterType(override, paint.getTexture()) != WaterType.NONE)
				return false;
			return true;
		}

		SceneTileModel model = tile.getSceneTileModel();
		if (model == null)
			return false;

		int[] worldPos = ctx.extendedSceneToWorld(tileExX, tileExY, tileZ);
		int overlayId = OVERLAY_FLAG | ctx.scene.getOverlayIds()[tileZ][tileExX][tileExY];
		int underlayId = ctx.scene.getUnderlayIds()[tileZ][tileExX][tileExY];
		var overlayOverride = tileOverrideManager.getOverride(ctx, tile, worldPos, overlayId);
		var underlayOverride = tileOverrideManager.getOverride(ctx, tile, worldPos, underlayId);

		final int[] triangleTextures = model.getTriangleTextureId();
		final int[] triangleColorA = model.getTriangleColorA();
		for (int face = 0; face < triangleColorA.length; face++) {
			if (triangleColorA[face] == HIDDEN_HSL)
				continue;

			int textureId = triangleTextures == null ? -1 : triangleTextures[face];
			boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, face);
			var override = isOverlay ? overlayOverride : underlayOverride;
			if (proceduralGenerator.seasonalWaterType(override, textureId) != WaterType.NONE)
				continue;

			return true;
		}
		return false;
	}

	private int countTerrainShellFaces(
		ZoneSceneContext ctx,
		Area area,
		Sample sample,
		int mzx,
		int mzz,
		ShellEdges shell,
		int[] clipBounds
	) {
		int[] faces = { 0 };
		forEachShellStrip(shell, (minX, minZ, maxX, maxZ) ->
			faces[0] += countShellStripTiles(ctx, area, mzx, mzz, sample.plane, minX, minZ, maxX, maxZ, clipBounds) * 2);
		return faces[0];
	}

	private void uploadTerrainZoneTiles(
		VertexWriteCache.Collection writeCache,
		ZoneSceneContext ctx,
		Sample sample,
		ZonePlan plan,
		int[][] cornerHeights,
		int mzx,
		int mzz
	) {
		int baseTileX = (mzx << 3) - ctx.sceneOffset;
		int baseTileY = (mzz << 3) - ctx.sceneOffset;

		for (int xoff = 0; xoff < CHUNK_SIZE; ++xoff) {
			for (int zoff = 0; zoff < CHUNK_SIZE; ++zoff) {
				if (!plan.hasTile(xoff, zoff))
					continue;

				int sw = cornerHeights[xoff][zoff];
				int se = cornerHeights[xoff + 1][zoff];
				int nw = cornerHeights[xoff][zoff + 1];
				int ne = cornerHeights[xoff + 1][zoff + 1];
				int baseX = xoff * LOCAL_TILE_SIZE;
				int baseZ = zoff * LOCAL_TILE_SIZE;

				int[] swWorld = ctx.sceneToWorld(baseTileX + xoff, baseTileY + zoff, sample.plane);
				int[] seWorld = ctx.sceneToWorld(baseTileX + xoff + 1, baseTileY + zoff, sample.plane);
				int[] nwWorld = ctx.sceneToWorld(baseTileX + xoff, baseTileY + zoff + 1, sample.plane);
				int[] neWorld = ctx.sceneToWorld(baseTileX + xoff + 1, baseTileY + zoff + 1, sample.plane);

				uploadTerrainTile(writeCache, ctx, sample, baseX, baseZ, sw, se, nw, ne, swWorld, seWorld, nwWorld, neWorld);
			}
		}
	}

	private void uploadFlatTilesInBounds(
		VertexWriteCache.Collection writeCache,
		ZoneSceneContext ctx,
		Sample sample,
		int mzx,
		int mzz,
		int minX,
		int minZ,
		int maxX,
		int maxZ,
		int height
	) {
		int baseTileX = (mzx << 3) - ctx.sceneOffset;
		int baseTileY = (mzz << 3) - ctx.sceneOffset;
		int plane = sample.plane;

		int startTileX = floorDiv(minX, LOCAL_TILE_SIZE);
		int startTileZ = floorDiv(minZ, LOCAL_TILE_SIZE);
		int endTileX = floorDiv(maxX - 1, LOCAL_TILE_SIZE);
		int endTileZ = floorDiv(maxZ - 1, LOCAL_TILE_SIZE);

		for (int tx = startTileX; tx <= endTileX; ++tx) {
			for (int tz = startTileZ; tz <= endTileZ; ++tz) {
				int baseX = tx * LOCAL_TILE_SIZE;
				int baseZ = tz * LOCAL_TILE_SIZE;
				if (baseX + LOCAL_TILE_SIZE <= minX || baseZ + LOCAL_TILE_SIZE <= minZ ||
					baseX >= maxX || baseZ >= maxZ)
					continue;

				int[] swWorld = ctx.sceneToWorld(baseTileX + baseX / LOCAL_TILE_SIZE, baseTileY + baseZ / LOCAL_TILE_SIZE, plane);
				int[] seWorld = ctx.sceneToWorld(baseTileX + baseX / LOCAL_TILE_SIZE + 1, baseTileY + baseZ / LOCAL_TILE_SIZE, plane);
				int[] nwWorld = ctx.sceneToWorld(baseTileX + baseX / LOCAL_TILE_SIZE, baseTileY + baseZ / LOCAL_TILE_SIZE + 1, plane);
				int[] neWorld = ctx.sceneToWorld(baseTileX + baseX / LOCAL_TILE_SIZE + 1, baseTileY + baseZ / LOCAL_TILE_SIZE + 1, plane);

				uploadTerrainTile(writeCache, ctx, sample, baseX, baseZ, height, height, height, height, swWorld, seWorld, nwWorld, neWorld);
			}
		}
	}

	private Material resolveMaterial(Sample sample, int[] worldPos) {
		if (plugin.configGroundTextures && sample.groundMaterial != null)
			return sample.groundMaterial.getRandomMaterial(worldPos[0], worldPos[1], worldPos[2]);
		return sample.material;
	}

	private void uploadTerrainTile(
		VertexWriteCache.Collection writeCache,
		ZoneSceneContext ctx,
		Sample sample,
		int baseX,
		int baseZ,
		int sw,
		int se,
		int nw,
		int ne,
		int[] swWorld,
		int[] seWorld,
		int[] nwWorld,
		int[] neWorld
	) {
		if (sample.isWaterSurface()) {
			putFlatQuad(
				writeCache,
				sample.waterType, sample.plane,
				baseX, baseZ, baseX + LOCAL_TILE_SIZE, baseZ + LOCAL_TILE_SIZE,
				sw, 127, 0
			);
			return;
		}

		int terrainData = HDUtils.packTerrainData(true, 0, WaterType.NONE, sample.plane);
		Material swMaterial = resolveMaterial(sample, swWorld);
		Material seMaterial = resolveMaterial(sample, seWorld);
		Material nwMaterial = resolveMaterial(sample, nwWorld);
		Material neMaterial = resolveMaterial(sample, neWorld);
		float uvx = fract(swWorld[0]);
		float uvy = fract(swWorld[1]);

		putTriangle(
			writeCache,
			sample.neColor, sample.nwColor, sample.seColor,
			neMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			terrainData, terrainData, terrainData,
			baseX + LOCAL_TILE_SIZE, ne, baseZ + LOCAL_TILE_SIZE, fract(neWorld[0]), fract(neWorld[1]),
			baseX, nw, baseZ + LOCAL_TILE_SIZE, uvx - 1, uvy,
			baseX + LOCAL_TILE_SIZE, se, baseZ, fract(seWorld[0]), uvy - 1
		);
		putTriangle(
			writeCache,
			sample.swColor, sample.seColor, sample.nwColor,
			swMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false),
			terrainData, terrainData, terrainData,
			baseX, sw, baseZ, uvx - 1, uvy - 1,
			baseX + LOCAL_TILE_SIZE, se, baseZ, fract(seWorld[0]), uvy - 1,
			baseX, nw, baseZ + LOCAL_TILE_SIZE, uvx - 1, uvy
		);
	}
}
