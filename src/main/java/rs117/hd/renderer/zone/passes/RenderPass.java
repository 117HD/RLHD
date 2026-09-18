package rs117.hd.renderer.zone.passes;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;
import net.runelite.api.*;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.RenderState;

public interface RenderPass {
	RenderPassType[] TYPES = RenderPassType.values();

	RenderPassType getType();

	default void initialize() {}

	default void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {}

	default void destroyShaders() {}

	default void destroy() {}

	default void addShaderIncludes(ShaderIncludes includes) {}

	default void processConfigChanges(Set<String> keys) {}

	default boolean zoneInFrustum(Zone z, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return false;
	}

	default boolean dynamicInFrustum(WorldViewContext ctx, Renderable renderable, Model model, ModelOverride modelOverride, int x, int y, int z) {
		return false;
	}

	default void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {}

	default void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {}

	default void drawPass(WorldViewContext ctx, int pass) {}

	default void preSceneDraw(WorldViewContext ctx, boolean isTopLevel) {}

	default void postSceneDraw(WorldViewContext ctx) {}

	default void draw(RenderState renderState) {}

	default void postDraw(RenderState renderState) {}
}
