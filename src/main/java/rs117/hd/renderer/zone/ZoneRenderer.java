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

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.DrawManager;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.ColorFilter;
import rs117.hd.config.DynamicLights;
import rs117.hd.config.ShadowMode;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.ShadowShaderProgram;
import rs117.hd.opengl.shader.SkyShaderProgram;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.Renderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.TimeOfDay;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.AtmosphereUtils;
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.ShadowCasterVolume;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLMappedBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.JobSystem;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static rs117.hd.HdPlugin.COLOR_FILTER_FADE_DURATION;
import static rs117.hd.HdPlugin.NEAR_PLANE;
import static rs117.hd.HdPlugin.ORTHOGRAPHIC_ZOOM;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_SHADOW;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;
import static rs117.hd.utils.buffer.GLBuffer.MAP_INVALIDATE;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;

@Slf4j
@Singleton
public class ZoneRenderer implements Renderer {
	public static final int FRAMES_IN_FLIGHT = 3;

	private static int TEXTURE_UNIT_COUNT = HdPlugin.TEXTURE_UNIT_COUNT;
	public static final int TEXTURE_UNIT_TEXTURED_FACES = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;

	private static int UNIFORM_BLOCK_COUNT = HdPlugin.UNIFORM_BLOCK_COUNT;
	public static final int UNIFORM_BLOCK_WORLD_VIEWS = UNIFORM_BLOCK_COUNT++;

	@Inject
	private Client client;

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
	private ShadowShaderProgram.Fast fastShadowProgram;

	@Inject
	private ShadowShaderProgram.Detailed detailedShadowProgram;

	@Inject
	private SkyShaderProgram skyProgram;

	@Inject
	private JobSystem jobSystem;

	@Inject
	private UBOWorldViews uboWorldViews;

	public final Camera sceneCamera = new Camera().setReverseZ(true);
	public final Camera directionalCamera = new Camera().setOrthographic(true);
	public final ShadowCasterVolume directionalShadowCasterVolume = new ShadowCasterVolume(directionalCamera);

	// Night sky texture
	private int nightSkyTexId = -1;

	// Day/Night Cycle - stored fog color for skybox clear
	private float[] calculatedFogColorSrgb = null;
	// Day/Night Cycle - sky gradient enabled flag
	private boolean skyGradientEnabled = false;

	private final int[] worldPos = new int[3];

	public final RenderState renderState = new RenderState();
	public final CommandBuffer sceneCmd = new CommandBuffer("Scene", renderState);
	public final CommandBuffer directionalCmd = new CommandBuffer("Directional", renderState);
	public final CommandBuffer playerCmd = new CommandBuffer("Player", renderState);

	private GLBuffer indirectDrawCmds;
	public static GpuIntBuffer indirectDrawCmdsStaging;

	public static GLBuffer eboAlpha;
	public static GLMappedBuffer eboAlphaMapped;
	public static int eboAlphaOffset;
	public static int eboAlphaPrevOffset;

	private boolean sceneFboValid;
	private boolean shouldRenderScene;

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

		SceneUploader.POOL = new ConcurrentPool<>(plugin.getInjector(), SceneUploader.class);
		FacePrioritySorter.POOL = new ConcurrentPool<>(plugin.getInjector(), FacePrioritySorter.class);

		sceneCmd.setFrameTimer(frameTimer);
		directionalCmd.setFrameTimer(frameTimer);

		jobSystem.startUp(config.cpuUsageLimit());
		uboWorldViews.initialize(UNIFORM_BLOCK_WORLD_VIEWS);
		sceneManager.initialize(renderState, uboWorldViews);
		modelStreamingManager.initialize();

		// Force updates that only run when the cameras change
		sceneCamera.setDirty();
		directionalCamera.setDirty();

