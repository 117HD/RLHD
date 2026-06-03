package rs117.hd.renderer.zone.passes;

import java.util.Arrays;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.hooks.*;
import rs117.hd.HdPlugin;
import rs117.hd.config.ReflectionMode;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.uniforms.UBOReflectionPlanes;
import rs117.hd.opengl.uniforms.UBOReflectionPlanes.WaterPlaneStruct;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.ModelStreamingManager;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_REFLECTION_MAP;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.renderer.zone.ZoneRenderer.UNIFORM_BLOCK_REFLECTION_PLANES;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public final class ReflectionPass implements RenderPass {
	public static final int MAX_REFLECTION_RENDERS = 4;
	public static final int WATER_HEIGHT_THRESHOLD = LOCAL_TILE_SIZE;

	@Inject
	private HdPlugin plugin;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private ModelStreamingManager streamingManager;

	@Inject
	private UBOReflectionPlanes uboReflectionPlanes;

	@Inject
	private FrameTimer frameTimer;

	private final int[] weightKeys   = new int[MAX_REFLECTION_RENDERS * 8];
	private final int[] weightCounts = new int[MAX_REFLECTION_RENDERS * 8];

	private final WaterPlane[] planes = new WaterPlane[MAX_REFLECTION_RENDERS];
	private int activePlanes = 0;

	private int[] waterReflectionResolution;
	private int fboWaterReflection;
	private int texWaterReflection;
	private int texWaterReflectionDepthMap;

	private boolean waterReflectionsEnabled;

	@Override
	public void initialize(RenderState renderState) {
		for(int i = 0; i < MAX_REFLECTION_RENDERS; i++) {
			if(planes[i] == null)
				planes[i] = new WaterPlane(uboReflectionPlanes.planes[i], i);
		}
		uboReflectionPlanes.initialize(UNIFORM_BLOCK_REFLECTION_PLANES);
	}

	@Override
	public void addShaderIncludes(ShaderIncludes includes) {
		includes
			.define("MAX_REFLECTION_RENDERS", MAX_REFLECTION_RENDERS)
			.define("WATER_HEIGHT_THRESHOLD", WATER_HEIGHT_THRESHOLD)
			.addUniformBuffer(uboReflectionPlanes);
	}

	@Override
	public void processConfigChanges(Set<String> keys) {
		if(keys.contains(KEY_PLANAR_REFLECTIONS)) {
			updateWaterReflectionsFbo();
		}
	}

	private void updateWaterReflectionsFbo() {
		if (plugin.configPlanarReflections == ReflectionMode.DISABLED || plugin.sceneViewport == null)
			return;

		// Clamp this to our target range since RuneLite allows manually typing numbers outside the range
		float resolutionScale = plugin.configPlanarReflections.resolutionFrac;
		int[] resolution = {
			Math.max(1, Math.round(plugin.sceneViewport[2] * resolutionScale)),
			Math.max(1, Math.round(plugin.sceneViewport[3] * resolutionScale))
		};
		if (Arrays.equals(waterReflectionResolution, resolution))
			return;

		destroyWaterReflectionsFbo();
		waterReflectionResolution = resolution;

		// Create and bind the FBO
		fboWaterReflection = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboWaterReflection);

		// Both of these are required color-renderable texture formats
		int format = plugin.configLinearAlphaBlending ? GL_SRGB8 : GL_RGB8;

		// Create color texture array
		texWaterReflection = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texWaterReflection);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, format, resolution[0], resolution[1], ReflectionPass.MAX_REFLECTION_RENDERS, 0, GL_RGB, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		checkGLErrors();

		// Bind layer 0 of the color texture array to COLOR_ATTACHMENT0
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texWaterReflection, 0, 0);
		glReadBuffer(GL_NONE);

		// Create depth texture array
		texWaterReflectionDepthMap = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texWaterReflectionDepthMap);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT16, resolution[0], resolution[1], ReflectionPass.MAX_REFLECTION_RENDERS, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT, 0);
		checkGLErrors();

		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, texWaterReflectionDepthMap, 0, 0);
		checkGLErrors();
	}

	private void destroyWaterReflectionsFbo() {
		waterReflectionResolution = null;

		if (texWaterReflection != 0)
			glDeleteTextures(texWaterReflection);
		texWaterReflection = 0;

		if (texWaterReflectionDepthMap != 0)
			glDeleteTextures(texWaterReflectionDepthMap);
		texWaterReflectionDepthMap = 0;

		if (fboWaterReflection != 0)
			glDeleteFramebuffers(fboWaterReflection);
		fboWaterReflection = 0;
	}

	@Override
	public boolean zoneInFrustum(Zone z, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		if(!waterReflectionsEnabled)
			return false;

		boolean anyVisible = false;
		for (int i = 0; i < activePlanes; i++)
			anyVisible |= z.setVisibility(planes[i].camera, planes[i].testZoneReflectionVisibility(minX, minY, minZ, maxX, maxY, maxZ));

		return anyVisible;
	}

	@Override
	public void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {
		if(z.onlyWater && z.modelCount == 0)
			return;

		for(int i = 0; i < activePlanes; i++) {
			if(z.isVisible(planes[i].camera))
				z.renderOpaque(planes[i].cmd, ctx, plugin.configRoofReflections);
		}
	}

	@Override
	public void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {
		if(z.onlyWater && z.modelCount == 0)
			return;

		final int offset = ctx.sceneContext.sceneOffset >> 3;
		for(int i = 0; i < activePlanes; i++) {
			if(z.isVisible(planes[i].camera))
				z.renderAlpha(planes[i].cmd, zx - offset, zz - offset, level, ctx, true, plugin.configRoofReflections);
		}
	}

	@Override
	public void drawPass(WorldViewContext ctx, int pass) {
		if(pass == DrawCallbacks.PASS_OPAQUE) {
			for(int i = 0; i < activePlanes; i++) {
				final WaterPlane plane = planes[i];
				if(plane.zoneCount > 0 && !plane.cmd.isEmpty())
					plane.cmd.ExecuteSubCommandBuffer(ctx.vaoSceneCmd);
			}
		}
	}

	@Override
	public void preSceneDraw(WorldViewContext ctx) {
		if(ctx != sceneManager.getRoot())
			return;

		if(!ctx.sceneContext.hasWater) {
			waterReflectionsEnabled = false;
			return;
		}

		updateWaterReflectionsFbo();

		waterReflectionsEnabled = plugin.configPlanarReflections != ReflectionMode.DISABLED;
		if(!waterReflectionsEnabled)
			return;

		for(int i = 0; i < activePlanes; i++)
			planes[i].reset();
		activePlanes = 0;

		for (int i = 0; i < MAX_REFLECTION_RENDERS; i++) {
			int numLevels = 0;

			for (int zx = 0; zx < ctx.getSizeX(); zx++) {
				for (int zz = 0; zz < ctx.getSizeZ(); zz++) {
					final Zone zone = ctx.zones[zx][zz];
					if (!zone.hasWater || !zone.isVisible(zoneRenderer.sceneCamera))
						continue;

					boolean isInRange = false;
					for (int k = 0; k < activePlanes; k++) {
						if (abs(zone.mostPrevalentWaterLevel - planes[k].waterHeight) <= WATER_HEIGHT_THRESHOLD) {
							isInRange = true;
							break;
						}
					}
					if (isInRange)
						continue;

					final int level = zone.mostPrevalentWaterLevel;

					int slot = -1;
					for (int j = 0; j < numLevels; j++) {
						if (weightKeys[j] == level) { slot = j; break; }
					}
					if (slot == -1) {
						slot = numLevels++;
						weightKeys[slot] = level;
						weightCounts[slot] = 0;
					}
					weightCounts[slot]++;
				}
			}

			if (numLevels == 0)
				break;

			int bestSlot = 0;
			for (int j = 1; j < numLevels; j++) {
				if (weightCounts[j] > weightCounts[bestSlot])
					bestSlot = j;
			}

			final int winningLevel = weightKeys[bestSlot];
			final WaterPlane plane = planes[activePlanes];
			plane.camera.copyFrom(zoneRenderer.sceneCamera);
			plane.camera.setPositionY(-winningLevel * 2 - zoneRenderer.sceneCamera.getPositionY());
			plane.camera.setPitch(-zoneRenderer.sceneCamera.getPitch());
			plane.waterHeight = winningLevel;

			for (int zx = 0; zx < ctx.getSizeX(); zx++) {
				for (int zz = 0; zz < ctx.getSizeZ(); zz++) {
					final Zone zone = ctx.zones[zx][zz];
					if (!zone.hasWater || !zone.isVisible(zoneRenderer.sceneCamera))
						continue;

					if (abs(zone.mostPrevalentWaterLevel - winningLevel) <= WATER_HEIGHT_THRESHOLD)
						plane.expandWaterBounds(ctx.sceneContext, zx, zz);
				}
			}

			streamingManager.addModelCullingFrustums(plane.camera);
			activePlanes++;
		}
		uboReflectionPlanes.activePlanes.set(activePlanes);
	}

	@Override
	public void draw(RenderState renderState) {
		if(!waterReflectionsEnabled || activePlanes <= 0)
			return;

		frameTimer.begin(Timer.RENDER_REFLECTIONS);

		zoneRenderer.sceneReflectionProgram.use();
		for(int i = 0; i < activePlanes; i++)
			planes[i].render(renderState);

		frameTimer.end(Timer.RENDER_REFLECTIONS);

		// Bind the water reflection texture array to the reflection map unit
		glActiveTexture(TEXTURE_UNIT_WATER_REFLECTION_MAP);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texWaterReflection);

		frameTimer.begin(Timer.REFLECTION_MIPMAPS);
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		frameTimer.end(Timer.REFLECTION_MIPMAPS);
	}

	@Override
	public void destroy() {
		destroyWaterReflectionsFbo();

		uboReflectionPlanes.destroy();
	}

	final class WaterPlane {
		public final WaterPlaneStruct struct;
		public final Camera camera;
		public final CommandBuffer cmd;
		public final int layer;
		public float waterHeight;

		public int waterMinX, waterMinZ, waterMaxX, waterMaxZ;
		public int[] zoneBounds = new int[4];
		public int zoneCount = 0;

		private WaterPlane(WaterPlaneStruct struct, int layer) {
			this.struct = struct;
			this.layer = layer;
			camera = new Camera().setCullingId(ZoneRenderer.CAMERA_COUNT++).setFlipY(true).setReverseZ(true);
			cmd = new CommandBuffer("WaterPlane - " + layer);
		}

		public void reset() {
			waterMinX = Integer.MAX_VALUE;
			waterMinZ = Integer.MAX_VALUE;
			waterMaxX = Integer.MIN_VALUE;
			waterMaxZ = Integer.MIN_VALUE;
			zoneCount = 0;
			cmd.reset();
		}

		public void expandWaterBounds(SceneContext sceneContext, int zx, int zz) {
			int minX = (zx * CHUNK_SIZE - sceneContext.sceneOffset) * LOCAL_TILE_SIZE;
			int minZ = (zz * CHUNK_SIZE - sceneContext.sceneOffset) * LOCAL_TILE_SIZE;
			int maxX = minX + CHUNK_SIZE * LOCAL_TILE_SIZE;
			int maxZ = minZ + CHUNK_SIZE * LOCAL_TILE_SIZE;

			waterMinX = Math.min(waterMinX, minX);
			waterMinZ = Math.min(waterMinZ, minZ);
			waterMaxX = Math.max(waterMaxX, maxX);
			waterMaxZ = Math.max(waterMaxZ, maxZ);

			if(zoneCount >= zoneBounds.length / 4)
				zoneBounds = Arrays.copyOf(zoneBounds, zoneBounds.length * 2);

			int base = zoneCount * 4;
			zoneBounds[base    ] = minX;
			zoneBounds[base + 1] = minZ;
			zoneBounds[base + 2] = maxX;
			zoneBounds[base + 3] = maxZ;
			zoneCount++;
		}

		/**
		 * Tests if this zone's reflection is visible by projecting its AABB corners onto
		 * the water plane (y = waterHeight) from the camera, then checking if that
		 * footprint overlaps any active reflection zone.
		 */
		public boolean testZoneReflectionVisibility(
			int zoneMinX, int zoneMinY, int zoneMinZ,
			int zoneMaxX, int zoneMaxY, int zoneMaxZ
		) {
			if (zoneCount <= 0)
				return false;

			if(!camera.intersectsAABB(zoneMinX, zoneMinY, zoneMinZ, zoneMaxX, zoneMaxY, zoneMaxZ))
				return false;

			final float camX = camera.getPositionX();
			final float camY = camera.getPositionY();
			final float camZ = camera.getPositionZ();

			float hitMinX = Float.POSITIVE_INFINITY;
			float hitMaxX = Float.NEGATIVE_INFINITY;
			float hitMinZ = Float.POSITIVE_INFINITY;
			float hitMaxZ = Float.NEGATIVE_INFINITY;

			int projected = 0;
			int skipped   = 0;

			for (int ci = 0; ci < 8; ci++) {
				final float cx = (ci & 1) == 0 ? zoneMinX : zoneMaxX;
				final float cy = (ci & 2) == 0 ? zoneMinY : zoneMaxY;
				final float cz = (ci & 4) == 0 ? zoneMinZ : zoneMaxZ;

				final float dy = cy - camY;
				if (Math.abs(dy) < 1e-5f) {
					skipped++;
					continue;
				}

				final float t = (waterHeight - camY) / dy;
				if (t <= 0f || t > 1f) {
					skipped++;
					continue;
				}

				final float hx = camX + (cx - camX) * t;
				final float hz = camZ + (cz - camZ) * t;

				hitMinX = min(hitMinX, hx);
				hitMaxX = max(hitMaxX, hx);
				hitMinZ = min(hitMinZ, hz);
				hitMaxZ = max(hitMaxZ, hz);
				projected++;
			}

			if (projected == 0)
				return skipped > 0;

			if (skipped > 0) {
				float expand = CHUNK_SIZE * LOCAL_TILE_SIZE * 2.0f;
				hitMinX -= expand;
				hitMaxX += expand;
				hitMinZ -= expand;
				hitMaxZ += expand;
			}

			if (hitMaxX < waterMinX || hitMinX > waterMaxX ||
			    hitMaxZ < waterMinZ || hitMinZ > waterMaxZ)
				return false;

			for (int i = 0; i < zoneCount; i++) {
				int base = i * 4;
				if (hitMaxX >= zoneBounds[base]     &&
				    hitMinX <= zoneBounds[base + 2] &&
				    hitMaxZ >= zoneBounds[base + 1] &&
				    hitMinZ <= zoneBounds[base + 3])
					return true;
			}

			return false;
		}

		public void render(RenderState renderState) {
			if(zoneCount <= 0)
				return;

			struct.camera.write(camera);
			struct.height.set(-waterHeight);

			uboReflectionPlanes.cullingPlane.set(0.0f, -1.0f, 0.0f, -waterHeight);
			uboReflectionPlanes.upload();

			plugin.uboGlobal.sceneCamera.write(camera);
			plugin.uboGlobal.upload();

			glViewport(0, 0, waterReflectionResolution[0], waterReflectionResolution[1]);

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboWaterReflection);

			// Redirect both attachments to this plane's layer before clearing/drawing
			glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texWaterReflection, 0, layer);
			glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, texWaterReflectionDepthMap, 0, layer);

			float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
			if (plugin.configLinearAlphaBlending) {
				glEnable(GL_FRAMEBUFFER_SRGB);
				// This is kind of stupid, but our shader expects fogColor in sRGB, so we transform it back here
				fogColor = ColorUtils.srgbToLinear(fogColor);
			}
			glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f);

			glClearDepth(0);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			// Since the game was never designed to be viewed from below, a lot of
			// things are missing triangles underneath. In most cases, it's fine
			// visually to render the top face from below.
			renderState.disable.set(GL_CULL_FACE);

			renderState.enable.set(GL_DEPTH_TEST);
			renderState.enable.set(GL_BLEND);
			renderState.enable.set(GL_CLIP_DISTANCE0);
			renderState.depthFunc.set(GL_GEQUAL);
			renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

			cmd.execute(renderState);

			// Reset everything back to the main pass' state
			renderState.disable.set(GL_DEPTH_TEST);
			renderState.disable.set(GL_CLIP_DISTANCE0);
			renderState.disable.set(GL_FRAMEBUFFER_SRGB);
			renderState.apply();
		}
	}
}
