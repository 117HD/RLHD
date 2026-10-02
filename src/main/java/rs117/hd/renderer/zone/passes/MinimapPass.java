package rs117.hd.renderer.zone.passes;

import java.awt.Rectangle;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.hooks.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.MinimapType;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.SceneCullingManager;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Renders a top-down 3D view of the scene into the vanilla minimap widget's on-screen rect.
 * <p>
 * To preserve the player/NPC dots, destination flag and compass that vanilla draws on top of the minimap
 * raster, a {@link net.runelite.api.TileFunction} is installed (see {@link #drawPlaceholderTile}) which
 * replaces real terrain pixels with a magic placeholder color instead of disabling the vanilla minimap
 * entirely. The UI shader then turns pixels still matching that placeholder color transparent, revealing
 * this pass's output underneath, while leftover marker pixels (drawn by vanilla after the tile callback)
 * stay opaque on top.
 */
@Slf4j
@Singleton
public class MinimapPass implements RenderPass {
	// Matches the magic color historically used by this feature; also referenced by ui_frag.glsl.
	public static final int PLACEHOLDER_COLOR = 12345678;

	public static final int MINIMAP_CAMERA_ID = ZoneRenderer.CAMERA_COUNT++;

	// World-space radius (in local scene units, 128 per tile) covered by the minimap camera.
	// Tunable; a follow-up should tie this to the vanilla minimap zoom level instead.
	private static final float MINIMAP_WORLD_RADIUS = 20 * Perspective.LOCAL_TILE_SIZE;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ZoneRenderer renderer;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private SceneCullingManager sceneCullingManager;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneShaderProgram minimapProgram;

	public final Camera minimapCamera = new Camera()
		.setOrthographic(true)
		.setReverseZ(true)
		.setCullingId(MINIMAP_CAMERA_ID);

	public final CommandBuffer minimapCmd = new CommandBuffer("Minimap");

	// Device-pixel on-screen rect (x, y, width, height) the minimap is currently being drawn into
	public final int[] viewportRect = new int[4];
	public boolean active;

	private boolean isCameraAddedToCulling;
	private boolean tileDrawerRegistered;

	private int fboMinimap, texMinimapColor, rboMinimapDepth;
	private int fboWidth, fboHeight;

	@Override
	public void initialize() {
		minimapCmd.setFrameTimer(frameTimer);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		minimapProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		minimapProgram.destroy();
	}

	@Override
	public void destroy() {
		if (isCameraAddedToCulling)
			sceneCullingManager.removeCamera(minimapCamera);
		isCameraAddedToCulling = false;

		if (tileDrawerRegistered)
			client.setMinimapTileDrawer(null);
		tileDrawerRegistered = false;

		destroyFbo();
	}

	@Override
	public int preprocess() {
		final boolean enabled = config.minimapType() == MinimapType.HD;

		if (enabled != tileDrawerRegistered) {
			client.setMinimapTileDrawer(enabled ? this::drawPlaceholderTile : null);
			tileDrawerRegistered = enabled;
		}

		if (!enabled) {
			if (isCameraAddedToCulling)
				sceneCullingManager.removeCamera(minimapCamera);
			isCameraAddedToCulling = false;
			active = false;
			return 0;
		}

		if (!isCameraAddedToCulling)
			sceneCullingManager.addCamera(minimapCamera);
		isCameraAddedToCulling = true;

		return PASS_DEFAULT;
	}

	private void drawPlaceholderTile(Tile tile, int tx, int ty, int px0, int py0, int px1, int py1) {
		client.getRasterizer().fillRectangle(px0, py0, px1 - px0, py1 - py0, PLACEHOLDER_COLOR);
	}

	// RuneLite exposes a different minimap draw-area widget depending on the active layout: classic fixed,
	// classic resizable, and "modern" resizable (orbs stacked along the left edge instead of on the
	// minimap itself). There's no single reliable way to tell which one is in play ahead of time, so just
	// try all of them and use whichever is actually present and visible.
	private static final int[] MINIMAP_DRAW_AREA_IDS = {
		ComponentID.FIXED_VIEWPORT_MINIMAP_DRAW_AREA,
		ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA,
		ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_MINIMAP_DRAW_AREA,
	};

	private Widget getMinimapWidget() {
		for (int componentId : MINIMAP_DRAW_AREA_IDS) {
			Widget widget = client.getWidget(componentId);
			if (widget != null && !widget.isHidden())
				return widget;
		}
		return null;
	}

	@Override
	public void preSceneDraw(WorldViewContext ctx, boolean isTopLevel) {
		if (!isTopLevel)
			return;

		minimapCmd.reset();
		active = false;

		final Widget widget = getMinimapWidget();
		if (widget == null || widget.isHidden())
			return;

		final Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
			return;

		// Convert the canvas-relative AWT pixel rect (top-left origin) into device-pixel GL coordinates
		// (bottom-left origin), the same way HdPlugin#updateSceneFbo derives sceneViewport.
		final float[] scale = plugin.sceneViewportScale;
		final int glX = bounds.x;
		final int glY = client.getCanvasHeight() - (bounds.y + bounds.height);
		viewportRect[0] = Math.round(glX * scale[0]);
		viewportRect[1] = Math.round(glY * scale[1]);
		viewportRect[2] = Math.round(bounds.width * scale[0]);
		viewportRect[3] = Math.round(bounds.height * scale[1]);

		if (viewportRect[2] <= 0 || viewportRect[3] <= 0)
			return;

		updateFbo(viewportRect[2], viewportRect[3]);

		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
			return;
		final LocalPoint lp = localPlayer.getLocalLocation();

		minimapCamera.setPosition(lp.getX(), plugin.cameraPosition[1], lp.getY());
		minimapCamera.setYaw(0);
		minimapCamera.setPitch((float) (Math.PI / 2));
		minimapCamera.setViewportWidth((int) (MINIMAP_WORLD_RADIUS * 2));
		minimapCamera.setViewportHeight((int) (MINIMAP_WORLD_RADIUS * 2));
		minimapCamera.setNearPlane(-20000);
		minimapCamera.setFarPlane(20000);

		active = true;
	}

	@Override
	public void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {
		if (!active || !sceneManager.isRoot(ctx) || !z.isVisible(minimapCamera))
			return;

		z.renderOpaque(minimapCmd, ctx, minimapCamera, false);
	}

	@Override
	public void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {
		if (!active || !sceneManager.isRoot(ctx) || !z.isVisible(minimapCamera))
			return;

		if (z.sizeA == 0 && z.visibleAlphaModels.isEmpty())
			return;

		final int offset = ctx.sceneContext.sceneOffset >> 3;
		z.renderAlpha(minimapCmd, zx - offset, zz - offset, level, ctx, minimapCamera, false, false);
	}

	@Override
	public void drawPass(WorldViewContext ctx, int pass) {
		if (!active || !sceneManager.isRoot(ctx))
			return;

		if (pass == DrawCallbacks.PASS_OPAQUE) {
			minimapCmd.ExecuteSubCommandBuffer(ctx.vaoMinimapCmd);
		} else if (pass == DrawCallbacks.PASS_ALPHA) {
			ctx.drawAll(WorldViewContext.VAO_OPAQUE, ctx.vaoMinimapCmd);
			ctx.drawAll(WorldViewContext.VAO_PLAYER, ctx.vaoMinimapCmd);
		}
	}

	@Override
	public void draw(RenderState renderState) {
		if (!active || fboMinimap == 0)
			return;

		minimapProgram.use();

		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, fboMinimap);
		renderState.disable.set(GL_MULTISAMPLE);
		renderState.viewport.set(0, 0, fboWidth, fboHeight);
		if (renderer.indirectDrawCmds != null)
			renderState.ido.set(renderer.indirectDrawCmds.id);

		renderState.enable.set(GL_BLEND);
		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		renderState.apply();

		glClearColor(0, 0, 0, 1);
		glClearDepth(0);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		// Temporarily point the shared scene-camera UBO slot at the minimap camera. This is safe because
		// this pass runs last in the frame (after BlitScenePass), so nothing else reads sceneCamera again
		// until next frame's preSceneDrawTopLevel overwrites it with the real camera before SCENE runs.
		plugin.uboGlobal.sceneCamera.write(minimapCamera);
		plugin.uboGlobal.upload();

		minimapCmd.execute(renderState);

		glBindVertexArray(0);

		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.apply();

		// Blit the rendered minimap onto the default framebuffer at the widget's on-screen rect
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboMinimap);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));
		glBlitFramebuffer(
			0, 0, fboWidth, fboHeight,
			viewportRect[0], viewportRect[1], viewportRect[0] + viewportRect[2], viewportRect[1] + viewportRect[3],
			GL_COLOR_BUFFER_BIT, GL_NEAREST
		);
	}

	private void updateFbo(int width, int height) {
		if (fboMinimap != 0 && width == fboWidth && height == fboHeight)
			return;

		destroyFbo();

		fboWidth = width;
		fboHeight = height;

		fboMinimap = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboMinimap);

		texMinimapColor = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texMinimapColor);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texMinimapColor, 0);

		rboMinimapDepth = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboMinimapDepth);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT32F, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboMinimapDepth);

		glBindFramebuffer(GL_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));
	}

	private void destroyFbo() {
		if (texMinimapColor != 0)
			glDeleteTextures(texMinimapColor);
		texMinimapColor = 0;

		if (rboMinimapDepth != 0)
			glDeleteRenderbuffers(rboMinimapDepth);
		rboMinimapDepth = 0;

		if (fboMinimap != 0)
			glDeleteFramebuffers(fboMinimap);
		fboMinimap = 0;

		fboWidth = fboHeight = 0;
	}

	@Override
	public RenderPassType getType() { return RenderPassType.MINIMAP; }
}
