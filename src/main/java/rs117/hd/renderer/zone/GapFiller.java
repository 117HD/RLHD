package rs117.hd.renderer.zone;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;

@Singleton
public class GapFiller {
	private static final int TILES_PER_ZONE = CHUNK_SIZE;

	@Inject
	private MaterialManager materialManager;

	public void estimateForZone(ZoneSceneContext ctx, Zone zone, int mzx, int mzz) {
		zone.hasGapFiller = false;
		if (!prepareContext(ctx))
			return;

		Area area = ctx.currentArea;
		int sceneMin = -ctx.expandedMapLoadingChunks * CHUNK_SIZE;
		int sceneMax = SCENE_SIZE + ctx.expandedMapLoadingChunks * CHUNK_SIZE;
		int baseExX = ctx.sceneBase[0];
		int baseExY = ctx.sceneBase[1];
		int basePlane = ctx.sceneBase[2];
		Tile[][][] extendedTiles = ctx.scene.getExtendedTiles();

		for (int xoff = 0; xoff < TILES_PER_ZONE; ++xoff) {
			for (int zoff = 0; zoff < TILES_PER_ZONE; ++zoff) {
				int tileExX = (mzx << 3) + xoff;
				int tileExY = (mzz << 3) + zoff;
				int faces = processGapTile(ctx, area, extendedTiles, null, tileExX, tileExY, sceneMin, sceneMax, baseExX, baseExY, basePlane, null, 0, 0, null, null);
				if (faces > 0) {
					zone.hasGapFiller = true;
					zone.sizeO += faces;
					zone.sizeF += faces;
				}
			}
		}
	}

	public void uploadForZone(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		int basex = (mzx - (ctx.sceneOffset >> 3)) << 10;
		int basez = (mzz - (ctx.sceneOffset >> 3)) << 10;
		int[][][] tileHeights = ctx.scene.getTileHeights();
		Material blackMaterial = materialManager.getMaterial("BLACK");

		int posBefore = vb.position();
		if (prepareContext(ctx)) {
			Area area = ctx.currentArea;
			int sceneMin = -ctx.expandedMapLoadingChunks * CHUNK_SIZE;
			int sceneMax = SCENE_SIZE + ctx.expandedMapLoadingChunks * CHUNK_SIZE;
			int baseExX = ctx.sceneBase[0];
			int baseExY = ctx.sceneBase[1];
			int basePlane = ctx.sceneBase[2];
			Tile[][][] extendedTiles = ctx.scene.getExtendedTiles();

			for (int xoff = 0; xoff < TILES_PER_ZONE; ++xoff) {
				for (int zoff = 0; zoff < TILES_PER_ZONE; ++zoff) {
					int tileExX = (mzx << 3) + xoff;
					int tileExY = (mzz << 3) + zoff;
					processGapTile(
						ctx,
						area,
						extendedTiles,
						tileHeights,
						tileExX,
						tileExY,
						sceneMin,
						sceneMax,
						baseExX,
						baseExY,
						basePlane,
						blackMaterial,
						basex,
						basez,
						vb,
						fb
					);
				}
			}
		}
		zone.hasGapFiller = vb.position() > posBefore;
	}

