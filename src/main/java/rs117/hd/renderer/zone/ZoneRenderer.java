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
import java.util.Arrays;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.DrawManager;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.ColorFilter;
import rs117.hd.config.DynamicLights;
import rs117.hd.config.ShadowMode;
import rs117.hd.opengl.buffer.storage.SSBOModelData;
import rs117.hd.opengl.buffer.uniforms.UBOLights;
import rs117.hd.opengl.buffer.uniforms.UBOWorldViews;
import rs117.hd.opengl.buffer.uniforms.UBOZoneData;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.ShadowShaderProgram;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.Renderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.ShadowCasterVolume;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.jobs.GenericJob;
import rs117.hd.utils.jobs.JobSystem;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static rs117.hd.HdPlugin.APPLE;
import static rs117.hd.HdPlugin.COLOR_FILTER_FADE_DURATION;
import static rs117.hd.HdPlugin.NEAR_PLANE;
import static rs117.hd.HdPlugin.ORTHOGRAPHIC_ZOOM;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.opengl.GLBinding.BINDING_SSAO_MODEL_DATA;
import static rs117.hd.opengl.GLBinding.BINDING_UBO_DISPLACEMENT;
import static rs117.hd.opengl.GLBinding.BINDING_UBO_WORLD_VIEW;
import static rs117.hd.opengl.GLBinding.BINDING_UBO_ZONES;
import static rs117.hd.utils.Mat4.clipFrustumToDistance;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class ZoneRenderer implements Renderer {
	private static final int ALPHA_ZSORT_CLOSE = 2048;

	@Inject
	private Client client;

	@Inject
	private DrawManager drawManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private LightManager lightManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private SSBOModelData ssboModelData;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private FacePrioritySorter facePrioritySorter;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneShaderProgram sceneProgram;

	@Inject
	private ShadowShaderProgram.Fast fastShadowProgram;

	@Inject
	private ShadowShaderProgram.Detailed detailedShadowProgram;

	@Inject
	private JobSystem jobSystem;

	@Inject
	private UBOWorldViews uboWorldViews;

	@Inject
	private UBOZoneData uboZoneData;

	private final Camera sceneCamera = new Camera();
	private final Camera directionalCamera = new Camera().setOrthographic(true);
	private final ShadowCasterVolume directionalShadowCasterVolume = new ShadowCasterVolume(directionalCamera);

	private final int[] worldPos = new int[3];

	private final RenderState renderState = new RenderState();
	private final CommandBuffer sceneCmd = new CommandBuffer(renderState);
	private final CommandBuffer directionalCmd = new CommandBuffer(renderState);

	private VAO.VAOList vaoO;
	private VAO.VAOList vaoA;
	private VAO.VAOList vaoPO;
	private VAO.VAOList vaoShadow;

	public static int indirectDrawCmds;
	public static GpuIntBuffer indirectDrawCmdsStaging;

	public static int eboAlpha;
	public static GpuIntBuffer eboAlphaStaging;
	public static int alphaFaceCount;

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
		
		uboWorldViews.initialize(BINDING_UBO_WORLD_VIEW);
		uboZoneData.initialize(BINDING_UBO_ZONES);
		plugin.uboDisplacement.initialize(BINDING_UBO_DISPLACEMENT);
		ssboModelData.initialize(BINDING_SSAO_MODEL_DATA);

		jobSystem.initialize();
		sceneManager.initialize(ssboModelData, uboWorldViews, uboZoneData);
	}

	@Override
	public void destroy() {
		destroyBuffers();

		jobSystem.destroy();
		sceneManager.destroy();
		uboWorldViews.destroy();
		uboZoneData.destroy();
		plugin.uboDisplacement.destroy();
		ssboModelData.destroy();
	}

	@Override
	public void waitUntilIdle() {
		glFinish();
	}

	@Override
	public void addShaderIncludes(ShaderIncludes includes) {
		includes
			.define("MAX_SIMULTANEOUS_WORLD_VIEWS", UBOWorldViews.MAX_SIMULTANEOUS_WORLD_VIEWS)
			.define("MAX_ZONE_DATA_COUNT", UBOZoneData.MAX_ZONES)
			.addInclude("WORLD_VIEW_GETTER", () -> plugin.generateGetter("WorldView", UBOWorldViews.MAX_SIMULTANEOUS_WORLD_VIEWS))
			.addInclude("ZONE_DATA_GETTER", () -> plugin.generateGetter("ZoneData", UBOZoneData.MAX_ZONES))
			.addInclude("MODEL_DATA_GETTER", () -> ssboModelData.generateGetter("ModelData", "MODEL_DATA_GETTER"))
			.addUniformBuffer(uboWorldViews)
			.addUniformBuffer(uboZoneData);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneProgram.compile(includes);
		fastShadowProgram.compile(includes);
		detailedShadowProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		sceneProgram.destroy();
		fastShadowProgram.destroy();
		detailedShadowProgram.destroy();
	}

	private void initializeBuffers() {
		eboAlpha = glGenBuffers();
		eboAlphaStaging = new GpuIntBuffer();

		indirectDrawCmds = glGenBuffers();
		indirectDrawCmdsStaging = new GpuIntBuffer();

		vaoO = new VAO.VAOList(eboAlpha);
		vaoA = new VAO.VAOList(eboAlpha);
		vaoPO = new VAO.VAOList(eboAlpha);
		vaoShadow = new VAO.VAOList(eboAlpha);
	}

	private void destroyBuffers() {
		vaoO.free();
		vaoA.free();
		vaoPO.free();
		vaoShadow.free();
		vaoO = vaoA = vaoPO = vaoShadow = null;

		if (eboAlpha != 0)
			glDeleteBuffers(eboAlpha);
		eboAlpha = 0;

		if (eboAlphaStaging != null)
			eboAlphaStaging.destroy();
		eboAlphaStaging = null;

		if (indirectDrawCmds != 0)
			glDeleteBuffers(indirectDrawCmds);
		indirectDrawCmds = 0;

		if (indirectDrawCmdsStaging != null)
			indirectDrawCmdsStaging.destroy();
		indirectDrawCmdsStaging = null;
	}

	@Override
	public void preSceneDraw(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds
	) {
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null)
			return;

		ctx.minLevel = minLevel;
		ctx.level = level;
		ctx.maxLevel = maxLevel;
		ctx.hideRoofIds = hideRoofIds;

		if (ctx.uboWorldViewStruct != null)
			ctx.uboWorldViewStruct.update();

		if (scene.getWorldViewId() == WorldView.TOPLEVEL) {
			preSceneDrawTopLevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		} else {
			Scene topLevel = client.getScene();
			vaoO.addRange(topLevel);
			vaoPO.addRange(topLevel);
			vaoShadow.addRange(topLevel);
		}
	}

	private void preSceneDrawTopLevel(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw
	) {
		jobSystem.processPendingClientCallbacks();

		scene.setDrawDistance(plugin.getDrawDistance());
		plugin.updateSceneFbo();

		if (!sceneManager.isTopLevelValid() || plugin.sceneViewport == null)
			return;

		WorldViewContext ctx = sceneManager.getContext(scene);

		frameTimer.begin(Timer.DRAW_FRAME);
		frameTimer.begin(Timer.DRAW_SCENE);

		boolean updateUniforms = true;

		if (!plugin.enableFreezeFrame) {
			if (!plugin.redrawPreviousFrame) {
//				// Only reset the target buffer offset right before drawing the scene. That way if there are frames
//				// after this that don't involve a scene draw, like during LOADING/HOPPING/CONNECTION_LOST, we can
//				// still redraw the previous frame's scene to emulate the client behavior of not painting over the
//				// viewport buffer.
//				renderBufferOffset = sceneContext.staticVertexCount;

				plugin.drawnTileCount = 0;
				plugin.drawnZoneCount = 0;
				plugin.drawCallCount = 0;
				plugin.drawnStaticRenderableCount = 0;
				plugin.drawnDynamicRenderableCount = 0;

//				// TODO: this could be done only once during scene swap, but is a bit of a pain to do
//				// Push unordered models that should always be drawn at the start of each frame.
//				// Used to fix issues like the right-click menu causing underwater tiles to disappear.
//				var staticUnordered = sceneContext.staticUnorderedModelBuffer.getBuffer();
//				modelPassthroughBuffer
//					.ensureCapacity(staticUnordered.limit())
//					.put(staticUnordered);
//				staticUnordered.rewind();
//				numPassthroughModels += staticUnordered.limit() / 8;
			}

			if (updateUniforms) {
				copyTo(plugin.cameraPosition, vec(cameraX, cameraY, cameraZ));
				copyTo(plugin.cameraOrientation, vec(cameraYaw, cameraPitch));

				if (ctx.sceneContext.scene == scene) {
					copyTo(plugin.cameraFocalPoint, ivec((int) client.getCameraFocalPointX(), (int) client.getCameraFocalPointZ()));
					Arrays.fill(plugin.cameraShift, 0);
				} else {
					plugin.cameraShift[0] = plugin.cameraFocalPoint[0] - (int) client.getCameraFocalPointX();
					plugin.cameraShift[1] = plugin.cameraFocalPoint[1] - (int) client.getCameraFocalPointZ();
					plugin.cameraPosition[0] += plugin.cameraShift[0];
					plugin.cameraPosition[2] += plugin.cameraShift[1];
				}

				// TODO: Wind & character displacement
//				plugin.uboCompute.windDirectionX.set(cos(environmentManager.currentWindAngle));
//				plugin.uboCompute.windDirectionZ.set(sin(environmentManager.currentWindAngle));
//				plugin.uboCompute.windStrength.set(environmentManager.currentWindStrength);
//				plugin.uboCompute.windCeiling.set(environmentManager.currentWindCeiling);
//				plugin.uboCompute.windOffset.set(plugin.windOffset);
//
//				if (plugin.configCharacterDisplacement) {
//					// The local player needs to be added first for distance culling
//					Model playerModel = localPlayer.getModel();
//					if (playerModel != null)
//						plugin.uboCompute.addCharacterPosition(lp.getX(), lp.getY(), (int) (Perspective.LOCAL_TILE_SIZE * 1.33f));
//				}

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
				sceneCamera.getViewMatrix(plugin.viewMatrix);
				sceneCamera.getViewProjMatrix(plugin.viewProjMatrix);
				sceneCamera.getInvViewProjMatrix(plugin.invViewProjMatrix);
				sceneCamera.getFrustumPlanes(plugin.cameraFrustum);

				if (sceneCamera.isDirty()) {
					int shadowDrawDistance = 90 * LOCAL_TILE_SIZE;
					directionalCamera.setPitch(environmentManager.currentSunAngles[0]);
					directionalCamera.setYaw(PI - environmentManager.currentSunAngles[1]);

					// Define a Finite Plane before extracting corners
					sceneCamera.setFarPlane(drawDistance * LOCAL_TILE_SIZE);

					int maxDistance = Math.min(shadowDrawDistance, (int) sceneCamera.getFarPlane());
					final float[][] sceneFrustumCorners = sceneCamera.getFrustumCorners();
					clipFrustumToDistance(sceneFrustumCorners, maxDistance);

					directionalShadowCasterVolume.build(sceneFrustumCorners);

					sceneCamera.setFarPlane(0.0f); // Reset so Scene can use Infinite Plane instead

					final float[] sceneCenter = new float[3];
					for (float[] corner : sceneFrustumCorners)
						add(sceneCenter, sceneCenter, corner);
					divide(sceneCenter, sceneCenter, (float) sceneFrustumCorners.length);

					float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
					float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
					float radius = 0f;
					for (float[] corner : sceneFrustumCorners) {
						radius = max(radius, distance(sceneCenter, corner));

						directionalCamera.transformPoint(corner, corner);

						minX = min(minX, corner[0]);
						maxX = max(maxX, corner[0]);

						minZ = min(minZ, corner[2]);
						maxZ = max(maxZ, corner[2]);
					}
					int directionalSize = (int) max(abs(maxX - minX), abs(maxZ - minZ));

					directionalCamera.setPosition(sceneCenter);
					directionalCamera.setNearPlane(radius * 2.0f);
					directionalCamera.setZoom(1.0f);
					directionalCamera.setViewportWidth(directionalSize);
					directionalCamera.setViewportHeight(directionalSize);

					plugin.uboGlobal.lightDir.set(directionalCamera.getForwardDirection());
					plugin.uboGlobal.lightProjectionMatrix.set(directionalCamera.getViewProjMatrix());
				}

				if (ctx.sceneContext.scene == scene) {
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
						plugin.stopPlugin();
						return;
					}
				}

				plugin.uboGlobal.cameraPos.set(plugin.cameraPosition);
				plugin.uboGlobal.viewMatrix.set(plugin.viewMatrix);
				plugin.uboGlobal.projectionMatrix.set(plugin.viewProjMatrix);
				plugin.uboGlobal.invProjectionMatrix.set(plugin.invViewProjMatrix);
				plugin.uboGlobal.pointLightsCount.set(ctx.sceneContext.numVisibleLights);
				plugin.uboGlobal.upload();
			}
		}

		if (plugin.configDynamicLights != DynamicLights.NONE && ctx.sceneContext.scene == scene) {
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
			frameTimer.end(Timer.UPDATE_LIGHTS);

			// Perform tiled lighting culling before the compute memory barrier, so it's performed asynchronously
			if (plugin.configTiledLighting) {
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
		}

		if (plugin.lastFrameTimeMillis > 0) {
			plugin.deltaTime = (float) ((System.currentTimeMillis() - plugin.lastFrameTimeMillis) / 1000.);

			// Restart the plugin to avoid potential buffer corruption if the computer has likely resumed from suspension
			if (plugin.deltaTime > 300) {
				log.debug("Restarting the plugin after probable OS suspend ({} second delta)", plugin.deltaTime);
				plugin.restartPlugin();
				return;
			}

			// If system time changes between frames, clamp the delta to a more sensible value
			if (abs(plugin.deltaTime) > 10)
				plugin.deltaTime = 1 / 60.f;
			plugin.elapsedTime += plugin.deltaTime;
			plugin.windOffset += plugin.deltaTime * environmentManager.currentWindSpeed;

			// The client delta doesn't need clamping
			plugin.deltaClientTime = (float) (plugin.elapsedClientTime - plugin.lastFrameClientTime);
		}
		plugin.lastFrameTimeMillis = System.currentTimeMillis();
		plugin.lastFrameClientTime = plugin.elapsedClientTime;

		// Upon logging in, the client will draw some frames with zero geometry before it hides the login screen
		if (client.getGameState().getState() >= GameState.LOGGED_IN.getState())
			plugin.hasLoggedIn = true;

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
		plugin.uboGlobal.fogColor.set(ColorUtils.linearToSrgb(environmentManager.currentFogColor));

		plugin.uboGlobal.drawDistance.set((float) plugin.getDrawDistance());
		plugin.uboGlobal.detailDrawDistance.set((float) config.detailDrawDistance());
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
		plugin.uboGlobal.pointLightsCount.set(ctx.sceneContext.numVisibleLights);
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
		eboAlphaStaging.clear();
		indirectDrawCmdsStaging.clear();
		sceneCmd.reset();
		directionalCmd.reset();
		renderState.reset();

		checkGLErrors();
	}

	@Override
	public void postSceneDraw(Scene scene) {
		jobSystem.processPendingClientCallbacks();

		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			postDrawTopLevel();
	}

	private void postDrawTopLevel() {
		if (!sceneManager.isTopLevelValid() || plugin.sceneViewport == null)
			return;

		sceneFboValid = true;

		vaoA.unmap();

		// Upload world views & zone data before rendering
		uboWorldViews.upload();
		uboZoneData.upload();

		// Scene draw state to apply before all recorded commands
		if (eboAlphaStaging.position() > 0) {
			eboAlphaStaging.flip();
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboAlpha);
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, eboAlphaStaging.getBuffer(), GL_STREAM_DRAW);
		}

		if (indirectDrawCmdsStaging.position() > 0) {
			indirectDrawCmdsStaging.flip();
			glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawCmds);
			glBufferData(GL_DRAW_INDIRECT_BUFFER, indirectDrawCmdsStaging.getBuffer(), GL_STREAM_DRAW);
		}

		ssboModelData.upload();

		checkGLErrors();

		frameTimer.end(Timer.DRAW_SCENE);
		frameTimer.begin(Timer.RENDER_FRAME);

		// Space out GL calls on Apple, to minimize stalls from the command queue filling up
		if (APPLE)
			directionalShadowPass();
		shouldRenderScene = true;

		// The client only updates animations once per client tick, so we can skip updating geometry buffers,
		// but the compute shaders should still be executed in case the camera angle has changed.
		// Technically we could skip compute shaders as well when the camera is unchanged,
		// but it would only lead to micro stuttering when rotating the camera, compared to no rotation.
