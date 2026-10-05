package rs117.hd.renderer.zone.passes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.hooks.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.SceneDepthResolveShaderProgram;
import rs117.hd.opengl.shader.SceneDepthShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_GREATER;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL13.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL30.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL32.GL_TEXTURE_2D_MULTISAMPLE;
import static org.lwjgl.opengl.GL31C.glBlitFramebuffer;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SCENE_OPAQUE_DEPTH;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PLAYER;

public class DepthPass implements RenderPass {

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ZoneRenderer renderer;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneDepthShaderProgram sceneDepthProgram;

	@Inject
	private SceneDepthResolveShaderProgram.ReverseZ reverseZSceneDepthResolveProgram;

	private final ArrayList<Zone> alphaZoneDraws = new ArrayList<>();

	private final CommandBuffer opaqueDepthCmd = new CommandBuffer("DepthPass::Opaque");
	private final CommandBuffer alphaDepthCmd = new CommandBuffer("DepthPass::Alpha");

	private boolean depthPassEnabled = true;
	private Camera sceneCamera;

	@Override
	public void initialize() {
		sceneCamera = renderer.sceneCamera;
		opaqueDepthCmd.setFrameTimer(frameTimer);
		alphaDepthCmd.setFrameTimer(frameTimer);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneDepthProgram.compile(includes);
		reverseZSceneDepthResolveProgram.compile(includes);
	}

	@Override
	public void processConfigChanges(Set<String> keys) {
		depthPassEnabled = config.depthPrePass();
	}

	@Override
	public void destroyShaders() {
		sceneDepthProgram.destroy();
		reverseZSceneDepthResolveProgram.destroy();
	}

	@Override
	public int preprocess() {
		opaqueDepthCmd.reset();
		alphaDepthCmd.reset();
		alphaZoneDraws.clear();
		return depthPassEnabled ? PASS_DEFAULT | PASS_SCENE_RENDERING : 0;
	}

	@Override
	public void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {
		if (!z.isVisible(sceneCamera))
			return;

		z.renderOpaque(opaqueDepthCmd, ctx, sceneCamera, false);
	}

	@Override
	public void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {
		if (!z.isVisible(sceneCamera) || level != 0 || z.sizeA == 0 || z.visibleAlphaModels.isEmpty())
			return;

		final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
		if (!isSquashed)
			alphaZoneDraws.add(ctx.zones[zx][zz]);
	}

	@Override
	public void drawPass(WorldViewContext ctx, int pass) {
		if (pass == DrawCallbacks.PASS_ALPHA) {
			ctx.drawAll(VAO_OPAQUE, opaqueDepthCmd);

			for(int i = alphaZoneDraws.size() - 1; i >= 0; i--) {
				final Zone zone = alphaZoneDraws.get(i);
				if(zone == null)
					continue;

				if (zone.hasWater)
					zone.renderOpaqueLevel(alphaDepthCmd, Zone.LEVEL_WATER_SURFACE);

				zone.renderAlpha(alphaDepthCmd, 0, 0, -1, ctx, sceneCamera, true, false); // TODO: Should really have a alpha depth draw function
			}
			ctx.drawAll(VAO_PLAYER, alphaDepthCmd);
			alphaZoneDraws.clear();
		}
	}

	@Override
	public void draw(RenderState renderState, int overlayColor) {
		renderState.program.set(sceneDepthProgram);
		renderState.drawFramebuffer.set(plugin.fboSceneDepth);
		renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		if(renderer.indirectDrawCmds != null)
			renderState.ido.set(renderer.indirectDrawCmds.id);

		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_BLEND);
		renderState.depthMask.set(true);
		renderState.depthFunc.set(GL_GREATER);
		renderState.colorMask.set(false, false, false, false);
		renderState.apply();

		opaqueDepthCmd.execute(renderState);

		// Alpha geometry is sorted back to front, so it would destroy the opaque depth ordering if it
		// shared the same buffer. It gets its own single-sampled buffer, which has to be cleared every
		// frame, even when nothing was drawn, so we never sample depth from a previous frame.
		renderState.drawFramebuffer.set(plugin.fboSceneAlphaDepth);
		renderState.disable.set(GL_MULTISAMPLE);
		renderState.clearDepth.set(0);
		renderState.apply();

		glClear(GL_DEPTH_BUFFER_BIT);

		alphaDepthCmd.execute(renderState);

		if (plugin.msaaSamples == 0) {
			renderState.readFramebuffer.set(plugin.fboSceneDepth);
			renderState.drawFramebuffer.set(plugin.fboSceneDepthResolve);
			renderState.apply();
			glBlitFramebuffer(
				0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1],
				0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1],
				GL_DEPTH_BUFFER_BIT, GL_NEAREST
			);
		} else {
			renderState.framebuffer.set(plugin.fboSceneDepthResolve);
			renderState.program.set(reverseZSceneDepthResolveProgram);
			renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
			renderState.vao.setVao(plugin.vaoTri);
			renderState.textureUnit.set(GL_TEXTURE_2D_MULTISAMPLE, TEXTURE_UNIT_SCENE_OPAQUE_DEPTH, plugin.texSceneDepthMultisample);
			renderState.enable.set(GL_DEPTH_TEST);
			renderState.depthFunc.set(GL_ALWAYS);
			renderState.disable.set(GL_CULL_FACE);
			renderState.disable.set(GL_BLEND);
			renderState.disable.set(GL_MULTISAMPLE);
			renderState.depthMask.set(true);
			renderState.colorMask.set(false, false, false, false);
			renderState.apply();
			reverseZSceneDepthResolveProgram.uniSampleCount.set(plugin.msaaSamples);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}
		checkGLErrors();
	}

	@Override
	public RenderPassType getType() { return RenderPassType.DEPTH_PASS; }
}
