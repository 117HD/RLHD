package rs117.hd.renderer.zone.passes;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.hooks.*;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.zone.ModelStreamingManager;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.SceneCullingManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11.GL_GEQUAL;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_ZERO;
import static org.lwjgl.opengl.GL13C.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PLAYER;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PRESCENE;

@Slf4j
@Singleton
public class ScenePass implements RenderPass {

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer renderer;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneCullingManager sceneCullingManager;

	@Inject
	private ModelStreamingManager modelStreamingManager;

	@Inject
	private SceneShaderProgram sceneProgram;

	public final CommandBuffer sceneCmd = new CommandBuffer("Scene");
	public final CommandBuffer gapFillerCmd = new CommandBuffer("GapFiller");

	private Camera sceneCamera;

	@Override
	public void initialize() {
		sceneCamera = renderer.sceneCamera;

		sceneCullingManager.addCamera(sceneCamera);

		sceneCmd.setFrameTimer(frameTimer);
		gapFillerCmd.setFrameTimer(frameTimer);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneProgram.compile(includes);
	}

	@Override
	public void destroy() {
		sceneCullingManager.removeCamera(sceneCamera);
	}

	@Override
	public void destroyShaders() {
		sceneProgram.destroy();
	}

	@Override
	public void preSceneDraw(WorldViewContext ctx, boolean isTopLevel) {
		final Scene scene = ctx.sceneContext.scene;
		if(scene.getWorldViewId() != WorldView.TOPLEVEL)
			return;

		gapFillerCmd.reset();
		sceneCmd.reset();

		Model skybox = scene.getSkybox();
		if (skybox != null) {
			skybox.calculateBoundsCylinder();
			modelStreamingManager.uploadTempModel(
				ctx,
				sceneCamera,
				null,
				skybox,
				ModelOverride.UNLIT,
				skybox,
				null,
				null,
				true,
				VAO_PRESCENE,
				-1,
				0,
				sceneCamera.getPositionX(), sceneCamera.getPositionY(), sceneCamera.getPositionZ()
			);
		}

		sceneCmd.DepthMask(false);
		ctx.drawAll(VAO_PRESCENE, sceneCmd);
		sceneCmd.DepthMask(true);
	}

	@Override
	public void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {
		if (sceneManager.isRoot(ctx) && !z.isVisible(sceneCamera))
			return;

		z.renderOpaque(sceneCmd, ctx, sceneCamera, false);

		if (z.hasGapFiller)
			z.renderOpaqueLevel(gapFillerCmd, Zone.LEVEL_GAP_FILLER);
	}

	@Override
	public void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {
		if (sceneManager.isRoot(ctx) && !z.isVisible(sceneCamera))
			return;

		final boolean renderWater = level == 0 && z.hasWater;
		if (renderWater)
			z.renderOpaqueLevel(sceneCmd, Zone.LEVEL_WATER_SURFACE);

		if (z.sizeA != 0 || !z.visibleAlphaModels.isEmpty()) {
			final int offset = ctx.sceneContext.sceneOffset >> 3;

			if (renderWater) {
				// Water is currently drawn with depth writes & depth testing enabled, and as such, alpha models and the water plane
				// can Z-fight depending on draw order. To avoid alpha models above water causing the water surface to fail its
				// depth test, we disable depth writes for alpha models and rely on correct back to front ordering of the zones
				sceneCmd.DepthMask(false);
				z.renderAlpha(sceneCmd, zx - offset, zz - offset, level, ctx, sceneCamera, false, false);
				sceneCmd.DepthMask(true);
			} else {
				// Draw alpha models in two passes, first blending colors correctly, then writing depth for subsequent opaque models
				// to test against. This is necessary because opaque models on higher planes can be drawn later

				// Write color without depth writes
				sceneCmd.DepthMask(false);
				sceneCmd.ColorMask(true, true, true, true);
				z.renderAlpha(sceneCmd, zx - offset, zz - offset, level, ctx, sceneCamera, false, false);

				// Write depth without color
				sceneCmd.DepthMask(true);
				sceneCmd.ColorMask(false, false, false, false);
				z.renderAlpha(sceneCmd, zx - offset, zz - offset, level, ctx, sceneCamera, true, false);

				// Restore color writes
				sceneCmd.ColorMask(true, true, true, true);
			}
		}
	}

	@Override
	public void drawPass(WorldViewContext ctx, int pass) {
		if (pass == DrawCallbacks.PASS_OPAQUE) {
			sceneCmd.ExecuteSubCommandBuffer(ctx.vaoSceneCmd);
		} else if (pass == DrawCallbacks.PASS_ALPHA) {
			// Draw opaque
			ctx.drawAll(VAO_OPAQUE, ctx.vaoSceneCmd);

			// Draw players with sorted alpha, without writing depth
			ctx.vaoSceneCmd.DepthMask(false);
			ctx.drawAll(VAO_PLAYER, ctx.vaoSceneCmd);
			ctx.vaoSceneCmd.DepthMask(true);

			// Redraw players, this time only writing depth, for correct ordering with the background
			ctx.vaoSceneCmd.ColorMask(false, false, false, false);
			ctx.drawAll(VAO_PLAYER, ctx.vaoSceneCmd);
			ctx.vaoSceneCmd.ColorMask(true, true, true, true);
		}
	}

	@Override
	public void draw(RenderState renderState) {
		sceneProgram.use();

		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		if (plugin.msaaSamples > 1) {
			renderState.enable.set(GL_MULTISAMPLE);
		} else {
			renderState.disable.set(GL_MULTISAMPLE);
		}
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		if(renderer.indirectDrawCmds != null)
			renderState.ido.set(renderer.indirectDrawCmds.id);

		renderState.enable.set(GL_BLEND);
		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

		if (!gapFillerCmd.isEmpty()) {
			renderState.depthMask.set(false);
			gapFillerCmd.execute(renderState);
			renderState.depthMask.set(true);
		}

		sceneCmd.execute(renderState);

		glBindVertexArray(0);

		// Done rendering the scene
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.apply();
	}

	@Override
	public RenderPassType getType() { return RenderPassType.SCENE; }
}
