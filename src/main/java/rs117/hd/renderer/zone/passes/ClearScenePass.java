package rs117.hd.renderer.zone.passes;

import javax.inject.Inject;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL13C.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static rs117.hd.utils.MathUtils.*;

public class ClearScenePass implements RenderPass {

	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private FrameTimer frameTimer;

	@Override
	public void draw(RenderState renderState) {
		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		if (plugin.msaaSamples > 1) {
			renderState.enable.set(GL_MULTISAMPLE);
		} else {
			renderState.disable.set(GL_MULTISAMPLE);
		}
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		renderState.apply();

		final float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
		final float[] gammaCorrectedFogColor = pow(fogColor, plugin.getGammaCorrection());
		glClearColor(
			gammaCorrectedFogColor[0],
			gammaCorrectedFogColor[1],
			gammaCorrectedFogColor[2],
			1f
		);
		glClearDepth(0);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		renderState.disable.set(GL_MULTISAMPLE);
	}

	@Override
	public RenderPassType getType() { return RenderPassType.CLEAR_SCENE; }
}