	public void drawGapFillers(CommandBuffer cmd, WorldViewContext ctx) {
		if (!ctx.sceneContext.fillGaps)
			return;

		cmd.DepthMask(false);
		cmd.Disable(GL_DEPTH_TEST);
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];
				if (zone.initialized && zone.hasGapFiller)
					zone.renderOpaqueLevel(cmd, Zone.LEVEL_GAP_FILLER);
			}
		}
		cmd.Enable(GL_DEPTH_TEST);
		cmd.DepthMask(true);
	}

	private boolean prepareContext(ZoneSceneContext ctx) {
		if (ctx.sceneBase == null)
			return false;

		resolveCurrentArea(ctx);
		Area area = ctx.currentArea;
		return area == null || area.fillGaps;
	}

	private void resolveCurrentArea(ZoneSceneContext ctx) {
		if (!ctx.enableAreaHiding) {
			ctx.currentArea = null;
			return;
		}

		assert ctx.sceneBase != null;
		Player localPlayer = ctx.client.getLocalPlayer();
		if (localPlayer == null)
			return;

		LocalPoint lp = localPlayer.getLocalLocation();
		int[] worldPos = {
			ctx.sceneBase[0] + lp.getSceneX(),
			ctx.sceneBase[1] + lp.getSceneY(),
			ctx.sceneBase[2] + ctx.client.getTopLevelWorldView().getPlane()
		};

		if (ctx.currentArea == null || !ctx.currentArea.containsPoint(false, worldPos)) {
			ctx.currentArea = null;
			for (Area possibleArea : ctx.possibleAreas) {
				if (possibleArea.containsPoint(false, worldPos)) {
					ctx.currentArea = possibleArea;
					break;
				}
			}
		}
	}

	/**
	 * @return face count if the tile should be gap-filled, otherwise 0
	 */
	private int processGapTile(
		ZoneSceneContext ctx,
		@Nullable Area area,
		Tile[][][] extendedTiles,
		@Nullable int[][][] tileHeights,
		int tileExX,
		int tileExY,
		int sceneMin,
		int sceneMax,
		int baseExX,
		int baseExY,
		int basePlane,
		@Nullable Material blackMaterial,
		int basex,
		int basez,
		@Nullable GpuIntBuffer vb,
		@Nullable GpuIntBuffer fb
	) {
		if (tileExX < 0 || tileExY < 0 ||
			tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
			return 0;

		int tileX = tileExX - ctx.sceneOffset;
		int tileY = tileExY - ctx.sceneOffset;

		if (area != null && !area.containsPoint(baseExX + tileX, baseExY + tileY, basePlane))
			return 0;

		Tile tile = extendedTiles[0][tileExX][tileExY];

		SceneTilePaint paint;
		SceneTileModel model = null;
		int renderLevel = 0;
		if (tile != null) {
			renderLevel = tile.getRenderLevel();
			paint = tile.getSceneTilePaint();
			model = tile.getSceneTileModel();

			if (model == null) {
				boolean hasTilePaint = paint != null && paint.getNeColor() != HIDDEN_HSL;
				if (!hasTilePaint) {
					tile = tile.getBridge();
					if (tile != null) {
						renderLevel = tile.getRenderLevel();
						paint = tile.getSceneTilePaint();
						model = tile.getSceneTileModel();
						hasTilePaint = paint != null && paint.getNeColor() != HIDDEN_HSL;
					}
				}

				if (hasTilePaint)
					return 0;
			}
		}

		int[] worldPoint = ctx.sceneToWorld(tileX, tileY, 0);
		boolean shouldFill =
			tileX > sceneMin &&
			tileY > sceneMin &&
			tileX < sceneMax - 1 &&
			tileY < sceneMax - 1 &&
			Area.OVERWORLD.containsPoint(worldPoint);

		if (shouldFill) {
			int tileRegionID = HDUtils.worldToRegionID(worldPoint);
			int[] regions = ctx.scene.getMapRegions();

			shouldFill = false;
			for (int region : regions) {
				if (region == tileRegionID) {
					shouldFill = true;
					break;
				}
			}

			if (!shouldFill && ctx.expandedMapLoadingChunks > 0)
				shouldFill = true;
		}

		if (!shouldFill)
			return 0;

		// Custom gap tiles sample heights at tileExX+1/tileExY+1
		if (model == null && (tileExX >= EXTENDED_SCENE_SIZE - 1 || tileExY >= EXTENDED_SCENE_SIZE - 1))
			return 0;

		int faces = model == null ? 2 : model.getFaceX().length;
		if (vb != null) {
			assert tileHeights != null && blackMaterial != null && fb != null;
			if (model == null) {
				uploadCustomTile(tileHeights, tileExX, tileExY, renderLevel, blackMaterial, tileX, tileY, basex, basez, vb, fb);
			} else if (tile != null) {
				uploadGapFillTileModel(tile, model, basex, basez, vb, fb);
			}
		}
		return faces;
	}

	private void uploadGapFillTileModel(
		Tile tile,
		SceneTileModel model,
		int basex,
		int basez,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		int tileZ = tile.getRenderLevel();
		Material black = materialManager.getMaterial("BLACK");
		int packedMaterial = black.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);
		int terrainData = HDUtils.packTerrainData(true, 0, WaterType.NONE, tileZ);

		final int[] faceX = model.getFaceX();
		final int[] faceY = model.getFaceY();
		final int[] faceZ = model.getFaceZ();
		final int[] vertexX = model.getVertexX();
		final int[] vertexY = model.getVertexY();
		final int[] vertexZ = model.getVertexZ();
		final int[] triangleColorA = model.getTriangleColorA();

		for (int face = 0; face < faceX.length; ++face) {
			if (triangleColorA[face] != HIDDEN_HSL)
				continue;

			int v0 = faceX[face];
			int v1 = faceY[face];
			int v2 = faceZ[face];

			putTerrainTriangle(
				vb, fb,
				0, 0, 0,
				packedMaterial, terrainData,
				vertexX[v0] - basex, vertexY[v0], vertexZ[v0] - basez, 0, 0,
				vertexX[v1] - basex, vertexY[v1], vertexZ[v1] - basez, 0, 0,
				vertexX[v2] - basex, vertexY[v2], vertexZ[v2] - basez, 0, 0
			);
		}
	}

	private static void putTerrainTriangle(
		GpuIntBuffer vb,
		GpuIntBuffer fb,
		int colorA,
		int colorB,
		int colorC,
		int packedMaterial,
		int terrainData,
		int x0, int y0, int z0, float u0, float v0,
		int x1, int y1, int z1, float u1, float v1,
		int x2, int y2, int z2, float u2, float v2
	) {
		int faceIdx = fb.putFace(
			colorA, colorB, colorC,
			packedMaterial, packedMaterial, packedMaterial,
			terrainData, terrainData, terrainData
		);
		vb.putVertex(x0, y0, z0, u0, v0, 0, 0, -1, 0, faceIdx);
		vb.putVertex(x1, y1, z1, u1, v1, 0, 0, -1, 0, faceIdx);
		vb.putVertex(x2, y2, z2, u2, v2, 0, 0, -1, 0, faceIdx);
	}

	private void uploadCustomTile(
		int[][][] tileHeights,
		int tileExX,
		int tileExY,
		int tileZ,
		Material material,
		int tileX,
		int tileY,
		int zoneBasex,
		int zoneBasez,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		int swHeight = tileHeights[tileZ][tileExX][tileExY];
		int seHeight = tileHeights[tileZ][tileExX + 1][tileExY];
		int neHeight = tileHeights[tileZ][tileExX + 1][tileExY + 1];
		int nwHeight = tileHeights[tileZ][tileExX][tileExY + 1];

		int terrainData = HDUtils.packTerrainData(true, 0, WaterType.NONE, tileZ);
		int packedMaterial = material.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, false);

		int baseX = tileX * LOCAL_TILE_SIZE - zoneBasex;
		int baseZ = tileY * LOCAL_TILE_SIZE - zoneBasez;

		putTerrainTriangle(
			vb, fb,
			0, 0, 0,
			packedMaterial, terrainData,
			baseX + LOCAL_TILE_SIZE, neHeight, baseZ + LOCAL_TILE_SIZE, 0, 0,
			baseX, nwHeight, baseZ + LOCAL_TILE_SIZE, 1, 0,
			baseX + LOCAL_TILE_SIZE, seHeight, baseZ, 0, 1
		);
		putTerrainTriangle(
			vb, fb,
			0, 0, 0,
			packedMaterial, terrainData,
			baseX, swHeight, baseZ, 1, 1,
			baseX + LOCAL_TILE_SIZE, seHeight, baseZ, 0, 1,
			baseX, nwHeight, baseZ + LOCAL_TILE_SIZE, 1, 0
		);
	}
}
