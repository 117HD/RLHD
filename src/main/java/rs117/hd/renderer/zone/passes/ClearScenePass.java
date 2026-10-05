package rs117.hd.renderer.zone.passes;

import javax.inject.Inject;
import rs117.hd.HdPlugin;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL13C.GL_MULTISAMPLE;
import static rs117.hd.utils.MathUtils.*;

public class ClearScenePass implements RenderPass {

	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	@Override
	public int preprocess() { return PASS_ENABLED | PASS_SCENE_RENDERING; }

	@Override
	public void draw(RenderState renderState, int overlayColor) {
		renderState.drawFramebuffer.set(plugin.fboScene);
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);

		final float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
		final float[] gammaCorrectedFogColor = pow(fogColor, plugin.getGammaCorrection());
		renderState.clearColor.set(
			gammaCorrectedFogColor[0],
			gammaCorrectedFogColor[1],
			gammaCorrectedFogColor[2],
			1f
		);
		renderState.clearDepth.set(0);
		renderState.apply();
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
	}


	@Override
	public RenderPassType getType() { return RenderPassType.CLEAR_SCENE; }
}
