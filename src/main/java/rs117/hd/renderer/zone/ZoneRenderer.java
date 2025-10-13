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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.DrawManager;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.model.ModelHasher;
import rs117.hd.model.ModelPusher;
import rs117.hd.opengl.compute.OpenCLManager;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.Renderer;
import rs117.hd.renderer.legacy.LegacySceneContext;
import rs117.hd.renderer.legacy.LegacySceneUploader;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.GroundMaterialManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.NpcDisplacementCache;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

@Slf4j
@Singleton
public class ZoneRenderer implements Renderer {
	private static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private OpenCLManager clManager;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private AreaManager areaManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private TextureManager textureManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private WaterTypeManager waterTypeManager;

	@Inject
	private GroundMaterialManager groundMaterialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private LegacySceneUploader sceneUploader;

	@Inject
	private ModelPusher modelPusher;

	@Inject
	private ModelHasher modelHasher;

	@Inject
	private FacePrioritySorter facePrioritySorter;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	public SceneShaderProgram sceneProgram;

	@Getter
	@Nullable
	private LegacySceneContext sceneContext;
	private LegacySceneContext nextSceneContext;

	private int cameraX, cameraY, cameraZ;
	private int cameraYaw, cameraPitch;
	private int minLevel, level, maxLevel;
	private Set<Integer> hideRoofIds;

	private VAO.VAOList vaoO;
	private VAO.VAOList vaoA;
	private VAO.VAOList vaoPO;

	static class WorldViewContext {
		final int sizeX, sizeZ;
		Zone[][] zones;

		WorldViewContext(int sizeX, int sizeZ) {
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
			for (int x = 0; x < sizeX; ++x) {
				for (int z = 0; z < sizeZ; ++z) {
					zones[x][z].free();
				}
			}
		}
	}

	WorldViewContext context(Scene scene) {
		int wvid = scene.getWorldViewId();
		if (wvid == -1) {
			return root;
		}
		return subs[wvid];
	}

	WorldViewContext context(WorldView wv) {
		int wvid = wv.getId();
		if (wvid == -1) {
			return root;
		}
		return subs[wvid];
	}

	private WorldViewContext root;
	private WorldViewContext[] subs;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	private int uniDrawDistance;
	private int uniExpandedMapLoadingChunks;
	private int uniWorldProj;
	static int uniEntityProj;
	static int uniEntityTint;
	static int uniBase;

	@Override
	public void initialize() {
		root = new WorldViewContext(NUM_ZONES, NUM_ZONES);
		subs = new WorldViewContext[MAX_WORLDVIEWS];

		vaoO = new VAO.VAOList();
		vaoA = new VAO.VAOList();
		vaoPO = new VAO.VAOList();

//		uniWorldProj = glGetUniformLocation(glProgram, "worldProj");
//		uniEntityProj = glGetUniformLocation(glProgram, "entityProj");
//		uniEntityTint = glGetUniformLocation(glProgram, "entityTint");
//		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
//		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
//		uniBase = glGetUniformLocation(glProgram, "base");

		if (client.getGameState() == GameState.LOGGED_IN)
			startupWorldLoad();
	}

	private void startupWorldLoad() {
		WorldView root = client.getTopLevelWorldView();
		Scene scene = root.getScene();
		loadScene(root, scene);
		swapScene(scene);

		for (WorldEntity subEntity : root.worldEntities()) {
			WorldView sub = subEntity.getWorldView();
			log.debug("WorldView loading: {}", sub.getId());
			loadSubScene(sub, sub.getScene());
			swapSub(sub.getScene());
		}
	}

	@Override
	public void destroy() {
		root.free();

		vaoO.free();
		vaoA.free();
		vaoPO.free();
		vaoO = vaoA = vaoPO = null;
	}

	private Projection lastProjection;

	private void updateEntityProject(Projection projection) {
		if (lastProjection != projection) {
			float[] p = projection instanceof FloatProjection ? ((FloatProjection) projection).getProjection() : Mat4.identity();
			glUniformMatrix4fv(uniEntityProj, false, p);
			lastProjection = projection;
		}
	}

