package rs117.hd.renderer.zone.passes;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.hooks.*;
import rs117.hd.HdPlugin;
import rs117.hd.config.ShadowMode;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.ShadowShaderProgram;
import rs117.hd.opengl.uniforms.UBOGlobal;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.SceneCullingManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.ShadowCasterVolume;

import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearDepth;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class DirectionalShadowPass implements RenderPass {

	public static final int DIRECTIONAL_CAMERA_ID = ZoneRenderer.CAMERA_COUNT++;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer renderer;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private SceneCullingManager	sceneCullingManager;

	@Inject
	private ShadowShaderProgram.Fast fastShadowProgram;

	@Inject
	private ShadowShaderProgram.Detailed detailedShadowProgram;

	public final Camera directionalCamera = new Camera().setOrthographic(true).setCullingId(DIRECTIONAL_CAMERA_ID);
	public final ShadowCasterVolume directionalShadowCasterVolume = new ShadowCasterVolume(directionalCamera);
	public final CommandBuffer directionalCmd = new CommandBuffer("Directional");

	private UBOGlobal uboGlobal;
	private Camera sceneCamera;

	private boolean isCameraAddedToCulling;
	private boolean shouldDrawRoofShadows;
	private boolean shouldClearShadowFbo;

	@Override
	public void initialize() {
		uboGlobal = plugin.uboGlobal;
		sceneCamera = renderer.sceneCamera;
		shouldClearShadowFbo = true;

		directionalCmd.setFrameTimer(frameTimer);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		fastShadowProgram.compile(includes);
		detailedShadowProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		fastShadowProgram.destroy();
		detailedShadowProgram.destroy();
	}

	@Override
	public void preSceneDraw(WorldViewContext ctx, boolean isTopLevel) {
		if(!isTopLevel)
			return;

		directionalCamera.setPitch(environmentManager.currentSunAngles[0]);
		directionalCamera.setYaw(PI - environmentManager.currentSunAngles[1]);
		uboGlobal.lightDir.set(directionalCamera.getForwardDirection());

		if(!plugin.configShadowsEnabled || sceneCamera.isOrthographic()) {
			if(isCameraAddedToCulling)
				sceneCullingManager.removeCamera(directionalCamera);
			isCameraAddedToCulling = false;
			return;
		}

		if(!isCameraAddedToCulling)
			sceneCullingManager.addCamera(directionalCamera);
		isCameraAddedToCulling = true;

		float drawDistance = (float) plugin.getDrawDistance();
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

		uboGlobal.lightProjectionMatrix.set(directionalCamera.getViewProjMatrix());
		uboGlobal.upload();

		shouldDrawRoofShadows =
			plugin.configShadowsEnabled &&
			plugin.configRoofShadows &&
			environmentManager.allowRoofShadows();

		directionalCmd.reset();
	}

	@Override
	public boolean zoneInFrustum(Zone z, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		if(!plugin.configShadowsEnabled)
			return false;

		if(z.isVisible(sceneCamera))
			return true;

		boolean isVisible = z.isVisible(directionalCamera);
		if (z.isVisible(directionalCamera) && plugin.configExpandShadowDraw) {
			int centerX = minX + (maxX - minX) / 2;
			int centerY = minY + (maxY - minY) / 2;
			int centerZ = minZ + (maxZ - minZ) / 2;
			isVisible = directionalShadowCasterVolume.intersectsPoint(centerX, centerY, centerZ);
		}

		return z.setVisibility(directionalCamera, isVisible);
	}

	@Override
	public void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {
		if(!plugin.configShadowsEnabled)
			return;

		if(sceneManager.isRoot(ctx) && !z.isVisible(directionalCamera))
			return;

		final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
		if (!isSquashed) {
			directionalCmd.SetShader(fastShadowProgram);
			z.renderOpaque(directionalCmd, ctx, directionalCamera, shouldDrawRoofShadows);
		}
	}

	@Override
	public void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {
		if(!plugin.configShadowsEnabled)
			return;

		if(sceneManager.isRoot(ctx) && !z.isVisible(directionalCamera))
			return;

		if (z.sizeA == 0 || z.alphaModels.isEmpty())
			return;

		final int offset = ctx.sceneContext.sceneOffset >> 3;
		final boolean isSquashed = ctx.uboWorldViewStruct != null && ctx.uboWorldViewStruct.isSquashed();
		if (!isSquashed) {
			directionalCmd.SetShader(plugin.configShadowMode == ShadowMode.DETAILED ? detailedShadowProgram : fastShadowProgram);
			z.renderAlpha(directionalCmd, zx - offset, zz - offset, level, ctx, directionalCamera, true, shouldDrawRoofShadows);
		}
	}

	@Override
	public boolean dynamicInFrustum(WorldViewContext ctx, Renderable renderable, Model model, ModelOverride modelOverride, int x, int y, int z) {
		if(!plugin.configShadowsEnabled || !modelOverride.castShadows)
			return false;

		return directionalShadowCasterVolume.intersectsPoint(x, y, z);
	}

	@Override
	public void drawPass(WorldViewContext ctx, int pass) {
		if(!plugin.configShadowsEnabled)
			return;

		if(pass == DrawCallbacks.PASS_OPAQUE) {
			directionalCmd.SetShader(fastShadowProgram);
			directionalCmd.ExecuteSubCommandBuffer(ctx.vaoDirectionalCmd);
		}
	}

	public void draw(RenderState renderState) {
		if(plugin.fboShadowMap == 0 || plugin.shadowMapResolution == 0)
			return;

		final boolean shouldRenderShadows =
			plugin.configShadowsEnabled &&
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
		renderState.ido.set(renderer.indirectDrawCmds.id);

		CommandBuffer.SKIP_DEPTH_MASKING = true;
		directionalCmd.execute(renderState);
		CommandBuffer.SKIP_DEPTH_MASKING = false;

		glBindVertexArray(0);

		renderState.disable.set(GL_DEPTH_TEST);

		shouldClearShadowFbo = true;
		frameTimer.end(Timer.RENDER_SHADOWS);
	}

	@Override
	public RenderPassType getType() { return RenderPassType.DIRECTIONAL; }
}
