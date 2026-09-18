package rs117.hd.renderer.zone.passes;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.config.DynamicLights;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11C.GL_NONE;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;

@Slf4j
@Singleton
public class TiledLightingPass implements RenderPass {

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	@Override
	public void draw(RenderState renderState) {
		if (!plugin.configTiledLighting || plugin.configDynamicLights == DynamicLights.NONE)
			return;

		plugin.updateTiledLightingFbo();
		assert plugin.fboTiledLighting != 0;

		frameTimer.begin(Timer.RENDER_TILED_LIGHTING);

		renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboTiledLighting);
		renderState.viewport.set(0, 0, plugin.tiledLightingResolution[0], plugin.tiledLightingResolution[1]);
		renderState.vao.setVao(plugin.vaoTri);

		if (plugin.tiledLightingImageStoreProgram.isValid()) {
			renderState.program.set(plugin.tiledLightingImageStoreProgram);
			renderState.drawBuffer.set(GL_NONE);
			renderState.apply();
			glDrawArrays(GL_TRIANGLES, 0, 3);
		} else {
			renderState.drawBuffer.set(GL_COLOR_ATTACHMENT0);
			int layerCount = plugin.configDynamicLights.getTiledLightingLayers();
			for (int layer = 0; layer < layerCount; layer++) {
				renderState.program.set(plugin.tiledLightingShaderPrograms.get(layer));
				renderState.framebufferTextureLayer.set(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, plugin.texTiledLighting, 0, layer);
				renderState.apply();
				glDrawArrays(GL_TRIANGLES, 0, 3);
			}
		}

		frameTimer.end(Timer.RENDER_TILED_LIGHTING);
	}

	@Override
	public RenderPassType getType() { return RenderPassType.TILED_LIGHTING; }
}
