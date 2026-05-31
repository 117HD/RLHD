package rs117.hd.renderer.zone;

import java.util.HashMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.scene.ExtendWaterSample;
import rs117.hd.scene.ExtendWaterTileUtil;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static java.lang.Math.max;
import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.HDUtils.UNDERWATER_HSL;
import static rs117.hd.utils.MathUtils.fract;

@Slf4j
@Singleton
public class WaterExtender {
	private static final int[] UP_NORMAL = { 0, -1, 0 };
	private static final int EDGE_INSET = 4;

	@Inject
	private HdPlugin plugin;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	/**
	 * Marks horizon tiles as water in procedural terrain data and clears land flags on adjacent water faces.
	 * Call once during underwater terrain generation before depth sinking.
	 */
	public void prepareSceneTerrain(SceneContext ctx, Tile[][][] tiles) {
		if (!ExtendWaterTileUtil.isEnabled(ctx))
			return;

		ExtendWaterTileUtil.buildHorizonTileMask(ctx);
		boolean[][] mask = ctx.horizonTileMask;
		if (mask == null)
			return;

		int z = ExtendWaterTileUtil.getExtendWaterPlane(ctx);
		int sizeX = ctx.sizeX;
		int sizeY = ctx.sizeZ;

		for (int x = 0; x < sizeX; ++x) {
			for (int y = 0; y < sizeY; ++y) {
				if (!mask[x][y])
					continue;

				if (ctx.tileIsWater[z][x][y])
					continue;

				Tile tile = tiles[z][x][y];
				if (tile != null && hasVisibleLand(ctx, tile, x, y, z))
					continue;

				ctx.tileIsWater[z][x][y] = true;

				if (tile == null || ExtendWaterTileUtil.isHiddenGroundTile(tile)) {
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
				if (tile != null && !ExtendWaterTileUtil.isHiddenGroundTile(tile))
					continue;

				for (int nx = x - 1; nx <= x + 1; ++nx) {
					for (int ny = y - 1; ny <= y + 1; ++ny) {
						if (nx == x && ny == y)
							continue;
						if (nx < 0 || ny < 0 || nx >= sizeX || ny >= sizeY)
							continue;
						if (!ctx.tileIsWater[z][nx][ny])
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
					ctx.vertexIsLand.remove(key);
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
				ctx.vertexIsLand.remove(key);
		}
	}

	public void estimateForZone(ZoneSceneContext ctx, Zone zone, int mzx, int mzz) {
		if (!isEnabled(ctx))
			return;

		for (int xoff = 0; xoff < CHUNK_SIZE; ++xoff) {
			for (int zoff = 0; zoff < CHUNK_SIZE; ++zoff) {
				int tileExX = (mzx << 3) + xoff;
				int tileExY = (mzz << 3) + zoff;
				if (shouldExtendTile(ctx, tileExX, tileExY) > 0) {
					zone.hasWater = true;
					// underwater + surface
					zone.sizeO += 4;
					zone.sizeF += 4;
				}
			}
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
		uploadForZone(ctx, zone, mzx, mzz, vb, fb, true);
	}

	public void uploadSurfaceForZone(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		uploadForZone(ctx, zone, mzx, mzz, vb, fb, false);
	}

	private void uploadForZone(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		GpuIntBuffer vb,
		GpuIntBuffer fb,
		boolean underwater
	) {
		ExtendWaterSample sample = resolveSample(ctx);
		if (sample == null)
			return;

		Area area = ExtendWaterTileUtil.getExtendWaterArea(ctx);
		assert area != null;

		int basex = (mzx - (ctx.sceneOffset >> 3)) << 10;
		int basez = (mzz - (ctx.sceneOffset >> 3)) << 10;

		int posBefore = vb.position();
		for (int xoff = 0; xoff < CHUNK_SIZE; ++xoff) {
			for (int zoff = 0; zoff < CHUNK_SIZE; ++zoff) {
				int tileExX = (mzx << 3) + xoff;
				int tileExY = (mzz << 3) + zoff;
				if (shouldExtendTile(ctx, tileExX, tileExY) > 0) {
					int tileX = tileExX - ctx.sceneOffset;
					int tileY = tileExY - ctx.sceneOffset;
					int[] worldPos = ctx.sceneToWorld(tileX, tileY, sample.plane);
					if (underwater) {
						uploadUnderwaterTile(ctx, area, sample, worldPos, tileX, tileY, basex, basez, vb, fb);
					} else {
						uploadSurfaceTile(ctx, area, sample, worldPos, tileX, tileY, basex, basez, vb, fb);
					}
				}
			}
		}

		if (vb.position() > posBefore)
			zone.hasWater = true;
	}

	private static boolean isEnabled(ZoneSceneContext ctx) {
		return ExtendWaterTileUtil.isEnabled(ctx);
	}

	@Nullable
	private ExtendWaterSample resolveSample(ZoneSceneContext ctx) {
		Area area = ExtendWaterTileUtil.getExtendWaterArea(ctx);
		if (!isEnabled(ctx))
			return null;

		if (ctx.extendWaterSample != null)
			return ctx.extendWaterSample;

		assert area != null;
		int[] ref = area.extendWaterReferenceTile;
		int worldX = ref[0];
		int worldY = ref[1];
		int plane = ref.length > 2 ? ref[2] : 0;

		WaterType waterType = WaterType.NONE;
		int textureId = -1;
		int flatHeight = 0;
		TileOverride override = TileOverride.NONE;

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
					} else {
						SceneTileModel model = tile.getSceneTileModel();
						if (model != null && model.getTriangleTextureId() != null) {
							for (int faceTexture : model.getTriangleTextureId()) {
								if (faceTexture != -1) {
									textureId = faceTexture;
									break;
								}
							}
						}
						flatHeight = averageHeight(tileHeights, plane, tileExX, tileExY);
					}
					waterType = proceduralGenerator.seasonalWaterType(override, textureId);
				}
			}
		}

		if (waterType == WaterType.NONE) {
			int[] worldPos = { worldX, worldY, plane };
			if (override == TileOverride.NONE)
				override = tileOverrideManager.getOverrideBeforeReplacements(worldPos);
			waterType = proceduralGenerator.seasonalWaterType(override, textureId);
		}

		if (waterType == WaterType.NONE)
			waterType = WaterType.WATER;

		HashMap<Long, Integer> boundarySurfaceHeights = new HashMap<>();
		HashMap<Long, Integer> boundaryUnderwaterHeights = new HashMap<>();
		buildBoundaryHeightMaps(ctx, area, plane, flatHeight, boundarySurfaceHeights, boundaryUnderwaterHeights);

		int flatUnderwaterHeight = flatHeight + (int) (ProceduralGenerator.MAX_DEPTH * .55f);
		if (!boundaryUnderwaterHeights.isEmpty()) {
			int sum = 0;
			for (int h : boundaryUnderwaterHeights.values())
				sum += h;
			flatUnderwaterHeight = sum / boundaryUnderwaterHeights.size();
		}

		ctx.extendWaterSample = new ExtendWaterSample(
			waterType,
			plane,
			flatHeight,
			flatUnderwaterHeight,
			boundarySurfaceHeights,
			boundaryUnderwaterHeights
		);
		log.info(
			"ExtendWater enabled for {} using reference tile [{}, {}, {}]: {} at height {}, {} boundary vertices sampled",
			area.name,
			worldX,
			worldY,
			plane,
			waterType,
			flatHeight,
			boundaryUnderwaterHeights.size()
		);
		return ctx.extendWaterSample;
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
				sampleEdgeWithInwardSearch(
					ctx, plane, aabb, x, aabb.minY, 0, 1, flatHeight, surfaceHeights, underwaterHeights
				);
				sampleEdgeWithInwardSearch(
					ctx, plane, aabb, x, aabb.maxY + 1, 0, -1, flatHeight, surfaceHeights, underwaterHeights
				);
			}
			for (int y = aabb.minY + 1; y <= aabb.maxY; y++) {
				sampleEdgeWithInwardSearch(
					ctx, plane, aabb, aabb.minX, y, 1, 0, flatHeight, surfaceHeights, underwaterHeights
				);
				sampleEdgeWithInwardSearch(
					ctx, plane, aabb, aabb.maxX + 1, y, -1, 0, flatHeight, surfaceHeights, underwaterHeights
				);
			}
		}
	}

	/**
	 * Sample underwater height along an AABB edge, stepping inward when the boundary
	 * vertex has no water depth (common on the south shore where minY is land).
	 * Results are stored under the boundary vertex key so extended tiles can look them up.
	 */
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
		HashMap<Long, Integer> underwaterHeights
	) {
		long boundaryKey = ExtendWaterSample.packWorldVertex(boundaryX, boundaryY);
		if (underwaterHeights.containsKey(boundaryKey))
			return;

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
				int boundarySurface = cornerSurfaceHeightAtWorldVertex(
					ctx, plane, boundaryX, boundaryY, flatHeight
				);
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

	@Nullable
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

	private static int clampBoundaryVertexX(AABB aabb, int worldX) {
		return Math.min(Math.max(worldX, aabb.minX), aabb.maxX + 1);
	}

	private static int clampBoundaryVertexY(AABB aabb, int worldY) {
		return Math.min(Math.max(worldY, aabb.minY), aabb.maxY + 1);
	}

	private static int resolveCornerSurfaceHeight(
		ZoneSceneContext ctx,
		ExtendWaterSample sample,
		AABB aabb,
		int worldX,
		int worldY
	) {
		int boundaryX = clampBoundaryVertexX(aabb, worldX);
		int boundaryY = clampBoundaryVertexY(aabb, worldY);

		Integer live = liveSurfaceHeightAtWorldVertex(ctx, sample.plane, boundaryX, boundaryY);
		if (live != null)
			return live;

		long key = ExtendWaterSample.packWorldVertex(boundaryX, boundaryY);
		Integer height = sample.boundarySurfaceHeights.get(key);
		return height != null ? height : sample.flatHeight;
	}

	private static int resolveCornerUnderwaterHeight(
		ZoneSceneContext ctx,
		ExtendWaterSample sample,
		AABB aabb,
		int worldX,
		int worldY
	) {
		int boundaryX = clampBoundaryVertexX(aabb, worldX);
		int boundaryY = clampBoundaryVertexY(aabb, worldY);

		Integer live = liveUnderwaterHeightAtWorldVertex(ctx, sample.plane, boundaryX, boundaryY);
		if (live != null)
			return live;

		long key = ExtendWaterSample.packWorldVertex(boundaryX, boundaryY);
		Integer height = sample.boundaryUnderwaterHeights.get(key);
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
		if (ctx.sceneBase == null || ctx.vertexUnderwaterDepth == null)
			return 0;

		int sceneX = worldX - ctx.sceneBase[0];
		int sceneY = worldY - ctx.sceneBase[1];
		int tileExX = sceneX + ctx.sceneOffset;
		int tileExY = sceneY + ctx.sceneOffset;

		int[][] cornerTiles = {
			{ 0, 0, 0 },
			{ -1, 0, 1 },
			{ 0, -1, 2 },
			{ -1, -1, 3 },
		};

		Tile[][][] tiles = ctx.scene.getExtendedTiles();
		for (int[] corner : cornerTiles) {
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
			Integer depth = ctx.vertexUnderwaterDepth.get(keys[vertexIndex]);
			if (depth != null && depth > 0)
				return depth;
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

		if (ctx.vertexUnderwaterDepth != null) {
			int height = cornerSurfaceHeightAtWorldVertex(ctx, plane, worldX, worldY, 0);
			int key = HDUtils.fastVertexHash(new int[] {
				sceneX * LOCAL_TILE_SIZE,
				sceneY * LOCAL_TILE_SIZE,
				height
			});
			Integer depth = ctx.vertexUnderwaterDepth.get(key);
			if (depth != null && depth > 0)
				return depth;
		}

		return 0;
	}

	private static int cornerSurfaceHeightAtWorldVertex(
		ZoneSceneContext ctx,
		int plane,
		int worldX,
		int worldY,
		int fallback
	) {
		if (ctx.sceneBase == null)
			return fallback;

		int sceneX = worldX - ctx.sceneBase[0];
		int sceneY = worldY - ctx.sceneBase[1];
		int tileExX = sceneX + ctx.sceneOffset;
		int tileExY = sceneY + ctx.sceneOffset;
		if (tileExX < 0 || tileExY < 0 ||
			tileExX >= ctx.scene.getTileHeights()[plane].length ||
			tileExY >= ctx.scene.getTileHeights()[plane][tileExX].length)
			return fallback;

		return ctx.scene.getTileHeights()[plane][tileExX][tileExY];
	}

	private static int averageHeight(int[][][] tileHeights, int plane, int tileExX, int tileExY) {
		int sw = tileHeights[plane][tileExX][tileExY];
		int se = tileHeights[plane][tileExX + 1][tileExY];
		int ne = tileHeights[plane][tileExX + 1][tileExY + 1];
		int nw = tileHeights[plane][tileExX][tileExY + 1];
		return (sw + se + ne + nw) / 4;
	}

	/**
	 * @return face count if the tile should be extended with water, otherwise 0
	 */
	private int shouldExtendTile(ZoneSceneContext ctx, int tileExX, int tileExY) {
		if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
			return 0;

		ExtendWaterSample sample = resolveSample(ctx);
		if (sample == null)
			return 0;

		int tileZ = sample.plane;

		if (!ExtendWaterTileUtil.shouldExtendWaterAtTile(ctx, tileExX, tileExY, tileZ))
			return 0;

		if (ctx.tileIsWater != null &&
			ctx.tileIsWater[tileZ][tileExX][tileExY] &&
			(ctx.filledTiles[tileExX][tileExY] & (1 << tileZ)) != 0)
			return 0;

		Tile[][][] extendedTiles = ctx.scene.getExtendedTiles();
		Tile tile = extendedTiles[tileZ][tileExX][tileExY];

		if (tile == null || ExtendWaterTileUtil.isHiddenGroundTile(tile))
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

	private void uploadUnderwaterTile(
		ZoneSceneContext ctx,
		Area area,
		ExtendWaterSample sample,
		int[] worldPos,
		int tileX,
		int tileY,
		int zoneBasex,
		int zoneBasez,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		int tileZ = sample.plane;
		AABB aabb = nearestAabb(area, worldPos[0], worldPos[1], tileZ);
		if (aabb == null)
			return;

		int swHeight = resolveCornerUnderwaterHeight(ctx, sample, aabb, worldPos[0], worldPos[1]);
		int seHeight = resolveCornerUnderwaterHeight(ctx, sample, aabb, worldPos[0] + 1, worldPos[1]);
		int nwHeight = resolveCornerUnderwaterHeight(ctx, sample, aabb, worldPos[0], worldPos[1] + 1);
		int neHeight = resolveCornerUnderwaterHeight(ctx, sample, aabb, worldPos[0] + 1, worldPos[1] + 1);

		int swDepth = max(1, swHeight - resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0], worldPos[1]));
		int seDepth = max(1, seHeight - resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0] + 1, worldPos[1]));
		int nwDepth = max(1, nwHeight - resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0], worldPos[1] + 1));
		int neDepth = max(1, neHeight - resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0] + 1, worldPos[1] + 1));

		Material swMaterial = Material.NONE;
		Material seMaterial = Material.NONE;
		Material neMaterial = Material.NONE;
		Material nwMaterial = Material.NONE;
		if (plugin.configGroundTextures) {
			GroundMaterial groundMaterial = GroundMaterial.UNDERWATER_GENERIC;
			swMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1], worldPos[2]);
			seMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1], worldPos[2]);
			nwMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1] + 1, worldPos[2]);
			neMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1] + 1, worldPos[2]);
		}

		int swTerrainData = HDUtils.packTerrainData(true, max(1, swDepth), sample.waterType, tileZ);
		int seTerrainData = HDUtils.packTerrainData(true, max(1, seDepth), sample.waterType, tileZ);
		int nwTerrainData = HDUtils.packTerrainData(true, max(1, nwDepth), sample.waterType, tileZ);
		int neTerrainData = HDUtils.packTerrainData(true, max(1, neDepth), sample.waterType, tileZ);
		int swMaterialData = swMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
		int seMaterialData = seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
		int nwMaterialData = nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
		int neMaterialData = neMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);

		float uvx = fract(worldPos[0]);
		float uvy = fract(worldPos[1]);

		int baseX = tileX * LOCAL_TILE_SIZE - zoneBasex;
		int baseZ = tileY * LOCAL_TILE_SIZE - zoneBasez;

		putTerrainTriangle(
			vb, fb,
			UNDERWATER_HSL, UNDERWATER_HSL, UNDERWATER_HSL,
			neMaterialData, nwMaterialData, seMaterialData,
			neTerrainData, nwTerrainData, seTerrainData,
			baseX + LOCAL_TILE_SIZE, neHeight, baseZ + LOCAL_TILE_SIZE, uvx, uvy,
			baseX, nwHeight, baseZ + LOCAL_TILE_SIZE, uvx - 1, uvy,
			baseX + LOCAL_TILE_SIZE, seHeight, baseZ, uvx, uvy - 1,
			UP_NORMAL
		);
		putTerrainTriangle(
			vb, fb,
			UNDERWATER_HSL, UNDERWATER_HSL, UNDERWATER_HSL,
			swMaterialData, seMaterialData, nwMaterialData,
			swTerrainData, seTerrainData, nwTerrainData,
			baseX, swHeight, baseZ, uvx - 1, uvy - 1,
			baseX + LOCAL_TILE_SIZE, seHeight, baseZ, uvx, uvy - 1,
			baseX, nwHeight, baseZ + LOCAL_TILE_SIZE, uvx - 1, uvy,
			UP_NORMAL
		);
	}

	private void uploadSurfaceTile(
		ZoneSceneContext ctx,
		Area area,
		ExtendWaterSample sample,
		int[] worldPos,
		int tileX,
		int tileY,
		int zoneBasex,
		int zoneBasez,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		int tileZ = sample.plane;
		AABB aabb = nearestAabb(area, worldPos[0], worldPos[1], tileZ);
		if (aabb == null)
			return;

		int swHeight = resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0], worldPos[1]);
		int seHeight = resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0] + 1, worldPos[1]);
		int nwHeight = resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0], worldPos[1] + 1);
		int neHeight = resolveCornerSurfaceHeight(ctx, sample, aabb, worldPos[0] + 1, worldPos[1] + 1);

		int terrainData = HDUtils.packTerrainData(true, 0, sample.waterType, tileZ);
		int packedMaterial = Material.NONE.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
		int color = 127;

		int baseX = tileX * LOCAL_TILE_SIZE - zoneBasex;
		int baseZ = tileY * LOCAL_TILE_SIZE - zoneBasez;

		putTerrainTriangle(
			vb, fb,
			color, color, color,
			packedMaterial, packedMaterial, packedMaterial,
			terrainData, terrainData, terrainData,
			baseX + LOCAL_TILE_SIZE, neHeight, baseZ + LOCAL_TILE_SIZE, 0, 0,
			baseX, nwHeight, baseZ + LOCAL_TILE_SIZE, 0, 0,
			baseX + LOCAL_TILE_SIZE, seHeight, baseZ, 0, 0,
			UP_NORMAL
		);
		putTerrainTriangle(
			vb, fb,
			color, color, color,
			packedMaterial, packedMaterial, packedMaterial,
			terrainData, terrainData, terrainData,
			baseX, swHeight, baseZ, 0, 0,
			baseX + LOCAL_TILE_SIZE, seHeight, baseZ, 0, 0,
			baseX, nwHeight, baseZ + LOCAL_TILE_SIZE, 0, 0,
			UP_NORMAL
		);
	}

	private static void putTerrainTriangle(
		GpuIntBuffer vb,
		GpuIntBuffer fb,
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
		int x2, int y2, int z2, float u2, float v2,
		int[] normal
	) {
		int faceIdx = fb.putFace(
			colorA, colorB, colorC,
			packedMaterialA, packedMaterialB, packedMaterialC,
			terrainDataA, terrainDataB, terrainDataC
		);
		vb.putVertex(x0, y0, z0, u0, v0, 0, normal[0], normal[2], normal[1], faceIdx);
		vb.putVertex(x1, y1, z1, u1, v1, 0, normal[0], normal[2], normal[1], faceIdx);
		vb.putVertex(x2, y2, z2, u2, v2, 0, normal[0], normal[2], normal[1], faceIdx);
	}
}
