package rs117.hd.renderer.zone;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.shader.OITCompositeShaderProgram;
import rs117.hd.opengl.shader.OITPrePassShaderProgram;
import rs117.hd.opengl.shader.OitDepthMergeShaderProgram;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.ARBSampleShading.GL_SAMPLE_SHADING_ARB;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.opengl.Utils.checkFramebufferComplete;
import static rs117.hd.opengl.Utils.labelObject;

/**
 * Owns the layered Weighted Blended Order Independent Transparency (WBOIT) pipeline: the
 * prepass, the accumulation pass, and the resolve/composite pass, along with the framebuffers
 * and shader programs they use. Kept separate from {@link ZoneRenderer} so the OIT render
 * path can be reasoned about (and disabled) in isolation.
 */
@Slf4j
@Singleton
public class OitRenderer {
	private static final float[] CLEAR_FIRST_LAYER = { 1e6f, 0f, 0f, 0f };
	private static final float[] CLEAR_FARTHEST_LAYER = { 0f, 0f, 0f, 0f };
	private static final float[] CLEAR_COVERAGE = { 1f, 0f, 0f, 0f };
	private static final float[] CLEAR_BIN = { 0f, 0f, 0f, 0f };
	private static final int[] CLEAR_STENCIL = { 0 };

	public static final int BIN_COUNT = ZoneRenderer.OIT_BIN_COUNT;

	public static final int TEXTURE_UNIT_FIRST_LAYER = ZoneRenderer.TEXTURE_UNIT_OIT_FIRST_LAYER;
	public static final int TEXTURE_UNIT_NET_COVERAGE = ZoneRenderer.TEXTURE_UNIT_OIT_NET_COVERAGE;
	public static final int TEXTURE_UNIT_COLOR_ACCUM = ZoneRenderer.TEXTURE_UNIT_OIT_COLOR_ACCUM;
	public static final int TEXTURE_UNIT_FARTHEST_LAYER = ZoneRenderer.TEXTURE_UNIT_OIT_FARTHEST_LAYER;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private ZoneRenderer renderer;

	@Inject
	private SceneShaderProgram.TransparentOIT sceneTransparentOITProgram;

	@Inject
	private SceneShaderProgram.TransparentOIT.Water sceneTransparentOITWaterProgram;

	@Inject
	private SceneShaderProgram.AlphaDiscard sceneAlphaDiscardProgram;

	@Inject
	private OITPrePassShaderProgram prePassProgram;

	@Inject
	private OITPrePassShaderProgram.Water prePassWaterProgram;

	@Inject
	private OITCompositeShaderProgram compositeProgram;

	@Inject
	private OITCompositeShaderProgram.SampleShading compositeSampleShadingProgram;

	@Inject
	private OitDepthMergeShaderProgram depthMergeProgram;

	@Inject
	private OitDepthMergeShaderProgram.MultiSample depthMergeMSProgram;

	public final CommandBuffer alphaDiscardCmd = new CommandBuffer("AlphaDiscard");
	public final CommandBuffer transparentCmd = new CommandBuffer("Transparent");
	public final CommandBuffer waterCmd = new CommandBuffer("Water");

	private int samples;
	private int width;
	private int height;
	private int fbo;

	private int colorAccumArrayTex;
	private int colorAccumArrayMSTex;

	private int firstLayerDepthFBO;
	private int firstLayerDepthTex;
	private int firstLayerDepthMSTex;
	private int firstLayerDepthResolveTex;
	private int farthestLayerDepthMSTex;
	private int farthestLayerDepthResolveTex;

	private int netCoverageTex;
	private int netCoverageMSTex;

	public void initialize() {
		alphaDiscardCmd.setFrameTimer(frameTimer);
		transparentCmd.setFrameTimer(frameTimer);
		waterCmd.setFrameTimer(frameTimer);
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneTransparentOITProgram.compile(includes);
		sceneTransparentOITWaterProgram.compile(includes);
		sceneAlphaDiscardProgram.compile(includes);

		prePassProgram.compile(includes);
		prePassWaterProgram.compile(includes);
		compositeProgram.compile(includes);
		compositeSampleShadingProgram.compile(includes);
		depthMergeProgram.compile(includes);
		depthMergeMSProgram.compile(includes);
	}

