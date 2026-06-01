package rs117.hd.renderer.zone.passes;

import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;

public interface RenderPass {
	default boolean zoneInFrustum(Zone z, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return false;
	}

	default void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {}

	default void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {}

	default void drawPass(WorldViewContext ctx, int pass) {}

	default void preTopLevelDraw() {}

	default void preSceneDraw() {}

	default void draw() {}

	default void postDraw() {}
}
