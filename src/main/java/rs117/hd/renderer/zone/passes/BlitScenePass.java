package rs117.hd.renderer.zone.passes;

import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.SceneScalingMode;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBlitFramebuffer;
import static rs117.hd.HdPlugin.APPLE;

@Slf4j
public class BlitScenePass implements RenderPass {

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	private SceneScalingMode scalingMode;

	@Override
	public void initialize() {
		scalingMode = config.sceneScalingMode();
	}

	@Override
	public void processConfigChanges(Set<String> keys) {
		scalingMode = config.sceneScalingMode();
	}

	@Override
	public int preprocess() { return PASS_ENABLED; }

	@Override
	public void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {
		log.debug("BlitScenePass.drawZoneOpaque({}, {}, {})", z, zx, zz);
	}

	@Override
	public void draw(RenderState renderState) {
		if (plugin.sceneResolution == null || plugin.sceneViewport == null)
			return;

		glBindFramebuffer(GL_READ_FRAMEBUFFER, plugin.fboScene);
		if (plugin.fboSceneResolve != 0) {
			// Blit from the scene FBO to the multisample resolve FBO
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, plugin.fboSceneResolve);
			glBlitFramebuffer(
				0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1],
				0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1],
				GL_COLOR_BUFFER_BIT, GL_NEAREST
			);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, plugin.fboSceneResolve);
		}

		// Blit from the resolved FBO to the default FBO
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));

		if (APPLE && !client.isResized()) {
			// On macOS, we need to ensure that the alpha channel is opaque to prevent whatever
			// is beneath from leaking through. In fixed mode, the MSAA resolve alone is not
			// sufficient, since the viewport only covers part of the screen.
			glClearColor(0, 0, 0, 1);
			glClear(GL_COLOR_BUFFER_BIT);
		}

		glBlitFramebuffer(
			0,
			0,
			plugin.sceneResolution[0],
			plugin.sceneResolution[1],
			plugin.sceneViewport[0],
			plugin.sceneViewport[1],
			plugin.sceneViewport[0] + plugin.sceneViewport[2],
			plugin.sceneViewport[1] + plugin.sceneViewport[3],
			GL_COLOR_BUFFER_BIT,
			scalingMode.glFilter
		);
	}

	@Override
	public RenderPassType getType() { return RenderPassType.BLIT_SCENE; }
}
