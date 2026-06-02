package rs117.hd.renderer.zone.passes;

import java.util.Comparator;
import java.util.Set;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.utils.RenderState;

public interface RenderPass {

	default int order() {
		return 0;
	}

	default String passName() {
		return getClass().getSimpleName();
	}

	default void initialize(RenderState renderState) {}

	default void initializeShaders(ShaderIncludes includes) {}

	default void destroyShaders() {}

	default void destroy() {}

	default void addShaderIncludes(ShaderIncludes includes) {}

	default void processConfigChanges(Set<String> keys) {}

	default boolean zoneInFrustum(Zone z, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return false;
	}

	default void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {}

	default void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {}

	default void drawPass(WorldViewContext ctx, int pass) {}

	default void preSceneDraw(WorldViewContext ctx) {}

	default void postSceneDraw(WorldViewContext ctx) {}

	default void draw(RenderState renderState) {}

	default void postDraw(RenderState renderState) {}

	Comparator<RenderPass> ORDER_COMPARATOR = Comparator.comparingInt(RenderPass::order);
}