		// Load night sky texture
		loadNightSkyTexture();
	}

	private void loadNightSkyTexture() {
		try {
			BufferedImage image = path(HdPlugin.class, "scene/textures/nightsky.png").loadImage();
			int width = image.getWidth();
			int height = image.getHeight();

			// Convert to INT_ARGB if needed
			BufferedImage argbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			argbImage.getGraphics().drawImage(image, 0, 0, null);

			int[] pixels = ((DataBufferInt) argbImage.getRaster().getDataBuffer()).getData();
			IntBuffer pixelBuffer = BufferUtils.createIntBuffer(pixels.length);
			pixelBuffer.put(pixels).flip();

			nightSkyTexId = glGenTextures();
			glActiveTexture(HdPlugin.TEXTURE_UNIT_NIGHT_SKY);
			glBindTexture(GL_TEXTURE_2D, nightSkyTexId);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
				GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);

			log.debug("Loaded night sky texture: {}x{}", width, height);
		} catch (Exception e) {
			log.warn("Failed to load night sky texture", e);
		}
	}

	@Override
	public void destroy() {
		destroyBuffers();

		if (nightSkyTexId != -1) {
			glDeleteTextures(nightSkyTexId);
			nightSkyTexId = -1;
		}

		jobSystem.shutDown();
		modelStreamingManager.destroy();
		sceneManager.destroy();
		uboWorldViews.destroy();

		SceneUploader.POOL = null;
		FacePrioritySorter.POOL = null;
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
			.addInclude("WORLD_VIEW_GETTER", () -> plugin.generateGetter("WorldView", UBOWorldViews.MAX_SIMULTANEOUS_WORLD_VIEWS))
			.addUniformBuffer(uboWorldViews);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneProgram.compile(includes);
		fastShadowProgram.compile(includes);
		detailedShadowProgram.compile(includes);
		skyProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		sceneProgram.destroy();
		fastShadowProgram.destroy();
		detailedShadowProgram.destroy();
		skyProgram.destroy();
	}

	private void initializeBuffers() {
		eboAlpha = new GLBuffer("eboAlpha", GL_ELEMENT_ARRAY_BUFFER, GL_STREAM_DRAW).initialize(MiB);
		eboAlphaOffset = 0;

		indirectDrawCmds = new GLBuffer("indirectDrawCmds", GL_DRAW_INDIRECT_BUFFER, GL_STREAM_DRAW).initialize(MiB);
		indirectDrawCmdsStaging = new GpuIntBuffer();
	}

	private void destroyBuffers() {
		if (eboAlpha != null)
			eboAlpha.destroy();
		eboAlpha = null;

		if (indirectDrawCmds != null)
			indirectDrawCmds.destroy();
		indirectDrawCmds = null;

		if (indirectDrawCmdsStaging != null)
			indirectDrawCmdsStaging.destroy();
		indirectDrawCmdsStaging = null;
	}

	@Override
	public void processConfigChanges(Set<String> keys) {
		if (keys.contains(KEY_ASYNC_MODEL_PROCESSING) || keys.contains(KEY_ASYNC_MODEL_CACHE_SIZE))
			modelStreamingManager.reinitialize();
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event) {
		try {
			frameTimer.begin(Timer.UPDATE_SCENE);
			sceneManager.update();
			frameTimer.end(Timer.UPDATE_SCENE);
		} catch (Exception ex) {
			log.error("Error while updating scene:", ex);
			plugin.stopPlugin();
		}
	}

	@Override
	public void preSceneDraw(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds
	) {
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
			return;

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

		int offset = ctx.sceneContext.sceneOffset >> 3;
		for (int zx = 0; zx < ctx.sizeX; ++zx)
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
				ctx.zones[zx][zz].multizoneLocs(ctx.sceneContext, zx - offset, zz - offset, sceneCamera, ctx.zones);

		ctx.sortStaticAlphaModels(sceneCamera);

		ctx.map();
		frameTimer.end(Timer.DRAW_PRESCENE);
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
			} catch (Exception ex) {
				log.error("Error while updating environment or lights:", ex);
				plugin.stopPlugin();
				return;
			}

			// Use Day/Night-Cycle sun/moon angles if enabled
			float[] shadowSunAngles = environmentManager.currentSunAngles;
			if (environmentManager.isOverworld() && config.enableDaylightCycle()) {
				double[] sunAnglesD = TimeOfDay.getSunAngles(plugin.latLong, config.cycleDurationMinutes());
				double sunAltDeg = Math.toDegrees(sunAnglesD[1]);

				if (sunAltDeg < 2.0) {
					// Below +2° sun shadows are faded out, switch to moon direction
					// early so the shadow map is already oriented when moon shadows
					// start fading in via smoothstep — prevents brightness pop
					double moonAltDeg = TimeOfDay.getMoonAltitudeDegrees(plugin.latLong, config.cycleDurationMinutes());
					if (moonAltDeg > -10) {
						Instant moonDate = TimeOfDay.getMoonDate(config.cycleDurationMinutes());
						double[] moonAnglesD = AtmosphereUtils.getMoonPosition(
							moonDate.toEpochMilli(), plugin.latLong);
						shadowSunAngles = new float[] {
							(float) moonAnglesD[1], (float) moonAnglesD[0]
						};
					} else {
						shadowSunAngles = new float[] { (float) sunAnglesD[1], (float) sunAnglesD[0] };
					}
				} else {
					shadowSunAngles = new float[] { (float) sunAnglesD[1], (float) sunAnglesD[0] };
				}
			}
			directionalCamera.setPitch(shadowSunAngles[0]);
			directionalCamera.setYaw(PI - shadowSunAngles[1]);
			boolean hasDirectionalCameraChanged = directionalCamera.isViewDirty() || directionalCamera.isProjDirty();

			if (hasSceneCameraChanged || hasDirectionalCameraChanged) {
				int shadowDrawDistance = 90 * LOCAL_TILE_SIZE;

				final float[][] volumeCorners = directionalShadowCasterVolume
					.build(sceneCamera, drawDistance * LOCAL_TILE_SIZE, shadowDrawDistance);

				final float[] sceneCenter = new float[3];
				for (float[] corner : volumeCorners)
					add(sceneCenter, sceneCenter, corner);
				divide(sceneCenter, sceneCenter, (float) volumeCorners.length);

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

				plugin.uboGlobal.lightDir.set(directionalCamera.getForwardDirection());
				plugin.uboGlobal.lightProjectionMatrix.set(directionalCamera.getViewProjMatrix());
			}

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

		// Day/Night Cycle - calculate modified lighting values
		float[] directionalColor = environmentManager.currentDirectionalColor;
		float directionalStrength = environmentManager.currentDirectionalStrength;
		float[] ambientColor = environmentManager.currentAmbientColor;
		float ambientStrength = environmentManager.currentAmbientStrength;
		float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
		float[] waterColor = environmentManager.currentWaterColor;
		float[] sunAngles = environmentManager.currentSunAngles;

		if (environmentManager.isOverworld() && config.enableDaylightCycle()) {
			int minimumBrightness = config.minimumBrightness();
			float cycleDuration = config.cycleDurationMinutes();

			float[] originalRegionalDirectionalColor = environmentManager.currentDirectionalColor;
			float[] originalRegionalAmbientColor = new float[3];
			System.arraycopy(environmentManager.currentAmbientColor, 0, originalRegionalAmbientColor, 0, 3);

			directionalColor = TimeOfDay.getRegionalDirectionalLight(plugin.latLong, cycleDuration, originalRegionalDirectionalColor);
			ambientColor = TimeOfDay.getRegionalAmbientLight(plugin.latLong, cycleDuration, originalRegionalAmbientColor);

			float brightnessMultiplier = TimeOfDay.getDynamicBrightnessMultiplier(plugin.latLong, cycleDuration, minimumBrightness);
			directionalStrength = environmentManager.currentDirectionalStrength * brightnessMultiplier;
			ambientStrength = environmentManager.currentAmbientStrength * brightnessMultiplier;

			double[] sunAnglesD = TimeOfDay.getSunAngles(plugin.latLong, cycleDuration);
			sunAngles = new float[] { (float) sunAnglesD[1], (float) sunAnglesD[0] };

			float[] originalRegionalFogColor = fogColor;
			fogColor = TimeOfDay.getEnhancedSkyColor(plugin.latLong, cycleDuration, originalRegionalFogColor, environmentManager.currentSunStrength);
			// Convert fogColor (sRGB) to linear for waterColor to match expected format
			waterColor = ColorUtils.srgbToLinear(fogColor);

			// Store calculated fog color for skybox clear in scenePass()
			calculatedFogColorSrgb = fogColor;

			// Calculate sky gradient colors for realistic sky rendering
			// Pass regional fog color to blend with during peak daytime
			float[][] skyGradientColors = TimeOfDay.getSkyGradientColors(plugin.latLong, cycleDuration, originalRegionalFogColor, environmentManager.currentSunStrength);
			float[] sunDirForSky = TimeOfDay.getSunDirectionForSky(plugin.latLong, cycleDuration);

			plugin.uboGlobal.skyGradientEnabled.set(1);
			plugin.uboGlobal.skyZenithColor.set(skyGradientColors[0]);
			plugin.uboGlobal.skyHorizonColor.set(skyGradientColors[1]);
			plugin.uboGlobal.skySunColor.set(skyGradientColors[2]);
			plugin.uboGlobal.skySunDir.set(sunDirForSky);

			// Set moon uniforms — getMoonDate() returns a continuously advancing
			// timestamp so no smoothing is needed here
			float[] moonDir = TimeOfDay.getMoonDirectionForSky(plugin.latLong, cycleDuration);
			float moonIllumination = TimeOfDay.getMoonIlluminationFraction(cycleDuration);
			float[] moonColor = environmentManager.currentMoonColor;
			plugin.uboGlobal.skyMoonDir.set(moonDir);
			plugin.uboGlobal.skyMoonColor.set(moonColor);
			plugin.uboGlobal.skyMoonIllumination.set(moonIllumination);
			plugin.uboGlobal.starVisibility.set(config.enableStarMap() ? environmentManager.currentStarVisibility : 0f);
			plugin.uboGlobal.moonVisibility.set(config.enableMoon() ? environmentManager.currentMoonVisibility : 0f);

			skyGradientEnabled = true;

			// Calculate shadow visibility based on sun and moon altitude
			// Moon values come from getMoonDate() which advances continuously,
			// so no smoothing is needed
			double sunAltitudeDegrees = Math.toDegrees(sunAnglesD[1]);
			double moonAltDeg = TimeOfDay.getMoonAltitudeDegrees(plugin.latLong, cycleDuration);
			float moonIllumFrac = moonIllumination;
			float shadowVisibility;

			float sunStrength = environmentManager.currentSunStrength;
			if (sunAltitudeDegrees > 2) {
				// Sun shadows (existing behavior)
				if (sunAltitudeDegrees <= 12) {
					shadowVisibility = (float) ((sunAltitudeDegrees - 2) / 10.0 * 0.6);
				} else if (sunAltitudeDegrees <= 15) {
					shadowVisibility = (float) (0.6 + ((sunAltitudeDegrees - 12) / 3.0) * 0.3);
				} else {
					double sineFactor = Math.sin(sunAnglesD[1]);
					shadowVisibility = (float) Math.max(0.9, Math.min(1.0, sineFactor));
				}
			} else {
				// Night: sun below +2 degrees
				// Moon shadow ramps in via smoothstep from +2° to -15°, then stays constant
				float moonBaseShadow = 0;
				if (moonAltDeg > -10 && moonIllumFrac > 0.01f) {
					// Smoothstep elevation factor: starts at -10deg (zero derivative),
					// reaches full strength at +20deg. C1 continuous onset prevents pop-in.
					float me = (float) Math.min(1.0, Math.max(0, (moonAltDeg + 10.0) / 30.0));
					float moonElevationFactor = me * me * (3.0f - 2.0f * me); // smoothstep
					moonBaseShadow = moonIllumFrac * 0.2f * moonElevationFactor;
				}

				// Smoothstep blend from 0 at +2° to full moonBaseShadow at -15°
				// Uses smoothstep so derivative is zero at both ends (no pop at -15°)
				float t = (float) Math.min(1.0, Math.max(0, (2.0 - sunAltitudeDegrees) / 17.0));
				float moonBlend = t * t * (3.0f - 2.0f * t); // smoothstep instead of quadratic
				shadowVisibility = moonBlend * moonBaseShadow;
			}

			// Smooth sun-to-moon directional light color crossfade
			// Begin moon color influence ABOVE the horizon (+5°) with a gentle ramp,
			// overlapping with the sun's natural warm-color fade-out near the horizon.
			// This prevents the color "pop" at 0° where warm sun tones vanish while
			// cool moon tones appear simultaneously.
			if (sunAltitudeDegrees < 5.0 && moonAltDeg > -10 && moonIllumFrac > 0.01f) {
				float moonInfluence;
				if (sunAltitudeDegrees >= 0.0) {
					// Pre-horizon: smoothstep from 0.0 at +5° to 0.05 at 0°
					float pt = (float) ((5.0 - sunAltitudeDegrees) / 5.0);
					float ps = pt * pt * (3.0f - 2.0f * pt);
					moonInfluence = ps * 0.05f;
				} else if (sunAltitudeDegrees >= -15.0) {
					// Main twilight: smoothstep from 0.05 at 0° to 0.8 at -15°
					float tt = (float) (-sunAltitudeDegrees / 15.0);
					float ts = tt * tt * (3.0f - 2.0f * tt);
					moonInfluence = 0.05f + ts * 0.75f;
				} else {
					// Deep night: cap at 0.8 so the moon tints but doesn't fully replace
					// the base nighttime directional color
					moonInfluence = 0.8f;
				}

				// Fade moon influence based on moon's own altitude (smoothstep)
				float ht = (float) Math.min(1.0, Math.max(0, (moonAltDeg + 10.0) / 30.0));
				float moonHorizonFade = ht * ht * (3.0f - 2.0f * ht);
				moonInfluence *= moonHorizonFade;

				// Scale by moon phase — full moon has strongest color tint, new moon has none
				moonInfluence *= moonIllumFrac;

				for (int i = 0; i < 3; i++) {
					directionalColor[i] = directionalColor[i] * (1 - moonInfluence)
						+ moonColor[i] * moonInfluence;
				}
			}

			add(ambientColor, ambientColor, multiply(directionalColor, 1 - shadowVisibility));
			directionalStrength *= shadowVisibility;
		} else {
			// Reset stored fog color when daylight cycle is disabled
			calculatedFogColorSrgb = null;
			skyGradientEnabled = false;
			plugin.uboGlobal.skyGradientEnabled.set(0);
			plugin.uboGlobal.skyMoonDir.set(new float[]{ 0, 0, 0 });
			plugin.uboGlobal.skyMoonColor.set(new float[]{ 0, 0, 0 });
			plugin.uboGlobal.skyMoonIllumination.set(0.0f);
			plugin.uboGlobal.starVisibility.set(1.0f);
		}

		float fogDepth = 0;
		switch (config.fogDepthMode()) {
			case USER_DEFINED:
				fogDepth = config.fogDepth();
				break;
			case DYNAMIC:
				fogDepth = environmentManager.currentFogDepth;
				break;
		}
		fogDepth *= min(plugin.getDrawDistance(), 90) / 10.f;
		plugin.uboGlobal.useFog.set(fogDepth > 0 ? 1 : 0);
		plugin.uboGlobal.fogDepth.set(fogDepth);
		plugin.uboGlobal.fogColor.set(fogColor);

		plugin.uboGlobal.drawDistance.set((float) plugin.getDrawDistance());
		plugin.uboGlobal.expandedMapLoadingChunks.set(ctx.sceneContext.expandedMapLoadingChunks);
		plugin.uboGlobal.colorBlindnessIntensity.set(config.colorBlindnessIntensity() / 100.f);

		float[] waterColorHsv = ColorUtils.srgbToHsv(waterColor);
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
		// Apply legacy brightness if enabled
		if (config.useLegacyBrightness()) {
			float factor = config.legacyBrightness() / 20f;
			ambientStrength *= factor;
			directionalStrength *= factor;
		}
		plugin.uboGlobal.ambientStrength.set(ambientStrength);
		plugin.uboGlobal.ambientColor.set(ambientColor);
		plugin.uboGlobal.lightStrength.set(directionalStrength);
		plugin.uboGlobal.lightColor.set(directionalColor);

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
		directionalCmd.reset();
		renderState.reset();

		int totalSortedFaces = sceneManager.getRoot().getSortedAlphaCount();

		WorldView wv = client.getTopLevelWorldView();
		for (WorldEntity we : wv.worldEntities()) {
			WorldViewContext entityCtx = sceneManager.getContext(we.getWorldView());
			if (entityCtx != null)
				totalSortedFaces += entityCtx.getSortedAlphaCount();
		}

		if ((plugin.frame % FRAMES_IN_FLIGHT) == 0)
			eboAlphaOffset = 0;
		eboAlphaPrevOffset = eboAlphaOffset;

		long alphaOffsetBytes = eboAlphaOffset * (long) Integer.BYTES;
		long alphaNextBytes = totalSortedFaces * 3L * Integer.BYTES;
		eboAlpha.ensureCapacity(alphaOffsetBytes + alphaNextBytes);
		eboAlphaMapped = eboAlpha.map(MAP_WRITE | MAP_INVALIDATE, alphaOffsetBytes, alphaNextBytes);

		checkGLErrors();
	}

	@Override
	public void postSceneDraw(Scene scene) {
		jobSystem.processPendingClientCallbacks();

		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
			return;

		frameTimer.begin(Timer.DRAW_POSTSCENE);
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			postDrawTopLevel();
		frameTimer.end(Timer.DRAW_POSTSCENE);
	}

	private void postDrawTopLevel() {
		if (!sceneManager.isTopLevelValid() || plugin.sceneViewport == null)
			return;

		sceneFboValid = true;

		// Upload world views before rendering
		uboWorldViews.upload();

		if (eboAlphaMapped != null) {
			eboAlphaMapped.setPositionBytes((eboAlphaOffset - eboAlphaPrevOffset) * Integer.BYTES);
			eboAlpha.unmap();
		}
		eboAlphaMapped = null;

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
		renderState.vao.set(plugin.vaoTri);

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
		if (!plugin.configShadowsEnabled || plugin.fboShadowMap == 0 || environmentManager.currentDirectionalStrength <= 0)
			return;

		frameTimer.begin(Timer.RENDER_SHADOWS);

		// Render to the shadow depth map
		renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboShadowMap);
		renderState.viewport.set(0, 0, plugin.shadowMapResolution, plugin.shadowMapResolution);
		renderState.ido.set(indirectDrawCmds.id);
		renderState.apply();

		glClearDepth(1);
		glClear(GL_DEPTH_BUFFER_BIT);

		renderState.enable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_CULL_FACE);
		renderState.depthFunc.set(GL_LEQUAL);

		CommandBuffer.SKIP_DEPTH_MASKING = true;
		directionalCmd.execute();
		CommandBuffer.SKIP_DEPTH_MASKING = false;

		renderState.disable.set(GL_DEPTH_TEST);

		frameTimer.end(Timer.RENDER_SHADOWS);
	}

	private void scenePass() {
		sceneProgram.use();

		frameTimer.begin(Timer.DRAW_SCENE);
		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		if (plugin.msaaSamples > 1) {
			renderState.enable.set(GL_MULTISAMPLE);
		} else {
			renderState.disable.set(GL_MULTISAMPLE);
		}
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		renderState.ido.set(indirectDrawCmds.id);
		renderState.apply();

		// Clear scene
		frameTimer.begin(Timer.CLEAR_SCENE);

		// Clear depth buffer
		glClearDepth(0);
		glClear(GL_DEPTH_BUFFER_BIT);

		// Render sky gradient if Day/Night Cycle is enabled, otherwise use solid color clear
		if (skyGradientEnabled && skyProgram.isValid()) {
			// Render sky gradient using fullscreen triangle
			renderState.disable.set(GL_DEPTH_TEST);
			renderState.disable.set(GL_CULL_FACE);
			renderState.apply();

			skyProgram.use();

			// Bind night sky texture
			if (nightSkyTexId != -1) {
				glActiveTexture(HdPlugin.TEXTURE_UNIT_NIGHT_SKY);
				glBindTexture(GL_TEXTURE_2D, nightSkyTexId);
			}

			renderState.vao.set(plugin.vaoTri);
			renderState.apply();
			glDrawArrays(GL_TRIANGLES, 0, 3);

			// Switch back to scene program
			sceneProgram.use();
		} else {
			// Use Day/Night Cycle fog color if available, otherwise use environment manager's fog color
			float[] fogColor = calculatedFogColorSrgb != null ? calculatedFogColorSrgb : ColorUtils.linearToSrgb(environmentManager.currentFogColor);
			float[] gammaCorrectedFogColor = pow(fogColor, plugin.getGammaCorrection());
			glClearColor(
				gammaCorrectedFogColor[0],
				gammaCorrectedFogColor[1],
				gammaCorrectedFogColor[2],
				1f
			);
			glClear(GL_COLOR_BUFFER_BIT);
		}
		frameTimer.end(Timer.CLEAR_SCENE);

		frameTimer.begin(Timer.RENDER_SCENE);

		renderState.enable.set(GL_BLEND);
		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

		// Render the scene
		sceneCmd.execute();

		// TODO: Filler tiles
		frameTimer.end(Timer.RENDER_SCENE);

		// Done rendering the scene
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.apply();

		frameTimer.end(Timer.DRAW_SCENE);
	}

	@Override
	public boolean zoneInFrustum(int zx, int zz, int maxY, int minY) {
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

		return false;
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz) {
		jobSystem.processPendingClientCallbacks();

		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
			return;

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized || z.sizeO == 0)
			return;

		frameTimer.begin(Timer.DRAW_ZONE_OPAQUE);
		if (!sceneManager.isRoot(ctx) || z.inSceneFrustum)
			z.renderOpaque(sceneCmd, ctx, false);

		final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
		if (!isSquashed && (!sceneManager.isRoot(ctx) || z.inShadowFrustum)) {
			directionalCmd.SetShader(fastShadowProgram);
			z.renderOpaque(directionalCmd, ctx, plugin.configRoofShadows);
		}
		frameTimer.end(Timer.DRAW_ZONE_OPAQUE);

		checkGLErrors();
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		final WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
			return;

		final Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
			return;

		frameTimer.begin(Timer.DRAW_ZONE_ALPHA);
		final boolean renderWater = z.inSceneFrustum && level == 0 && z.hasWater;
		if (renderWater)
			z.renderOpaqueLevel(sceneCmd, Zone.LEVEL_WATER_SURFACE);

		modelStreamingManager.ensureAsyncUploadsComplete(z);

		final boolean hasAlpha = z.sizeA != 0 || !z.alphaModels.isEmpty();
		if (hasAlpha) {
			final int offset = ctx.sceneContext.sceneOffset >> 3;
			// Only sort if the alpha will be directly visible, since shadows don't require sorting
			if (level == 0 && (!sceneManager.isRoot(ctx) || z.inSceneFrustum))
				z.alphaSort(zx - offset, zz - offset, sceneCamera);

			final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
			if (!isSquashed && (!sceneManager.isRoot(ctx) || z.inShadowFrustum)) {
				directionalCmd.SetShader(plugin.configShadowMode == ShadowMode.DETAILED ? detailedShadowProgram : fastShadowProgram);
				z.renderAlpha(directionalCmd, zx - offset, zz - offset, level, ctx, true, plugin.configRoofShadows);
			}

			if (!sceneManager.isRoot(ctx) || z.inSceneFrustum)
				z.renderAlpha(sceneCmd, zx - offset, zz - offset, level, ctx, false, false);
		}
		frameTimer.end(Timer.DRAW_ZONE_ALPHA);

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass) {
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || !sceneManager.isRoot(ctx) && ctx.isLoading)
			return;

		frameTimer.begin(Timer.DRAW_PASS);

		switch (pass) {
			case DrawCallbacks.PASS_OPAQUE:
				directionalCmd.SetShader(fastShadowProgram);

				sceneCmd.ExecuteSubCommandBuffer(ctx.vaoSceneCmd);
				directionalCmd.ExecuteSubCommandBuffer(ctx.vaoDirectionalCmd);

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

				// Draw shadow-only models
				ctx.drawAll(VAO_SHADOW, ctx.vaoDirectionalCmd);

				final int offset = ctx.sceneContext.sceneOffset >> 3;
				for (int zx = 0; zx < ctx.sizeX; ++zx) {
					for (int zz = 0; zz < ctx.sizeZ; ++zz) {
						final Zone z = ctx.zones[zx][zz];

						if (!z.playerModels.isEmpty() && (!sceneManager.isRoot(ctx) || z.inSceneFrustum || z.inShadowFrustum)) {
							z.playerSort(zx - offset, zz - offset, sceneCamera);

							z.renderPlayers(playerCmd, zx - offset, zz - offset);

							if (!playerCmd.isEmpty()) {
								// Draw players shadow, with depth writes & alpha
								ctx.vaoDirectionalCmd.append(playerCmd);

								ctx.vaoSceneCmd.DepthMask(false);
								ctx.vaoSceneCmd.append(playerCmd);
								ctx.vaoSceneCmd.DepthMask(true);

								// Draw players opaque, writing only depth
								ctx.vaoSceneCmd.ColorMask(false, false, false, false);
								ctx.vaoSceneCmd.append(playerCmd);
								ctx.vaoSceneCmd.ColorMask(true, true, true, true);
							}

							playerCmd.reset();
						}
					}
				}

				for (int zx = 0; zx < ctx.sizeX; ++zx)
					for (int zz = 0; zz < ctx.sizeZ; ++zz)
						ctx.zones[zx][zz].postAlphaPass();
				break;
		}

		frameTimer.end(Timer.DRAW_PASS);
		checkGLErrors();
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
		final long start = System.nanoTime();
		try {
			modelStreamingManager.drawDynamic(renderThreadId, projection, scene, tileObject, r, m, orient, x, y, z);
		} catch (Exception ex) {
			log.error("Error in drawDynamic:", ex);
		} finally {
			frameTimer.add(renderThreadId == -1 ? Timer.DRAW_DYNAMIC : Timer.DRAW_DYNAMIC_ASYNC, System.nanoTime() - start);
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orientation, int x, int y, int z) {
		frameTimer.begin(Timer.DRAW_TEMP);
		try {
			modelStreamingManager.drawTemp(worldProjection, scene, gameObject, m, orientation, x, y, z);
		} catch (Exception ex) {
			log.error("Error in drawTemp:", ex);
		} finally {
			frameTimer.end(Timer.DRAW_TEMP);
		}
	}

	@Override
	public void draw(int overlayColor) {
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
		sceneManager.despawnWorldView(worldView);
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
