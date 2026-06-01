package rs117.hd.renderer.zone.passes;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.hooks.*;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UBOReflectionPlanes;
import rs117.hd.opengl.uniforms.UBOReflectionPlanes.WaterPlaneStruct;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.ModelStreamingManager;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_GEQUAL;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_ZERO;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearDepth;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glFramebufferTextureLayer;
import static org.lwjgl.opengl.GL30C.GL_CLIP_DISTANCE0;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glGenerateMipmap;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_REFLECTION_MAP;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class ReflectionPass implements RenderPass {
	public static final int MAX_REFLECTION_RENDERS = 4; // TODO: Increase once the FrameBuffer supports it
	public static final int WATER_HEIGHT_THRESHOLD = LOCAL_TILE_SIZE;

	@Inject
	private HdPlugin plugin;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private ModelStreamingManager streamingManager;

	@Inject
	private UBOReflectionPlanes uboReflectionPlanes;

	@Inject
	private FrameTimer frameTimer;

	private RenderState renderState = new RenderState();

	private final HashMap<Integer, Integer> waterLevelWeights = new HashMap<>();
	private final WaterPlane[] planes = new WaterPlane[MAX_REFLECTION_RENDERS];
	private int activePlanes = 0;

	private boolean waterReflectionsEnabled;

	public void initialize(RenderState renderState) {
		for(int i = 0; i < MAX_REFLECTION_RENDERS; i++) {
			if(planes[i] == null)
				planes[i] = new WaterPlane(uboReflectionPlanes.planes[i], renderState, i);
		}
		this.renderState = renderState;
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
		if(z.onlyWater || z.modelCount == 0)
			return;

		for(int i = 0; i < activePlanes; i++) {
			if(z.isVisible(planes[i].camera))
				z.renderOpaque(planes[i].cmd, ctx, plugin.configRoofReflections);
		}
	}

	@Override
	public void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {
		if(z.onlyWater || z.modelCount == 0)
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
				if(planes[i].shouldRender)
					planes[i].cmd.ExecuteSubCommandBuffer(ctx.vaoSceneCmd);
			}
		}
	}

	@Override
	public void preTopLevelDraw() {
		WorldViewContext ctx = sceneManager.getRoot();
		if(!ctx.sceneContext.hasWater) {
			waterReflectionsEnabled = false;
			return;
		}

		waterReflectionsEnabled = plugin.configPlanarReflections && !plugin.configLegacyWater;
		if(!waterReflectionsEnabled)
			return;

		activePlanes = 0;
		for(int i = 0; i < MAX_REFLECTION_RENDERS; i++) {
			waterLevelWeights.clear();
			for (int x = 0; x < EXTENDED_SCENE_SIZE >> 3; ++x) {
				for (int z = 0; z < EXTENDED_SCENE_SIZE >> 3; ++z) {
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

					if(isInRange)
						continue;

					waterLevelWeights.put(
						zone.mostPrevalentWaterLevel,
						waterLevelWeights.getOrDefault(zone.mostPrevalentWaterLevel, 0) + 1
					);
				}
			}

			Map.Entry<Integer, Integer> best = null;
			for (var entry : waterLevelWeights.entrySet()) {
				if (best == null || entry.getValue() > best.getValue())
					best = entry;
			}

			if(best == null)
				break;

			final WaterPlane plane = planes[activePlanes];
			plane.setup(zoneRenderer.sceneCamera, best.getKey());
			streamingManager.addModelCullingFrustums(plane.camera);

			activePlanes++;
		}
		uboReflectionPlanes.activePlanes.set(activePlanes);
	}

	@Override
	public void postDraw() {
		if(!waterReflectionsEnabled || activePlanes <= 0)
			return;

		frameTimer.begin(Timer.RENDER_REFLECTIONS);

		zoneRenderer.sceneReflectionProgram.use();
		for(int i = 0; i < activePlanes; i++)
			planes[i].render();
		activePlanes = 0;

		frameTimer.end(Timer.RENDER_REFLECTIONS);

		// Bind the water reflection texture array to the reflection map unit
		glActiveTexture(TEXTURE_UNIT_WATER_REFLECTION_MAP);
		glBindTexture(GL_TEXTURE_2D_ARRAY, plugin.texWaterReflection);

		frameTimer.begin(Timer.REFLECTION_MIPMAPS);
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY); // TODO: Do it per level? Since ActivePlanes might be less than the amount of available layers
		frameTimer.end(Timer.REFLECTION_MIPMAPS);
	}

	public void shutdown() {

	}

	class WaterPlane {
		public final WaterPlaneStruct struct;
		public final Camera camera;
		public final CommandBuffer cmd;
		public final int layer;
		public float waterHeight;
		public boolean shouldRender;

		private WaterPlane(WaterPlaneStruct struct, RenderState renderState, int layer) {
			this.struct = struct;
			this.layer = layer;
			camera = new Camera().setCullingId(ZoneRenderer.CAMERA_COUNT++).setFlipY(true).setReverseZ(true);
			cmd = new CommandBuffer("WaterPlane", renderState);
		}

		public void setup(Camera sceneCamera, int targetWaterHeight) {
			camera.copyFrom(sceneCamera);
			camera.setPositionY(-targetWaterHeight * 2 - sceneCamera.getPositionY());
			camera.setPitch(-sceneCamera.getPitch());
			waterHeight = targetWaterHeight;

			struct.camera.write(camera);
			struct.height.set(-waterHeight);

			cmd.reset();
			shouldRender = true;
		}

		public void render() {
			final WorldViewContext ctx = sceneManager.getRoot();
			if (!shouldRender || !plugin.configPlanarReflections || plugin.configLegacyWater || !ctx.sceneContext.hasWater)
				return;
			shouldRender = false;

			uboReflectionPlanes.cullingPlane.set(0.0f, -1.0f, 0.0f, -waterHeight);
			uboReflectionPlanes.upload();

			plugin.uboGlobal.sceneCamera.write(camera);
			plugin.uboGlobal.upload();

			glViewport(0, 0, plugin.waterReflectionResolution[0], plugin.waterReflectionResolution[1]);

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, plugin.fboWaterReflection);

			// Redirect both attachments to this plane's layer before clearing/drawing
			glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, plugin.texWaterReflection, 0, layer);
			glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, plugin.texWaterReflectionDepthMap, 0, layer);

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

			cmd.execute();

			// Reset everything back to the main pass' state
			renderState.disable.set(GL_DEPTH_TEST);
			renderState.disable.set(GL_CLIP_DISTANCE0);
			renderState.disable.set(GL_CULL_FACE);
		}
	}
}