//		if (!plugin.redrawPreviousFrame) {
//			updateSceneVao(hRenderBufferVertices, hRenderBufferUvs, hRenderBufferNormals);
//		}

//		frameTimer.begin(Timer.COMPUTE);
//		plugin.uboCompute.upload();
//		frameTimer.end(Timer.COMPUTE);
	}

	private void directionalShadowPass() {
		if (!plugin.configShadowsEnabled || plugin.fboShadowMap == 0 || environmentManager.currentDirectionalStrength <= 0)
			return;

		frameTimer.begin(Timer.RENDER_SHADOWS);

		// Render to the shadow depth map
		renderState.framebuffer.set(GL_FRAMEBUFFER, plugin.fboShadowMap);
		renderState.viewport.set(0, 0, plugin.shadowMapResolution, plugin.shadowMapResolution);
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
			plugin.drawCallCount += directionalCmd.getDrawCallCount();

			checkGLErrors();
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
		renderState.apply();

		// Clear scene
		frameTimer.begin(Timer.CLEAR_SCENE);

		float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
		float[] gammaCorrectedFogColor = pow(fogColor, plugin.getGammaCorrection());
		glClearColor(
			gammaCorrectedFogColor[0],
			gammaCorrectedFogColor[1],
			gammaCorrectedFogColor[2],
			1f
		);
		glClearDepth(0);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
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

		plugin.drawCallCount += sceneCmd.getDrawCallCount();

		checkGLErrors();
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

		minX *= LOCAL_TILE_SIZE;
		minZ *= LOCAL_TILE_SIZE;
		int maxX = minX + CHUNK_SIZE * LOCAL_TILE_SIZE;
		int maxZ = minZ + CHUNK_SIZE * LOCAL_TILE_SIZE;
		Zone zone = ctx.zones[zx][zz];
		if (zone.hasWater) {
			maxY += ProceduralGenerator.MAX_DEPTH;
			minY -= ProceduralGenerator.MAX_DEPTH;
		}

		zone.inSceneFrustum = sceneCamera.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
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
		if (ctx == null)
			return;

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized || z.sizeO == 0)
			return;

		plugin.drawnZoneCount++;
		if (!sceneManager.isRoot(ctx) || z.inSceneFrustum)
			z.renderOpaque(sceneCmd, ctx, false);

		if (!sceneManager.isRoot(ctx) || z.inShadowFrustum) {
			directionalCmd.SetShader(fastShadowProgram);
			z.renderOpaque(directionalCmd, ctx, plugin.configRoofShadows);
		}

		checkGLErrors();
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		jobSystem.processPendingClientCallbacks();

		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null)
			return;

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
			return;

		boolean renderWater = z.inSceneFrustum && level == 0 && z.hasWater;
		if (renderWater)
			z.renderOpaqueLevel(sceneCmd, Zone.LEVEL_WATER_SURFACE);

		boolean hasAlpha = z.sizeA != 0 || !z.alphaModels.isEmpty();
		if (!hasAlpha)
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int dx = (int) plugin.cameraPosition[0] - ((zx - offset) << 10);
		int dz = (int) plugin.cameraPosition[2] - ((zz - offset) << 10);
		// If the zone is at sea, allow incorrect alpha ordering in the distance, for areas like north of Prifddinas
		boolean useStaticUnsorted = z.onlyWater && dx * dx + dz * dz > ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;

		if (level == 0) {
			z.alphaSort(zx - offset, zz - offset, sceneCamera);
			z.multizoneLocs(ctx.sceneContext, zx - offset, zz - offset, sceneCamera, ctx.zones);
		}

		if (!sceneManager.isRoot(ctx) || z.inSceneFrustum) {
			z.renderAlpha(
				sceneCmd,
				zx - offset,
				zz - offset,
				level,
				ctx,
				sceneCamera,
				false,
				useStaticUnsorted
			);
		}

		if (!sceneManager.isRoot(ctx) || z.inShadowFrustum) {
			directionalCmd.SetShader(plugin.configShadowMode == ShadowMode.DETAILED ? detailedShadowProgram : fastShadowProgram);
			z.renderAlpha(
				sceneCmd,
				zx - offset,
				zz - offset,
				level,
				ctx,
				sceneCamera,
				plugin.configRoofShadows,
				useStaticUnsorted
			);
		}

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass) {
		jobSystem.processPendingClientCallbacks();

		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null)
			return;

		switch (pass) {
			case DrawCallbacks.PASS_OPAQUE:
				vaoO.addRange(scene);
				vaoPO.addRange(scene);
				vaoShadow.addRange(scene);

				if (scene.getWorldViewId() == -1) {
					directionalCmd.SetShader(fastShadowProgram);

					// Draw opaque
					vaoO.unmap();
					vaoO.drawAll(sceneCmd);
					vaoO.drawAll(directionalCmd);
					vaoO.resetAll();

					vaoPO.unmap();

					// Draw shadow-only models
					vaoShadow.unmap();
					vaoShadow.drawAll(directionalCmd);
					vaoShadow.resetAll();

					// Draw players opaque, without depth writes
					sceneCmd.DepthMask(false);
					vaoPO.drawAll(sceneCmd);
					sceneCmd.DepthMask(true);

					// Draw players opaque, writing only depth
					sceneCmd.ColorMask(false, false, false, false);
					vaoPO.drawAll(sceneCmd);
					sceneCmd.ColorMask(true, true, true, true);

					vaoPO.resetAll();
				}
				break;
			case DrawCallbacks.PASS_ALPHA:
				for (int x = 0; x < ctx.sizeX; ++x)
					for (int z = 0; z < ctx.sizeZ; ++z)
						ctx.zones[x][z].removeTemp();
				break;
		}

		checkGLErrors();
	}

	@Override
	public void drawDynamic(
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
		jobSystem.processPendingClientCallbacks();

		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || !renderCallbackManager.drawObject(scene, tileObject))
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (x >> 10) + offset;
		int zz = (z >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		if (!zone.initialized)
			return;

		if (sceneManager.isRoot(ctx)) {
			// Cull based on detail draw distance
			float squaredDistance = sceneCamera.squaredDistanceTo(x, y, z);
			int detailDrawDistanceTiles = plugin.configDetailDrawDistance * LOCAL_TILE_SIZE;
			if (squaredDistance > detailDrawDistanceTiles * detailDrawDistanceTiles)
				return;

			// Hide everything outside the current area if area hiding is enabled
			if (ctx.sceneContext.currentArea != null) {
				var base = ctx.sceneContext.sceneBase;
				assert base != null;
				boolean inArea = ctx.sceneContext.currentArea.containsPoint(
					base[0] + (x >> Perspective.LOCAL_COORD_BITS),
					base[1] + (z >> Perspective.LOCAL_COORD_BITS),
					base[2] + client.getTopLevelWorldView().getPlane()
				);
				if (!inArea)
					return;
			}
		}

		ctx.sceneContext.localToWorld(tileObject.getLocalLocation(), tileObject.getPlane(), worldPos);
		int uuid = ModelHash.generateUuid(client, tileObject.getHash(), r);
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		if (sceneManager.isRoot(ctx)) {
			try (var ignored = frameTimer.begin(Timer.VISIBILITY_CHECK)) {
				// Additional Culling checks to help reduce dynamic object perf impact when off screen
				if (!zone.inSceneFrustum && zone.inShadowFrustum && !modelOverride.castShadows)
					return;

				if (zone.inSceneFrustum && !modelOverride.castShadows && !sceneCamera.intersectsSphere(x, y, z, m.getRadius()))
					return;

				if (!zone.inSceneFrustum && zone.inShadowFrustum && modelOverride.castShadows &&
					!directionalShadowCasterVolume.intersectsPoint(x, y, z))
					return;
			}
		}

		int modelDataOffset = ssboModelData.addDynamicModelData(r, m, modelOverride, x, y, z, sceneManager.isRoot(ctx));
		int preOrientation = HDUtils.getModelPreOrientation(HDUtils.getObjectConfig(tileObject));

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		VAO o = vaoO.get(size);

		boolean hasAlpha = m.getFaceTransparencies() != null;
		if (hasAlpha) {
			VAO a = vaoA.get(size);
			int start = a.vbo.vb.position();

			if (zone.inSceneFrustum) {
				try {
				facePrioritySorter.uploadSortedModel(projection, m, modelOverride, preOrientation, orient, x, y, z, zone.zoneData.zoneIdx, modelDataOffset, o.vbo.vb, a.vbo.vb);
				} catch (Exception ex) {
					log.debug("error drawing entity", ex);
				}

				if (plugin.configShadowsEnabled) {
					// Since priority sorting of models includes back-face culling,
					// we need to upload the entire model again for shadows
					VAO vao = vaoShadow.get(size);
					sceneUploader.uploadTempModel(
						m,
						modelOverride,
						preOrientation,
						orient,
						x, y, z,
						zone.zoneData.zoneIdx,
						modelDataOffset,
						vao.vbo.vb,
						vao.vbo.vb
					);
				}
			} else {
				sceneUploader.uploadTempModel(m, modelOverride, preOrientation, orient, x, y, z, zone.zoneData.zoneIdx, modelDataOffset, o.vbo.vb, a.vbo.vb);
			}

			int end = a.vbo.vb.position();
			if (end > start) {
				// level is checked prior to this callback being run, in order to cull clickboxes, but
				// tileObject.getPlane()>maxLevel if visbelow is set - lower the object to the max level
				int plane = Math.min(ctx.maxLevel, tileObject.getPlane());
				// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y, z & 1023);
			}
		} else {
			sceneUploader.uploadTempModel(m, modelOverride, preOrientation, orient, x, y, z, zone.zoneData.zoneIdx, modelDataOffset, o.vbo.vb, o.vbo.vb);
		}
		plugin.drawnDynamicRenderableCount++;
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orientation, int x, int y, int z) {
		jobSystem.processPendingClientCallbacks();

		WorldViewContext ctx = sceneManager.getContext(scene);
		if (ctx == null || !renderCallbackManager.drawObject(scene, gameObject))
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (gameObject.getX() >> 10) + offset;
		int zz = (gameObject.getY() >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		if(!zone.initialized)
			return;

		ctx.sceneContext.localToWorld(gameObject.getLocalLocation(), gameObject.getPlane(), worldPos);
		// Hide everything outside the current area if area hiding is enabled
		if (ctx.sceneContext.currentArea != null && scene.getWorldViewId() == -1) {
			var base = ctx.sceneContext.sceneBase;
			assert base != null;
			boolean inArea = ctx.sceneContext.currentArea.containsPoint(
				base[0] + (x >> Perspective.LOCAL_COORD_BITS),
				base[1] + (z >> Perspective.LOCAL_COORD_BITS),
				base[2] + client.getTopLevelWorldView().getPlane()
			);
			if (!inArea)
				return;
		}

		Renderable renderable = gameObject.getRenderable();
		int uuid = ModelHash.generateUuid(client, gameObject.getHash(), renderable);
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		int modelDataOffset = ssboModelData.addDynamicModelData(renderable, m, modelOverride, x, y, z, false);
		int preOrientation = HDUtils.getModelPreOrientation(gameObject.getConfig());

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (renderable instanceof Player || m.getFaceTransparencies() != null) {
			GenericJob shadowUploadTask = null;
			if (zone.inShadowFrustum) {
				final VAO o = vaoShadow.get(size);

				shadowUploadTask = GenericJob
					.build("uploadTempModel", t -> {
						// Since priority sorting of models includes back-face culling,
						// we need to upload the entire model again for shadows
						sceneUploader.uploadTempModel(
							m,
							modelOverride,
							preOrientation,
							orientation,
							x, y, z,
							zone.zoneData.zoneIdx,
							modelDataOffset,
							o.vbo.vb,
							o.vbo.vb
						);
					})
					.setExecuteAsync(!sceneManager.isRoot(ctx) || zone.inSceneFrustum)
					.queue(true);
			}

			if (!sceneManager.isRoot(ctx) || zone.inSceneFrustum) {
				// opaque player faces have their own vao and are drawn in a separate pass from normal opaque faces
				// because they are not depth tested. transparent player faces don't need their own vao because normal
				// transparent faces are already not depth tested
				VAO o = renderable instanceof Player ? vaoPO.get(size) : vaoO.get(size);
				VAO a = vaoA.get(size);

				int start = a.vbo.vb.position();
				try {
					facePrioritySorter.uploadSortedModel(
						worldProjection,
						m,
						modelOverride,
						preOrientation,
						orientation,
						x, y, z,
						zone.zoneData.zoneIdx,
						modelDataOffset,
						o.vbo.vb,
						a.vbo.vb
					);
				} catch (Exception ex) {
					log.debug("error drawing entity", ex);
				}
				int end = a.vbo.vb.position();
				if (end > start) {
					zone.addTempAlphaModel(
						a.vao,
						start,
						end,
						gameObject.getPlane(),
						x & 1023,
						y - renderable.getModelHeight() /* to render players over locs */,
						z & 1023
					);
				}
			}

			if (shadowUploadTask != null) {
				shadowUploadTask.waitForCompletion();
				shadowUploadTask.release();
			}
		} else {
			VAO o = vaoO.get(size);
			sceneUploader.uploadTempModel(
				m,
				modelOverride,
				preOrientation,
				orientation,
				x, y, z,
				zone.zoneData.zoneIdx, modelDataOffset,
				o.vbo.vb,
				o.vbo.vb
			);
		}
		plugin.drawnDynamicRenderableCount++;
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

		if (shouldRenderScene) {
			if (!APPLE)
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

		jobSystem.processPendingClientCallbacks(false);

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

		ssboModelData.freeDynamicModelData();

		frameTimer.end(Timer.DRAW_FRAME);
		frameTimer.end(Timer.RENDER_FRAME);
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
		ssboModelData.defrag();

	}
}
