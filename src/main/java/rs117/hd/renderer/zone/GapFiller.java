package rs117.hd.renderer.zone;

import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Tile;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Constants.CHUNK_SIZE;
import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;

public class GapFiller {

	private static final int[] UP_NORMAL = { 0, -1, 0 };
	private static final int[] SOUTH_NORMAL = { 0, 0, 1 };
	private static final int[] EAST_NORMAL = { -1, 0, 0 };
	private static final int[] NORTH_NORMAL = { 0, 0, -1 };
	private static final int[] WEST_NORMAL = { 1, 0, 0 };
	private static final int GAP_DEPTH_OFFSET = 1900;
	private static final int GAP_EXPAND_MARGIN = 40;
	private static final int ZONE_SIZE = 8;
	private static final int RENDER_LEVEL = 0;
	private static final int COLOR_BLACK = 0;

	@Inject
	private Client client;

	@Inject
	private MaterialManager materialManager;

	private final int[] worldPos = new int[3];

	public void uploadZoneGapTiles(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		GpuIntBuffer vb,
		GpuIntBuffer fb,
		Tile[][][] tiles,
		int[][][] tileHeights,
		int basex,
		int basez,
		int[][] vertices,
		int[] vertexKeys
	) {
		if (!ctx.fillGaps || ctx.sceneBase == null || (ctx.currentArea != null && !ctx.currentArea.fillGaps))
			return;

		int sceneMin = -ctx.expandedMapLoadingChunks * CHUNK_SIZE;
		int sceneMax = SCENE_SIZE + ctx.expandedMapLoadingChunks * CHUNK_SIZE;
		int[] regions = client.getMapRegions();
		Area area = ctx.currentArea;
		int baseExX = ctx.sceneBase[0];
		int baseExY = ctx.sceneBase[1];
		int basePlane = ctx.sceneBase[2];

		Material black = materialManager.getMaterial("BLACK");
		int materialData = black.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
		int terrainData = HDUtils.packTerrainData(true, 0, WaterType.NONE, RENDER_LEVEL);

		// Pass 1: emit model hidden-face drops and mark flat-gap tiles
		boolean[][] flatGap = new boolean[ZONE_SIZE][ZONE_SIZE];
		for (int xoff = 0; xoff < ZONE_SIZE; xoff++) {
			for (int zoff = 0; zoff < ZONE_SIZE; zoff++) {
				int tileExX = (mzx << 3) + xoff;
				int tileExY = (mzz << 3) + zoff;
				if (!inGapRegion(ctx, area, baseExX, baseExY, basePlane, tileExX, tileExY, sceneMin, sceneMax, regions))
					continue;

				Tile tile = tiles[0][tileExX][tileExY];
				if (tile == null) {
					flatGap[xoff][zoff] = true;
					continue;
				}

				if (tile.getSceneTileModel() != null) {
					uploadModelHiddenFaces(ctx, tile, tileExX, tileExY, vb, fb, basex, basez, materialData, terrainData, vertices, vertexKeys);
					continue;
				}

				if (hasVisiblePaint(tile))
					continue;

				flatGap[xoff][zoff] = true;
			}
		}

		// Pass 2: emit one box (bottom + 4 sides) per flat-gap tile
		for (int xoff = 0; xoff < ZONE_SIZE; xoff++) {
			for (int zoff = 0; zoff < ZONE_SIZE; zoff++) {
				if (!flatGap[xoff][zoff])
					continue;

				int tileExX = (mzx << 3) + xoff;
				int tileExY = (mzz << 3) + zoff;
				int lx0 = (tileExX - ctx.sceneOffset) * LOCAL_TILE_SIZE - basex - GAP_EXPAND_MARGIN;
				int lz0 = (tileExY - ctx.sceneOffset) * LOCAL_TILE_SIZE - basez - GAP_EXPAND_MARGIN;
				int lx1 = (tileExX + 1 - ctx.sceneOffset) * LOCAL_TILE_SIZE - basex + GAP_EXPAND_MARGIN;
				int lz1 = (tileExY + 1 - ctx.sceneOffset) * LOCAL_TILE_SIZE - basez + GAP_EXPAND_MARGIN;

				int swT = tileHeights[RENDER_LEVEL][tileExX][tileExY];
				int seT = tileHeights[RENDER_LEVEL][tileExX + 1][tileExY];
				int neT = tileHeights[RENDER_LEVEL][tileExX + 1][tileExY + 1];
				int nwT = tileHeights[RENDER_LEVEL][tileExX][tileExY + 1];
				int swB = swT + GAP_DEPTH_OFFSET;
				int seB = seT + GAP_DEPTH_OFFSET;
				int neB = neT + GAP_DEPTH_OFFSET;
				int nwB = nwT + GAP_DEPTH_OFFSET;

				putQuadYUp(vb, fb, lx0, lz0, lx1, lz1, swB, seB, neB, nwB, materialData, terrainData);
				if (isSolidGround(tiles, tileExX, tileExY - 1))
					putSide(vb, fb, lx0, lz0, swB, swT, lx1, lz0, seB, seT, SOUTH_NORMAL, materialData, terrainData);
				if (isSolidGround(tiles, tileExX + 1, tileExY))
					putSide(vb, fb, lx1, lz0, seB, seT, lx1, lz1, neB, neT, WEST_NORMAL, materialData, terrainData);
				if (isSolidGround(tiles, tileExX, tileExY + 1))
					putSide(vb, fb, lx1, lz1, neB, neT, lx0, lz1, nwB, nwT, NORTH_NORMAL, materialData, terrainData);
				if (isSolidGround(tiles, tileExX - 1, tileExY))
					putSide(vb, fb, lx0, lz1, nwB, nwT, lx0, lz0, swB, swT, EAST_NORMAL, materialData, terrainData);
			}
		}
	}