	public void destroyShaders() {
		sceneTransparentOITProgram.destroy();
		sceneTransparentOITWaterProgram.destroy();
		sceneAlphaDiscardProgram.destroy();

		prePassProgram.destroy();
		prePassWaterProgram.destroy();
		compositeProgram.destroy();
		compositeSampleShadingProgram.destroy();
		depthMergeProgram.destroy();
		depthMergeMSProgram.destroy();
	}

	/**
	 * Recreates the OIT framebuffers and textures if they are missing or the resolution or
	 * sample count changed. Call once per frame before {@link #prePass}.
	 */
	public void updateFBO(boolean force) {
		if (!force
			&& fbo != 0
			&& samples == plugin.msaaSamples
			&& width == plugin.sceneResolution[0]
			&& height == plugin.sceneResolution[1])
			return;

		destroyFBO();

		samples = plugin.msaaSamples;
		width = plugin.sceneResolution[0];
		height = plugin.sceneResolution[1];
		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		labelObject(GL_FRAMEBUFFER, fbo, "Transparent FBO");

		glActiveTexture(TEXTURE_UNIT_COLOR_ACCUM);
		colorAccumArrayTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, colorAccumArrayTex);
		labelObject(GL_TEXTURE, colorAccumArrayTex, "OIT Color Accumulation");
		glTexImage3D(
			GL_TEXTURE_2D_ARRAY, 0, GL_RGBA16F,
			width, height, BIN_COUNT,
			0, GL_RGBA, GL_HALF_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		if (samples > 1) {
			colorAccumArrayMSTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, colorAccumArrayMSTex);
			labelObject(GL_TEXTURE, colorAccumArrayMSTex, "OIT Color Accumulation MS");
			glTexImage3DMultisample(
				GL_TEXTURE_2D_MULTISAMPLE_ARRAY, samples, GL_RGBA16F,
				width, height, BIN_COUNT,
				true
			);
		}

		int[] accumDrawBuffers = new int[BIN_COUNT];
		for (int k = 0; k < BIN_COUNT; k++) {
			accumDrawBuffers[k] = GL_COLOR_ATTACHMENT0 + k;
			glFramebufferTextureLayer(GL_FRAMEBUFFER, accumDrawBuffers[k], samples > 1 ? colorAccumArrayMSTex : colorAccumArrayTex, 0, k);
		}
		glFramebufferTexture2D(
			GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
			samples > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D,
			plugin.getTexSceneDepth(), 0
		);
		glDrawBuffers(accumDrawBuffers);
		checkFramebufferComplete(fbo);

