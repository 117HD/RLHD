/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.DrawManager;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.ColorFilter;
import rs117.hd.config.DynamicLights;
import rs117.hd.config.ShadowMode;
import rs117.hd.opengl.shader.OITCompositeShaderProgram;
import rs117.hd.opengl.shader.OITPrePassShaderProgram;
import rs117.hd.opengl.shader.OitDepthMergeShaderProgram;
import rs117.hd.opengl.shader.ResolveShaderProgram;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.ShadowShaderProgram;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.Renderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.ShadowCasterVolume;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.JobSystem;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.ARBSampleShading.GL_SAMPLE_SHADING_ARB;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static rs117.hd.HdPlugin.APPLE;
import static rs117.hd.HdPlugin.COLOR_FILTER_FADE_DURATION;
import static rs117.hd.HdPlugin.NEAR_PLANE;
import static rs117.hd.HdPlugin.ORTHOGRAPHIC_ZOOM;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.opengl.Utils.checkFramebufferComplete;
import static rs117.hd.opengl.Utils.checkGLErrors;
import static rs117.hd.opengl.Utils.labelObject;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_ALPHA;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PLAYER;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PRESCENE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_SHADOW;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class ZoneRenderer implements Renderer {
	public static final int FRAMES_IN_FLIGHT = 3;

	private static final float[] OIT_CLEAR_FIRST_LAYER = { 1e6f, 0f, 0f, 0f };
	private static final float[] OIT_CLEAR_COVERAGE = { 1f, 0f, 0f, 0f };
	private static final float[] OIT_CLEAR_BIN = { 0f, 0f, 0f, 0f };
	public static final int OIT_BIN_COUNT = 4;

	private static int TEXTURE_UNIT_COUNT = HdPlugin.TEXTURE_UNIT_COUNT;
	public static final int TEXTURE_UNIT_TEXTURED_FACES = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_OIT_FIRST_LAYER = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_OIT_NET_COVERAGE = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_OIT_COLOR_ACCUM = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_OIT_OPAQUE_DEPTH = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;

	private static int UNIFORM_BLOCK_COUNT = HdPlugin.UNIFORM_BLOCK_COUNT;
	public static final int UNIFORM_BLOCK_WORLD_VIEWS = UNIFORM_BLOCK_COUNT++;

	@Inject
	private Injector injector;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private LightManager lightManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ModelStreamingManager modelStreamingManager;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneShaderProgram sceneProgram;

	@Inject
	private SceneShaderProgram.Opaque sceneOpaqueProgram;

	@Inject
	private SceneShaderProgram.TransparentOIT sceneTransparentOITProgram;

	@Inject
	private SceneShaderProgram.AlphaDiscard sceneAlphaDiscardProgram;

	@Inject
	private ShadowShaderProgram.Fast fastShadowProgram;

	@Inject
	private ShadowShaderProgram.Detailed detailedShadowProgram;


	@Inject
	private OITCompositeShaderProgram oitCompositeShaderProgram;

	@Inject
	private OITCompositeShaderProgram.SampleShading oitCompositeSampleShadingProgram;

	@Inject
	private OITPrePassShaderProgram oitPrePassProgram;

	@Inject
	private ResolveShaderProgram.Min msaaaMinResolveProgram;
	@Inject
	private OitDepthMergeShaderProgram oitDepthMergeProgram;

	@Inject
	private JobSystem jobSystem;

	@Inject
	private UBOWorldViews uboWorldViews;

	public final Camera sceneCamera = new Camera().setReverseZ(true);
	public final Camera directionalCamera = new Camera().setOrthographic(true);
	public final ShadowCasterVolume directionalShadowCasterVolume = new ShadowCasterVolume(directionalCamera);

	public final RenderState renderState = new RenderState();
	public final CommandBuffer sceneCmd = new CommandBuffer("Scene");
	public final CommandBuffer alphaDiscardCmd = new CommandBuffer("AlphaDiscard");
	public final CommandBuffer transparentCmd = new CommandBuffer("Transparent");
	public final CommandBuffer directionalCmd = new CommandBuffer("Directional");
	public final CommandBuffer gapFillerCmd = new CommandBuffer("GapFiller");

	private GLBuffer indirectDrawCmds;
	public static GpuIntBuffer indirectDrawCmdsStaging;

	public static GLBuffer.EBO eboAlpha;
	public static GLMappedBufferIntWriter eboAlphaWriter;

	private int transparentSamples;
	private int transparentWidth;
	private int transparentHeight;
	private int transparentFBO;
	private int colorAccumArrayTex;
	private int colorAccumArrayMSTex;

	private int firstLayerDepthFBO;
	private int firstLayerDepthTex;
	private int firstLayerDepthMSTex;
	private int firstLayerDepthResolveTex;

	private int netCoverageTex;
	private int netCoverageMSTex;

	private boolean sceneFboValid;
	private boolean shouldRenderSkybox;
	private boolean shouldRenderScene;
	private boolean shouldClearShadowFbo;
	private boolean shouldDrawRoofShadows;

	@Override
	public boolean supportsGpu(GLCapabilities glCaps) {
		return glCaps.OpenGL33;
	}

	@Override
	public int gpuFlags() {
		return
			DrawCallbacks.ZBUF |
			DrawCallbacks.ZBUF_ZONE_FRUSTUM_CHECK |
			DrawCallbacks.NORMALS;
	}

	@Override
	public void initialize() {
		initializeBuffers();

		if (SceneUploader.POOL == null)
			SceneUploader.POOL = new ConcurrentPool<>(() -> injector.getInstance(SceneUploader.class));

		if (FacePrioritySorter.POOL == null)
			FacePrioritySorter.POOL = new ConcurrentPool<>(() -> injector.getInstance(FacePrioritySorter.class));

		sceneCmd.setFrameTimer(frameTimer);
		alphaDiscardCmd.setFrameTimer(frameTimer);
		directionalCmd.setFrameTimer(frameTimer);
		gapFillerCmd.setFrameTimer(frameTimer);

		jobSystem.startUp(config.cpuUsageLimit());
		uboWorldViews.initialize(UNIFORM_BLOCK_WORLD_VIEWS);
		sceneManager.initialize(uboWorldViews);
		modelStreamingManager.initialize();

		// Force updates that only run when the cameras change
		sceneCamera.setDirty();
		directionalCamera.setDirty();
	}

	@Override
	public void destroy() {
		destroyBuffers();

		jobSystem.shutDown();
		modelStreamingManager.destroy();
		sceneManager.destroy();
		uboWorldViews.destroy();

		if (SceneUploader.POOL != null)
			SceneUploader.POOL.destroy();

		if (FacePrioritySorter.POOL != null)
			FacePrioritySorter.POOL.destroy();
	}

	@Override
	public void waitUntilIdle() {
		sceneManager.completeAllStreaming();
		glFinish();
	}

	@Override
	public void addShaderIncludes(ShaderIncludes includes) {
		includes
			.define("MAX_SIMULTANEOUS_WORLD_VIEWS", UBOWorldViews.MAX_SIMULTANEOUS_WORLD_VIEWS)
			.define("OIT_BIN_COUNT", OIT_BIN_COUNT)
			.addInclude("WORLD_VIEW_GETTER", () -> plugin.generateGetter("WorldView", UBOWorldViews.MAX_SIMULTANEOUS_WORLD_VIEWS))
			.addUniformBuffer(uboWorldViews);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneProgram.compile(includes);
		sceneOpaqueProgram.compile(includes);
		sceneTransparentOITProgram.compile(includes);
		sceneAlphaDiscardProgram.compile(includes);
		fastShadowProgram.compile(includes);
		detailedShadowProgram.compile(includes);
		oitCompositeShaderProgram.compile(includes);
		oitCompositeSampleShadingProgram.compile(includes);
		oitPrePassProgram.compile(includes);
		msaaaMinResolveProgram.compile(includes);
		oitDepthMergeProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		sceneProgram.destroy();
		sceneOpaqueProgram.destroy();
		sceneTransparentOITProgram.destroy();
		sceneAlphaDiscardProgram.destroy();
		fastShadowProgram.destroy();
		detailedShadowProgram.destroy();
		oitCompositeShaderProgram.destroy();
		oitCompositeSampleShadingProgram.destroy();
		oitPrePassProgram.destroy();
		msaaaMinResolveProgram.destroy();
		oitDepthMergeProgram.destroy();
	}

	private void initializeBuffers() {
		eboAlpha = new GLBuffer.EBO("eboAlpha", GL_STREAM_DRAW);
		eboAlpha.initialize(MiB);
		eboAlphaWriter = new GLMappedBufferIntWriter(eboAlpha);

		indirectDrawCmds = new GLBuffer("indirectDrawCmds", GL_DRAW_INDIRECT_BUFFER, GL_STREAM_DRAW).initialize(MiB);
		indirectDrawCmdsStaging = new GpuIntBuffer();
	}

	private void destroyBuffers() {
		if (eboAlpha != null)
			eboAlpha.destroy();
		eboAlpha = null;

		if (eboAlphaWriter != null)
			eboAlphaWriter.destroy();
		eboAlphaWriter = null;

		if (indirectDrawCmds != null)
			indirectDrawCmds.destroy();
		indirectDrawCmds = null;

		if (indirectDrawCmdsStaging != null)
			indirectDrawCmdsStaging.destroy();
		indirectDrawCmdsStaging = null;

		destroyTransparentFBO();
	}

	@Override
	public void processConfigChanges(Set<String> keys) {
		if (keys.contains(KEY_ASYNC_MODEL_PROCESSING))
			modelStreamingManager.reinitialize();
	}

	@Override
	public void preSceneDraw(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds
	) {
		if (plugin.isPluginStopPending())
			return;

		try {
			WorldViewContext ctx = sceneManager.getContext(scene);
			if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading) {
				// When triggering plugin restarts in rapid succession, it can end up in a state where no scene is loaded initially
				if (scene.getWorldViewId() == WorldView.TOPLEVEL && client.getGameState() == GameState.LOGGED_IN)
					clientThread.invokeLater(() -> client.setGameState(GameState.LOADING));
				return;
			}

			frameTimer.begin(Timer.DRAW_PRESCENE);
			ctx.minLevel = minLevel;
			ctx.level = level;
			ctx.maxLevel = maxLevel;
			ctx.hideRoofIds = hideRoofIds;
			ctx.vaoSceneCmd.reset();
			ctx.vaoDirectionalCmd.reset();

			if (ctx.uboWorldViewStruct != null)
				ctx.uboWorldViewStruct.update();

			if (scene.getWorldViewId() == WorldView.TOPLEVEL)
				preSceneDrawTopLevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);

			ctx.completeInvalidation();

			if(!plugin.configUseOIT) {
				int offset = ctx.sceneContext.sceneOffset >> 3;
				for (int zx = 0; zx < ctx.sizeX; ++zx)
					for (int zz = 0; zz < ctx.sizeZ; ++zz)
						ctx.zones[zx][zz].multizoneLocs(ctx.sceneContext, zx - offset, zz - offset, sceneCamera, ctx.zones);

				ctx.sortStaticAlphaModels(sceneCamera);
			}

			ctx.map();

			if (scene.getWorldViewId() == WorldView.TOPLEVEL) {
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
						cameraX, cameraY, cameraZ
					);
				}

				sceneCmd.DepthMask(false);
				ctx.drawAll(VAO_PRESCENE, sceneCmd);
				sceneCmd.DepthMask(true);
			}

			frameTimer.end(Timer.DRAW_PRESCENE);
		} catch (Throwable ex) {
			log.error("Error in preSceneDraw({}):", scene != null ? scene.getWorldViewId() : null, ex);
			plugin.requestPluginStop();
		}
	}

	private void updateTransparentFBO() {
		if (transparentFBO != 0
			&& transparentSamples == plugin.msaaSamples
			&& transparentWidth == plugin.sceneResolution[0]
			&& transparentHeight == plugin.sceneResolution[1])
			return;

		destroyTransparentFBO();

		transparentSamples = plugin.msaaSamples;
		transparentWidth = plugin.sceneResolution[0];
		transparentHeight = plugin.sceneResolution[1];
		transparentFBO = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, transparentFBO);
		labelObject(GL_FRAMEBUFFER, transparentFBO, "Transparent FBO");

		glActiveTexture(TEXTURE_UNIT_OIT_COLOR_ACCUM);
		colorAccumArrayTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, colorAccumArrayTex);
		labelObject(GL_TEXTURE, colorAccumArrayTex, "OIT Color Accumulation");
		glTexImage3D(
			GL_TEXTURE_2D_ARRAY, 0, GL_RGBA16F,
			plugin.sceneResolution[0], plugin.sceneResolution[1], OIT_BIN_COUNT,
			0, GL_RGBA, GL_HALF_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		if (transparentSamples > 1) {
			colorAccumArrayMSTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, colorAccumArrayMSTex);
			labelObject(GL_TEXTURE, colorAccumArrayMSTex, "OIT Color Accumulation MS");
			glTexImage3DMultisample(
				GL_TEXTURE_2D_MULTISAMPLE_ARRAY, transparentSamples, GL_RGBA16F,
				plugin.sceneResolution[0], plugin.sceneResolution[1], OIT_BIN_COUNT,
				true
			);
		}

		int[] accumDrawBuffers = new int[OIT_BIN_COUNT];
		for (int k = 0; k < OIT_BIN_COUNT; k++) {
			accumDrawBuffers[k] = GL_COLOR_ATTACHMENT0 + k;
			glFramebufferTextureLayer(GL_FRAMEBUFFER, accumDrawBuffers[k], transparentSamples > 1 ? colorAccumArrayMSTex : colorAccumArrayTex, 0, k);
		}

		glFramebufferTexture2D(
			GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
			transparentSamples > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D,
			plugin.getTexSceneDepth(), 0
		);
		glDrawBuffers(accumDrawBuffers);
		checkFramebufferComplete(transparentFBO);

		firstLayerDepthFBO = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, firstLayerDepthFBO);
		labelObject(GL_FRAMEBUFFER, firstLayerDepthFBO, "OIT Depth Layers FBO");

		glActiveTexture(TEXTURE_UNIT_OIT_FIRST_LAYER);
		firstLayerDepthTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, firstLayerDepthTex);
		labelObject(GL_TEXTURE, firstLayerDepthTex, "OIT First Layer Depth");
		glTexImage2D(
			GL_TEXTURE_2D, 0, GL_RG16F,
			plugin.sceneResolution[0], plugin.sceneResolution[1],
			0, GL_RG, GL_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_OIT_FIRST_LAYER);
		firstLayerDepthResolveTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, firstLayerDepthResolveTex);
		labelObject(GL_TEXTURE, firstLayerDepthResolveTex, "OIT First Layer Depth Resolve");
		glTexImage2D(
			GL_TEXTURE_2D, 0, GL_R32F,
			plugin.sceneResolution[0], plugin.sceneResolution[1],
			0, GL_RED, GL_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_OIT_NET_COVERAGE);
		netCoverageTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, netCoverageTex);
		labelObject(GL_TEXTURE, netCoverageTex, "OIT Net Coverage");
		glTexImage2D(
			GL_TEXTURE_2D, 0, GL_R16F,
			plugin.sceneResolution[0], plugin.sceneResolution[1],
			0, GL_RED, GL_HALF_FLOAT, 0
		);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		if (transparentSamples > 1) {
			glActiveTexture(TEXTURE_UNIT_OIT_FIRST_LAYER);
			firstLayerDepthMSTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, firstLayerDepthMSTex);
			labelObject(GL_TEXTURE, firstLayerDepthMSTex, "OIT First Layer Depth MS");
			glTexImage2DMultisample(
				GL_TEXTURE_2D_MULTISAMPLE, transparentSamples, GL_R32F,
				plugin.sceneResolution[0], plugin.sceneResolution[1], true
			);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D_MULTISAMPLE, firstLayerDepthMSTex, 0);

			glActiveTexture(TEXTURE_UNIT_OIT_NET_COVERAGE);
			netCoverageMSTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, netCoverageMSTex);
			labelObject(GL_TEXTURE, netCoverageMSTex, "OIT Net Coverage MS");
			glTexImage2DMultisample(
				GL_TEXTURE_2D_MULTISAMPLE, transparentSamples, GL_R16F,
				plugin.sceneResolution[0], plugin.sceneResolution[1], true
			);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D_MULTISAMPLE, netCoverageMSTex, 0);
		} else {
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, firstLayerDepthResolveTex, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, netCoverageTex, 0);
		}
		glFramebufferTexture2D(
			GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
			transparentSamples > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D,
			plugin.getTexSceneDepth(), 0
		);
		glDrawBuffers(new int[] { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 });
		checkFramebufferComplete(firstLayerDepthFBO);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	private void destroyTransparentFBO() {
		if (transparentFBO != 0)
			glDeleteFramebuffers(transparentFBO);
		transparentFBO = 0;
		transparentWidth = 0;
		transparentHeight = 0;

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

		if (netCoverageTex != 0)
			glDeleteTextures(netCoverageTex);
		netCoverageTex = 0;

		if (netCoverageMSTex != 0)
			glDeleteTextures(netCoverageMSTex);
		netCoverageMSTex = 0;

	}

	private void preSceneDrawTopLevel(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw
	) {
		jobSystem.processPendingClientCallbacks();

		scene.setDrawDistance(plugin.getDrawDistance());

		// Ensure that the previous frames commands have finished flushing
		frameTimer.begin(Timer.DRAW_FLUSH);
		glFlush();
		frameTimer.end(Timer.DRAW_FLUSH);

		plugin.updateSceneFbo();
		updateTransparentFBO();

		if (!sceneManager.isTopLevelValid() || plugin.sceneViewport == null)
			return;

		WorldViewContext ctx = sceneManager.getContext(scene);

		frameTimer.begin(Timer.DRAW_FRAME);
		frameTimer.begin(Timer.DRAW_SCENE);

		if (!plugin.enableFreezeFrame && !plugin.redrawPreviousFrame) {
			plugin.drawnTempRenderableCount = 0;
			plugin.drawnDynamicRenderableCount = 0;

			copyTo(plugin.cameraPosition, vec(cameraX, cameraY, cameraZ));
			copyTo(plugin.cameraOrientation, vec(cameraYaw, cameraPitch));

			copyTo(plugin.cameraFocalPoint, ivec((int) client.getCameraFocalPointX(), (int) client.getCameraFocalPointZ()));
			Arrays.fill(plugin.cameraShift, 0);

			float zoom = client.get3dZoom();
			float drawDistance = (float) plugin.getDrawDistance();

			if (plugin.orthographicProjection)
				zoom *= ORTHOGRAPHIC_ZOOM;

			// Calculate the viewport dimensions before scaling in order to include the extra padding
			sceneCamera.setOrthographic(plugin.orthographicProjection);
			sceneCamera.setPosition(plugin.cameraPosition);
			sceneCamera.setOrientation(plugin.cameraOrientation);
			sceneCamera.setFixedYaw(client.getCameraYaw());
			sceneCamera.setFixedPitch(client.getCameraPitch());
			sceneCamera.setViewportWidth((int) (plugin.sceneViewport[2] / plugin.sceneViewportScale[0]));
			sceneCamera.setViewportHeight((int) (plugin.sceneViewport[3] / plugin.sceneViewportScale[1]));
			sceneCamera.setNearPlane(plugin.orthographicProjection ? -40000 : NEAR_PLANE);
			sceneCamera.setZoom(zoom);

			// Calculate view matrix, view proj & inv matrix
			boolean hasSceneCameraChanged = sceneCamera.isViewDirty() || sceneCamera.isProjDirty();
			sceneCamera.getViewMatrix(plugin.viewMatrix);
			sceneCamera.getViewProjMatrix(plugin.viewProjMatrix);
			sceneCamera.getInvViewProjMatrix(plugin.invViewProjMatrix);
			sceneCamera.getFrustumPlanes(plugin.cameraFrustum);

			try {
				frameTimer.begin(Timer.UPDATE_ENVIRONMENT);
				environmentManager.update(ctx.sceneContext);
				frameTimer.end(Timer.UPDATE_ENVIRONMENT);

				frameTimer.begin(Timer.UPDATE_LIGHTS);
				lightManager.update(ctx.sceneContext, plugin.cameraShift, plugin.cameraFrustum);
				frameTimer.end(Timer.UPDATE_LIGHTS);

				frameTimer.begin(Timer.UPDATE_SCENE);
				sceneManager.update();
				frameTimer.end(Timer.UPDATE_SCENE);
			} catch (Exception ex) {
				log.error("Error while updating environment or lights:", ex);
				plugin.requestPluginStop();
				return;
			}

			directionalCamera.setPitch(environmentManager.currentSunAngles[0]);
			directionalCamera.setYaw(PI - environmentManager.currentSunAngles[1]);
			boolean hasDirectionalCameraChanged = directionalCamera.isViewDirty() || directionalCamera.isProjDirty();

			if (plugin.configShadowsEnabled &&
				(hasSceneCameraChanged || hasDirectionalCameraChanged) &&
				!sceneCamera.isOrthographic()
			) {
				int shadowDrawDistance = 90 * LOCAL_TILE_SIZE;

				final float[][] volumeCorners = directionalShadowCasterVolume
					.build(sceneCamera, drawDistance * LOCAL_TILE_SIZE, shadowDrawDistance);

				final float[] sceneCenter = new float[3];
				for (float[] corner : volumeCorners)
					add(sceneCenter, sceneCenter, corner);
				divide(sceneCenter, sceneCenter, (float) volumeCorners.length);

				// Reset position before transforming points
				directionalCamera.setPosition(0, 0, 0);

				float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
				float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
				float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
				float radius = 0f;
				for (float[] corner : volumeCorners) {
					radius = max(radius, distance(sceneCenter, corner));

					directionalCamera.transformPoint(corner, corner);

					minX = min(minX, corner[0]);
					maxX = max(maxX, corner[0]);

					minY = min(minY, corner[1]);
					maxY = max(maxY, corner[1]);

					minZ = min(minZ, corner[2]);
					maxZ = max(maxZ, corner[2]);
				}

				// Offset the Directional Camera by the radius of the scene
				float[] directionalFwd = directionalCamera.getForwardDirection();
				multiply(directionalFwd, directionalFwd, radius);
				add(sceneCenter, sceneCenter, directionalFwd);

				// Calculate directional size from the AABB of the scene frustum corners
				// Then snap to the nearest multiple of `LOCAL_HALF_TILE_SIZE` to prevent shimmering
				int directionalSize = (int) max(abs(maxY - minY), abs(maxX - minX), abs(maxZ - minZ));
				directionalSize = Math.round(directionalSize / (float) LOCAL_HALF_TILE_SIZE) * LOCAL_HALF_TILE_SIZE;
				directionalSize = max(8000, directionalSize); // Clamp the size to prevent going too small at reduced draw distances

				// Ignore directional size changes below the change threshold to avoid inducing shimmering
				int previousDirectionalSize = directionalCamera.getViewportWidth();
				float changeThreshold = previousDirectionalSize * 0.05f; // 10% of the previous directional size
				if (abs(directionalSize - previousDirectionalSize) < changeThreshold)
					directionalSize = previousDirectionalSize;

				// Snap Position to Shadow Texel Grid to prevent shimmering
				directionalCamera.transformPoint(sceneCenter, sceneCenter);

				float texelSize = (float) directionalSize / plugin.shadowMapResolution;
				sceneCenter[0] = (float) floor(sceneCenter[0] / texelSize + 0.5f) * texelSize;
				sceneCenter[1] = (float) floor(sceneCenter[1] / texelSize + 0.5f) * texelSize;

				directionalCamera.setPosition(directionalCamera.inverseTransformPoint(sceneCenter, sceneCenter));
				directionalCamera.setNearPlane(Math.max(0.1f, radius * 0.05f));
				directionalCamera.setFarPlane(radius * 2.0f);
				directionalCamera.setZoom(1.0f);
				directionalCamera.setViewportWidth(directionalSize);
				directionalCamera.setViewportHeight(directionalSize);

				plugin.uboGlobal.lightProjectionMatrix.set(directionalCamera.getViewProjMatrix());
			}

			shouldDrawRoofShadows =
				plugin.configShadowsEnabled &&
				plugin.configRoofShadows &&
				environmentManager.allowRoofShadows();

			plugin.uboGlobal.lightDir.set(directionalCamera.getForwardDirection());
			plugin.uboGlobal.cameraPos.set(plugin.cameraPosition);
			plugin.uboGlobal.viewMatrix.set(plugin.viewMatrix);
			plugin.uboGlobal.projectionMatrix.set(plugin.viewProjMatrix);
			plugin.uboGlobal.invProjectionMatrix.set(plugin.invViewProjMatrix);

			if (plugin.configDynamicLights != DynamicLights.NONE) {
				// Update lights UBO
				assert ctx.sceneContext.numVisibleLights <= UBOLights.MAX_LIGHTS;

				frameTimer.begin(Timer.UPDATE_LIGHTS);
				final float[] lightPosition = new float[4];
				final float[] lightColor = new float[4];
				for (int i = 0; i < ctx.sceneContext.numVisibleLights; i++) {
					final Light light = ctx.sceneContext.lights.get(i);
					final float lightRadiusSq = light.radius * light.radius;
					lightPosition[0] = light.pos[0] + plugin.cameraShift[0];
					lightPosition[1] = light.pos[1];
					lightPosition[2] = light.pos[2] + plugin.cameraShift[1];
					lightPosition[3] = lightRadiusSq;

					lightColor[0] = light.color[0] * light.strength;
					lightColor[1] = light.color[1] * light.strength;
					lightColor[2] = light.color[2] * light.strength;
					lightColor[3] = 0.0f;

					plugin.uboLights.setLight(i, lightPosition, lightColor);

					if (plugin.configTiledLighting) {
						// Pre-calculate the view space position of the light, to save having to do the multiplication in the culling shader
						lightPosition[3] = 1.0f;
						Mat4.mulVec(lightPosition, plugin.viewMatrix, lightPosition);
						lightPosition[3] = lightRadiusSq; // Restore lightRadiusSq
						plugin.uboLightsCulling.setLight(i, lightPosition, lightColor);
					}
				}

				plugin.uboLights.upload();
				plugin.uboLightsCulling.upload();
				plugin.uboGlobal.pointLightsCount.set(ctx.sceneContext.numVisibleLights);
				frameTimer.end(Timer.UPDATE_LIGHTS);
			}
		}

		// Upon logging in, the client will draw some frames with zero geometry before it hides the login screen
		if (client.getGameState().getState() >= GameState.LOGGED_IN.getState())
			plugin.hasLoggedIn = true;

		shouldRenderSkybox = scene.getSkybox() != null;

		float fogDepth = 0;
		if (!shouldRenderSkybox) {
			switch (config.fogDepthMode()) {
				case USER_DEFINED:
					fogDepth = config.fogDepth();
					break;
				case DYNAMIC:
					fogDepth = environmentManager.currentFogDepth;
					break;
			}
			fogDepth *= min(plugin.getDrawDistance(), 90) / 10.f;
		}
		plugin.uboGlobal.useFog.set(fogDepth > 0 ? 1 : 0);
		plugin.uboGlobal.fogDepth.set(fogDepth);
		plugin.uboGlobal.fogColor.set(ColorUtils.linearToSrgb(environmentManager.currentFogColor));

		plugin.uboGlobal.drawDistance.set((float) plugin.getDrawDistance());
		plugin.uboGlobal.expandedMapLoadingChunks.set(ctx.sceneContext.expandedMapLoadingChunks);
		plugin.uboGlobal.colorBlindnessIntensity.set(config.colorBlindnessIntensity() / 100.f);

		float[] waterColorHsv = ColorUtils.srgbToHsv(environmentManager.currentWaterColor);
		float lightBrightnessMultiplier = 0.8f;
		float midBrightnessMultiplier = 0.45f;
		float darkBrightnessMultiplier = 0.05f;
		float[] waterColorLight = ColorUtils.linearToSrgb(ColorUtils.hsvToSrgb(new float[] {
			waterColorHsv[0],
			waterColorHsv[1],
			waterColorHsv[2] * lightBrightnessMultiplier
		}));
		float[] waterColorMid = ColorUtils.linearToSrgb(ColorUtils.hsvToSrgb(new float[] {
			waterColorHsv[0],
			waterColorHsv[1],
			waterColorHsv[2] * midBrightnessMultiplier
		}));
		float[] waterColorDark = ColorUtils.linearToSrgb(ColorUtils.hsvToSrgb(new float[] {
			waterColorHsv[0],
			waterColorHsv[1],
			waterColorHsv[2] * darkBrightnessMultiplier
		}));
		plugin.uboGlobal.waterColorLight.set(waterColorLight);
		plugin.uboGlobal.waterColorMid.set(waterColorMid);
		plugin.uboGlobal.waterColorDark.set(waterColorDark);

		plugin.uboGlobal.gammaCorrection.set(plugin.getGammaCorrection());
		float ambientStrength = environmentManager.currentAmbientStrength;
		float directionalStrength = environmentManager.currentDirectionalStrength;
		if (config.useLegacyBrightness()) {
			float factor = config.legacyBrightness() / 20f;
			ambientStrength *= factor;
			directionalStrength *= factor;
		}
		plugin.uboGlobal.ambientStrength.set(ambientStrength);
		plugin.uboGlobal.ambientColor.set(environmentManager.currentAmbientColor);
		plugin.uboGlobal.lightStrength.set(directionalStrength);
		plugin.uboGlobal.lightColor.set(environmentManager.currentDirectionalColor);

		plugin.uboGlobal.underglowStrength.set(environmentManager.currentUnderglowStrength);
		plugin.uboGlobal.underglowColor.set(environmentManager.currentUnderglowColor);

		plugin.uboGlobal.groundFogStart.set(environmentManager.currentGroundFogStart);
		plugin.uboGlobal.groundFogEnd.set(environmentManager.currentGroundFogEnd);
		plugin.uboGlobal.groundFogOpacity.set(config.groundFog() ?
			environmentManager.currentGroundFogOpacity :
			0);

		// Lights & lightning
		plugin.uboGlobal.lightningBrightness.set(environmentManager.getLightningBrightness());

		plugin.uboGlobal.saturation.set(config.saturation() / 100f);
		plugin.uboGlobal.contrast.set(config.contrast() / 100f);
		plugin.uboGlobal.underwaterEnvironment.set(environmentManager.isUnderwater() ? 1 : 0);
		plugin.uboGlobal.underwaterCaustics.set(config.underwaterCaustics() ? 1 : 0);
		plugin.uboGlobal.underwaterCausticsColor.set(environmentManager.currentUnderwaterCausticsColor);
		plugin.uboGlobal.underwaterCausticsStrength.set(environmentManager.currentUnderwaterCausticsStrength);
		plugin.uboGlobal.elapsedTime.set((float) (plugin.elapsedTime % MAX_FLOAT_WITH_128TH_PRECISION));

		if (plugin.configColorFilter != ColorFilter.NONE) {
			plugin.uboGlobal.colorFilter.set(plugin.configColorFilter.ordinal());
			plugin.uboGlobal.colorFilterPrevious.set(plugin.configColorFilterPrevious.ordinal());
			long timeSinceChange = System.currentTimeMillis() - plugin.colorFilterChangedAt;
			plugin.uboGlobal.colorFilterFade.set(clamp(timeSinceChange / COLOR_FILTER_FADE_DURATION, 0, 1));
		}

		plugin.uboGlobal.upload();

		// Reset buffers for the next frame
		indirectDrawCmdsStaging.clear();
		sceneCmd.reset();
		alphaDiscardCmd.reset();
		transparentCmd.reset();
		directionalCmd.reset();
		gapFillerCmd.reset();
		renderState.reset();

		if(!plugin.configUseOIT) {
			// OIT Doesn't use Elements Drawing since it doesn't need to sort the face indicies
			eboAlpha.orphan();
			eboAlphaWriter.map(true);
		}

		checkGLErrors();
	}

	@Override
	public void postSceneDraw(Scene scene) {
		if (plugin.isPluginStopPending())
			return;

		try {
			jobSystem.processPendingClientCallbacks();

			WorldViewContext ctx = sceneManager.getContext(scene);
			if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
				return;

			frameTimer.begin(Timer.DRAW_POSTSCENE);
			if (scene.getWorldViewId() == WorldView.TOPLEVEL)
				postDrawTopLevel();
			frameTimer.end(Timer.DRAW_POSTSCENE);
		} catch (Throwable ex) {
			log.error("Error in postSceneDraw({}):", scene != null ? scene.getWorldViewId() : null, ex);
			plugin.requestPluginStop();
		}
	}

	private void postDrawTopLevel() {
		if (!sceneManager.isTopLevelValid() || plugin.sceneViewport == null)
			return;

		sceneFboValid = true;

		// Upload world views before rendering
		uboWorldViews.upload();

		if (eboAlphaWriter != null)
			eboAlphaWriter.flush();

		// Scene draw state to apply before all recorded commands
		if (indirectDrawCmdsStaging.position() > 0) {
			indirectDrawCmdsStaging.flip();
			indirectDrawCmds.orphan();
			indirectDrawCmds.upload(indirectDrawCmdsStaging);
		}

		frameTimer.end(Timer.DRAW_SCENE);
		frameTimer.begin(Timer.RENDER_FRAME);
		shouldRenderScene = true;

		// TODO: Add proper support for stat tracking to the FrameTimer or elsewhere
		plugin.drawnDynamicRenderableCount += modelStreamingManager.getDrawnDynamicRenderableCount();

		checkGLErrors();
	}

	private void tiledLightingPass() {
		if (!plugin.configTiledLighting || plugin.configDynamicLights == DynamicLights.NONE)
			return;

		plugin.updateTiledLightingFbo();
		assert plugin.fboTiledLighting != 0;

		frameTimer.begin(Timer.DRAW_TILED_LIGHTING);
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
		frameTimer.end(Timer.DRAW_TILED_LIGHTING);
	}

	private void directionalShadowPass() {
		final boolean shouldRenderShadows =
			plugin.configShadowsEnabled &&
			plugin.fboShadowMap != 0 &&
			environmentManager.currentDirectionalStrength > 0;

		if (shouldRenderShadows || shouldClearShadowFbo) {
			// Render to the shadow depth map
			renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboShadowMap);
			renderState.viewport.set(0, 0, plugin.shadowMapResolution, plugin.shadowMapResolution);
			renderState.apply();

			glClearDepth(1);
			glClear(GL_DEPTH_BUFFER_BIT);
			shouldClearShadowFbo = false;
		}

		if (!shouldRenderShadows)
			return;

		frameTimer.begin(Timer.RENDER_SHADOWS);

		renderState.enable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_CULL_FACE);
		renderState.depthFunc.set(GL_LEQUAL);
		renderState.ido.set(indirectDrawCmds.id);
		directionalCmd.execute(renderState);

		glBindVertexArray(0);

		renderState.enable.set(GL_DEPTH_TEST);

		shouldClearShadowFbo = true;
		frameTimer.end(Timer.RENDER_SHADOWS);
	}

	private void alphaPrePass() {
		if (transparentCmd.isEmpty())
			return;

		frameTimer.begin(Timer.DRAW_ALPHA_PRE_PASS);
		frameTimer.begin(Timer.RENDER_ALPHA_PREPASS);

		renderState.framebuffer.set(GL_FRAMEBUFFER, firstLayerDepthFBO);
		renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
		renderState.program.set(oitPrePassProgram);
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		renderState.ido.set(indirectDrawCmds.id);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.enable.set(GL_CULL_FACE);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.depthMask.set(false);
		renderState.apply();

		glClearBufferfv(GL_COLOR, 0, OIT_CLEAR_FIRST_LAYER);
		glClearBufferfv(GL_COLOR, 1, OIT_CLEAR_COVERAGE);

		renderState.enable.set(GL_BLEND);
		renderState.blendEquationi.set(0, GL_MIN);
		renderState.blendFunci.set(0, GL_ONE, GL_ONE, GL_ONE, GL_ONE);
		renderState.blendEquationi.set(1, GL_FUNC_ADD);
		renderState.blendFunci.set(1, GL_ZERO, GL_ONE_MINUS_SRC_COLOR, GL_ZERO, GL_ONE_MINUS_SRC_COLOR);
		renderState.apply();

		transparentCmd.execute(renderState);

		renderState.vao.setVao(0);
		renderState.disable.set(GL_BLEND);
		renderState.apply();

		if (plugin.msaaSamples > 1) {
			msaaaMinResolveProgram.resolve(
				renderState,
				plugin.vaoTri,
				TEXTURE_UNIT_OIT_FIRST_LAYER,
				firstLayerDepthMSTex,
				firstLayerDepthResolveTex,
				plugin.msaaSamples
			);
		}

		oitDepthMergeProgram.merge(
			renderState,
			plugin.vaoTri,
			TEXTURE_UNIT_OIT_FIRST_LAYER,
			TEXTURE_UNIT_OIT_OPAQUE_DEPTH,
			firstLayerDepthResolveTex,
			plugin.msaaSamples > 1 ? plugin.getTexSceneDepthResolve() : plugin.getTexSceneDepth(),
			firstLayerDepthTex
		);

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, 0);

		renderState.disable.set(GL_DEPTH_TEST);

		frameTimer.end(Timer.RENDER_ALPHA_PREPASS);
		frameTimer.end(Timer.DRAW_ALPHA_PRE_PASS);
	}

	private void alphaDiscardPass() {
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

	private void resolveSceneDepth() {
		if (plugin.msaaSamples > 1) {
			msaaaMinResolveProgram.resolve(
				renderState,
				plugin.vaoTri,
				TEXTURE_UNIT_OIT_OPAQUE_DEPTH,
				plugin.getTexSceneDepth(),
				plugin.getTexSceneDepthResolve(),
				plugin.msaaSamples
			);

			glActiveTexture(TEXTURE_UNIT_OIT_OPAQUE_DEPTH);
			glBindTexture(GL_TEXTURE_2D, plugin.getTexSceneDepthResolve());
		} else {
			glActiveTexture(TEXTURE_UNIT_OIT_OPAQUE_DEPTH);
			glBindTexture(GL_TEXTURE_2D, plugin.getTexSceneDepth());
		}

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void scenePass() {
		if(plugin.configUseOIT)
			sceneOpaqueProgram.use();
		else
			sceneProgram.use();

		frameTimer.begin(Timer.DRAW_SCENE);
		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		renderState.ido.set(indirectDrawCmds.id);
		renderState.apply();

		// Clear scene
		frameTimer.begin(Timer.CLEAR_SCENE);

		float[] clearColor = { 0, 0, 0 };
		if (!shouldRenderSkybox) {
			float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
			pow(clearColor, fogColor, plugin.getGammaCorrection());
		}
		glClearColor(clearColor[0], clearColor[1], clearColor[2], 1f);
		glClearDepth(0);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		frameTimer.end(Timer.CLEAR_SCENE);

		frameTimer.begin(Timer.RENDER_SCENE);

		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		renderState.toggle(GL_BLEND, !plugin.configUseOIT);

		if (!gapFillerCmd.isEmpty()) {
			renderState.depthMask.set(false);
			gapFillerCmd.execute(renderState);
			renderState.depthMask.set(true);
		}

		sceneCmd.execute(renderState);

		frameTimer.end(Timer.RENDER_SCENE);

		glBindVertexArray(0);

		// Done rendering the scene
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_BLEND);
		renderState.apply();

		frameTimer.end(Timer.DRAW_SCENE);
	}

	private void alphaPass() {
		if(!plugin.configUseOIT)
			return;

		alphaDiscardPass();

		if (!transparentCmd.isEmpty()) {
			resolveSceneDepth();
			alphaPrePass();

			frameTimer.begin(Timer.DRAW_ALPHA);
			frameTimer.begin(Timer.RENDER_ALPHA);
			renderState.program.set(sceneTransparentOITProgram);

			glActiveTexture(TEXTURE_UNIT_OIT_FIRST_LAYER);
			glBindTexture(GL_TEXTURE_2D, firstLayerDepthTex);

			renderState.depthMask.set(false);
			renderState.enable.set(GL_BLEND);
			renderState.blendFunc.set(GL_ONE, GL_ONE, GL_ONE, GL_ONE);
			renderState.blendEquation.set(GL_FUNC_ADD);

			renderState.framebuffer.set(GL_FRAMEBUFFER, transparentFBO);
			renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
			renderState.apply();
			for (int k = 0; k < OIT_BIN_COUNT; k++)
				glClearBufferfv(GL_COLOR, k, OIT_CLEAR_BIN);

			renderState.enable.set(GL_DEPTH_TEST);
			renderState.enable.set(GL_CULL_FACE);
			renderState.depthFunc.set(GL_GEQUAL);
			renderState.depthMask.set(false);

			transparentCmd.execute(renderState);

			renderState.depthMask.set(true);
			frameTimer.end(Timer.RENDER_ALPHA);
			frameTimer.end(Timer.DRAW_ALPHA);

			frameTimer.begin(Timer.DRAW_ALPHA_COMPOSITE);
			frameTimer.begin(Timer.RENDER_ALPHA_COMPOSITE);

			final boolean sampleShading = plugin.msaaSamples > 1;

			renderState.program.set(sampleShading ? oitCompositeSampleShadingProgram : oitCompositeShaderProgram);
			renderState.depthFunc.set(GL_ALWAYS);
			renderState.enable.set(GL_BLEND);
			renderState.blendFunc.set(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

			glActiveTexture(TEXTURE_UNIT_OIT_NET_COVERAGE);
			glBindTexture(sampleShading ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D, sampleShading ? netCoverageMSTex : netCoverageTex);

			glActiveTexture(TEXTURE_UNIT_OIT_COLOR_ACCUM);
			glBindTexture(
				sampleShading ? GL_TEXTURE_2D_MULTISAMPLE_ARRAY : GL_TEXTURE_2D_ARRAY,
				sampleShading ? colorAccumArrayMSTex : colorAccumArrayTex
			);

			renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboScene);
			renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
			renderState.vao.setVao(plugin.vaoTri);
			renderState.apply();

			if (sampleShading) {
				renderState.enable.set(GL_SAMPLE_SHADING_ARB);
				renderState.sampleShading.set(1.0f);
			}

			glDrawArrays(GL_TRIANGLES, 0, 3);
			frameTimer.end(Timer.RENDER_ALPHA_COMPOSITE);
			frameTimer.end(Timer.DRAW_ALPHA_COMPOSITE);
		}

		renderState.disable.set(GL_SAMPLE_SHADING_ARB);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_CULL_FACE);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.depthMask.set(true);
		renderState.blendFunc.set(GL_ONE, GL_ZERO, GL_ONE, GL_ZERO);
		renderState.blendEquation.set(GL_FUNC_ADD);
		renderState.apply();
		frameTimer.end(Timer.DRAW_ALPHA);
	}

	@Override
	public boolean zoneInFrustum(int zx, int zz, int maxY, int minY) {
		if (plugin.isPluginStopPending())
			return false;

		try {
			if (!sceneManager.isTopLevelValid())
				return false;

			WorldViewContext ctx = sceneManager.getRoot();
			if (plugin.enableDetailedTimers) frameTimer.begin(Timer.VISIBILITY_CHECK);
			int minX = zx * CHUNK_SIZE - ctx.sceneContext.sceneOffset;
			int minZ = zz * CHUNK_SIZE - ctx.sceneContext.sceneOffset;
			if (ctx.sceneContext.currentArea != null) {
				var base = ctx.sceneContext.sceneBase;
				assert base != null;
				boolean inArea = ctx.sceneContext.currentArea.intersects(
					true, base[0] + minX, base[1] + minZ, base[0] + minX + 7, base[1] + minZ + 7);
				if (!inArea) {
					if (plugin.enableDetailedTimers) frameTimer.end(Timer.VISIBILITY_CHECK);
					return false;
				}
			}

			Zone zone = ctx.zones[zx][zz];
			if (plugin.freezeCulling)
				return zone.inSceneFrustum || zone.inShadowFrustum;

			minX *= LOCAL_TILE_SIZE;
			minZ *= LOCAL_TILE_SIZE;
			int maxX = minX + CHUNK_SIZE * LOCAL_TILE_SIZE;
			int maxZ = minZ + CHUNK_SIZE * LOCAL_TILE_SIZE;
			if (zone.hasWater) {
				maxY += ProceduralGenerator.MAX_DEPTH;
				minY -= ProceduralGenerator.MAX_DEPTH;
			}

			final int PADDING = 4 * LOCAL_TILE_SIZE;
			zone.inSceneFrustum = sceneCamera.intersectsAABB(
				minX - PADDING, minY, minZ - PADDING, maxX + PADDING, maxY, maxZ + PADDING);

			if (zone.inSceneFrustum) {
				if (plugin.enableDetailedTimers)
					frameTimer.end(Timer.VISIBILITY_CHECK);
				return zone.inShadowFrustum = true;
			}

			if(plugin.configUseOIT) {
				// when using OIT we don't need to multi loc unless the zone isn't visible
				int offset = ctx.sceneContext.sceneOffset >> 3;
				zone.multizoneLocs(ctx.sceneContext, zx - offset, zz - offset, sceneCamera, ctx.zones);
			}

			if (plugin.configShadowsEnabled && plugin.configExpandShadowDraw) {
				zone.inShadowFrustum = directionalCamera.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
				if (zone.inShadowFrustum) {
					int centerX = minX + (maxX - minX) / 2;
					int centerY = minY + (maxY - minY) / 2;
					int centerZ = minZ + (maxZ - minZ) / 2;
					zone.inShadowFrustum = directionalShadowCasterVolume.intersectsPoint(centerX, centerY, centerZ);
				}
				if (plugin.enableDetailedTimers)
					frameTimer.end(Timer.VISIBILITY_CHECK);
				return zone.inShadowFrustum;
			}

			if (plugin.enableDetailedTimers)
				frameTimer.end(Timer.VISIBILITY_CHECK);
			if (plugin.orthographicProjection)
				return zone.inSceneFrustum = true;
		} catch (Throwable ex) {
			log.error("Error in zoneInFrustum({}, {}, {}, {}):", zx, zz, maxY, minY, ex);
			plugin.requestPluginStop();
		}
		return false;
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz) {
		if (plugin.isPluginStopPending())
			return;

		try {
			WorldViewContext ctx = sceneManager.getContext(scene);
			if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
				return;

			Zone z = ctx.zones[zx][zz];
			if (!z.initialized || z.sizeO == 0)
				return;

			frameTimer.begin(Timer.DRAW_ZONE_OPAQUE);
			if (!sceneManager.isRoot(ctx) || z.inSceneFrustum) {
				z.renderOpaque(sceneCmd, ctx, false);

				if (z.hasGapFiller)
					z.renderOpaqueLevel(gapFillerCmd, Zone.LEVEL_GAP_FILLER);
			}

			final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
			if (!isSquashed && (!sceneManager.isRoot(ctx) || z.inShadowFrustum)) {
				directionalCmd.SetShader(fastShadowProgram);
				z.renderOpaque(directionalCmd, ctx, shouldDrawRoofShadows);
			}
			frameTimer.end(Timer.DRAW_ZONE_OPAQUE);

			checkGLErrors();
		} catch (Throwable ex) {
			log.error("Error in drawZoneOpaque({}, {}, {}):", zx, zz, scene != null ? scene.getWorldViewId() : null, ex);
			plugin.requestPluginStop();
		}
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		if (plugin.isPluginStopPending())
			return;

		try {
			final WorldViewContext ctx = sceneManager.getContext(scene);
			if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
				return;

			final Zone z = ctx.zones[zx][zz];
			if (!z.initialized)
				return;

			frameTimer.begin(Timer.DRAW_ZONE_ALPHA);
			final boolean renderWater = z.inSceneFrustum && level == 0 && z.hasWater;
			if (renderWater)
				z.renderOpaqueLevel(plugin.configUseOIT ? transparentCmd : sceneCmd, Zone.LEVEL_WATER_SURFACE);

			modelStreamingManager.ensureAsyncUploadsComplete(z);

			final boolean hasAlpha = z.sizeA != 0 || !z.alphaModels.isEmpty();
			if (hasAlpha) {
				final int offset = ctx.sceneContext.sceneOffset >> 3;
				final int zoneX = zx - offset;
				final int zoneZ = zz - offset;
				// Only sort if the alpha will be directly visible, since shadows don't require sorting
				if (level == 0 && (!sceneManager.isRoot(ctx) || z.inSceneFrustum))
					z.alphaSort(zoneX, zoneZ, sceneCamera, plugin.configUseOIT);

				final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
				if (!isSquashed && (!sceneManager.isRoot(ctx) || z.inShadowFrustum)) {
					directionalCmd.SetShader(plugin.configShadowMode == ShadowMode.DETAILED ? detailedShadowProgram : fastShadowProgram);
					z.renderAlphaModels(directionalCmd, zoneX, zoneZ, level, ctx, Zone.ALPHA_DRAW_ROOF);
				}

				if (!sceneManager.isRoot(ctx) || z.inSceneFrustum) {
					if(plugin.configUseOIT) {
						// OIT is a Composite pass, so we don't need to sort alpha models or even write depth
						// However Alpha Discard needs to be drawn as part of the Scene Pass since Opaque faces break OIT
						z.renderAlphaModels(transparentCmd, zoneX, zoneZ, level, ctx, Zone.ALPHA_DRAW_BLEND_ONLY);
						z.renderAlphaModels(alphaDiscardCmd, zoneX, zoneZ, level, ctx, Zone.ALPHA_DRAW_DISCARD_ONLY);
					} else {
						if (renderWater) {
							// Water is currently drawn with depth writes & depth testing enabled, and as such, alpha models and the water plane
							// can Z-fight depending on draw order. To avoid alpha models above water causing the water surface to fail its
							// depth test, we disable depth writes for alpha models and rely on correct back to front ordering of the zones
							sceneCmd.DepthMask(false);
							z.renderSortedAlpha(sceneCmd, zx - offset, zz - offset, level, ctx, false, false);
							sceneCmd.DepthMask(true);
						} else {
							// Draw alpha models in two passes, first blending colors correctly, then writing depth for subsequent opaque models
							// to test against. This is necessary because opaque models on higher planes can be drawn later

							// Write color without depth writes
							sceneCmd.DepthMask(false);
							sceneCmd.ColorMask(true, true, true, true);
							z.renderSortedAlpha(sceneCmd, zx - offset, zz - offset, level, ctx, false, false);

							// Write depth without color
							sceneCmd.DepthMask(true);
							sceneCmd.ColorMask(false, false, false, false);
							z.renderSortedAlpha(sceneCmd, zx - offset, zz - offset, level, ctx, true, false);

							// Restore color writes
							sceneCmd.ColorMask(true, true, true, true);
						}
					}
				}
			}
			frameTimer.end(Timer.DRAW_ZONE_ALPHA);

			checkGLErrors();
		} catch (Throwable ex) {
			log.error("Error in drawZoneAlpha({}, {}, {}, {}):", zx, zz, level, scene != null ? scene.getWorldViewId() : null, ex);
			plugin.requestPluginStop();
		}
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass) {
		if (plugin.isPluginStopPending())
			return;

		try {
			WorldViewContext ctx = sceneManager.getContext(scene);
			if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
				return;

			frameTimer.begin(Timer.DRAW_PASS);

			switch (pass) {
				case DrawCallbacks.PASS_OPAQUE:
					directionalCmd.SetShader(fastShadowProgram);
					directionalCmd.ExecuteSubCommandBuffer(ctx.vaoDirectionalCmd);

					sceneCmd.ExecuteSubCommandBuffer(ctx.vaoSceneCmd);
					break;
				case DrawCallbacks.PASS_ALPHA:
					modelStreamingManager.ensureAsyncUploadsComplete(null);

					if (sceneManager.isRoot(ctx))
						frameTimer.begin(Timer.UNMAP_ROOT_CTX);

					ctx.unmap();

					if (sceneManager.isRoot(ctx))
						frameTimer.end(Timer.UNMAP_ROOT_CTX);

					// Draw opaque
					ctx.drawAll(VAO_OPAQUE, ctx.vaoSceneCmd);
					ctx.drawAll(VAO_OPAQUE, ctx.vaoDirectionalCmd);
					ctx.drawAll(VAO_PLAYER, ctx.vaoDirectionalCmd);

					// Draw shadow-only models
					ctx.drawAll(VAO_SHADOW, ctx.vaoDirectionalCmd);

					if(plugin.configUseOIT) // Draw all alpha-models regardless of order when using OIT
						ctx.drawAll(VAO_ALPHA, transparentCmd);

					// Draw players with sorted alpha, without writing depth
					ctx.vaoSceneCmd.DepthMask(false);
					ctx.drawAll(VAO_PLAYER, ctx.vaoSceneCmd);
					ctx.vaoSceneCmd.DepthMask(true);

					// Redraw players, this time only writing depth, for correct ordering with the background
					ctx.vaoSceneCmd.ColorMask(false, false, false, false);
					ctx.drawAll(VAO_PLAYER, ctx.vaoSceneCmd);
					ctx.vaoSceneCmd.ColorMask(true, true, true, true);

					for (int zx = 0; zx < ctx.sizeX; ++zx)
						for (int zz = 0; zz < ctx.sizeZ; ++zz)
							ctx.zones[zx][zz].postAlphaPass();
					break;
			}

			frameTimer.end(Timer.DRAW_PASS);
			checkGLErrors();
		} catch (Throwable ex) {
			log.error("Error in drawPass({}, {}, {}):", projection, scene != null ? scene.getWorldViewId() : null, pass, ex);
			plugin.requestPluginStop();
		}
	}

	@Override
	public void drawDynamic(
		int renderThreadId,
		Projection projection,
		Scene scene,
		TileObject tileObject,
		Renderable r,
		Model m,
		int orient,
		int x,
		int y,
		int z
	) {
		if (plugin.isPluginStopPending())
			return;

		final long start = System.nanoTime();
		try {
			modelStreamingManager.drawTemp(renderThreadId, projection, scene, tileObject, r, m, orient, x, y, z);
		} catch (Exception ex) {
			log.error("Error in drawDynamic:", ex);
		} finally {
			frameTimer.add(renderThreadId == -1 ? Timer.DRAW_DYNAMIC : Timer.DRAW_DYNAMIC_ASYNC, System.nanoTime() - start);
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orientation, int x, int y, int z) {
		if (plugin.isPluginStopPending())
			return;

		frameTimer.begin(Timer.DRAW_TEMP);
		try {
			modelStreamingManager.drawTemp(-1, worldProjection, scene, gameObject, gameObject.getRenderable(), m, orientation, x, y, z);
		} catch (Exception ex) {
			log.error("Error in drawTemp:", ex);
		} finally {
			frameTimer.end(Timer.DRAW_TEMP);
		}
	}

	@Override
	public void draw(int overlayColor) {
		if (plugin.isPluginStopPending())
			return;

		try {
			final GameState gameState = client.getGameState();
			if (gameState == GameState.STARTING) {
				frameTimer.end(Timer.DRAW_FRAME);
				return;
			}

			try {
				plugin.prepareInterfaceTexture();
			} catch (Exception ex) {
				// Fixes: https://github.com/runelite/runelite/issues/12930
				// Gracefully Handle loss of opengl buffers and context
				log.warn("prepareInterfaceTexture exception", ex);
				plugin.restartPlugin();
				return;
			}

			frameTimer.begin(Timer.DRAW_SUBMIT);
			if (shouldRenderScene) {
				tiledLightingPass();
				directionalShadowPass();
				scenePass();
				alphaPass();
			}

			if (sceneFboValid && plugin.sceneResolution != null && plugin.sceneViewport != null) {
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

				if (APPLE) {
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
					config.sceneScalingMode().glFilter
				);
			} else {
				glBindFramebuffer(GL_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));
				glClearColor(0, 0, 0, 1);
				glClear(GL_COLOR_BUFFER_BIT);
			}

			plugin.drawUi(overlayColor);
			frameTimer.end(Timer.DRAW_SUBMIT);

			jobSystem.processPendingClientCallbacks();

			frameTimer.end(Timer.DRAW_FRAME);
			frameTimer.end(Timer.RENDER_FRAME);

			try {
				frameTimer.begin(Timer.SWAP_BUFFERS);
				plugin.awtContext.swapBuffers();
				frameTimer.end(Timer.SWAP_BUFFERS);
				drawManager.processDrawComplete(plugin::screenshot);
			} catch (RuntimeException ex) {
				// this is always fatal
				if (!plugin.canvas.isValid()) {
					// this might be AWT shutting down on VM shutdown, ignore it
					return;
				}

				log.error("Unable to swap buffers:", ex);
			}

			glBindFramebuffer(GL_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));

			frameTimer.endFrameAndReset();
			checkGLErrors();

			shouldRenderScene = false;
		} catch (Throwable ex) {
			log.error("Error in draw({}):", overlayColor, ex);
			plugin.requestPluginStop();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		GameState state = gameStateChanged.getGameState();
		if (state.getState() < GameState.LOADING.getState()) {
			// this is to avoid scene fbo blit when going from <loading to >=loading,
			// but keep it when doing >loading to loading
			sceneFboValid = false;
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz) {
		sceneManager.invalidateZone(scene, zx, zz);
	}

	@Override
	public void reloadScene() {
		if (sceneManager.isTopLevelValid() && client.getGameState().getState() >= GameState.LOGGED_IN.getState())
			sceneManager.reloadScene();
	}

	@Override
	public SceneContext getSceneContext() {
		return sceneManager.getSceneContext();
	}

	@Override
	public boolean isLoadingScene() {
		return sceneManager.isLoadingScene();
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene) {
		if (!plugin.isActive())
			return;

		try {
			sceneManager.loadScene(worldView, scene);
		} catch (OutOfMemoryError oom) {
			log.error(
				"Ran out of memory while generating scene data (32-bit: {}, low memory mode: {})",
				HDUtils.is32Bit(), plugin.useLowMemoryMode, oom
			);
			plugin.displayOutOfMemoryMessage();
			plugin.stopPlugin();
		} catch (Throwable ex) {
			log.error("Error while loading scene:", ex);
			plugin.stopPlugin();
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView) {
		try {
			sceneManager.despawnWorldView(worldView);
		} catch (Throwable ex) {
			log.error("Error in despawnWorldView({}):", worldView.getId(), ex);
			plugin.requestPluginStop();
		}
	}

	@Override
	public void swapScene(Scene scene) {
		try {
			sceneManager.swapScene(scene);
		} catch (Throwable ex) {
			log.error("Error during swapScene:", ex);
			plugin.stopPlugin();
		}
	}
}