	@Override
	public void preSceneDraw(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds
	) {
		this.cameraX = (int) cameraX;
		this.cameraY = (int) cameraY;
		this.cameraZ = (int) cameraZ;
		this.cameraYaw = client.getCameraYaw();
		this.cameraPitch = client.getCameraPitch();
		this.minLevel = minLevel;
		this.level = level;
		this.maxLevel = maxLevel;
		this.hideRoofIds = hideRoofIds;

		if (scene.getWorldViewId() == WorldView.TOPLEVEL) {
			preSceneDrawToplevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		} else {
			Scene toplevel = client.getScene();
			vaoO.addRange(null, toplevel);
			vaoPO.addRange(null, toplevel);
			glUniform4i(
				uniEntityTint,
				scene.getOverrideHue(),
				scene.getOverrideSaturation(),
				scene.getOverrideLuminance(),
				scene.getOverrideAmount()
			);
		}
	}

	private void preSceneDrawToplevel(
		Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw
	) {
		scene.setDrawDistance(plugin.getDrawDistance());

//		// UBO
//		uniformBuffer.clear();
//		uniformBuffer
//			.put(cameraYaw)
//			.put(cameraPitch)
//			.put(cameraX)
//			.put(cameraY)
//			.put(cameraZ);
//		uniformBuffer.flip();
//
//		glBindBuffer(GL_UNIFORM_BUFFER, glUniformBuffer.glBufferId);
//		glBufferData(GL_UNIFORM_BUFFER, uniformBuffer.getBuffer(), GL_DYNAMIC_DRAW);
//		glBindBuffer(GL_UNIFORM_BUFFER, 0);
//		uniformBuffer.clear();
//
//		glBindBufferBase(GL_UNIFORM_BUFFER, 0, glUniformBuffer.glBufferId);
//
//		checkGLErrors();
//
//		final int canvasHeight = client.getCanvasHeight();
//		final int canvasWidth = client.getCanvasWidth();
//
//		final int viewportHeight = client.getViewportHeight();
//		final int viewportWidth = client.getViewportWidth();
//
//		// Setup FBO and anti-aliasing
//
//		// Clear scene
//		int sky = client.getSkyboxColor();
//		glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
//		glClearDepthf(0f);
//		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
//
//		// Setup uniforms
//		final int drawDistance = getDrawDistance();
//		glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);
//		glUniform1i(uniExpandedMapLoadingChunks, client.getExpandedMapLoading());
//
//		// Calculate projection matrix
//		float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
//		Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
//		Mat4.mul(projectionMatrix, Mat4.rotateX(cameraPitch));
//		Mat4.mul(projectionMatrix, Mat4.rotateY(cameraYaw));
//		Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));
//		glUniformMatrix4fv(uniWorldProj, false, projectionMatrix);
//
//		projectionMatrix = Mat4.identity();
//		glUniformMatrix4fv(uniEntityProj, false, projectionMatrix);
//
//		glUniform4i(uniEntityTint, 0, 0, 0, 0);
//
//		// Bind uniforms
//		glUniformBlockBinding(glProgram, uniBlockMain, 0);

		// Enable face culling
		glEnable(GL_CULL_FACE);

		// Enable blending
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);

		// Enable depth testing
		glDepthFunc(GL_GREATER);
		glEnable(GL_DEPTH_TEST);

