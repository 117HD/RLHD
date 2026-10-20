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
import rs117.hd.opengl.shader.TerrainShadowShaderProgram;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.Renderer;
import rs117.hd.renderer.SkyRenderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SkyManager;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
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

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.ARBSampleShading.GL_SAMPLE_SHADING_ARB;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.APPLE;
import static rs117.hd.HdPlugin.COLOR_FILTER_FADE_DURATION;
import static rs117.hd.HdPlugin.NEAR_PLANE;
import static rs117.hd.HdPlugin.ORTHOGRAPHIC_ZOOM;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
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
	private static final long FENCE_SYNC_TIMEOUT_NS = SECONDS.toNanos(1);

	private static final float[] OIT_CLEAR_FIRST_LAYER = { 1e6f, 0f, 0f, 0f };
	private static final float[] OIT_CLEAR_FARTHEST_LAYER = { 0f, 0f, 0f, 0f };
	private static final float[] OIT_CLEAR_COVERAGE = { 1f, 0f, 0f, 0f };
	private static final float[] OIT_CLEAR_BIN = { 0f, 0f, 0f, 0f };
	private static final int[] OIT_CLEAR_STENCIL = { 0 };
	public static final int OIT_BIN_COUNT = 4;

	private static int TEXTURE_UNIT_COUNT = HdPlugin.TEXTURE_UNIT_COUNT;
	public static final int TEXTURE_UNIT_TEXTURED_FACES = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_OIT_FIRST_LAYER = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_OIT_NET_COVERAGE = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_OIT_COLOR_ACCUM = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_OIT_FARTHEST_LAYER = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;

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
	private SkyManager skyManager;

	@Inject
	private SkyRenderer skyRenderer;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneShaderProgram sceneProgram;

	@Inject
	private SceneShaderProgram.Water waterProgram;

	@Inject
	private SceneShaderProgram.Opaque sceneOpaqueProgram;

	@Inject
	private SceneShaderProgram.TransparentOIT sceneTransparentOITProgram;

	@Inject
	private SceneShaderProgram.TransparentOIT.Water sceneTransparentOITWaterProgram;

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
	private OITPrePassShaderProgram.Water oitPrePassWaterProgram;

	@Inject
	private ResolveShaderProgram.Min msaaaMinResolveProgram;

	@Inject
	private ResolveShaderProgram.Max msaaaMaxResolveProgram;

	@Inject
	private OitDepthMergeShaderProgram oitDepthMergeProgram;

	@Inject
	private TerrainShadowShaderProgram terrainShadowProgram;

	@Inject
	private JobSystem jobSystem;

	@Inject
	private UBOWorldViews uboWorldViews;

	public final Camera sceneCamera = new Camera().setReverseZ(true);
	public final Camera directionalCamera = new Camera().setOrthographic(true);
	public final ShadowCasterVolume directionalShadowCasterVolume = new ShadowCasterVolume(directionalCamera);
	private int shadowDrawDistance;

	public final RenderState renderState = new RenderState();
	public final CommandBuffer sceneCmd = new CommandBuffer("Scene");
	public final CommandBuffer alphaDiscardCmd = new CommandBuffer("AlphaDiscard");
	public final CommandBuffer transparentCmd = new CommandBuffer("Transparent");
	public final CommandBuffer waterCmd = new CommandBuffer("Water");
	public final CommandBuffer gapFillerCmd = new CommandBuffer("GapFiller");
	public final CommandBuffer directionalCmd = new CommandBuffer("Directional");
	public final CommandBuffer terrainShadowCmd = new CommandBuffer("TerrainShadow");

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
	private int farthestLayerDepthMSTex;
	private int farthestLayerDepthResolveTex;

	private int netCoverageTex;
	private int netCoverageMSTex;

	private boolean sceneFboValid;
	private boolean shouldRenderScene;
	private boolean shouldRenderVanillaSkybox;
	private boolean shouldClearShadowFbo;
	private boolean shouldDrawRoofShadows;

	private final long[] frameFences = new long[FRAMES_IN_FLIGHT];
	private long
		lastFenceCreationWarningMs,
		lastFenceWaitWarningMs,
		lastFenceTimeoutWarningMs;
	private int
		fenceCreationFailureCount,
		fenceWaitFailureCount,
		fenceTimeoutCount;

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
		transparentCmd.setFrameTimer(frameTimer);
		waterCmd.setFrameTimer(frameTimer);
		gapFillerCmd.setFrameTimer(frameTimer);
		directionalCmd.setFrameTimer(frameTimer);
		terrainShadowCmd.setFrameTimer(frameTimer);

		jobSystem.startUp(config.cpuUsageLimit());
		uboWorldViews.initialize(UNIFORM_BLOCK_WORLD_VIEWS);
		sceneManager.initialize(uboWorldViews);
		modelStreamingManager.initialize();

		skyRenderer.initialize();

		// Force updates that only run when the cameras change
		sceneCamera.setDirty();
		directionalCamera.setDirty();
	}

	@Override
	public void destroy() {
		destroyBuffers();
		skyRenderer.destroy();

		jobSystem.shutDown();
		modelStreamingManager.destroy();
		sceneManager.destroy();
		uboWorldViews.destroy();

		for (int i = 0; i < frameFences.length; i++) {
			if (frameFences[i] != 0)
				glDeleteSync(frameFences[i]);
			frameFences[i] = 0;
		}

		if (SceneUploader.POOL != null)
			SceneUploader.POOL.destroy();

		if (FacePrioritySorter.POOL != null)
			FacePrioritySorter.POOL.destroy();
	}

	private void waitForFrameFence() {
		// Drain existing fences even if synchronization has been disabled
		final int slot = plugin.frame % FRAMES_IN_FLIGHT;
		final long fence = frameFences[slot];
		if (fence == 0)
			return;

		try (var ignored = frameTimer.begin(Timer.FRAME_FENCE_WAIT)) {
			final long deadline = System.nanoTime() + FENCE_SYNC_TIMEOUT_NS;
			int flags = GL_SYNC_FLUSH_COMMANDS_BIT;
			boolean waitFailed = false;
			long remaining;

			while ((remaining = deadline - System.nanoTime()) > 0) {
				final int result = glClientWaitSync(fence, flags, remaining);
				flags = 0; // only flush commands on the first sync

				if (result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED)
					return;

				if (result == GL_WAIT_FAILED) {
					waitFailed = true;
					break;
				}
			}

			long now = System.currentTimeMillis();
			if (waitFailed) {
				fenceWaitFailureCount++;
				if (fenceWaitFailureCount == 1 || now - lastFenceWaitWarningMs >= 1000) {
					log.warn("glClientWaitSync failed, count {}. Falling back to glFinish...", fenceWaitFailureCount);
					lastFenceWaitWarningMs = now;
				}
			} else {
				fenceTimeoutCount++;
				if (fenceTimeoutCount == 1 || now - lastFenceTimeoutWarningMs >= 1000) {
					log.warn(
						"Timed out waiting {}ms for frame fence, count {}. Falling back to glFinish...",
						NANOSECONDS.toMillis(FENCE_SYNC_TIMEOUT_NS), fenceTimeoutCount
					);
					lastFenceTimeoutWarningMs = now;
				}
			}

			glFinish();
		} finally {
			glDeleteSync(fence);
			frameFences[slot] = 0;
		}
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
			.addInclude(
				"WORLD_VIEW_GETTER",
				() -> plugin.generateGetter("WorldView", "uboWorldViews.Array", UBOWorldViews.MAX_SIMULTANEOUS_WORLD_VIEWS)
			)
			.addUniformBuffer(uboWorldViews);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneProgram.compile(includes);
		waterProgram.compile(includes);
		sceneOpaqueProgram.compile(includes);
		sceneTransparentOITProgram.compile(includes);
		sceneTransparentOITWaterProgram.compile(includes);
		sceneAlphaDiscardProgram.compile(includes);
		fastShadowProgram.compile(includes);
		detailedShadowProgram.compile(includes);
		terrainShadowProgram.compile(includes);
		oitCompositeShaderProgram.compile(includes);
		oitCompositeSampleShadingProgram.compile(includes);
		oitPrePassProgram.compile(includes);
		oitPrePassWaterProgram.compile(includes);
		msaaaMinResolveProgram.compile(includes);
		msaaaMaxResolveProgram.compile(includes);
		oitDepthMergeProgram.compile(includes);
		skyRenderer.initializeShaders(includes);
	}

	@Override
	public void destroyShaders() {
		sceneProgram.destroy();
		waterProgram.destroy();
		sceneOpaqueProgram.destroy();
		sceneTransparentOITProgram.destroy();
		sceneTransparentOITWaterProgram.destroy();
		sceneAlphaDiscardProgram.destroy();
		fastShadowProgram.destroy();
		detailedShadowProgram.destroy();
		terrainShadowProgram.destroy();
		oitCompositeShaderProgram.destroy();
		oitCompositeSampleShadingProgram.destroy();
		oitPrePassProgram.destroy();
		oitPrePassWaterProgram.destroy();
		msaaaMinResolveProgram.destroy();
		msaaaMaxResolveProgram.destroy();
		oitDepthMergeProgram.destroy();
		skyRenderer.destroyShaders();
	}

	private void initializeBuffers() {
		eboAlpha = new GLBuffer.EBO("eboAlpha", GL_STREAM_DRAW);
		eboAlpha.initialize(MiB);
		eboAlphaWriter = new GLMappedBufferIntWriter(eboAlpha);

		if (SUPPORTS_INDIRECT_DRAW) {
			indirectDrawCmds = new GLBuffer("indirectDrawCmds", GL40.GL_DRAW_INDIRECT_BUFFER, GL_STREAM_DRAW).initialize(MiB);
			indirectDrawCmdsStaging = new GpuIntBuffer();
		}
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

		if (keys.contains(KEY_SHADOW_RESOLUTION) || keys.contains(KEY_SHADOW_MODE))
			directionalCamera.setDirty();

		skyRenderer.processConfigChanges(keys);
	}

	@Override
	public void preSceneDraw(
		Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds
	) {
		if (plugin.isPluginStopPending())
			return;

		try {
			boolean isTopLevel = scene.getWorldViewId() == WorldView.TOPLEVEL;

			WorldViewContext ctx = sceneManager.getContext(scene);
			if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading) {
				// When triggering plugin restarts in rapid succession, it can end up in a state where no scene is loaded initially
				if (isTopLevel && client.getGameState() == GameState.LOGGED_IN)
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

			if (isTopLevel) {
				preSceneDrawTopLevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
			} else {
				ctx.updateProjection(client.getTopLevelWorldView());
			}

			ctx.completeInvalidation();

			if(!plugin.configUseOIT) {
				int offset = ctx.sceneContext.sceneOffset >> 3;
				for (int zx = 0; zx < ctx.sizeX; ++zx)
					for (int zz = 0; zz < ctx.sizeZ; ++zz)
						ctx.zones[zx][zz].multizoneLocs(ctx.sceneContext, zx - offset, zz - offset, sceneCamera, ctx.zones);

				ctx.sortStaticAlphaModels(sceneCamera);
			}

			ctx.map();

			if (isTopLevel && shouldRenderVanillaSkybox) {
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

				// Vanilla skyboxes require alpha blending
				sceneCmd.Enable(GL_BLEND);
				sceneCmd.DepthMask(false);
				ctx.drawAll(VAO_PRESCENE, sceneCmd);
				sceneCmd.DepthMask(true);
				sceneCmd.Disable(GL_BLEND);
			}

			frameTimer.end(Timer.DRAW_PRESCENE);
		} catch (Throwable ex) {
			plugin.requestPluginStop(
				String.format("Error in preSceneDraw(%s)", scene != null ? scene.getWorldViewId() : null),
				ex
			);
		}
	}

	private void updateTransparentFBO(boolean force) {
		if (!force
			&& transparentFBO != 0
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
			GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
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
			GL_TEXTURE_2D, 0, GL_RG32F,
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

		glActiveTexture(TEXTURE_UNIT_OIT_FARTHEST_LAYER);
		farthestLayerDepthResolveTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, farthestLayerDepthResolveTex);
		labelObject(GL_TEXTURE, farthestLayerDepthResolveTex, "OIT Farthest Layer Depth Resolve");
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

			glActiveTexture(TEXTURE_UNIT_OIT_FARTHEST_LAYER);
			farthestLayerDepthMSTex = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, farthestLayerDepthMSTex);
			labelObject(GL_TEXTURE, farthestLayerDepthMSTex, "OIT Farthest Layer Depth MS");
			glTexImage2DMultisample(
				GL_TEXTURE_2D_MULTISAMPLE, transparentSamples, GL_R32F,
				plugin.sceneResolution[0], plugin.sceneResolution[1], true
			);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D_MULTISAMPLE, farthestLayerDepthMSTex, 0);
		} else {
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, firstLayerDepthResolveTex, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, netCoverageTex, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, farthestLayerDepthResolveTex, 0);
		}
		glFramebufferTexture2D(
			GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
			transparentSamples > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D,
			plugin.getTexSceneDepth(), 0
		);
		glDrawBuffers(new int[] { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2 });
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

	private void preSceneDrawTopLevel(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw
	) {
		jobSystem.processPendingClientCallbacks();

		scene.setDrawDistance(plugin.getDrawDistance());

		waitForFrameFence();

		boolean sceneResized = plugin.updateSceneFbo();
		updateTransparentFBO(sceneResized);

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

			boolean hasFocalPointChanged =
				plugin.cameraFocalPoint[0] != (int) client.getCameraFocalPointX() ||
				plugin.cameraFocalPoint[1] != (int) client.getCameraFocalPointZ();
			copyTo(plugin.cameraFocalPoint, ivec((int) client.getCameraFocalPointX(), (int) client.getCameraFocalPointZ()));
			Arrays.fill(plugin.cameraShift, 0);

			float zoom = client.get3dZoom();
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

				frameTimer.begin(Timer.UPDATE_SKY);
				skyManager.update();
				frameTimer.end(Timer.UPDATE_SKY);

				frameTimer.begin(Timer.UPDATE_SCENE);
				sceneManager.update();
				frameTimer.end(Timer.UPDATE_SCENE);
			} catch (Exception ex) {
				plugin.requestPluginStop("Error while updating environment, sky or scene", ex);
				return;
			}

			skyRenderer.prepareFrame(plugin.uboGlobal);
			skyManager.updateDirectionalCamera(directionalCamera, skyRenderer.usesMoonShadows);

			boolean hasDirectionalCameraChanged = directionalCamera.isViewDirty() || directionalCamera.isProjDirty();
			boolean hasCullingModeChanged = plugin.configConservativeShadowCulling != directionalShadowCasterVolume.isConservative;

			float focalDistance = sceneCamera.distanceTo(
				client.getCameraFocalPointX(),
				client.getCameraFocalPointY(),
				client.getCameraFocalPointZ()
			);
			int desiredShadowDistance = ceil(min(90, plugin.getDrawDistance()) * LOCAL_TILE_SIZE + focalDistance);

			boolean needsUpdate =
				hasSceneCameraChanged ||
				hasFocalPointChanged ||
				hasDirectionalCameraChanged ||
				hasCullingModeChanged ||
				shadowDrawDistance != desiredShadowDistance;

			if (plugin.configShadowsEnabled && !sceneCamera.isOrthographic() && needsUpdate) {
				shadowDrawDistance = desiredShadowDistance;
				plugin.uboGlobal.shadowDrawDistance.set((float) shadowDrawDistance);
				directionalShadowCasterVolume.build(
					sceneCamera,
					shadowDrawDistance,
					shadowDrawDistance,
					plugin.configConservativeShadowCulling
				);

				// Reset position before recalculating shadow volume bounds
				directionalCamera.setPosition(0, 0, 0);

				// Fit only receivers inside draw distance, not the empty sides of the zoomed-out
				// frustum. Allow an extra chunk for zone culling and four tiles for model overhang.
				float receiverRadius = (min(90, plugin.getDrawDistance()) + CHUNK_SIZE + 4) * LOCAL_TILE_SIZE;
				float[] bounds = new float[6];
				directionalShadowCasterVolume.getReceiverBounds(
					bounds,
					plugin.cameraFocalPoint[0] - receiverRadius, plugin.cameraFocalPoint[1] - receiverRadius,
					plugin.cameraFocalPoint[0] + receiverRadius, plugin.cameraFocalPoint[1] + receiverRadius
				);
				float minX = bounds[0], maxX = bounds[3];
				float minY = bounds[1], maxY = bounds[4];
				float minZ = bounds[2], maxZ = bounds[5];

				// Fit the receiver footprint with room for filtering and texel snapping.
				int directionalSize =
					ceil((max(maxX - minX, maxY - minY) + 8 * LOCAL_TILE_SIZE) / LOCAL_HALF_TILE_SIZE) * LOCAL_HALF_TILE_SIZE;

				// Ignore directional size changes below the change threshold to avoid inducing shimmering
				int previousDirectionalSize = directionalCamera.getViewportWidth();
				float changeThreshold = previousDirectionalSize * 0.05f;
				if (abs(directionalSize - previousDirectionalSize) < changeThreshold && directionalSize <= previousDirectionalSize)
					directionalSize = previousDirectionalSize;

				// Bounds above are already in light-view space; both modes use the same projection.
				float[] lightSpaceCenter = {
					(minX + maxX) * .5f,
					(minY + maxY) * .5f,
					minZ - shadowDrawDistance - 4 * LOCAL_TILE_SIZE - 2
				};
				// Snap in light-view XY, then transform back to world space for the camera position.
				float texelSize = (float) directionalSize / plugin.shadowMapResolution;
				lightSpaceCenter[0] = (float) floor(lightSpaceCenter[0] / texelSize + 0.5f) * texelSize;
				lightSpaceCenter[1] = (float) floor(lightSpaceCenter[1] / texelSize + 0.5f) * texelSize;
				float shadowDepth = maxZ + 4 * LOCAL_TILE_SIZE - lightSpaceCenter[2];

				directionalCamera.setPosition(directionalCamera.inverseTransformPoint(lightSpaceCenter, lightSpaceCenter));
				// Mat4.orthographic maps view-space Z = 2 * near/far to the clip planes.
				directionalCamera.setNearPlane(1);
				directionalCamera.setFarPlane(shadowDepth * .5f);
				directionalCamera.setZoom(1);
				directionalCamera.setViewportWidth(directionalSize);
				directionalCamera.setViewportHeight(directionalSize);

				plugin.uboGlobal.lightProjectionMatrix.set(directionalCamera.getViewProjMatrix());
				plugin.uboGlobal.invLightProjectionMatrix.set(directionalCamera.getInvViewProjMatrix());
				float depthRange = 2 * (directionalCamera.getFarPlane() - directionalCamera.getNearPlane());
				plugin.uboGlobal.shadowBiasScale.set(texelSize / depthRange);
			}

			shouldDrawRoofShadows =
				plugin.configShadowsEnabled &&
				plugin.configRoofShadows &&
				environmentManager.getTargetEnvironment().allowRoofShadows;

			plugin.uboGlobal.sceneBase.set(scene.getBaseX() * LOCAL_TILE_SIZE, scene.getBaseY() * LOCAL_TILE_SIZE);
			plugin.uboGlobal.lightDir.set(directionalCamera.getForwardDirection());
			plugin.uboGlobal.viewportSize.set(slice(plugin.sceneViewport, 2));
			plugin.uboGlobal.cameraPos.set(plugin.cameraPosition);
			plugin.uboGlobal.viewMatrix.set(plugin.viewMatrix);
			plugin.uboGlobal.projectionMatrix.set(plugin.viewProjMatrix);
			plugin.uboGlobal.invProjectionMatrix.set(plugin.invViewProjMatrix);
			plugin.uboGlobal.orthographicProjection.set(plugin.orthographicProjection ? 1 : 0);
		}

		// Upon logging in, the client will draw some frames with zero geometry before it hides the login screen
		if (client.getGameState().getState() >= GameState.LOGGED_IN.getState())
			plugin.hasLoggedIn = true;

		if (plugin.enableFreezeFrame || plugin.redrawPreviousFrame)
			skyRenderer.prepareFrame(plugin.uboGlobal);

		boolean replaceVanillaSkybox =
			config.replaceVanillaSkyboxes() &&
			environmentManager.getTargetEnvironment().hideVanillaSkyboxes;
		shouldRenderVanillaSkybox = scene.getSkybox() != null && !replaceVanillaSkybox;

		Environment env = environmentManager.getCurrentEnvironment();
		float fogDepth = 0;
		if (!shouldRenderVanillaSkybox) {
			switch (config.fogDepthMode()) {
				case USER_DEFINED:
					fogDepth = config.fogDepth();
					break;
				case DYNAMIC:
					fogDepth = env.fogDepth;
					break;
			}
			fogDepth *= min(plugin.getDrawDistance(), 90) / 10.f;
		}
		plugin.uboGlobal.useFog.set(fogDepth > 0 ? 1 : 0);
		plugin.uboGlobal.fogDepth.set(fogDepth);

		plugin.uboGlobal.drawDistance.set((float) plugin.getDrawDistance());
		plugin.uboGlobal.expandedMapLoadingChunks.set(ctx.sceneContext.expandedMapLoadingChunks);
		plugin.uboGlobal.colorBlindnessIntensity.set(config.colorBlindnessIntensity() / 100.f);
		plugin.uboGlobal.glareIntensity.set(config.glareIntensity() / 100f);

		plugin.uboGlobal.gammaCorrection.set(plugin.getGammaCorrection());

		plugin.uboGlobal.underglowStrength.set(env.underglowStrength);
		plugin.uboGlobal.underglowColor.set(env.getUnderglowColor());

		plugin.uboGlobal.groundFogStart.set(env.groundFogStart);
		plugin.uboGlobal.groundFogEnd.set(env.groundFogEnd);
		plugin.uboGlobal.groundFogOpacity.set(config.groundFog() ? env.groundFogOpacity : 0);

		// Lights & lightning
		plugin.uboGlobal.lightningBrightness.set(environmentManager.getLightningBrightness());

		plugin.uboGlobal.saturation.set(config.saturation() / 100f);
		plugin.uboGlobal.contrast.set(config.contrast() / 100f);
		plugin.uboGlobal.underwaterEnvironment.set(environmentManager.getTargetEnvironment().isUnderwater ? 1 : 0);
		plugin.uboGlobal.underwaterCaustics.set(config.underwaterCaustics() ? 1 : 0);
		plugin.uboGlobal.underwaterCausticsColor.set(env.getWaterCausticsColor());
		plugin.uboGlobal.underwaterCausticsStrength.set(env.waterCausticsStrength);
		plugin.uboGlobal.elapsedTime.set((float) (plugin.elapsedTime % MAX_FLOAT_WITH_128TH_PRECISION));
		plugin.uboGlobal.orthographicProjection.set(plugin.orthographicProjection ? 1 : 0);

		if (plugin.configColorFilter != ColorFilter.NONE) {
			plugin.uboGlobal.colorFilter.set(plugin.configColorFilter.ordinal());
			plugin.uboGlobal.colorFilterPrevious.set(plugin.configColorFilterPrevious.ordinal());
			long timeSinceChange = System.currentTimeMillis() - plugin.colorFilterChangedAt;
			plugin.uboGlobal.colorFilterFade.set(clamp(timeSinceChange / COLOR_FILTER_FADE_DURATION, 0, 1));
		}

		plugin.uboGlobal.upload();

		// Reset buffers for the next frame
		if (SUPPORTS_INDIRECT_DRAW)
			indirectDrawCmdsStaging.clear();
		sceneCmd.reset();
		alphaDiscardCmd.reset();
		transparentCmd.reset();
		waterCmd.reset();
		directionalCmd.reset();
		terrainShadowCmd.reset();
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
			plugin.requestPluginStop(
				String.format(
					"Error in postSceneDraw(%s):",
					scene != null ? scene.getWorldViewId() : null
				),
				ex
			);
		}
	}

	private void postDrawTopLevel() {
		var ctx = sceneManager.getSceneContext();
		if (ctx == null || plugin.sceneViewport == null)
			return;

		sceneFboValid = true;

		// Upload world views before rendering
		sceneManager.updateWorldViewProjections();
		uboWorldViews.upload();

		if (eboAlphaWriter != null)
			eboAlphaWriter.flush();

		// Scene draw state to apply before all recorded commands
		if (SUPPORTS_INDIRECT_DRAW && indirectDrawCmdsStaging.position() > 0) {
			indirectDrawCmdsStaging.flip();
			indirectDrawCmds.orphan();
			indirectDrawCmds.upload(indirectDrawCmdsStaging);
		}

		frameTimer.end(Timer.DRAW_SCENE);

		if (plugin.configDynamicLights != DynamicLights.NONE) {
			frameTimer.begin(Timer.UPDATE_LIGHTS);
			try {
				lightManager.update(ctx, plugin.cameraShift, plugin.cameraFrustum);
				assert lightManager.getNumVisibleLights() <= UBOLights.MAX_LIGHTS;

				final float[] lightPosition = new float[4];
				final float[] lightColor = new float[4];
				for (int i = 0; i < lightManager.getNumVisibleLights(); i++) {
					final Light light = lightManager.getLights().get(i);
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

				plugin.uboGlobal.pointLightsCount.set(lightManager.getNumVisibleLights());

				plugin.uboLights.upload();
				plugin.uboLightsCulling.upload();
				plugin.uboGlobal.upload();
			} catch (Exception ex) {
				log.error("Error while updating lights:", ex);
			}
			frameTimer.end(Timer.UPDATE_LIGHTS);
		}

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
			skyRenderer.castsShadows;

		if (shouldRenderShadows || shouldClearShadowFbo) {
			if (plugin.configTerrainShadows && plugin.fboTerrainShadowMap != 0) {
				renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboTerrainShadowMap);
				renderState.viewport.set(0, 0, plugin.terrainShadowMapResolution, plugin.terrainShadowMapResolution);
				renderState.apply();

				glClearDepth(1);
				glClear(GL_DEPTH_BUFFER_BIT);
			}

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
		renderState.depthFunc.set(plugin.configShadowTransparency ? GL_LEQUAL : GL_LESS);
		renderState.enable.set(GL_POLYGON_OFFSET_FILL);
		renderState.polygonOffset.set(0.5f, 1.0f);
		if (SUPPORTS_INDIRECT_DRAW)
			renderState.ido.set(indirectDrawCmds.id);

		directionalCmd.execute(renderState);

		frameTimer.end(Timer.RENDER_SHADOWS);

		// Render terrain-only shadow map
		if (plugin.configTerrainShadows && plugin.fboTerrainShadowMap != 0) {
			frameTimer.begin(Timer.RENDER_TERRAIN_SHADOWS);

			renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboTerrainShadowMap);
			renderState.viewport.set(0, 0, plugin.terrainShadowMapResolution, plugin.terrainShadowMapResolution);
			renderState.depthFunc.set(GL_LESS);
			renderState.cullFace.set(GL_FRONT);
			renderState.enable.set(GL_CULL_FACE);
			renderState.apply();

			terrainShadowProgram.use();
			terrainShadowCmd.execute(renderState);

			frameTimer.end(Timer.RENDER_TERRAIN_SHADOWS);
		}

		glBindVertexArray(0);

		renderState.cullFace.set(GL_BACK);
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_POLYGON_OFFSET_FILL);

		shouldClearShadowFbo = true;
	}

	private void alphaPrePass() {
		if (transparentCmd.isEmpty() && waterCmd.isEmpty())
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
		// Mark every pixel containing alpha (depth pass, no discard) so the composite can skip opaque-only pixels
		renderState.enable.set(GL_STENCIL_TEST);
		renderState.stencilFunc.set(GL_ALWAYS, 1, 0xFF);
		renderState.stencilOp.set(GL_KEEP, GL_KEEP, GL_REPLACE);
		renderState.stencilMask.set(0xFF);
		renderState.apply();

		glClearBufferfv(GL_COLOR, 0, OIT_CLEAR_FIRST_LAYER);
		glClearBufferfv(GL_COLOR, 1, OIT_CLEAR_COVERAGE);
		glClearBufferfv(GL_COLOR, 2, OIT_CLEAR_FARTHEST_LAYER);
		glClearBufferiv(GL_STENCIL, 0, OIT_CLEAR_STENCIL);

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
			renderState.program.set(oitPrePassWaterProgram);
			renderState.apply();
			waterCmd.execute(renderState);
		}

		renderState.vao.setVao(0);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_STENCIL_TEST);
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
			msaaaMaxResolveProgram.resolve(
				renderState,
				plugin.vaoTri,
				TEXTURE_UNIT_OIT_FARTHEST_LAYER,
				farthestLayerDepthMSTex,
				farthestLayerDepthResolveTex,
				plugin.msaaSamples
			);
		}

		oitDepthMergeProgram.merge(
			renderState,
			plugin.vaoTri,
			TEXTURE_UNIT_OIT_FIRST_LAYER,
			TEXTURE_UNIT_OIT_FARTHEST_LAYER,
			firstLayerDepthResolveTex,
			farthestLayerDepthResolveTex,
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

	private void scenePass() {
		frameTimer.begin(Timer.DRAW_SCENE);
		renderState.disable.set(GL_STENCIL_TEST);
		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		renderState.toggle(GL_MULTISAMPLE, plugin.msaaSamples > 1);
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		if (SUPPORTS_INDIRECT_DRAW)
			renderState.ido.set(indirectDrawCmds.id);
		renderState.apply();

		skyRenderer.clear(shouldRenderVanillaSkybox);

		frameTimer.begin(Timer.RENDER_SCENE_AND_SKY);

		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		renderState.toggle(GL_BLEND, !plugin.configUseOIT);

		sceneProgram.use();

		if (!gapFillerCmd.isEmpty()) {
			// Render gap fillers just above depth 0, which we reserve for rendering the sky later
			glDepthRange(Float.MIN_NORMAL, Float.MIN_NORMAL);
			gapFillerCmd.execute(renderState);
			glDepthRange(0, 1);
		}

		if (plugin.configUseOIT)
			sceneOpaqueProgram.use();
		else
		sceneCmd.execute(renderState);

		frameTimer.end(Timer.RENDER_SCENE_AND_SKY);

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

		if (!transparentCmd.isEmpty() || !waterCmd.isEmpty()) {
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

			if (plugin.configUseOIT) {
				// when using OIT we don't need to multi loc unless the zone isn't visible
				int offset = ctx.sceneContext.sceneOffset >> 3;
				zone.multizoneLocs(ctx.sceneContext, zx - offset, zz - offset, sceneCamera, ctx.zones);
			}

			zone.inShadowFrustum =
				plugin.configShadowsEnabled &&
				directionalCamera.intersectsAABB(
					minX - PADDING, minY - PADDING, minZ - PADDING,
					maxX + PADDING, maxY + PADDING, maxZ + PADDING
				) &&
				directionalShadowCasterVolume.intersectsAABB(
					minX - PADDING, minY - PADDING, minZ - PADDING,
					maxX + PADDING, maxY + PADDING, maxZ + PADDING
				);

			if (plugin.enableDetailedTimers)
				frameTimer.end(Timer.VISIBILITY_CHECK);
			if (plugin.orthographicProjection)
				return zone.inSceneFrustum = true;
			return zone.inShadowFrustum;
		} catch (Throwable ex) {
			plugin.requestPluginStop(
				String.format("Error in zoneInFrustum(%d, %d, %d, %d)", zx, zz, maxY, minY),
				ex
			);
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
			boolean renderGroundLevel = ctx.minLevel == 0;
			if (!sceneManager.isRoot(ctx) || z.inSceneFrustum) {
				sceneCmd.Disable(GL_BLEND);
				if (renderGroundLevel)
					z.renderOpaqueLevel(sceneCmd, Zone.LEVEL_TERRAIN);
				z.renderOpaque(sceneCmd, ctx, false);

				if (renderGroundLevel && z.hasGapFiller)
					z.renderOpaqueLevel(gapFillerCmd, Zone.LEVEL_GAP_FILLER);
			}

			if (skyRenderer.castsShadows && !ctx.isSquashed && (!sceneManager.isRoot(ctx) || z.inShadowFrustum)) {
				if (!z.onlyWater || z.modelCount > 0) {
					directionalCmd.SetShader(fastShadowProgram);
					z.renderOpaque(directionalCmd, ctx, shouldDrawRoofShadows);
				}

				if (renderGroundLevel && plugin.configTerrainShadows && plugin.fboTerrainShadowMap != 0)
					z.renderOpaqueLevel(terrainShadowCmd, Zone.LEVEL_TERRAIN);
			}
			frameTimer.end(Timer.DRAW_ZONE_OPAQUE);

			checkGLErrors();
		} catch (Throwable ex) {
			plugin.requestPluginStop(
				String.format(
					"Error in drawZoneOpaque(%d, %d, %s)",
					zx, zz, scene != null ? scene.getWorldViewId() : null
				),
				ex
			);
		}
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		if (plugin.isPluginStopPending())
			return;

		try {
			final WorldViewContext ctx = sceneManager.getContext(scene);
			if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading || ctx.isSquashed)
				return;

			final Zone z = ctx.zones[zx][zz];
			if (!z.initialized)
				return;

			frameTimer.begin(Timer.DRAW_ZONE_ALPHA);
			if (!plugin.configUseOIT)
				sceneCmd.Enable(GL_BLEND);

			final boolean renderWater = z.inSceneFrustum && ctx.minLevel == 0 && level == 0 && z.hasWater;
			if (renderWater) {
				if (plugin.configUseOIT) {
					z.renderOpaqueLevel(waterCmd, Zone.LEVEL_WATER_SURFACE);
				} else {
					sceneCmd.SetShader(waterProgram);
					z.renderOpaqueLevel(sceneCmd, Zone.LEVEL_WATER_SURFACE);
					sceneCmd.SetShader(sceneProgram);
				}
			}

			modelStreamingManager.ensureAsyncUploadsComplete(z);

			final boolean hasAlpha = z.sizeA != 0 || !z.alphaModels.isEmpty();
			if (hasAlpha) {
				final int offset = ctx.sceneContext.sceneOffset >> 3;
				final int zoneX = zx - offset;
				final int zoneZ = zz - offset;
				// Only sort if the alpha will be directly visible, since shadows don't require sorting
				if (level == 0 && (!sceneManager.isRoot(ctx) || z.inSceneFrustum))
					z.alphaSort(zoneX, zoneZ, sceneCamera, plugin.configUseOIT);

				if (skyRenderer.castsShadows && !ctx.isSquashed && (!sceneManager.isRoot(ctx) || z.inShadowFrustum)) {
					directionalCmd.SetShader(plugin.configShadowMode == ShadowMode.DETAILED ? detailedShadowProgram : fastShadowProgram);
					z.renderAlphaModels(directionalCmd, zoneX, zoneZ, level, ctx, shouldDrawRoofShadows ? Zone.ALPHA_DRAW_ROOF : 0);
				}

				if (!sceneManager.isRoot(ctx) || z.inSceneFrustum) {
					if (plugin.configUseOIT) {
						// OIT is a Composite pass, so we don't need to sort alpha models or even write depth
						// However Alpha Discard needs to be drawn as part of the Scene Pass since Opaque faces break OIT
						z.renderAlphaModels(transparentCmd, zoneX, zoneZ, level, ctx, Zone.ALPHA_DRAW_BLEND_ONLY);
						z.renderAlphaModels(alphaDiscardCmd, zoneX, zoneZ, level, ctx, Zone.ALPHA_DRAW_DISCARD_ONLY);
					} else if (!sceneManager.isRoot(ctx) || renderWater) {
						// Alpha is currently drawn with depth writes & depth testing enabled, and as such, alpha models and the water plane
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
			frameTimer.end(Timer.DRAW_ZONE_ALPHA);

			checkGLErrors();
		} catch (Throwable ex) {
			plugin.requestPluginStop(
				String.format(
					"Error in drawZoneAlpha(%d, %d, %d, %s)",
					zx, zz, level, scene != null ? scene.getWorldViewId() : null
				),
				ex
			);
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

					if (skyRenderer.shouldRender(shouldRenderVanillaSkybox) && sceneManager.isRoot(ctx)) {
						// Draw the sky after drawing top-level scene opaque
						skyRenderer.appendTo(sceneCmd);
						sceneCmd.SetShader(sceneProgram);
					}
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

					// Draw to shadow map
					if (skyRenderer.castsShadows && !ctx.isSquashed) {
						ctx.drawAll(VAO_OPAQUE, ctx.vaoDirectionalCmd);
						ctx.drawAll(VAO_PLAYER, ctx.vaoDirectionalCmd);

						// Draw shadow-only models
						ctx.drawAll(VAO_SHADOW, ctx.vaoDirectionalCmd);
					}

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
			plugin.requestPluginStop(
				String.format(
					"Error in drawPass(%s, %s, %s)",
					projection, scene != null ? scene.getWorldViewId() : null, pass
				),
				ex
			);
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

			// Should only ever wait when not rendering the scene, since this will have been called in preSceneDraw
			waitForFrameFence();

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
				if (plugin.configFrameSynchronization) {
					final int slot = plugin.frame % FRAMES_IN_FLIGHT;
					assert frameFences[slot] == 0 : "Frame fence already set, this should never happen";
					frameFences[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
					if (frameFences[slot] == 0) {
						fenceCreationFailureCount++;
						long now = System.currentTimeMillis();
						if (fenceCreationFailureCount == 1 || now - lastFenceCreationWarningMs >= 1000) {
							log.warn("Unable to create frame fence, count {}. Falling back to glFinish...", fenceCreationFailureCount);
							lastFenceCreationWarningMs = now;
						}
						glFinish();
					}
				}

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
			plugin.frame = (plugin.frame + 1) & Integer.MAX_VALUE;
		} catch (Throwable ex) {
			plugin.requestPluginStop(String.format("Error in draw(%08X)", overlayColor), ex);
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
			plugin.requestPluginStop(String.format("Error in despawnWorldView(%d)", worldView.getId()), ex);
		}
	}

	@Override
	public void swapScene(Scene scene) {
		try {
			sceneManager.swapScene(scene);
			directionalCamera.setDirty();
		} catch (Throwable ex) {
			log.error("Error during swapScene:", ex);
			plugin.stopPlugin();
		}
	}
}
