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

import com.google.common.base.Stopwatch;
import com.google.inject.Injector;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
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
import rs117.hd.opengl.buffer.uniforms.UBOCommandStructuredBuffer;
import rs117.hd.opengl.buffer.uniforms.UBOLights;
import rs117.hd.opengl.buffer.uniforms.UBOWorldViews;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.Renderer;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.COLOR_FILTER_FADE_DURATION;
import static rs117.hd.HdPlugin.NEAR_PLANE;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.Mat4.clipFrustumToDistance;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class ZoneRenderer implements Renderer {
	private static final int NUM_ZONES = EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

	private static int UNIFORM_BLOCK_COUNT = HdPlugin.UNIFORM_BLOCK_COUNT;
	public static final int UNIFORM_BLOCK_COMMAND_BUFFER = UNIFORM_BLOCK_COUNT++;
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
	private AreaManager areaManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private FacePrioritySorter facePrioritySorter;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneShaderProgram sceneProgram;

	private final Camera sceneCamera = new Camera();
	private final Camera directionalCamera = new Camera().setOrthographic(true);

	private int minLevel, level, maxLevel;
	private Set<Integer> hideRoofIds;

	private final CommandBuffer sceneCmd = new CommandBuffer();
	private final CommandBuffer directionalCmd = new CommandBuffer();

	private VAO.VAOList vaoO;
	private VAO.VAOList vaoA;
	private VAO.VAOList vaoPO;
	private VAO.VAOList vaoPOShadow;

	public static int eboAlpha;
	public static GpuIntBuffer eboAlphaStaging;
	public static int alphaFaceCount;

	static class WorldViewContext {
		final int sizeX, sizeZ;
		ZoneSceneContext sceneContext;
		Zone[][] zones;

		WorldViewContext(@Nullable ZoneSceneContext sceneContext, int sizeX, int sizeZ) {
			this.sceneContext = sceneContext;
			this.sizeX = sizeX;
			this.sizeZ = sizeZ;
			zones = new Zone[sizeX][sizeZ];
			for (int x = 0; x < sizeX; ++x) {
				for (int z = 0; z < sizeZ; ++z) {
					zones[x][z] = new Zone();
				}
			}
		}

		void free() {
			if (sceneContext != null)
				sceneContext.destroy();
			sceneContext = null;

			for (int x = 0; x < sizeX; ++x)
				for (int z = 0; z < sizeZ; ++z)
					zones[x][z].free();
		}
	}

	WorldViewContext context(Scene scene) {
		return context(scene.getWorldViewId());
	}

	WorldViewContext context(WorldView wv) {
		return context(wv.getId());
	}

	WorldViewContext context(int worldViewId) {
		if (worldViewId != -1)
			return subs[worldViewId];
		if (root.sceneContext == null)
			return null;
		return root;
	}

	private boolean sceneFboValid;

	private final UBOCommandStructuredBuffer uboCommandBuffer = new UBOCommandStructuredBuffer();
	public final UBOWorldViews uboWorldViews = new UBOWorldViews(MAX_WORLDVIEWS);

	private final WorldViewContext root = new WorldViewContext(null, NUM_ZONES, NUM_ZONES);
	private final WorldViewContext[] subs = new WorldViewContext[MAX_WORLDVIEWS];
	private ZoneSceneContext nextSceneContext;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	@Nullable
	public ZoneSceneContext getSceneContext() {
		return root.sceneContext;
	}

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

		uboCommandBuffer.initialize(UNIFORM_BLOCK_COMMAND_BUFFER);
		uboWorldViews.initialize(UNIFORM_BLOCK_WORLD_VIEWS);

		sceneCmd.setUboCommandBuffer(uboCommandBuffer);
		directionalCmd.setUboCommandBuffer(uboCommandBuffer);
	}

	@Override
	public void destroy() {
		root.free();

		for (int i = 0; i < subs.length; i++) {
			if (subs[i] != null)
				subs[i].free();
			subs[i] = null;
		}

		destroyBuffers();
		uboCommandBuffer.destroy();
		uboWorldViews.destroy();

		nextZones = null;
		nextRoofChanges = null;
		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;
	}

	@Override
	public void waitUntilIdle() {
		glFinish();
	}

	@Override
	public void addShaderIncludes(ShaderIncludes includes) {
		includes
			.define("MAX_SIMULTANEOUS_WORLD_VIEWS", UBOWorldViews.MAX_SIMULTANEOUS_WORLD_VIEWS)
			.addInclude("WORLD_VIEW_GETTER", () -> plugin.generateGetter("WorldView", MAX_WORLDVIEWS))
			.addUniformBuffer(uboWorldViews)
			.addUniformBuffer(uboCommandBuffer);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		sceneProgram.destroy();
	}

	private void initializeBuffers() {
		eboAlpha = glGenBuffers();
		eboAlphaStaging = new GpuIntBuffer();

		vaoO = new VAO.VAOList(eboAlpha);
		vaoA = new VAO.VAOList(eboAlpha);
		vaoPO = new VAO.VAOList(eboAlpha);
		vaoPOShadow = new VAO.VAOList(eboAlpha);
	}

	private void destroyBuffers() {
		vaoO.free();
		vaoA.free();
		vaoPO.free();
		vaoPOShadow.free();
		vaoO = vaoA = vaoPO = vaoPOShadow = null;

		if (eboAlpha != 0)
			glDeleteBuffers(eboAlpha);
		eboAlpha = 0;

		if (eboAlphaStaging != null)
			eboAlphaStaging.destroy();
		eboAlphaStaging = null;
	}

	@Override
	public void preSceneDraw(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds
	) {
		log.trace(
			"preSceneDraw({}, cameraPos=[{}, {}, {}], cameraOri=[{}, {}], minLevel={}, level={}, maxLevel={}, hideRoofIds=[{}])",
			scene,
			cameraX,
			cameraY,
			cameraZ,
			cameraPitch,
			cameraYaw,
			minLevel,
			level,
			maxLevel,
			hideRoofIds.stream().map(i -> Integer.toString(i)).collect(
				Collectors.joining(", "))
		);
		this.minLevel = minLevel;
		this.level = level;
		this.maxLevel = maxLevel;
		this.hideRoofIds = hideRoofIds;

		if (scene.getWorldViewId() == WorldView.TOPLEVEL) {
			preSceneDrawTopLevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		} else {
			Scene topLevel = client.getScene();
			vaoO.addRange(topLevel);
			vaoPO.addRange(topLevel);
			vaoPOShadow.addRange(topLevel);
			sceneCmd.SetWorldViewIndex(uboWorldViews.getIndex(scene));
			directionalCmd.SetWorldViewIndex(uboWorldViews.getIndex(scene));
		}
	}

	private void preSceneDrawTopLevel(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw
	) {
		scene.setDrawDistance(plugin.getDrawDistance());
		plugin.updateSceneFbo();

		if (root.sceneContext == null || plugin.sceneViewport == null)
			return;

		frameTimer.begin(Timer.DRAW_FRAME);
		frameTimer.begin(Timer.DRAW_SCENE);

		boolean updateUniforms = true;

		Player localPlayer = client.getLocalPlayer();
		var lp = localPlayer.getLocalLocation();
		if (root.sceneContext.enableAreaHiding) {
			assert root.sceneContext.sceneBase != null;
			int[] worldPos = {
				root.sceneContext.sceneBase[0] + lp.getSceneX(),
				root.sceneContext.sceneBase[1] + lp.getSceneY(),
				root.sceneContext.sceneBase[2] + client.getTopLevelWorldView().getPlane()
			};

			// We need to check all areas contained in the scene in the order they appear in the list,
			// in order to ensure lower floors can take precedence over higher floors which include tiny
			// portions of the floor beneath around stairs and ladders
			Area newArea = null;
			for (var area : root.sceneContext.possibleAreas) {
				if (area.containsPoint(false, worldPos)) {
					newArea = area;
					break;
				}
			}

			// Force a scene reload if the player is no longer in the same area
			if (newArea != root.sceneContext.currentArea) {
				if (plugin.justChangedArea) {
					// Prevent getting stuck in a scene reloading loop if this breaks for any reason
					root.sceneContext.forceDisableAreaHiding = true;
					log.error(
						"Force disabling area hiding after moving from {} to {} at {}",
						root.sceneContext.currentArea,
						newArea,
						worldPos
					);
				} else {
					plugin.justChangedArea = true;
				}
				// Reload the scene to reapply area hiding
				client.setGameState(GameState.LOADING);
				updateUniforms = false;
				plugin.redrawPreviousFrame = true;
			} else {
				plugin.justChangedArea = false;
			}
		} else {
			plugin.justChangedArea = false;
		}

		if (!plugin.enableFreezeFrame) {
			if (!plugin.redrawPreviousFrame) {
//				// Only reset the target buffer offset right before drawing the scene. That way if there are frames
//				// after this that don't involve a scene draw, like during LOADING/HOPPING/CONNECTION_LOST, we can
//				// still redraw the previous frame's scene to emulate the client behavior of not painting over the
//				// viewport buffer.
//				renderBufferOffset = sceneContext.staticVertexCount;

				plugin.drawnTileCount = 0;
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

				if (root.sceneContext.scene == scene) {
					copyTo(plugin.cameraFocalPoint, ivec((int) client.getCameraFocalPointX(), (int) client.getCameraFocalPointZ()));
					Arrays.fill(plugin.cameraShift, 0);

					try {
						frameTimer.begin(Timer.UPDATE_ENVIRONMENT);
						environmentManager.update(root.sceneContext);
						frameTimer.end(Timer.UPDATE_ENVIRONMENT);

						frameTimer.begin(Timer.UPDATE_LIGHTS);
						lightManager.update(root.sceneContext, plugin.cameraShift, plugin.cameraFrustum);
						frameTimer.end(Timer.UPDATE_LIGHTS);
					} catch (Exception ex) {
						log.error("Error while updating environment or lights:", ex);
						plugin.stopPlugin();
						return;
					}
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

				// Calculate the viewport dimensions before scaling in order to include the extra padding
				sceneCamera.setPosition(plugin.cameraPosition);
				sceneCamera.setOrientation(plugin.cameraOrientation);
				sceneCamera.setFixedYaw(client.getCameraYaw());
				sceneCamera.setFixedPitch(client.getCameraPitch());
				sceneCamera.setViewportWidth((int) (plugin.sceneViewport[2] / plugin.sceneViewportScale[0]));
				sceneCamera.setViewportHeight((int) (plugin.sceneViewport[3] / plugin.sceneViewportScale[1]));
				sceneCamera.setNearPlane(NEAR_PLANE);
				sceneCamera.setZoom(zoom);


				// Calculate view matrix, view proj & inv matrix
				sceneCamera.getViewMatrix(plugin.viewMatrix);
				sceneCamera.getViewProjMatrix(plugin.viewProjMatrix);
				sceneCamera.getInvViewProjMatrix(plugin.invViewProjMatrix);
				sceneCamera.getFrustumPlanes(plugin.cameraFrustum);

				int shadowDrawDistance = config.shadowDistance().getValue() * LOCAL_TILE_SIZE;
				directionalCamera.setPitch(environmentManager.currentSunAngles[0]);
				directionalCamera.setYaw(PI - environmentManager.currentSunAngles[1]);

				// Define a Finite Plane before extracting corners
				sceneCamera.setFarPlane(drawDistance * LOCAL_TILE_SIZE);

				int maxDistance = Math.min(shadowDrawDistance, (int) sceneCamera.getFarPlane());
				final float[][] sceneFrustumCorners = Mat4.extractFrustumCorners(sceneCamera.getInvViewProjMatrix());
				clipFrustumToDistance(sceneFrustumCorners, maxDistance);
				sceneCamera.setFarPlane(0.0f); // Reset so Scene can use Infinite Plane instead

				final float[] centerXZ = new float[2];
				for (float[] corner : sceneFrustumCorners) {
					add(centerXZ, centerXZ, corner[0], corner[2]);
				}
				divide(centerXZ, centerXZ, (float) sceneFrustumCorners.length);

				float radius = 0f;
				for (float[] corner : sceneFrustumCorners) {
					radius = Math.max(radius, length(corner[0] - centerXZ[0], corner[2] - centerXZ[1]));
				}

				directionalCamera.setPositionX(centerXZ[0]);
				directionalCamera.setPositionZ(centerXZ[1]);
				directionalCamera.setNearPlane(radius);
				directionalCamera.setZoom(1.0f);
				directionalCamera.setViewportWidth((int) radius);
				directionalCamera.setViewportHeight((int) radius);

				plugin.uboGlobal.lightDir.set(directionalCamera.getForwardDirection());
				plugin.uboGlobal.lightProjectionMatrix.set(directionalCamera.getViewProjMatrix());

				if (root.sceneContext.scene == scene) {
					try {
						frameTimer.begin(Timer.UPDATE_ENVIRONMENT);
						environmentManager.update(root.sceneContext);
						frameTimer.end(Timer.UPDATE_ENVIRONMENT);

						frameTimer.begin(Timer.UPDATE_LIGHTS);
						lightManager.update(root.sceneContext, plugin.cameraShift, plugin.cameraFrustum);
						frameTimer.end(Timer.UPDATE_LIGHTS);
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
				plugin.uboGlobal.pointLightsCount.set(root.sceneContext.numVisibleLights);
				plugin.uboGlobal.upload();
			}
		}

		if (plugin.configDynamicLights != DynamicLights.NONE && root.sceneContext.scene == scene && updateUniforms) {
			// Update lights UBO
			assert root.sceneContext.numVisibleLights <= UBOLights.MAX_LIGHTS;

			frameTimer.begin(Timer.UPDATE_LIGHTS);
			final float[] lightPosition = new float[4];
			final float[] lightColor = new float[4];
			for (int i = 0; i < root.sceneContext.numVisibleLights; i++) {
				final Light light = root.sceneContext.lights.get(i);
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

				glViewport(0, 0, plugin.tiledLightingResolution[0], plugin.tiledLightingResolution[1]);
				glBindFramebuffer(GL_FRAMEBUFFER, plugin.fboTiledLighting);

				glBindVertexArray(plugin.vaoTri);

				if (plugin.tiledLightingImageStoreProgram.isValid()) {
					plugin.tiledLightingImageStoreProgram.use();
					glDrawBuffer(GL_NONE);
					glDrawArrays(GL_TRIANGLES, 0, 3);
				} else {
					glDrawBuffer(GL_COLOR_ATTACHMENT0);
					int layerCount = plugin.configDynamicLights.getTiledLightingLayers();
					for (int layer = 0; layer < layerCount; layer++) {
						plugin.tiledLightingShaderPrograms.get(layer).use();
						glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, plugin.texTiledLighting, 0, layer);
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
		plugin.uboGlobal.expandedMapLoadingChunks.set(root.sceneContext.expandedMapLoadingChunks);
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
		plugin.uboGlobal.pointLightsCount.set(root.sceneContext.numVisibleLights);
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
		uboWorldViews.update(client);

		// Reset buffers for the next frame
		eboAlphaStaging.clear();
		sceneCmd.reset();
		directionalCmd.reset();

		sceneCmd.SetWorldViewIndex(uboWorldViews.getIndex(null));
		directionalCmd.SetWorldViewIndex(uboWorldViews.getIndex(null));

		checkGLErrors();
	}

	@Override
	public void postSceneDraw(Scene scene) {
		log.trace("postSceneDraw({})", scene);
		if (scene.getWorldViewId() == WorldView.TOPLEVEL) {
			postDrawTopLevel();
		} else {
			sceneCmd.SetWorldViewIndex(uboWorldViews.getIndex(null));
			directionalCmd.SetWorldViewIndex(uboWorldViews.getIndex(null));
		}
	}

	private void postDrawTopLevel() {
		if (root.sceneContext == null || plugin.sceneViewport == null)
			return;

		sceneFboValid = true;

		vaoA.unmap();

		// Scene draw state to apply before all recorded commands
		if (eboAlphaStaging.position() > 0) {
			eboAlphaStaging.flip();
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboAlpha);
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, eboAlphaStaging.getBuffer(), GL_STREAM_DRAW);
		}

		if (plugin.configShadowsEnabled &&
			plugin.fboShadowMap != 0 &&
			environmentManager.currentDirectionalStrength > 0
		) {
			frameTimer.begin(Timer.RENDER_SHADOWS);

			// Render to the shadow depth map
			glViewport(0, 0, plugin.shadowMapResolution, plugin.shadowMapResolution);
			glBindFramebuffer(GL_FRAMEBUFFER, plugin.fboShadowMap);
			glClearDepth(1);
			glClear(GL_DEPTH_BUFFER_BIT);

			plugin.shadowProgram.use();

			// TODO: Depth test will get changed by the command buffer, but we'll be adding a shadowCmd anyway
			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_LEQUAL);
			glDisable(GL_CULL_FACE);

			CommandBuffer.SKIP_DEPTH_MASKING = true;
			directionalCmd.execute();
			CommandBuffer.SKIP_DEPTH_MASKING = false;

			glDisable(GL_DEPTH_TEST);

			frameTimer.end(Timer.RENDER_SHADOWS);
		}

		sceneProgram.use();

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		if (plugin.msaaSamples > 1) {
			glEnable(GL_MULTISAMPLE);
		} else {
			glDisable(GL_MULTISAMPLE);
		}
		glViewport(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);

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

		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		glEnable(GL_CULL_FACE);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);

		// Render the scene
		sceneCmd.execute();

		// TODO: Filler tiles

		frameTimer.end(Timer.DRAW_SCENE);
		frameTimer.end(Timer.RENDER_SCENE);
		frameTimer.begin(Timer.RENDER_FRAME);

		// Done rendering the scene
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

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

		checkGLErrors();
	}

	@Override
	public boolean zoneInFrustum(int zx, int zz, int maxY, int minY) {
		if (root.sceneContext == null)
			return false;

		Zone zone = root.zones[zx][zz];
		int minX = (zx << 3) - root.sceneContext.sceneOffset << 7;
		int minZ = (zz << 3) - root.sceneContext.sceneOffset << 7;
		int maxX = minX + CHUNK_SIZE * LOCAL_TILE_SIZE;
		int maxZ = minZ + CHUNK_SIZE * LOCAL_TILE_SIZE;
		if (zone.hasWater) {
			maxY += ProceduralGenerator.MAX_DEPTH;
			minY -= ProceduralGenerator.MAX_DEPTH;
		}

		zone.inSceneFrustum = sceneCamera.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
		if (zone.inSceneFrustum)
			return zone.inShadowFrustum = true;

		return zone.inShadowFrustum = directionalCamera.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz) {
		log.trace("drawZoneOpaque({}, {}, zx={}, zz={})", entityProjection, scene, zx, zz);

		WorldViewContext ctx = context(scene);
		if (ctx == null)
			return;

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized || z.sizeO == 0)
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		if (ctx != root || z.inSceneFrustum) {
			sceneCmd.SetWorldViewIndex(uboWorldViews.getIndex(scene));
			z.renderOpaque(sceneCmd, zx - offset, zz - offset, minLevel, level, maxLevel, hideRoofIds);
		}

		if (ctx != root || z.inShadowFrustum) {
			directionalCmd.SetWorldViewIndex(uboWorldViews.getIndex(scene));
			z.renderOpaque(
				directionalCmd,
				zx - offset,
				zz - offset,
				minLevel,
				level,
				plugin.configRoofShadows ? 3 : maxLevel,
				plugin.configRoofShadows ? Collections.emptySet() : hideRoofIds
			);
		}

		checkGLErrors();
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		log.trace("drawZoneAlpha({}, {}, level={}, zx={}, zz={})", entityProjection, scene, level, zx, zz);

		WorldViewContext ctx = context(scene);
		if (ctx == null)
			return;

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
			return;

		boolean hasAlpha = z.sizeA != 0 || !z.alphaModels.isEmpty();
		boolean renderWater = z.inSceneFrustum && level == 0 && z.hasWater;

		if (renderWater || hasAlpha)
			sceneCmd.SetWorldViewIndex(uboWorldViews.getIndex(scene));

		int offset = ctx.sceneContext.sceneOffset >> 3;
		if (renderWater)
			z.renderOpaqueLevel(sceneCmd, zx - offset, zz - offset, Zone.LEVEL_WATER_SURFACE);

		if (!hasAlpha)
			return;

		if (level == 0) {
			z.alphaSort(zx - offset, zz - offset, sceneCamera);
			z.multizoneLocs(ctx.sceneContext, zx - offset, zz - offset, sceneCamera, ctx.zones);
		}

		if (ctx != root || z.inSceneFrustum) {
			z.renderAlpha(
				sceneCmd,
				zx - offset,
				zz - offset,
				minLevel,
				this.level,
				maxLevel,
				level,
				sceneCamera,
				hideRoofIds
			);
		}

		if (ctx != root || z.inShadowFrustum) {
			directionalCmd.SetWorldViewIndex(uboWorldViews.getIndex(scene));
			z.renderAlpha(
				directionalCmd,
				zx - offset,
				zz - offset,
				minLevel,
				this.level,
				plugin.configRoofShadows ? 3 : maxLevel,
				level,
				sceneCamera,
				plugin.configRoofShadows ? Collections.emptySet() : hideRoofIds
			);
		}

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass) {
		log.trace("drawPass({}, {}, pass={})", projection, scene, pass);
		WorldViewContext ctx = context(scene);
		if (ctx == null)
			return;

		switch (pass) {
			case DrawCallbacks.PASS_OPAQUE:
				vaoO.addRange(scene);
				vaoPO.addRange(scene);
				vaoPOShadow.addRange(scene);

				if (scene.getWorldViewId() == -1) {
					sceneCmd.SetBaseOffset(0, 0, 0);
					directionalCmd.SetBaseOffset(0, 0, 0);

					// Draw opaque
					vaoO.unmap();
					vaoO.drawAll(this, sceneCmd);
					vaoO.drawAll(this, directionalCmd);
					vaoO.resetAll();

					vaoPO.unmap();

					// Draw player shadows
					vaoPOShadow.unmap();
					vaoPOShadow.drawAll(this, directionalCmd);
					vaoPOShadow.resetAll();

					// Draw players opaque, without depth writes
					sceneCmd.DepthMask(false);
					vaoPO.drawAll(this, sceneCmd);
					sceneCmd.DepthMask(true);

					// Draw players opaque, writing only depth
					sceneCmd.ColorMask(false, false, false, false);
					vaoPO.drawAll(this, sceneCmd);
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
		Projection worldProjection,
		Scene scene,
		TileObject tileObject,
		Renderable r,
		Model m,
		int orient,
		int x,
		int y,
		int z
	) {
		log.trace(
			"drawDynamic({}, {}, tileObject={}, renderable={}, model={}, orientation={}, modelPos=[{}, {}, {}])",
			worldProjection, scene, tileObject, r, m, orient, x, y, z
		);
		WorldViewContext ctx = context(scene);
		if (ctx == null)
			return;

		int uuid = ModelHash.generateUuid(client, tileObject.getHash(), r);
		int[] worldPos = ctx.sceneContext.localToWorld(tileObject.getLocalLocation(), tileObject.getPlane());
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		int preOrientation = HDUtils.getModelPreOrientation(HDUtils.getObjectConfig(tileObject));

		byte[] transparencies = m.getFaceTransparencies();
		short[] faceTextures = m.getFaceTextures();
		boolean hasAlpha = false;
		if (transparencies != null || faceTextures != null) {
			for (int face = 0; face < m.getFaceCount(); ++face) {
				boolean alpha =
					transparencies != null && transparencies[face] != 0 ||
					faceTextures != null && Material.hasVanillaTransparency(faceTextures[face]);
				if (alpha) {
					hasAlpha = true;
					break;
				}
			}
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (!hasAlpha) {
			VAO o = vaoO.get(size);
			sceneUploader.uploadTempModel(m, modelOverride, preOrientation, orient, x, y, z, o.vbo.vb);
		} else {
			m.calculateBoundsCylinder();
			VAO o = vaoO.get(size), a = vaoA.get(size);
			int start = a.vbo.vb.position();
			facePrioritySorter.uploadSortedModel(worldProjection, m, modelOverride, preOrientation, orient, x, y, z, o.vbo.vb, a.vbo.vb);
			int end = a.vbo.vb.position();

			if (end > start) {
				int offset = ctx.sceneContext.sceneOffset >> 3;
				int zx = (x >> 10) + offset;
				int zz = (z >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
				zone.addTempAlphaModel(a.vao, start, end, tileObject.getPlane(), x & 1023, y, z & 1023);
			}
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m) {
		log.trace("drawTemp({}, {}, gameObject={}, model={})", worldProjection, scene, gameObject, m);
		WorldViewContext ctx = context(scene);
		if (ctx == null)
			return;

		Renderable renderable = gameObject.getRenderable();
		int uuid = ModelHash.generateUuid(client, gameObject.getHash(), renderable);
		int[] worldPos = root.sceneContext.localToWorld(gameObject.getLocalLocation(), gameObject.getPlane());
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		int preOrientation = HDUtils.getModelPreOrientation(gameObject.getConfig());

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (renderable instanceof Player || m.getFaceTransparencies() != null) {
			int offset = ctx.sceneContext.sceneOffset >> 3;
			int zx = (gameObject.getX() >> 10) + offset;
			int zz = (gameObject.getY() >> 10) + offset;
			Zone zone = ctx.zones[zx][zz];

			if (ctx != root || zone.inSceneFrustum) {
				// opaque player faces have their own vao and are drawn in a separate pass from normal opaque faces
				// because they are not depth tested. transparent player faces don't need their own vao because normal
				// transparent faces are already not depth tested
				VAO o = renderable instanceof Player ? vaoPO.get(size) : vaoO.get(size);
				VAO a = vaoA.get(size);

				int start = a.vbo.vb.position();
				m.calculateBoundsCylinder();
				try {
					facePrioritySorter.uploadSortedModel(
						worldProjection,
						m,
						modelOverride,
						preOrientation,
						gameObject.getModelOrientation(),
						gameObject.getX(),
						gameObject.getZ(),
						gameObject.getY(),
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
						gameObject.getX() & 1023,
						gameObject.getZ() - renderable.getModelHeight() /* to render players over locs */,
						gameObject.getY() & 1023
					);
				}
			}

			if (zone.inShadowFrustum) {
				// Since priority sorting of models includes back-face culling,
				// we need to upload the entire model again for shadows
				VAO o = vaoPOShadow.get(size);
				sceneUploader.uploadTempModel(
					m,
					modelOverride,
					preOrientation,
					gameObject.getModelOrientation(),
					gameObject.getX(),
					gameObject.getZ(),
					gameObject.getY(),
					o.vbo.vb
				);
			}
		} else {
			VAO o = vaoO.get(size);
			sceneUploader.uploadTempModel(
				m,
				modelOverride,
				preOrientation,
				gameObject.getModelOrientation(),
				gameObject.getX(),
				gameObject.getZ(),
				gameObject.getY(),
				o.vbo.vb
			);
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz) {
		log.trace("invalidateZone({}, zoneX={}, zoneZ={})", scene, zx, zz);
		WorldViewContext ctx = context(scene);
		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate) {
			z.invalidate = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event) {
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
			return;

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
			rebuild(we.getWorldView());
	}

	private void rebuild(WorldView wv) {
		WorldViewContext ctx = context(wv);
		if (ctx == null)
			return;

		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];
				if (!zone.invalidate)
					continue;

				assert zone.initialized;
				zone.free();
				zone = ctx.zones[x][z] = new Zone();

				SceneUploader sceneUploader = injector.getInstance(SceneUploader.class);
				sceneUploader.zoneSize(ctx.sceneContext, zone, x, z);

				VBO o = null, a = null;
				int sz = zone.sizeO * Zone.VERT_SIZE * 3;
				if (sz > 0) {
					o = new VBO(sz);
					o.initialize(GL_STATIC_DRAW);
					o.map();
				}

				sz = zone.sizeA * Zone.VERT_SIZE * 3;
				if (sz > 0) {
					a = new VBO(sz);
					a.initialize(GL_STATIC_DRAW);
					a.map();
				}

				zone.initialize(o, a, eboAlpha);

				sceneUploader.uploadZone(ctx.sceneContext, zone, x, z);

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;

				log.trace("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
			}
		}
	}

	@Override
	public void draw(int overlayColor) {
		log.trace("draw(overlaySrgba={})", overlayColor);
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

		frameTimer.end(Timer.DRAW_FRAME);
		frameTimer.end(Timer.RENDER_FRAME);
		frameTimer.endFrameAndReset();
//		frameModelInfoMap.clear();
		checkGLErrors();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		GameState state = gameStateChanged.getGameState();
		if (state.getState() < GameState.LOADING.getState()) {
			// this is to avoid scene fbo blit when going from <loading to >=loading,
			// but keep it when doing >loading to loading
			sceneFboValid = false;
		}
//		if (state == GameState.STARTING) {
//			if (textureArrayId != -1) {
//				textureManager.freeTextureArray(textureArrayId);
//			}
//			textureArrayId = -1;
//			lastAnisotropicFilteringLevel = -1;
//		}
	}

	@Override
	public void reloadScene() {
		if (client.getGameState().getState() < GameState.LOGGED_IN.getState() || root.sceneContext == null)
			return;

		proceduralGenerator.generateSceneData(root.sceneContext);
		for (int i = 0; i < NUM_ZONES; i++)
			for (int j = 0; j < NUM_ZONES; j++)
				root.zones[i][j].invalidate = true;
	}

	@Override
	public boolean isLoadingScene() {
		return nextSceneContext != null;
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene) {
		log.trace("loadScene({}, {})", worldView, scene);
		if (scene.getWorldViewId() > -1) {
			loadSubScene(worldView, scene);
			return;
		}

		assert scene.getWorldViewId() == -1;
		if (nextZones != null) {
			// does this happen? this needs to free nextZones?
			throw new RuntimeException("Double zone load!");
		}

		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;

		try {
			nextSceneContext = new ZoneSceneContext(
				client,
				worldView,
				scene,
				plugin.getExpandedMapLoadingChunks(),
				root.sceneContext
			);
			// If area hiding was determined to be incorrect previously, keep it disabled
			nextSceneContext.forceDisableAreaHiding = root.sceneContext != null && root.sceneContext.forceDisableAreaHiding;

			environmentManager.loadSceneEnvironments(nextSceneContext);
			proceduralGenerator.generateSceneData(nextSceneContext);
		} catch (OutOfMemoryError oom) {
			log.error(
				"Ran out of memory while loading scene (32-bit: {}, low memory mode: {}, cache size: {})",
				HDUtils.is32Bit(), plugin.useLowMemoryMode, config.modelCacheSizeMiB(), oom
			);
			plugin.displayOutOfMemoryMessage();
			plugin.stopPlugin();
		} catch (Throwable ex) {
			log.error("Error while loading scene:", ex);
			plugin.stopPlugin();
		}

		WorldViewContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		// TODO: We can't prepare this early
//		regionManager.prepare(scene);

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		final int SCENE_ZONES = NUM_ZONES;

		// initially mark every zone as needing culled
		for (int x = 0; x < SCENE_ZONES; ++x) {
			for (int z = 0; z < SCENE_ZONES; ++z) {
				ctx.zones[x][z].cull = true;
			}
		}

		// find zones which overlap and copy them
		Zone[][] newZones = new Zone[SCENE_ZONES][SCENE_ZONES];
		if (prev.isInstance() == scene.isInstance()
			&& prev.getRoofRemovalMode() == scene.getRoofRemovalMode()) {
			int[][][] prevTemplates = prev.getInstanceTemplateChunks();
			int[][][] curTemplates = scene.getInstanceTemplateChunks();

			for (int x = 0; x < SCENE_ZONES; ++x) {
				next:
				for (int z = 0; z < SCENE_ZONES; ++z) {
					int ox = x + dx;
					int oz = z + dy;

					// Reused the old zone if it is also in the new scene, except for the edges, to work around
					// tile blending, (edge) shadows, sharelight, etc.
					if (canReuse(ctx.zones, ox, oz)) {
						if (scene.isInstance()) {
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - nextSceneContext.sceneOffset / 8;
							int jz = z - nextSceneContext.sceneOffset / 8;
							int jox = ox - nextSceneContext.sceneOffset / 8;
							int joz = oz - nextSceneContext.sceneOffset / 8;
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < SCENE_SIZE / 8 && jz >= 0 && jz < SCENE_SIZE / 8) {
								if (jox >= 0 && jox < SCENE_SIZE / 8 && joz >= 0 && joz < SCENE_SIZE / 8) {
									for (int level = 0; level < 4; ++level) {
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate) {
											// Does this ever happen?
											log.warn("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;

						if (old.dirty) {
							continue;
						}
						assert old.sizeO > 0 || old.sizeA > 0;

						assert old.cull;
						old.cull = false;

						newZones[x][z] = old;
					}
				}
			}
		}

		// Fill out any zones that weren't copied
		for (int x = 0; x < SCENE_ZONES; ++x) {
			for (int z = 0; z < SCENE_ZONES; ++z) {
				if (newZones[x][z] == null) {
					newZones[x][z] = new Zone();
				}
			}
		}

		// size the zones which require upload
		SceneUploader sceneUploader = injector.getInstance(SceneUploader.class);
		Stopwatch sw = Stopwatch.createStarted();
		int len = 0, lena = 0;
		int reused = 0, newzones = 0;
		for (int x = 0; x < NUM_ZONES; ++x) {
			for (int z = 0; z < NUM_ZONES; ++z) {
				Zone zone = newZones[x][z];
				if (!zone.initialized) {
					assert zone.glVao == 0;
					assert zone.glVaoA == 0;
					sceneUploader.zoneSize(nextSceneContext, zone, x, z);
					len += zone.sizeO;
					lena += zone.sizeA;
					newzones++;
				} else {
					reused++;
				}
			}
		}
		log.debug(
			"Scene size time {} reused {} new {} len opaque {} size opaque {}kb len alpha {} size alpha {}kb",
			sw, reused, newzones,
			len, ((long) len * Zone.VERT_SIZE * 3) / 1024,
			lena, ((long) lena * Zone.VERT_SIZE * 3) / 1024
		);

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < EXTENDED_SCENE_SIZE >> 3; ++x) {
				for (int z = 0; z < EXTENDED_SCENE_SIZE >> 3; ++z) {
					Zone zone = newZones[x][z];

					if (zone.initialized) {
						continue;
					}

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						o = new VBO(sz);
						o.initialize(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						a = new VBO(sz);
						a.initialize(GL_STATIC_DRAW);
						a.map();
					}

					zone.initialize(o, a, eboAlpha);
				}
			}

			latch.countDown();
		});
		try {
			latch.await();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		// upload zones
		sw = Stopwatch.createStarted();
		for (int x = 0; x < EXTENDED_SCENE_SIZE >> 3; ++x) {
			for (int z = 0; z < EXTENDED_SCENE_SIZE >> 3; ++z) {
				Zone zone = newZones[x][z];

				if (!zone.initialized) {
					sceneUploader.uploadZone(nextSceneContext, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);
		// TODO: Can't clear since zone invalidation may need it
//		proceduralGenerator.clearSceneData(nextSceneContext);

		// Roof ids aren't consistent between scenes, so build a mapping of old -> new roof ids
		Map<Integer, Integer> roofChanges;
		{
			int[][][] prids = prev.getRoofs();
			int[][][] nrids = scene.getRoofs();
			dx <<= 3;
			dy <<= 3;
			roofChanges = new HashMap<>();

			sw = Stopwatch.createStarted();
			for (int level = 0; level < 4; ++level) {
				for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
					for (int z = 0; z < EXTENDED_SCENE_SIZE; ++z) {
						int ox = x + dx;
						int oz = z + dy;

						// old zone still in scene?
						if (ox >= 0 && oz >= 0 && ox < EXTENDED_SCENE_SIZE && oz < EXTENDED_SCENE_SIZE) {
							int prid = prids[level][ox][oz];
							int nrid = nrids[level][x][z];
							if (prid > 0 && nrid > 0 && prid != nrid) {
								Integer old = roofChanges.putIfAbsent(prid, nrid);
								if (old == null) {
									log.trace("Roof change: {} -> {}", prid, nrid);
								} else if (old != nrid) {
									log.debug("Roof change mismatch: {} -> {} vs {}", prid, nrid, old);
								}
							}
						}
					}
				}
			}
			sw.stop();

			log.debug("Roof remapping time {}", sw);
		}

		nextZones = newZones;
		nextRoofChanges = roofChanges;
	}

	private static boolean canReuse(Zone[][] zones, int zx, int zz) {
		// For tile blending, sharelight, and shadows to work correctly, the zones surrounding
		// the zone must be valid.
		for (int x = zx - 1; x <= zx + 1; ++x) {
			if (x < 0 || x >= NUM_ZONES)
				return false;
			for (int z = zz - 1; z <= zz + 1; ++z) {
				if (z < 0 || z >= NUM_ZONES)
					return false;
				Zone zone = zones[x][z];
				if (!zone.initialized)
					return false;
				if (zone.sizeO == 0 && zone.sizeA == 0)
					return false;
				if (zone.hasWater)
					return false; // TODO: Regenerate underwater geometry instead of discarding entire zones
			}
		}
		return true;
	}

	private void loadSubScene(WorldView worldView, Scene scene) {
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		WorldViewContext prevCtx = subs[worldViewId];
		if (prevCtx != null) {
			log.error("Reload of an already loaded sub scene?");
			prevCtx.free();
		}
		assert prevCtx == null;

		var sceneContext = new ZoneSceneContext(client, worldView, scene, plugin.getExpandedMapLoadingChunks(), null);
		proceduralGenerator.generateSceneData(sceneContext);

		final WorldViewContext ctx = new WorldViewContext(sceneContext, worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		subs[worldViewId] = ctx;

		SceneUploader sceneUploader = injector.getInstance(SceneUploader.class);
		for (int x = 0; x < ctx.sizeX; ++x)
			for (int z = 0; z < ctx.sizeZ; ++z)
				sceneUploader.zoneSize(sceneContext, ctx.zones[x][z], x, z);

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < ctx.sizeX; ++x) {
				for (int z = 0; z < ctx.sizeZ; ++z) {
					Zone zone = ctx.zones[x][z];

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						o = new VBO(sz);
						o.initialize(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						a = new VBO(sz);
						a.initialize(GL_STATIC_DRAW);
						a.map();
					}

					zone.initialize(o, a, eboAlpha);
				}
			}

			latch.countDown();
		});
		try {
			latch.await();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		for (int x = 0; x < ctx.sizeX; ++x)
			for (int z = 0; z < ctx.sizeZ; ++z)
				sceneUploader.uploadZone(sceneContext, ctx.zones[x][z], x, z);

		// TODO: Can't clear since zone invalidation may need it
//		proceduralGenerator.clearSceneData(subSceneContext);
	}

	@Override
	public void despawnWorldView(WorldView worldView) {
		log.trace("despawnWorldView({})", worldView);
		int worldViewId = worldView.getId();
		if (worldViewId > -1) {
			log.debug("WorldView despawn: {}", worldViewId);
			subs[worldViewId].free();
			subs[worldViewId] = null;
		}
	}

	@Override
	public void swapScene(Scene scene) {
		log.trace("swapScene({})", scene);
		if (scene.getWorldViewId() > -1) {
			swapSub(scene);
			return;
		}

		if (!plugin.isActive() || plugin.skipScene == scene) {
			plugin.redrawPreviousFrame = true;
			return;
		}

		// If the scene wasn't loaded by a call to loadScene, load it synchronously instead
		// TODO: Low memory mode
		if (nextSceneContext == null) {
//			loadSceneInternal(scene);
//			if (nextSceneContext == null)
				return; // Return early if scene loading failed
		}

		lightManager.loadSceneLights(nextSceneContext, root.sceneContext);
		fishingSpotReplacer.despawnRuneLiteObjects();
		npcDisplacementCache.clear();

		boolean isFirst = root.sceneContext == null;
		if (!isFirst)
			root.sceneContext.destroy(); // Destroy the old context before replacing it
		root.sceneContext = nextSceneContext;
		nextSceneContext = null;

//		sceneUploader.prepareBeforeSwap(sceneContext);

		if (root.sceneContext.intersects(areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
			plugin.isInHouse = true;
			plugin.isInChambersOfXeric = false;
		} else {
			plugin.isInHouse = false;
			plugin.isInChambersOfXeric = root.sceneContext.intersects(areaManager.getArea("CHAMBERS_OF_XERIC"));
		}

		WorldViewContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];

				if (zone.cull) {
					zone.free();
				} else {
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}
			}
		}
		nextRoofChanges = null;

		ctx.zones = nextZones;
		nextZones = null;

		// setup vaos
		for (int x = 0; x < ctx.zones.length; ++x) {
			for (int z = 0; z < ctx.zones[0].length; ++z) {
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized) {
					zone.unmap();
					zone.initialized = true;
				}
			}
		}

		if (isFirst) {
			// Load all pre-existing sub scenes on the first scene load
			for (WorldEntity subEntity : client.getTopLevelWorldView().worldEntities()) {
				WorldView sub = subEntity.getWorldView();
				log.debug("WorldView loading: {}", sub.getId());
				loadSubScene(sub, sub.getScene());
				swapSub(sub.getScene());
			}
		}

		checkGLErrors();
	}

	private void swapSub(Scene scene) {
		WorldViewContext ctx = context(scene);
		// setup vaos
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized) {
					zone.unmap();
					zone.initialized = true;
				}
			}
		}
		log.debug("WorldView ready: {}", scene.getWorldViewId());
	}
}
