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
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

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

		boolean isVisible = false;
		for(int i = 0; i < activePlanes; i++)
			isVisible |= z.setVisibility(planes[i].camera, planes[i].camera.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ));

		return isVisible;
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
				if(planes[i].shouldRender && !planes[i].cmd.isEmpty()) {
					planes[i].cmd.ExecuteSubCommandBuffer(ctx.vaoSceneCmd);
				}
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

		activePlanes = 0;
		for (int i = 0; i < MAX_REFLECTION_RENDERS; i++) {
			int numLevels = 0;

			for (int x = 0; x < ctx.getSizeX(); x++) {
				for (int z = 0; z < ctx.getSizeZ(); z++) {
					final Zone zone = ctx.zones[x][z];
					if (!zone.hasWater || !zone.isVisible(zoneRenderer.sceneCamera))
						continue;

					// Check if zone height is within range of current waterHeights
					boolean isInRange = false;
					for (int k = 0; k < activePlanes; k++) {
						final float diff = abs(zone.mostPrevalentWaterLevel - planes[k].waterHeight);
						if (diff <= WATER_HEIGHT_THRESHOLD) {
							isInRange = true;
							break;
						}
					}
					if (isInRange)
						continue;

					final int level = zone.mostPrevalentWaterLevel;

					// Linear scan to find or insert this level
					int slot = -1;
					for (int j = 0; j < numLevels; j++) {
						if (weightKeys[j] == level) {
							slot = j;
							break;
						}
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

			final WaterPlane plane = planes[activePlanes];
			plane.setup(zoneRenderer.sceneCamera, weightKeys[bestSlot]);
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
		activePlanes = 0;

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
		public boolean shouldRender;

		private WaterPlane(WaterPlaneStruct struct, int layer) {
			this.struct = struct;
			this.layer = layer;
			camera = new Camera().setCullingId(ZoneRenderer.CAMERA_COUNT++).setFlipY(true).setReverseZ(true);
			cmd = new CommandBuffer("WaterPlane");
		}

		public void setup(Camera sceneCamera, int targetWaterHeight) {
			// TODO: Plane Camera can be further refined down to encompasses only the zones its rendering reflections for
			camera.copyFrom(sceneCamera);
			camera.setPositionY(-targetWaterHeight * 2 - sceneCamera.getPositionY());
			camera.setPitch(-sceneCamera.getPitch());
			waterHeight = targetWaterHeight;
			cmd.reset();
			shouldRender = true;
		}

		public void render(RenderState renderState) {
			if(!shouldRender)
				return;
			shouldRender = false;

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