	/** Emit model hidden faces: bottom triangle at depth + 3 vertical drop quads. */
	private void uploadModelHiddenFaces(
		ZoneSceneContext ctx,
		Tile tile,
		int tileExX,
		int tileExY,
		GpuIntBuffer vb,
		GpuIntBuffer fb,
		int basex,
		int basez,
		int materialData,
		int terrainData,
		int[][] vertices,
		int[] vertexKeys
	) {
		net.runelite.api.SceneTileModel model = tile.getSceneTileModel();
		int[] triangleColorA = model.getTriangleColorA();
		int faceCount = triangleColorA.length;
		int terrainDataTile = HDUtils.packTerrainData(true, 0, WaterType.NONE, tile.getRenderLevel());
		int tileBaseX = (tileExX - ctx.sceneOffset) * LOCAL_TILE_SIZE - basex;
		int tileBaseZ = (tileExY - ctx.sceneOffset) * LOCAL_TILE_SIZE - basez;
		Map<Integer, int[]> normals = ctx.vertexTerrainNormals;

		for (int f = 0; f < faceCount; f++) {
			if (triangleColorA[f] != HIDDEN_HSL)
				continue;

			ProceduralGenerator.faceVertexKeys(tile, f, vertices, vertexKeys);
			ProceduralGenerator.faceLocalVertices(tile, f, vertices);

			int lx0 = tileBaseX + vertices[0][0], ly0 = vertices[0][2], lz0 = tileBaseZ + vertices[0][1];
			int lx1 = tileBaseX + vertices[1][0], ly1 = vertices[1][2], lz1 = tileBaseZ + vertices[1][1];
			int lx2 = tileBaseX + vertices[2][0], ly2 = vertices[2][2], lz2 = tileBaseZ + vertices[2][1];

			if (isHorizontalFace(lx0, ly0, lz0, lx1, ly1, lz1, lx2, ly2, lz2))
				continue;

			int ly0B = ly0 + GAP_DEPTH_OFFSET, ly1B = ly1 + GAP_DEPTH_OFFSET, ly2B = ly2 + GAP_DEPTH_OFFSET;
			int[] nA = normals.getOrDefault(vertexKeys[0], UP_NORMAL);
			int[] nB = normals.getOrDefault(vertexKeys[1], UP_NORMAL);
			int[] nC = normals.getOrDefault(vertexKeys[2], UP_NORMAL);

			int fi = fb.putFace(COLOR_BLACK, COLOR_BLACK, COLOR_BLACK, materialData, materialData, materialData, terrainDataTile, terrainDataTile, terrainDataTile);
			vb.putVertex(lx0, ly0B, lz0, 0, 0, 0, nA[0], nA[2], nA[1], fi);
			vb.putVertex(lx2, ly2B, lz2, 0, 0, 0, nC[0], nC[2], nC[1], fi);
			vb.putVertex(lx1, ly1B, lz1, 0, 0, 0, nB[0], nB[2], nB[1], fi);

			putSide(vb, fb, lx0, lz0, ly0B, ly0, lx1, lz1, ly1B, ly1, SOUTH_NORMAL, materialData, terrainDataTile);
			putSide(vb, fb, lx1, lz1, ly1B, ly1, lx2, lz2, ly2B, ly2, SOUTH_NORMAL, materialData, terrainDataTile);
			putSide(vb, fb, lx2, lz2, ly2B, ly2, lx0, lz0, ly0B, ly0, SOUTH_NORMAL, materialData, terrainDataTile);
		}
	}

