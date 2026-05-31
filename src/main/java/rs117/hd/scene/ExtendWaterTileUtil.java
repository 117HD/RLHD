package rs117.hd.scene;

import javax.annotation.Nullable;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import rs117.hd.scene.areas.Area;

import static net.runelite.api.Constants.CHUNK_SIZE;
import static net.runelite.api.Constants.SCENE_SIZE;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;

public final class ExtendWaterTileUtil {
	private ExtendWaterTileUtil() {}

	@Nullable
	public static Area getExtendWaterArea(SceneContext ctx) {
		if (!ctx.enableExtendWater)
			return null;
		if (ctx.extendWaterArea != null)
			return ctx.extendWaterArea;
		if (ctx.currentArea != null && ctx.currentArea.extendWater)
			return ctx.currentArea;
		return null;
	}

	public static boolean isEnabled(SceneContext ctx) {
		if (!ctx.enableExtendWater)
			return false;

		Area area = getExtendWaterArea(ctx);
		return ctx.sceneBase != null &&
			area != null &&
			area.extendWater &&
			area.extendWaterReferenceTile != null &&
			area.extendWaterReferenceTile.length >= 2;
	}

	public static int getExtendWaterPlane(SceneContext ctx) {
		Area area = getExtendWaterArea(ctx);
		if (area == null || area.extendWaterReferenceTile == null)
			return 0;
		int[] ref = area.extendWaterReferenceTile;
		return ref.length > 2 ? ref[2] : 0;
	}

	public static boolean isWithinExtendWaterBounds(SceneContext ctx, int tileExX, int tileExY, int plane) {
		Area area = getExtendWaterArea(ctx);
		if (area == null)
			return false;
		int[] worldPos = ctx.extendedSceneToWorld(tileExX, tileExY, plane);
		return area.containsPoint(false, worldPos[0], worldPos[1], worldPos[2]);
	}

	/**
	 * The loaded map region including extended map loading chunks. Horizon tiles must stay
	 * within this region — the extended scene buffer is larger, but only this area has map data.
	 */
	public static boolean isWithinHorizonRange(SceneContext ctx, int tileExX, int tileExY) {
		if (ctx.sceneBase == null)
			return false;

		int tileX = tileExX - ctx.sceneOffset;
		int tileY = tileExY - ctx.sceneOffset;
		int sceneMin = -ctx.expandedMapLoadingChunks * CHUNK_SIZE;
		int sceneMax = SCENE_SIZE + ctx.expandedMapLoadingChunks * CHUNK_SIZE;
		return tileX >= sceneMin && tileY >= sceneMin && tileX <= sceneMax && tileY <= sceneMax;
	}

	public static boolean isHiddenGroundTile(Tile tile) {
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

	private static boolean computeShouldExtendWaterAtTile(SceneContext ctx, int tileExX, int tileExY, int plane) {
		if (!isWithinHorizonRange(ctx, tileExX, tileExY))
			return false;
		return !isWithinExtendWaterBounds(ctx, tileExX, tileExY, plane);
	}

	public static void buildHorizonTileMask(SceneContext ctx) {
		if (!isEnabled(ctx)) {
			ctx.horizonTileMask = null;
			return;
		}

		int plane = getExtendWaterPlane(ctx);
		boolean[][] mask = new boolean[ctx.sizeX][ctx.sizeZ];
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int y = 0; y < ctx.sizeZ; ++y) {
				mask[x][y] = computeShouldExtendWaterAtTile(ctx, x, y, plane);
			}
		}
		ctx.horizonTileMask = mask;
	}

	/**
	 * Horizon tiles fill empty space outside the area AABB up to the edge of the loaded map.
	 */
	public static boolean shouldExtendWaterAtTile(SceneContext ctx, int tileExX, int tileExY, int plane) {
		if (!isEnabled(ctx) || plane != getExtendWaterPlane(ctx))
			return false;
		if (tileExX < 0 || tileExY < 0 || tileExX >= ctx.sizeX || tileExY >= ctx.sizeZ)
			return false;

		boolean[][] mask = ctx.horizonTileMask;
		if (mask != null)
			return mask[tileExX][tileExY];

		return computeShouldExtendWaterAtTile(ctx, tileExX, tileExY, plane);
	}
}