		firstLayerDepthFBO = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, firstLayerDepthFBO);
		labelObject(GL_FRAMEBUFFER, firstLayerDepthFBO, "OIT Depth Layers FBO");

		glActiveTexture(TEXTURE_UNIT_FIRST_LAYER);
		firstLayerDepthTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, firstLayerDepthTex);
		labelObject(GL_TEXTURE, firstLayerDepthTex, "OIT First Layer Depth");
		glTexImage2D(
			GL_TEXTURE_2D, 0, GL_RG32F,
			width, height, 0, GL_RG, GL_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_FIRST_LAYER);
		firstLayerDepthResolveTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, firstLayerDepthResolveTex);
		labelObject(GL_TEXTURE, firstLayerDepthResolveTex, "OIT First Layer Depth Resolve");
		glTexImage2D(
			GL_TEXTURE_2D, 0, GL_R32F,
			width, height, 0, GL_RED, GL_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_FARTHEST_LAYER);
		farthestLayerDepthResolveTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, farthestLayerDepthResolveTex);
		labelObject(GL_TEXTURE, farthestLayerDepthResolveTex, "OIT Farthest Layer Depth Resolve");
		glTexImage2D(
			GL_TEXTURE_2D, 0, GL_R32F,
			width, height, 0, GL_RED, GL_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_NET_COVERAGE);
		netCoverageTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, netCoverageTex);
		labelObject(GL_TEXTURE, netCoverageTex, "OIT Net Coverage");
		glTexImage2D(
			GL_TEXTURE_2D, 0, GL_R16F,
			width, height, 0, GL_RED, GL_HALF_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		if (samples > 1) {
			glActiveTexture(TEXTURE_UNIT_FIRST_LAYER);
			firstLayerDepthMSTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, firstLayerDepthMSTex);
			labelObject(GL_TEXTURE, firstLayerDepthMSTex, "OIT First Layer Depth MS");
			glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, samples, GL_R32F, width, height, true);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D_MULTISAMPLE, firstLayerDepthMSTex, 0);

			glActiveTexture(TEXTURE_UNIT_NET_COVERAGE);
			netCoverageMSTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, netCoverageMSTex);
			labelObject(GL_TEXTURE, netCoverageMSTex, "OIT Net Coverage MS");
			glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, samples, GL_R16F, width, height, true);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D_MULTISAMPLE, netCoverageMSTex, 0);

			glActiveTexture(TEXTURE_UNIT_FARTHEST_LAYER);
			farthestLayerDepthMSTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, farthestLayerDepthMSTex);
			labelObject(GL_TEXTURE, farthestLayerDepthMSTex, "OIT Farthest Layer Depth MS");
			glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, samples, GL_R32F, width, height, true);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D_MULTISAMPLE, farthestLayerDepthMSTex, 0);
		} else {
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, firstLayerDepthResolveTex, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, netCoverageTex, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, farthestLayerDepthResolveTex, 0);
		}
		glFramebufferTexture2D(
			GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
			samples > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D,
			plugin.getTexSceneDepth(), 0
		);
		glDrawBuffers(new int[] { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2 });
		checkFramebufferComplete(firstLayerDepthFBO);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	public void destroyFBO() {
		if (fbo != 0)
			glDeleteFramebuffers(fbo);
		fbo = 0;
		width = 0;
		height = 0;

		if (colorAccumArrayTex != 0)
			glDeleteTextures(colorAccumArrayTex);
		colorAccumArrayTex = 0;

		if (colorAccumArrayMSTex != 0)
			glDeleteTextures(colorAccumArrayMSTex);
		colorAccumArrayMSTex = 0;

		if (firstLayerDepthFBO != 0)
			glDeleteFramebuffers(firstLayerDepthFBO);
		firstLayerDepthFBO = 0;

		if (firstLayerDepthTex != 0)
			glDeleteTextures(firstLayerDepthTex);
		firstLayerDepthTex = 0;

		if (firstLayerDepthMSTex != 0)
			glDeleteTextures(firstLayerDepthMSTex);
		firstLayerDepthMSTex = 0;

		if (firstLayerDepthResolveTex != 0)
			glDeleteTextures(firstLayerDepthResolveTex);
		firstLayerDepthResolveTex = 0;

		if (farthestLayerDepthMSTex != 0)
			glDeleteTextures(farthestLayerDepthMSTex);
		farthestLayerDepthMSTex = 0;

		if (farthestLayerDepthResolveTex != 0)
			glDeleteTextures(farthestLayerDepthResolveTex);
		farthestLayerDepthResolveTex = 0;

		if (netCoverageTex != 0)
			glDeleteTextures(netCoverageTex);
		netCoverageTex = 0;

		if (netCoverageMSTex != 0)
			glDeleteTextures(netCoverageMSTex);
		netCoverageMSTex = 0;
	}

	public void preSceneDraw(WorldViewContext context, boolean isTopLevel) {
		if(!isTopLevel)
			return;

		alphaDiscardCmd.reset();
		transparentCmd.reset();
		waterCmd.reset();
	}

	private void alphaDiscardPass(RenderState renderState) {
		if (alphaDiscardCmd.isEmpty())
			return;

		frameTimer.begin(Timer.DRAW_ALPHA_DISCARD);
		frameTimer.begin(Timer.RENDER_ALPHA_DISCARD);

		renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboScene);
		renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
		renderState.program.set(sceneAlphaDiscardProgram);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.enable.set(GL_CULL_FACE);
		renderState.disable.set(GL_BLEND);

		renderState.depthFunc.set(GL_GEQUAL);
		renderState.depthMask.set(true);

		alphaDiscardCmd.execute(renderState);

		renderState.depthMask.set(false);
		renderState.disable.set(GL_DEPTH_TEST);

		frameTimer.end(Timer.RENDER_ALPHA_DISCARD);
		frameTimer.end(Timer.DRAW_ALPHA_DISCARD);
	}

	private void prePass(RenderState renderState) {
		if (transparentCmd.isEmpty() && waterCmd.isEmpty())
			return;

		frameTimer.begin(Timer.DRAW_ALPHA_PRE_PASS);
		frameTimer.begin(Timer.RENDER_ALPHA_PREPASS);

		renderState.framebuffer.set(GL_FRAMEBUFFER, firstLayerDepthFBO);
		renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
		renderState.program.set(prePassProgram);
		renderState.viewport.set(0, 0, width, height);
		if(SUPPORTS_INDIRECT_DRAW)
			renderState.ido.set(renderer.indirectDrawCmds.id);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.enable.set(GL_CULL_FACE);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.depthMask.set(false);
		// Mark every pixel containing alpha (depth pass, no discard) so the composite can skip opaque-only pixels
		renderState.enable.set(GL_STENCIL_TEST);
		renderState.stencilFunc.set(GL_ALWAYS, 1, 0xFF);
		renderState.stencilOp.set(GL_KEEP, GL_KEEP, GL_REPLACE);
		renderState.stencilMask.set(0xFF);
		renderState.apply();

		glClearBufferfv(GL_COLOR, 0, CLEAR_FIRST_LAYER);
		glClearBufferfv(GL_COLOR, 1, CLEAR_COVERAGE);
		glClearBufferfv(GL_COLOR, 2, CLEAR_FARTHEST_LAYER);
		glClearBufferiv(GL_STENCIL, 0, CLEAR_STENCIL);

		renderState.enable.set(GL_BLEND);
		renderState.blendEquationi.set(0, GL_MIN);
		renderState.blendFunci.set(0, GL_ONE, GL_ONE, GL_ONE, GL_ONE);
		renderState.blendEquationi.set(1, GL_FUNC_ADD);
		renderState.blendFunci.set(1, GL_ZERO, GL_ONE_MINUS_SRC_COLOR, GL_ZERO, GL_ONE_MINUS_SRC_COLOR);
		renderState.blendEquationi.set(2, GL_MAX);
		renderState.blendFunci.set(2, GL_ONE, GL_ONE, GL_ONE, GL_ONE);
		renderState.apply();

		transparentCmd.execute(renderState);

		if (!waterCmd.isEmpty()) {
			renderState.program.set(prePassWaterProgram);
			renderState.apply();
			waterCmd.execute(renderState);
		}

		renderState.disable.set(GL_BLEND);
		renderState.stencilFunc.set(GL_EQUAL, 1, 0xFF);
		renderState.stencilOp.set(GL_KEEP, GL_KEEP, GL_KEEP);
		
		if (plugin.msaaSamples > 1) {
			depthMergeMSProgram.merge(
				renderState, plugin.vaoTri,
				TEXTURE_UNIT_FIRST_LAYER, TEXTURE_UNIT_FARTHEST_LAYER,
				firstLayerDepthMSTex, farthestLayerDepthMSTex,
				firstLayerDepthTex
			);
		} else {
			depthMergeProgram.merge(
				renderState, plugin.vaoTri,
				TEXTURE_UNIT_FIRST_LAYER, TEXTURE_UNIT_FARTHEST_LAYER,
				firstLayerDepthResolveTex, farthestLayerDepthResolveTex,
				firstLayerDepthTex
			);
		}

		glActiveTexture(HdPlugin.TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, 0);

		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_STENCIL_TEST);

		frameTimer.end(Timer.RENDER_ALPHA_PREPASS);
		frameTimer.end(Timer.DRAW_ALPHA_PRE_PASS);
	}

	public void render( RenderState renderState) {
		alphaDiscardPass(renderState);

		if (!transparentCmd.isEmpty() || !waterCmd.isEmpty()) {
			prePass(renderState);

			frameTimer.begin(Timer.DRAW_ALPHA);
			frameTimer.begin(Timer.RENDER_ALPHA);
			renderState.program.set(sceneTransparentOITProgram);

			glActiveTexture(TEXTURE_UNIT_FIRST_LAYER);
			glBindTexture(GL_TEXTURE_2D, firstLayerDepthTex);

			renderState.depthMask.set(false);
			renderState.enable.set(GL_BLEND);
			renderState.blendFunc.set(GL_ONE, GL_ONE, GL_ONE, GL_ONE);
			renderState.blendEquation.set(GL_FUNC_ADD);

			renderState.framebuffer.set(GL_FRAMEBUFFER, fbo);
			renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
			renderState.apply();
			for (int k = 0; k < BIN_COUNT; k++)
				glClearBufferfv(GL_COLOR, k, CLEAR_BIN);

			renderState.enable.set(GL_DEPTH_TEST);
			renderState.enable.set(GL_CULL_FACE);
			renderState.depthFunc.set(GL_GEQUAL);
			renderState.depthMask.set(false);

			transparentCmd.execute(renderState);

			if (!waterCmd.isEmpty()) {
				renderState.program.set(sceneTransparentOITWaterProgram);
				renderState.apply();
				waterCmd.execute(renderState);
			}

			renderState.depthMask.set(true);
			frameTimer.end(Timer.RENDER_ALPHA);
			frameTimer.end(Timer.DRAW_ALPHA);

			frameTimer.begin(Timer.DRAW_ALPHA_COMPOSITE);
			frameTimer.begin(Timer.RENDER_ALPHA_COMPOSITE);

			final boolean sampleShading = plugin.msaaSamples > 1;

			renderState.program.set(sampleShading ? compositeSampleShadingProgram : compositeProgram);
			renderState.depthFunc.set(GL_ALWAYS);
			renderState.enable.set(GL_BLEND);
			renderState.blendFunc.set(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

			glActiveTexture(TEXTURE_UNIT_NET_COVERAGE);
			glBindTexture(sampleShading ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D, sampleShading ? netCoverageMSTex : netCoverageTex);

			glActiveTexture(TEXTURE_UNIT_COLOR_ACCUM);
			glBindTexture(
				sampleShading ? GL_TEXTURE_2D_MULTISAMPLE_ARRAY : GL_TEXTURE_2D_ARRAY,
				sampleShading ? colorAccumArrayMSTex : colorAccumArrayTex
			);

			// Only shade pixels the prepass marked as containing alpha
			renderState.enable.set(GL_STENCIL_TEST);
			renderState.stencilFunc.set(GL_EQUAL, 1, 0xFF);
			renderState.stencilOp.set(GL_KEEP, GL_KEEP, GL_KEEP);

			renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboScene);
			renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
			renderState.vao.setVao(plugin.vaoTri);
			renderState.apply();

			if (sampleShading) {
				renderState.enable.set(GL_SAMPLE_SHADING_ARB);
				renderState.sampleShading.set(1.0f);
				renderState.apply();
			}

			glDrawArrays(GL_TRIANGLES, 0, 3);

			frameTimer.end(Timer.RENDER_ALPHA_COMPOSITE);
			frameTimer.end(Timer.DRAW_ALPHA_COMPOSITE);
		}

		renderState.disable.set(GL_SAMPLE_SHADING_ARB);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_STENCIL_TEST);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.depthMask.set(true);
		renderState.blendFunc.set(GL_ONE, GL_ZERO, GL_ONE, GL_ZERO);
		renderState.blendEquation.set(GL_FUNC_ADD);
		renderState.apply();
		frameTimer.end(Timer.DRAW_ALPHA);
	}
}