	/** Horizontal quad (Y-up): two triangles for bottom face. */
	private void putQuadYUp(GpuIntBuffer vb, GpuIntBuffer fb,
		int lx0, int lz0, int lx1, int lz1,
		int swB, int seB, int neB, int nwB,
		int materialData, int terrainData
	) {
		int u0 = UP_NORMAL[0], u1 = UP_NORMAL[2], u2 = UP_NORMAL[1];
		int fi = fb.putFace(COLOR_BLACK, COLOR_BLACK, COLOR_BLACK, materialData, materialData, materialData, terrainData, terrainData, terrainData);
		vb.putVertex(lx1, neB, lz1, 0, 0, 0, u0, u1, u2, fi);
		vb.putVertex(lx0, nwB, lz1, 0, 0, 0, u0, u1, u2, fi);
		vb.putVertex(lx1, seB, lz0, 0, 0, 0, u0, u1, u2, fi);
		fi = fb.putFace(COLOR_BLACK, COLOR_BLACK, COLOR_BLACK, materialData, materialData, materialData, terrainData, terrainData, terrainData);
		vb.putVertex(lx0, swB, lz0, 0, 0, 0, u0, u1, u2, fi);
		vb.putVertex(lx1, seB, lz0, 0, 0, 0, u0, u1, u2, fi);
		vb.putVertex(lx0, nwB, lz1, 0, 0, 0, u0, u1, u2, fi);
	}

	/** Vertical quad from (x0,z0) to (x1,z1), bottom-to-top. */
	private void putSide(GpuIntBuffer vb, GpuIntBuffer fb,
		int x0, int z0, int bot0, int top0,
		int x1, int z1, int bot1, int top1,
		int[] outwardNormal,
		int materialData, int terrainData
	) {
		int dx = x1 - x0, dz = z1 - z0, dy = top0 - bot0;
		int nx = -dz * dy, nz = dx * dy;
		int snx, sny, snz;
		if (nx == 0 && nz == 0) {
			snx = -outwardNormal[0];
			sny = -outwardNormal[2];
			snz = -outwardNormal[1];
		} else {
			int dot = nx * outwardNormal[0] + nz * outwardNormal[2];
			if (dot > 0) {
				nx = -nx;
				nz = -nz;
			}
			snx = nx;
			sny = nz;
			snz = 0;
		}
		int fi = fb.putFace(COLOR_BLACK, COLOR_BLACK, COLOR_BLACK, materialData, materialData, materialData, terrainData, terrainData, terrainData);
		vb.putVertex(x0, bot0, z0, 0, 0, 0, snx, sny, snz, fi);
		vb.putVertex(x1, top1, z1, 0, 0, 0, snx, sny, snz, fi);
		vb.putVertex(x1, bot1, z1, 0, 0, 0, snx, sny, snz, fi);
		fi = fb.putFace(COLOR_BLACK, COLOR_BLACK, COLOR_BLACK, materialData, materialData, materialData, terrainData, terrainData, terrainData);
		vb.putVertex(x0, bot0, z0, 0, 0, 0, snx, sny, snz, fi);
		vb.putVertex(x0, top0, z0, 0, 0, 0, snx, sny, snz, fi);
		vb.putVertex(x1, top1, z1, 0, 0, 0, snx, sny, snz, fi);
	}

	private boolean inGapRegion(ZoneSceneContext ctx, Area area, int baseExX, int baseExY, int basePlane,
		int tileExX, int tileExY, int sceneMin, int sceneMax, int[] regions) {
		int tileX = tileExX - ctx.sceneOffset;
		int tileY = tileExY - ctx.sceneOffset;
		if (tileX <= sceneMin || tileY <= sceneMin || tileX >= sceneMax - 1 || tileY >= sceneMax - 1)
			return false;
		if (area != null && !area.containsPoint(baseExX + tileExX, baseExY + tileExY, basePlane))
			return false;
		ctx.sceneToWorld(tileX, tileY, 0, worldPos);
		if (!Area.OVERWORLD.containsPoint(worldPos))
			return false;
		int regionId = HDUtils.worldToRegionID(worldPos);
		for (int r : regions) {
			if (r == regionId)
				return true;
		}
		return false;
	}

	private boolean hasVisiblePaint(Tile tile) {
		if (tile == null)
			return false;
		net.runelite.api.SceneTilePaint p = tile.getSceneTilePaint();
		if (p != null && p.getNeColor() != HIDDEN_HSL)
			return true;
		Tile bridge = tile.getBridge();
		if (bridge != null) {
			p = bridge.getSceneTilePaint();
			if (p != null && p.getNeColor() != HIDDEN_HSL)
				return true;
		}
		return false;
	}

	private boolean isSolidGround(Tile[][][] tiles, int tileExX, int tileExY) {
		if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
			return false;
		Tile t = tiles[0][tileExX][tileExY];
		if (t == null)
			return false;
		if (t.getSceneTileModel() != null)
			return true;
		net.runelite.api.SceneTilePaint p = t.getSceneTilePaint();
		return p != null && p.getNeColor() != HIDDEN_HSL;
	}

	private static boolean isHorizontalFace(int x0, int y0, int z0, int x1, int y1, int z1, int x2, int y2, int z2) {
		int ex = x1 - x0, ey = y1 - y0, ez = z1 - z0;
		int fx = x2 - x0, fy = y2 - y0, fz = z2 - z0;
		int nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
		int ay = ny < 0 ? -ny : ny, ax = nx < 0 ? -nx : nx, az = nz < 0 ? -nz : nz;
		return ay > ax + az;
	}
}
