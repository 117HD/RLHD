package rs117.hd.renderer.zone.passes;

import java.awt.Dimension;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.UIScalingMode;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.UIShaderProgram;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.GammaCalibrationOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.jobs.GenericJob;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_ZERO;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL11C.glTexSubImage2D;
import static org.lwjgl.opengl.GL12C.GL_BGRA;
import static org.lwjgl.opengl.GL12C.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;

@Slf4j
public class UiPass implements RenderPass {
	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private UIShaderProgram uiProgram;

	@Inject
	private DeveloperTools developerTools;

	@Inject
	private GammaCalibrationOverlay gammaCalibrationOverlay;

	@Inject
	private ShadowMapOverlay shadowMapOverlay;

	@Inject
	private TiledLightingOverlay tiledLightingOverlay;

	private UIScalingMode scalingMode;
	private boolean scalingModeChanged;

	@Override
	public void initialize() {
		scalingMode = config.uiScalingMode();
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		uiProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		uiProgram.destroy();
	}

	@Override
	public void processConfigChanges(Set<String> keys) {
		UIScalingMode newMode = config.uiScalingMode();
		scalingModeChanged = scalingMode != newMode;
		scalingMode = newMode;
	}

	@Override
	public void preDraw(RenderState renderState) {
		try {
			if (plugin.uiCopyJob != null)
				plugin.uiCopyJob.waitForCompletion(true);
			plugin.uiCopyJob = null;

			int[] resolution = {
				max(1, client.getCanvasWidth()),
				max(1, client.getCanvasHeight())
			};
			boolean resize = !Arrays.equals(plugin.getUiResolution(), resolution);
			if (resize) {
				plugin.setUiResolution(resolution);
				glActiveTexture(TEXTURE_UNIT_UI);
				glBindTexture(GL_TEXTURE_2D, plugin.texUi);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, resolution[0], resolution[1], 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
				scalingModeChanged = true;
				checkGLErrors();
			}

			if(scalingModeChanged) {
				scalingModeChanged = false;

				// Set the sampling function used when stretching the UI.
				// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
				// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
				// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/pixel isn't
				glActiveTexture(TEXTURE_UNIT_UI);
				glBindTexture(GL_TEXTURE_2D, plugin.texUi);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, scalingMode.glSamplingFunction);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, scalingMode.glSamplingFunction);
				glBindTexture(GL_TEXTURE_2D, 0);
				checkGLErrors();
			}

			if (client.isStretchedEnabled()) {
				Dimension dim = client.getStretchedDimensions();
				plugin.actualUiResolution[0] = dim.width;
				plugin.actualUiResolution[1] = dim.height;
			} else {
				copyTo(plugin.actualUiResolution, plugin.getUiResolution());
			}
			round(plugin.actualUiResolution, multiply(vec(plugin.actualUiResolution), plugin.getDpiScaling()));

			final BufferProvider bufferProvider = client.getBufferProvider();
			final int[] pixels = bufferProvider.getPixels();
			plugin.uiWidth = bufferProvider.getWidth();
			plugin.uiHeight = bufferProvider.getHeight();

			frameTimer.begin(Timer.MAP_UI_BUFFER);
			final GLBuffer pbo = plugin.pboUi[plugin.frame % 3];
			pbo.map(MAP_WRITE, 0, plugin.uiWidth * plugin.uiHeight * 4L);
			frameTimer.end(Timer.MAP_UI_BUFFER);
			if (!pbo.isMapped()) {
				log.error("Unable to map interface PBO. Skipping UI...");
			} else if (plugin.uiWidth > plugin.getUiResolution()[0] || plugin.uiHeight > plugin.getUiResolution()[1]) {
				log.error("UI texture resolution mismatch ({}x{} > {}). Skipping UI...", plugin.uiWidth, plugin.uiHeight, plugin.getUiResolution());
			} else {
				plugin.uiCopyJob = GenericJob
					.build(
						"AsyncUICopy",
						t -> {
							long start = System.nanoTime();
							pbo.mapped().intView().put(pixels, 0, plugin.uiWidth * plugin.uiHeight);
							frameTimer.add(Timer.COPY_UI_ASYNC, System.nanoTime() - start);
						}
					)
					.setExecuteAsync(!plugin.isPowerSaving)
					.queue();
			}
			pbo.unbind();
		} catch (Exception ex) {
			log.warn("prepareInterfaceTexture exception", ex);
		}
	}

	@Override
	public void draw(RenderState renderState, int overlayColor) {
		final int[] uiResolution = plugin.getUiResolution();
		if (uiResolution == null || developerTools.isHideUiEnabled() && plugin.hasLoggedIn)
			return;

		// Fix vanilla bug causing the overlay to remain on the login screen in areas like Fossil Island underwater
		if (client.getGameState().getState() < GameState.LOADING.getState())
			overlayColor = 0;

		renderState.framebuffer.set(plugin.awtContext.getFramebuffer(false));
		renderState.viewport.set(0, 0, plugin.actualUiResolution[0], plugin.actualUiResolution[1]);
		renderState.colorMask.set(true, true, true, false);
		renderState.apply();

		tiledLightingOverlay.render();

		renderState.program.set(uiProgram);
		renderState.texture.set(GL_TEXTURE_2D, TEXTURE_UNIT_UI, plugin.texUi);
		renderState.enable.set(GL_BLEND);
		renderState.blendFunc.set(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		renderState.vao.setVao(plugin.vaoTri);
		renderState.apply();

		plugin.uboUI.sourceDimensions.set(uiResolution);
		plugin.uboUI.targetDimensions.set(plugin.actualUiResolution);
		plugin.uboUI.alphaOverlay.set(ColorUtils.srgba(overlayColor));
		plugin.uboUI.upload();

		if (plugin.uiCopyJob != null) {
			checkGLErrors();

			frameTimer.begin(Timer.COPY_UI);
			plugin.uiCopyJob.waitForCompletion(true);
			plugin.uiCopyJob = null;
			frameTimer.end(Timer.COPY_UI);

			frameTimer.begin(Timer.UPLOAD_UI);
			final GLBuffer pbo = plugin.pboUi[plugin.frame % 3];
			pbo.unmap();
			pbo.bind();

			glActiveTexture(TEXTURE_UNIT_UI);
			glBindTexture(GL_TEXTURE_2D, plugin.texUi);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, plugin.uiWidth, plugin.uiHeight, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
			pbo.unbind();
			frameTimer.end(Timer.UPLOAD_UI);

			checkGLErrors();
		}

		glDrawArrays(GL_TRIANGLES, 0, 3);
		checkGLErrors();

		shadowMapOverlay.render();
		gammaCalibrationOverlay.render();

		frameTimer.end(Timer.RENDER_UI);
	}

	@Override
	public RenderPassType getType() { return RenderPassType.UI; }
}
