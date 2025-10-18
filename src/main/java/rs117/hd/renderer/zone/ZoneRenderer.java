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
import java.util.HashMap;
import java.util.List;
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
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.opengl.uniforms.UBOWorldViews;
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
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.NpcDisplacementCache;

import static net.runelite.api.Constants.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.COLOR_FILTER_FADE_DURATION;
import static rs117.hd.HdPlugin.NEAR_PLANE;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class ZoneRenderer implements Renderer {
	private static final int NUM_ZONES = EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

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

	private int cameraX, cameraY, cameraZ;
	private int cameraYaw, cameraPitch;
	private int cameraZoom;
	private int cameraYawSin, cameraYawCos;
	private int cameraPitchSin, cameraPitchCos;
	private int minLevel, level, maxLevel;
	private Set<Integer> hideRoofIds;

	private VAO.VAOList vaoO;
	private VAO.VAOList vaoA;
	private VAO.VAOList vaoPO;

	private List<VAO> vaoO_DrawList;
	private List<VAO> vaoPO_DrawList;

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

	private final UBOWorldViews uboWorldViews = new UBOWorldViews(MAX_WORLDVIEWS);

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
		initializeVaos();
		uboWorldViews.initialize(UNIFORM_BLOCK_WORLD_VIEWS);
	}

	@Override
	public void destroy() {
		root.free();

		for (int i = 0; i < subs.length; i++) {
			if (subs[i] != null)
				subs[i].free();
			subs[i] = null;
		}

		destroyVaos();
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
			.addUniformBuffer(uboWorldViews);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		sceneProgram.destroy();
	}

	private void initializeVaos() {
		vaoO = new VAO.VAOList();
		vaoA = new VAO.VAOList();
		vaoPO = new VAO.VAOList();
	}

	private void destroyVaos() {
		vaoO.free();
		vaoA.free();
		vaoPO.free();
		vaoO = vaoA = vaoPO = null;
	}

	private Projection lastProjection;

	void updateEntityProjection(Scene scene, Projection projection) {
		plugin.uboGlobal.worldViewId.set(uboWorldViews.getIndex(scene));
		plugin.uboGlobal.upload();
		// TODO: Remove this
//		if (lastProjection == projection)
//			return;
//
//		plugin.uboGlobal.entityProjectionMatrix.set(projection instanceof FloatProjection ?
//			((FloatProjection) projection).getProjection() : Mat4.identity());
//		plugin.uboGlobal.upload();
//		lastProjection = projection;
	}

	void updateEntityTint(@Nullable Scene scene) {
		plugin.uboGlobal.worldViewId.set(uboWorldViews.getIndex(scene));
		plugin.uboGlobal.upload();
		// TODO: Remove this
//		if (scene == null) {
//			plugin.uboGlobal.entityTint.set(0, 0, 0, 0);
//		} else {
//			plugin.uboGlobal.entityTint.set(
//				scene.getOverrideHue(),
//				scene.getOverrideSaturation(),
//				scene.getOverrideLuminance(),
//				scene.getOverrideAmount()
//			);
//		}
//		plugin.uboGlobal.upload();
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
		this.cameraX = (int) cameraX;
		this.cameraY = (int) cameraY;
		this.cameraZ = (int) cameraZ;
		this.cameraYaw = client.getCameraYaw();
		this.cameraPitch = client.getCameraPitch();
		this.cameraYawSin = Perspective.SINE[this.cameraYaw];
		this.cameraYawCos = Perspective.COSINE[this.cameraYaw];
		this.cameraPitchSin = Perspective.SINE[this.cameraPitch];
		this.cameraPitchCos = Perspective.COSINE[this.cameraPitch];
		this.cameraZoom = client.get3dZoom();
		this.minLevel = minLevel;
		this.level = level;
		this.maxLevel = maxLevel;
		this.hideRoofIds = hideRoofIds;

		if (scene.getWorldViewId() == WorldView.TOPLEVEL) {
			preSceneDrawTopLevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		} else {
			Scene topLevel = client.getScene();
			vaoO.addRange(null, topLevel);
			vaoPO.addRange(null, topLevel);
			updateEntityTint(scene);
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

				// Calculate the viewport dimensions before scaling in order to include the extra padding
				int viewportWidth = (int) (plugin.sceneViewport[2] / plugin.sceneViewportScale[0]);
				int viewportHeight = (int) (plugin.sceneViewport[3] / plugin.sceneViewportScale[1]);

				// Calculate projection matrix
				float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
				if (plugin.orthographicProjection) {
					Mat4.mul(projectionMatrix, Mat4.scale(HdPlugin.ORTHOGRAPHIC_ZOOM, HdPlugin.ORTHOGRAPHIC_ZOOM, -1));
					Mat4.mul(projectionMatrix, Mat4.orthographic(viewportWidth, viewportHeight, 40000));
				} else {
					Mat4.mul(projectionMatrix, Mat4.perspective(viewportWidth, viewportHeight, HdPlugin.NEAR_PLANE));
				}


				// Calculate view matrix
				plugin.viewMatrix = Mat4.rotateX(plugin.cameraOrientation[1]);
				Mat4.mul(plugin.viewMatrix, Mat4.rotateY(plugin.cameraOrientation[0]));
				Mat4.mul(
					plugin.viewMatrix,
					Mat4.translate(-plugin.cameraPosition[0], -plugin.cameraPosition[1], -plugin.cameraPosition[2])
				);

				// Calculate view proj & inv matrix
				plugin.viewProjMatrix = Mat4.identity();
				Mat4.mul(plugin.viewProjMatrix, projectionMatrix);
				Mat4.mul(plugin.viewProjMatrix, plugin.viewMatrix);
				Mat4.extractPlanes(plugin.viewProjMatrix, plugin.cameraFrustum);
				plugin.invViewProjMatrix = Mat4.inverse(plugin.viewProjMatrix);

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

		plugin.updateSceneFbo();

		// Draw 3d scene
//		if (plugin.hasLoggedIn && sceneContext != null && plugin.sceneViewport != null) {
//		} else {
//			// TODO
//		}

		float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
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

		float[] lightViewMatrix = Mat4.rotateX(environmentManager.currentSunAngles[0]);
		Mat4.mul(lightViewMatrix, Mat4.rotateY(PI - environmentManager.currentSunAngles[1]));
		// Extract the 3rd column from the light view matrix (the float array is column-major).
		// This produces the light's direction vector in world space, which we negate in order to
		// get the light's direction vector pointing away from each fragment
		plugin.uboGlobal.lightDir.set(-lightViewMatrix[2], -lightViewMatrix[6], -lightViewMatrix[10]);

		final int camX = plugin.cameraFocalPoint[0];
		final int camY = plugin.cameraFocalPoint[1];

		final int drawDistanceSceneUnits =
			min(config.shadowDistance().getValue(), plugin.getDrawDistance())
			* Perspective.LOCAL_TILE_SIZE / 2;
		final int east = min(camX + drawDistanceSceneUnits, Perspective.LOCAL_TILE_SIZE * Constants.SCENE_SIZE);
		final int west = max(camX - drawDistanceSceneUnits, 0);
		final int north = min(camY + drawDistanceSceneUnits, Perspective.LOCAL_TILE_SIZE * Constants.SCENE_SIZE);
		final int south = max(camY - drawDistanceSceneUnits, 0);
		final int width = east - west;
		final int height = north - south;
		final int depthScale = 10000;

		final int maxDrawDistance = 90;
		final float maxScale = 0.7f;
		final float minScale = 0.4f;
		final float scaleMultiplier = 1.0f - (plugin.getDrawDistance() / (maxDrawDistance * maxScale));
		float scale = mix(maxScale, minScale, scaleMultiplier);
		float[] lightProjectionMatrix = Mat4.identity();
		Mat4.mul(lightProjectionMatrix, Mat4.scale(scale, scale, scale));
		Mat4.mul(lightProjectionMatrix, Mat4.orthographic(width, height, depthScale));
		Mat4.mul(lightProjectionMatrix, lightViewMatrix);
		Mat4.mul(lightProjectionMatrix, Mat4.translate(-(width / 2f + west), 0, -(height / 2f + south)));
		plugin.uboGlobal.lightProjectionMatrix.set(lightProjectionMatrix);

		if (plugin.configColorFilter != ColorFilter.NONE) {
			plugin.uboGlobal.colorFilter.set(plugin.configColorFilter.ordinal());
			plugin.uboGlobal.colorFilterPrevious.set(plugin.configColorFilterPrevious.ordinal());
			long timeSinceChange = System.currentTimeMillis() - plugin.colorFilterChangedAt;
			plugin.uboGlobal.colorFilterFade.set(clamp(timeSinceChange / COLOR_FILTER_FADE_DURATION, 0, 1));
		}

		updateEntityTint(null);

		plugin.uboGlobal.upload();
		uboWorldViews.update(client);
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

		// We just allow the GL to do face culling. Note this requires the priority renderer
		// to have logic to disregard culled faces in the priority depth testing.
		glEnable(GL_CULL_FACE);
		glCullFace(GL_BACK);

		// Enable blending for alpha
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);

		// Draw with buffers bound to scene VAO
//		glBindVertexArray(vaoScene);

		// TODO
//		// When there are custom tiles, we need depth testing to draw them in the correct order, but the rest of the
//		// scene doesn't support depth testing, so we only write depths for custom tiles.
//		if (sceneContext.staticCustomTilesVertexCount > 0) {
//			// Draw gap filler tiles first, without depth testing
//			if (sceneContext.staticGapFillerTilesVertexCount > 0) {
//				glDisable(GL_DEPTH_TEST);
//				glDrawArrays(
//					GL_TRIANGLES,
//					sceneContext.staticGapFillerTilesOffset,
//					sceneContext.staticGapFillerTilesVertexCount
//				);
//			}
//
//			glEnable(GL_DEPTH_TEST);
//			glDepthFunc(GL_GREATER);
//
//			// Draw custom tiles, writing depth
//			glDepthMask(true);
//			glDrawArrays(
//				GL_TRIANGLES,
//				sceneContext.staticCustomTilesOffset,
//				sceneContext.staticCustomTilesVertexCount
//			);
//
//			// Draw the rest of the scene with depth testing, but not against itself
//			glDepthMask(false);
//			glDrawArrays(
//				GL_TRIANGLES,
//				sceneContext.staticVertexCount,
//				renderBufferOffset - sceneContext.staticVertexCount
//			);
//		}

		checkGLErrors();
	}

	private void renderShadows(WorldViewContext viewCtx) {
		for(int zx = 0; zx < viewCtx.sizeX; zx++) {
			for(int zz = 0; zz < viewCtx.sizeX; zz++) {
				Zone z = viewCtx.zones[zx][zz];
				if(root == viewCtx && !z.inShadowFrustum) {
					continue;
				}

				if (!z.initialized || z.sizeO == 0) {
					continue;
				}

				int offset = viewCtx.sceneContext.sceneOffset >> 3;
				z.renderOpaque(plugin.uboGlobal, zx - offset, zz - offset, minLevel, level, maxLevel, hideRoofIds);
				z.renderAlpha(
					plugin.uboGlobal,
					zx - offset,
					zz - offset,
					this.cameraYaw,
					this.cameraPitch,
					minLevel,
					this.level,
					maxLevel,
					level,
					hideRoofIds
				);
			}
		}
	}

	@Override
	public void postSceneDraw(Scene scene) {
		log.trace("postSceneDraw({})", scene);
		if (scene.getWorldViewId() == WorldView.TOPLEVEL) {
			postDrawTopLevel();
		} else {
			updateEntityTint(null);
		}
	}

	private void postDrawTopLevel() {
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

		sceneFboValid = true;

		if (root.sceneContext == null)
			return;

		frameTimer.end(Timer.DRAW_SCENE);
		frameTimer.end(Timer.RENDER_SCENE);
		frameTimer.begin(Timer.RENDER_FRAME);

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
		int x = (((zx << 3) - root.sceneContext.sceneOffset) << 7) + 512 - cameraX;
		int z = (((zz << 3) - root.sceneContext.sceneOffset) << 7) + 512 - cameraZ;
		int y = maxY - cameraY;
		int zoneRadius = 724; // ~ 512 * sqrt(2)
		int waterDepth = zone.hasWater ? ProceduralGenerator.MAX_DEPTH : 0;

		final int leftClip = client.getRasterizer3D_clipNegativeMidX();
		final int rightClip = client.getRasterizer3D_clipMidX2();
		final int topClip = client.getRasterizer3D_clipNegativeMidY();
		final int bottomClip = client.getRasterizer3D_clipMidY2();

		// Check if the tile is within the near plane of the frustum
		zone.inSceneFrustum = false;
		int transformedZ = z * cameraYawCos - x * cameraYawSin >> 16;
		int depth = (y + waterDepth) * cameraPitchSin + (transformedZ + zoneRadius) * cameraPitchCos >> 16;
		if (depth > NEAR_PLANE) {
			// Check left bound
			int transformedX = z * cameraYawSin + x * cameraYawCos >> 16;
			int left = transformedX - zoneRadius;
			if (left * cameraZoom < rightClip * depth) {
				// Check right bound
				int right = transformedX + zoneRadius;
				if (right * cameraZoom > leftClip * depth) {
					// Check top bound
					int transformedY = y * cameraPitchCos - transformedZ * cameraPitchSin >> 16;
					int transformedRadius = zoneRadius * cameraPitchSin >> 16;
					int transformedWaterDepth = waterDepth * cameraPitchCos >> 16;
					int bottom = transformedY + transformedRadius + transformedWaterDepth;
					if (bottom * cameraZoom > topClip * depth) {
						// Check bottom bound
						int transformedZoneHeight = minY * cameraPitchCos >> 16;
						int top = transformedY - transformedRadius + transformedZoneHeight;
						zone.inSceneFrustum = top * cameraZoom < bottomClip * depth;
					}
				}
			}
		}

		if (zone.inSceneFrustum) {
			zone.inShadowFrustum = true;
			return true;
		}

		// TODO: Shadow frustum checks
		float[] angles = environmentManager.currentSunAngles;
		zone.inShadowFrustum = true;

		return zone.inShadowFrustum;
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz) {
		log.trace("drawZoneOpaque({}, {}, zx={}, zz={})", entityProjection, scene, zx, zz);
		updateEntityProjection(scene, entityProjection);

		WorldViewContext ctx = context(scene);
		if (ctx == null) {
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized || z.sizeO == 0) {
			return;
		}

		int offset = ctx.sceneContext.sceneOffset >> 3;
		z.renderOpaque(plugin.uboGlobal, zx - offset, zz - offset, minLevel, level, maxLevel, hideRoofIds);

		checkGLErrors();
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		log.trace("drawZoneAlpha({}, {}, level={}, zx={}, zz={})", entityProjection, scene, level, zx, zz);
		updateEntityProjection(scene, entityProjection);

		WorldViewContext ctx = context(scene);
		if (ctx == null) {
			return;
		}

		// this is a noop after the first zone
		vaoA.unmap();

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized) {
			return;
		}

		int offset = ctx.sceneContext.sceneOffset >> 3;
		if (level == 0 && z.hasWater)
			z.renderOpaqueLevel(plugin.uboGlobal, zx - offset, zz - offset, Zone.LEVEL_WATER_SURFACE);

		if (z.sizeA == 0 && z.alphaModels.isEmpty())
			return;

		if (level == 0) {
			z.alphaSort(zx - offset, zz - offset, cameraX, cameraY, cameraZ);
			z.multizoneLocs(ctx.sceneContext, zx - offset, zz - offset, cameraX, cameraZ, ctx.zones);
		}

		glDepthMask(false);
		z.renderAlpha(
			plugin.uboGlobal,
			zx - offset,
			zz - offset,
			cameraYaw,
			cameraPitch,
			minLevel,
			this.level,
			maxLevel,
			level,
			hideRoofIds
		);
		glDepthMask(true);

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass) {
		log.trace("drawPass({}, {}, pass={})", projection, scene, pass);
		WorldViewContext ctx = context(scene);
		if (ctx == null) {
			return;
		}

		updateEntityProjection(scene, projection);

		if (pass == DrawCallbacks.PASS_OPAQUE) {
			vaoO.addRange(projection, scene);
			vaoPO.addRange(projection, scene);

			if (scene.getWorldViewId() == -1) {
				plugin.uboGlobal.sceneBase.set(0, 0, 0);
				plugin.uboGlobal.upload();

//				if (client.getGameCycle() % 100 == 0)
//				{
//					vaoO.debug();
//				}
				vaoO_DrawList = vaoO.unmap();
				for (VAO vao : vaoO_DrawList) {
					vao.draw(this);
				}

				vaoPO_DrawList = vaoPO.unmap();
				glDepthMask(false);
				for (VAO vao : vaoPO_DrawList) {
					vao.draw(this);
				}
				glDepthMask(true);

				glColorMask(false, false, false, false);
				for (VAO vao : vaoPO_DrawList) {
					vao.draw(this);
				}
				glColorMask(true, true, true, true);
			}
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
		if (ctx == null) {
			return;
		}

		int uuid = ModelHash.generateUuid(client, tileObject.getHash(), r);
		int[] worldPos = ctx.sceneContext.localToWorld(tileObject.getLocalLocation(), tileObject.getPlane());
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		int preOrientation = HDUtils.getModelPreOrientation(HDUtils.getObjectConfig(tileObject));

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (m.getFaceTransparencies() == null) {
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
		if (ctx == null) {
			return;
		}

		Renderable renderable = gameObject.getRenderable();
		int uuid = ModelHash.generateUuid(client, gameObject.getHash(), renderable);
		int[] worldPos = root.sceneContext.localToWorld(gameObject.getLocalLocation(), gameObject.getPlane());
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		int preOrientation = HDUtils.getModelPreOrientation(gameObject.getConfig());

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (renderable instanceof Player || m.getFaceTransparencies() != null) {
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
				int offset = ctx.sceneContext.sceneOffset >> 3;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
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
		if (wv == null) {
			return;
		}

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities()) {
			wv = we.getWorldView();
			rebuild(wv);
		}
	}

	private void rebuild(WorldView wv) {
		WorldViewContext ctx = context(wv);
		if (ctx == null) {
			return;
		}

		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];
				if (!zone.invalidate) {
					continue;
				}

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

				zone.initialize(o, a);

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

		if (plugin.configShadowsEnabled && plugin.fboShadowMap != 0
			&& environmentManager.currentDirectionalStrength > 0){
			frameTimer.begin(Timer.RENDER_SHADOWS);

			// Render to the shadow depth map
			glViewport(0, 0, plugin.shadowMapResolution, plugin.shadowMapResolution);
			glBindFramebuffer(GL_FRAMEBUFFER, plugin.fboShadowMap);
			glClearDepth(1);
			glClear(GL_DEPTH_BUFFER_BIT);
			glDepthFunc(GL_LEQUAL);

			plugin.shadowProgram.use();

			glEnable(GL_DEPTH_TEST);

			renderShadows(root);

			for (WorldViewContext sub : subs) {
				if (sub != null) {
					renderShadows(sub);
				}
			}

			plugin.uboGlobal.sceneBase.set(0, 0, 0);
			plugin.uboGlobal.upload();

			if(vaoO_DrawList != null) {
				for (VAO vao : vaoO_DrawList) {
					vao.draw(this);
				}
			}

			if(vaoPO_DrawList != null) {
				for (VAO vao : vaoPO_DrawList) {
					vao.draw(this);
				}
			}

			glDisable(GL_DEPTH_TEST);

			frameTimer.end(Timer.RENDER_SHADOWS);
		}

		if(vaoO_DrawList != null) {
			for (VAO vao : vaoO_DrawList) {
				vao.reset();
			}
			vaoO_DrawList = null;
		}

		if(vaoPO_DrawList != null) {
			for (VAO vao : vaoPO_DrawList) {
				vao.reset();
			}
			vaoPO_DrawList = null;
		}

		for (int x = 0; x < root.sizeX; ++x) {
			for (int z = 0; z < root.sizeZ; ++z) {
				root.zones[x][z].removeTemp();
			}
		}

		for (WorldViewContext sub : subs) {
			if (sub != null) {
				for (int x = 0; x < sub.sizeX; ++x) {
					for (int z = 0; z < sub.sizeZ; ++z) {
						sub.zones[x][z].removeTemp();
					}
				}
			}
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

					zone.initialize(o, a);
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

					zone.initialize(o, a);
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