		checkGLErrors();
	}

	@Override
	public void postSceneDraw(Scene scene) {
		if (scene.getWorldViewId() == WorldView.TOPLEVEL) {
			postDrawToplevel();
		} else {
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
		}
	}

	private void postDrawToplevel() {
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

//		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
//		sceneFboValid = true;
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz) {
		updateEntityProject(entityProjection);

		WorldViewContext ctx = context(scene);
		if (ctx == null) {
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized) {
			return;
		}

		int offset = scene.getWorldViewId() == -1 ? (SCENE_OFFSET >> 3) : 0;
		z.renderOpaque(zx - offset, zz - offset, minLevel, level, maxLevel, hideRoofIds);

		checkGLErrors();
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		updateEntityProject(entityProjection);

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

		int offset = scene.getWorldViewId() == -1 ? (SCENE_OFFSET >> 3) : 0;
		if (level == 0) {
			z.alphaSort(zx - offset, zz - offset, cameraX, cameraY, cameraZ);
			z.multizoneLocs(scene, zx - offset, zz - offset, cameraX, cameraZ, ctx.zones);
		}

		glDepthMask(false);
		z.renderAlpha(zx - offset, zz - offset, cameraYaw, cameraPitch, minLevel, this.level, maxLevel, level, hideRoofIds);
		glDepthMask(true);

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass) {
		WorldViewContext ctx = context(scene);
		if (ctx == null) {
			return;
		}

		updateEntityProject(projection);

		if (pass == DrawCallbacks.PASS_OPAQUE) {
			vaoO.addRange(projection, scene);
			vaoPO.addRange(projection, scene);

			if (scene.getWorldViewId() == -1) {
//				glProgramUniform3i(glProgram, uniBase, 0, 0, 0);

//				if (client.getGameCycle() % 100 == 0)
//				{
//					vaoO.debug();
//				}
				var vaos = vaoO.unmap();
				for (VAO vao : vaos) {
					vao.draw();
					vao.reset();
				}

				vaos = vaoPO.unmap();
				glDepthMask(false);
				for (VAO vao : vaos) {
					vao.draw();
				}
				glDepthMask(true);

				glColorMask(false, false, false, false);
				for (VAO vao : vaos) {
					vao.draw();
					vao.reset();
				}
				glColorMask(true, true, true, true);
			}
		} else if (pass == DrawCallbacks.PASS_ALPHA) {
			for (int x = 0; x < ctx.sizeX; ++x) {
				for (int z = 0; z < ctx.sizeZ; ++z) {
					Zone zone = ctx.zones[x][z];
					zone.removeTemp();
				}
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
		WorldViewContext ctx = context(scene);
		if (ctx == null) {
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (m.getFaceTransparencies() == null) {
			VAO o = vaoO.get(size);
			SceneUploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb);
		} else {
			m.calculateBoundsCylinder();
			VAO o = vaoO.get(size), a = vaoA.get(size);
			int start = a.vbo.vb.position();
			facePrioritySorter.uploadSortedModel(worldProjection, m, orient, x, y, z, o.vbo.vb, a.vbo.vb);
			int end = a.vbo.vb.position();

			if (end > start) {
				int offset = scene.getWorldViewId() == -1 ? (SCENE_OFFSET >> 3) : 0;
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
		WorldViewContext ctx = context(scene);
		if (ctx == null) {
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (gameObject.getRenderable() instanceof Player || m.getFaceTransparencies() != null) {
			// opaque player faces have their own vao and are drawn in a separate pass from normal opaque faces
			// because they are not depth tested. transparent player faces don't need their own vao because normal
			// transparent faces are already not depth tested
			VAO o = gameObject.getRenderable() instanceof Player ? vaoPO.get(size) : vaoO.get(size);
			VAO a = vaoA.get(size);

			int start = a.vbo.vb.position();
			m.calculateBoundsCylinder();
			try {
				facePrioritySorter.uploadSortedModel(
					worldProjection,
					m,
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
				int offset = scene.getWorldViewId() == -1 ? (SCENE_OFFSET >> 3) : 0;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				zone.addTempAlphaModel(
					a.vao,
					start,
					end,
					gameObject.getPlane(),
					gameObject.getX() & 1023,
					gameObject.getZ() - gameObject.getRenderable().getModelHeight() /* to render players over locs */,
					gameObject.getY() & 1023
				);
			}
		} else {
			VAO o = vaoO.get(size);
			SceneUploader.uploadTempModel(
				m,
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

				Scene scene = wv.getScene();
				SceneUploader sceneUploader = new SceneUploader();
				sceneUploader.zoneSize(scene, zone, x, z);

				VBO o = null, a = null;
				int sz = zone.sizeO * Zone.VERT_SIZE * 3;
				if (sz > 0) {
					o = new VBO(sz);
					o.init();
					o.map();
				}

				sz = zone.sizeA * Zone.VERT_SIZE * 3;
				if (sz > 0) {
					a = new VBO(sz);
					a.init();
					a.map();
				}

				zone.init(o, a);

				sceneUploader.uploadZone(scene, zone, x, z);

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;

				log.debug("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
			}
		}
	}

	@Override
	public void draw(int overlayColor) {
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING) {
			return;
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

//		prepareInterfaceTexture(canvasWidth, canvasHeight);
//
//		glClearColor(0, 0, 0, 1);
//		glClear(GL_COLOR_BUFFER_BIT);
//
//		if (sceneFboValid) {
//			blitSceneFbo();
//		}
//
//		// Texture on UI
//		drawUi(overlayColor, canvasHeight, canvasWidth);
//
//		try {
//			awtContext.swapBuffers();
//		} catch (RuntimeException ex) {
//			// this is always fatal
//			if (!canvas.isValid()) {
//				// this might be AWT shutting down on VM shutdown, ignore it
//				return;
//			}
//
//			log.error("error swapping buffers", ex);
//
//			// try to stop the plugin
//			SwingUtilities.invokeLater(() ->
//			{
//				try {
//					pluginManager.stopPlugin(this);
//				} catch (PluginInstantiationException ex2) {
//					log.error("error stopping plugin", ex2);
//				}
//			});
//			return;
//		}
//
//		drawManager.processDrawComplete(this::screenshot);
//
//		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		GameState state = gameStateChanged.getGameState();
//		if (state.getState() < GameState.LOADING.getState()) {
//			// this is to avoid scene fbo blit when going from <loading to >=loading,
//			// but keep it when doing >loading to loading
//			sceneFboValid = false;
//		}
//		if (state == GameState.STARTING) {
//			if (textureArrayId != -1) {
//				textureManager.freeTextureArray(textureArrayId);
//			}
//			textureArrayId = -1;
//			lastAnisotropicFilteringLevel = -1;
//		}
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene) {
		if (scene.getWorldViewId() > -1) {
			loadSubScene(worldView, scene);
			return;
		}

		assert scene.getWorldViewId() == -1;
		if (nextZones != null) {
			// does this happen? this needs to free nextZones?
			throw new RuntimeException("Double zone load!");
		}

		WorldViewContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

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
							int jx = x - (SCENE_OFFSET / 8);
							int jz = z - (SCENE_OFFSET / 8);
							int jox = ox - (SCENE_OFFSET / 8);
							int joz = oz - (SCENE_OFFSET / 8);
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < Constants.SCENE_SIZE / 8 && jz >= 0 && jz < Constants.SCENE_SIZE / 8) {
								if (jox >= 0 && jox < Constants.SCENE_SIZE / 8 && joz >= 0 && joz < Constants.SCENE_SIZE / 8) {
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
		SceneUploader sceneUploader = new SceneUploader();
		Stopwatch sw = Stopwatch.createStarted();
		int len = 0, lena = 0;
		int reused = 0, newzones = 0;
		for (int x = 0; x < NUM_ZONES; ++x) {
			for (int z = 0; z < NUM_ZONES; ++z) {
				Zone zone = newZones[x][z];
				if (!zone.initialized) {
					assert zone.glVao == 0;
					assert zone.glVaoA == 0;
					sceneUploader.zoneSize(scene, zone, x, z);
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
			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x) {
				for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z) {
					Zone zone = newZones[x][z];

					if (zone.initialized) {
						continue;
					}

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						o = new VBO(sz);
						o.init();
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						a = new VBO(sz);
						a.init();
						a.map();
					}

					zone.init(o, a);
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
		for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x) {
			for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z) {
				Zone zone = newZones[x][z];

				if (!zone.initialized) {
					sceneUploader.uploadZone(scene, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);

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
				for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE; ++x) {
					for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE; ++z) {
						int ox = x + dx;
						int oz = z + dy;

						// old zone still in scene?
						if (ox >= 0 && oz >= 0 && ox < Constants.EXTENDED_SCENE_SIZE && oz < Constants.EXTENDED_SCENE_SIZE) {
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
			if (x < 0 || x >= NUM_ZONES) {
				return false;
			}
			for (int z = zz - 1; z <= zz + 1; ++z) {
				if (z < 0 || z >= NUM_ZONES) {
					return false;
				}
				Zone zone = zones[x][z];
				if (!zone.initialized) {
					return false;
				}
				if (zone.sizeO == 0 && zone.sizeA == 0) {
					return false;
				}
			}
		}
		return true;
	}

	private void loadSubScene(WorldView worldView, Scene scene) {
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		WorldViewContext ctx0 = subs[worldViewId];
		if (ctx0 != null) {
			log.error("Reload of an already loaded sub scene?");
			ctx0.free();
		}
		assert ctx0 == null;

		final WorldViewContext ctx = new WorldViewContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		subs[worldViewId] = ctx;

		SceneUploader sceneUploader = new SceneUploader();
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];
				sceneUploader.zoneSize(scene, zone, x, z);
			}
		}

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
						o.init();
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						a = new VBO(sz);
						a.init();
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try {
			latch.await();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];

				sceneUploader.uploadZone(scene, zone, x, z);
			}
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView) {
		int worldViewId = worldView.getId();
		if (worldViewId > -1) {
			log.debug("WorldView despawn: {}", worldViewId);
			subs[worldViewId].free();
			subs[worldViewId] = null;
		}
	}

	@Override
	public void swapScene(Scene scene) {
		if (scene.getWorldViewId() > -1) {
			swapSub(scene);
			return;
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
