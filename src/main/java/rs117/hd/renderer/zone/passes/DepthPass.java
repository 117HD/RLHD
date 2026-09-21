package rs117.hd.renderer.zone.passes;

import java.io.IOException;
import javax.inject.Inject;
import net.runelite.api.hooks.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.SceneDepthShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_GREATER;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL13.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;

public class DepthPass implements RenderPass {

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ZoneRenderer renderer;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneDepthShaderProgram sceneDepthProgram;

	public final CommandBuffer opaqueDepthCmd = new CommandBuffer("DepthPass");

	private Camera sceneCamera;

	@Override
	public void initialize() {
		sceneCamera = renderer.sceneCamera;
		opaqueDepthCmd.setFrameTimer(frameTimer);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneDepthProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		sceneDepthProgram.destroy();
	}

	@Override
	public void preSceneDraw(WorldViewContext ctx, boolean isTopLevel) {
		opaqueDepthCmd.reset();
	}

	@Override
	public void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {
		if (sceneManager.isRoot(ctx) && !z.isVisible(sceneCamera))
			return;

		z.renderOpaque(opaqueDepthCmd, ctx, sceneCamera, false);
	}

	@Override
	public void drawPass(WorldViewContext ctx, int pass) {
		if (pass == DrawCallbacks.PASS_ALPHA)
			ctx.drawAll(VAO_OPAQUE, opaqueDepthCmd);
	}

	@Override
	public void draw(RenderState renderState) {
		if(!config.depthPrePass())
			return;

		sceneDepthProgram.use();

		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboSceneDepth);
		if (plugin.msaaSamples > 1) {
			renderState.enable.set(GL_MULTISAMPLE);
		} else {
			renderState.disable.set(GL_MULTISAMPLE);
		}
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		if(renderer.indirectDrawCmds != null)
			renderState.ido.set(renderer.indirectDrawCmds.id);

		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_BLEND);
		renderState.depthFunc.set(GL_GREATER);
		renderState.colorMask.set(false, false, false, false);
		renderState.apply();

		opaqueDepthCmd.execute(renderState);

		glBindVertexArray(0);

		renderState.colorMask.set(true, true, true, true);
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.apply();
	}

	@Override
	public RenderPassType getType() { return RenderPassType.DEPTH_PASS; }
}
