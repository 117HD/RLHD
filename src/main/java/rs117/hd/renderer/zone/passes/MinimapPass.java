package rs117.hd.renderer.zone.passes;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.hooks.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.MinimapType;
import rs117.hd.opengl.shader.MinimapSampleShaderProgram;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_MINIMAP_CACHE;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_MINIMAP_MASK;

@Slf4j
@Singleton
public class MinimapPass implements RenderPass {
	public static final int PLACEHOLDER_COLOR = 12345678;

	private static final int ZONE_PX = 128;
	private static final int WINDOW_RADIUS_ZONES = 10;
	private static final int WINDOW_ZONES = WINDOW_RADIUS_ZONES * 2 + 1;
	private static final int WINDOW_RECENTER_MARGIN = 2;
	private static final int CACHE_PX = WINDOW_ZONES * ZONE_PX;
	private static final int CACHE_TILE_SPAN = WINDOW_ZONES * Constants.CHUNK_SIZE;
	private static final int MAX_ZONE_UPDATES_PER_FRAME = 8;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ZoneRenderer renderer;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private SceneShaderProgram minimapProgram;

	@Inject
	private MinimapSampleShaderProgram sampleProgram;

	public final Camera cacheCamera = new Camera().setOrthographic(true).setReverseZ(true);

	private final CommandBuffer[] zoneCacheCmds = new CommandBuffer[MAX_ZONE_UPDATES_PER_FRAME];

	public final int[] viewportRect = new int[4];
	public boolean active;

	private boolean tileDrawerRegistered;

	private int fboWidth, fboHeight;

	private int fboMinimapCache, texMinimapCacheColor, rboMinimapCacheDepth;

	private int texMinimapMask;
	private int maskResizedState = -1;

	private WorldViewContext rootCtx;
	private final Zone[][] cachedZoneRefs = new Zone[SceneManager.NUM_ZONES][SceneManager.NUM_ZONES];
	private int windowOriginZx = -1, windowOriginZz = -1;
	private int lastPlane = -1;
	private final int[] pendingZx = new int[MAX_ZONE_UPDATES_PER_FRAME];
	private final int[] pendingZz = new int[MAX_ZONE_UPDATES_PER_FRAME];
	private int pendingCount;

	private float displayCosYaw, displaySinYaw, displayPixelToWorld;
	private float cacheCenterUvX, cacheCenterUvZ, cacheUvPerWorldUnit;

	private boolean wasEnvironmentTransitioning = true;

	@Override
	public void initialize() {
		for (int i = 0; i < MAX_ZONE_UPDATES_PER_FRAME; i++) {
			zoneCacheCmds[i] = new CommandBuffer("MinimapCache" + i);
			zoneCacheCmds[i].setFrameTimer(frameTimer);
		}
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		minimapProgram.compile(includes);
		sampleProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		minimapProgram.destroy();
		sampleProgram.destroy();
	}

	@Override
	public void destroy() {
		if (tileDrawerRegistered)
			client.setMinimapTileDrawer(null);
		tileDrawerRegistered = false;

		destroyCacheFbo();
		destroyMask();
	}

	@Override
	public int preprocess() {
		final boolean enabled = config.minimapType() == MinimapType.HD;

		if (enabled != tileDrawerRegistered) {
			client.setMinimapTileDrawer(enabled ? this::drawPlaceholderTile : null);
			tileDrawerRegistered = enabled;
		}

		if (!enabled) {
			active = false;
			return 0;
		}

		if (fboMinimapCache == 0)
			initializeCacheFbo();

		return PASS_ENABLED;
	}

	private void drawPlaceholderTile(Tile tile, int tx, int ty, int px0, int py0, int px1, int py1) {
		client.getRasterizer().fillRectangle(px0, py0, px1 - px0, py1 - py0, PLACEHOLDER_COLOR);
	}

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

		rootCtx = ctx;
		pendingCount = 0;
		active = false;

		final Widget widget = getMinimapWidget();
		if (widget == null || widget.isHidden())
			return;

		final Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
			return;

		final float[] scale = plugin.sceneViewportScale;
		final int glX = bounds.x;
		final int glY = client.getCanvasHeight() - (bounds.y + bounds.height);
		viewportRect[0] = Math.round(glX * scale[0]);
		viewportRect[1] = Math.round(glY * scale[1]);
		viewportRect[2] = Math.round(bounds.width * scale[0]);
		viewportRect[3] = Math.round(bounds.height * scale[1]);

		if (viewportRect[2] <= 0 || viewportRect[3] <= 0)
			return;

		fboWidth = viewportRect[2];
		fboHeight = viewportRect[3];
		ensureMaskTexture();
		if (texMinimapMask == 0)
			return;

		final CameraFocusableEntity cameraFocus = client.getCameraFocusEntity();
		if (cameraFocus == null)
			return;
		final LocalPoint focus = cameraFocus.getCameraFocus();
		if (focus == null)
			return;

		final double minimapScale = client.getMinimapZoom() / Perspective.LOCAL_TILE_SIZE;
		if (minimapScale <= 0)
			return;

		final float yaw = (float) ((client.getCameraYawTarget() & 0x3fff) * Perspective.UNIT14);

		displayCosYaw = (float) Math.cos(yaw);
		displaySinYaw = (float) Math.sin(yaw);
		displayPixelToWorld = (float) (1.0 / minimapScale);

		final int sceneOffset = ctx.sceneContext.sceneOffset;
		final int playerZx = clampZone(Math.floorDiv(
			(int) (focus.getX() / Perspective.LOCAL_TILE_SIZE) + sceneOffset,
			Constants.CHUNK_SIZE
		));
		final int playerZz = clampZone(Math.floorDiv(
			(int) (focus.getY() / Perspective.LOCAL_TILE_SIZE) + sceneOffset,
			Constants.CHUNK_SIZE
		));

		final boolean needsRecenter = windowOriginZx < 0 ||
			playerZx < windowOriginZx + WINDOW_RECENTER_MARGIN ||
			playerZx > windowOriginZx + WINDOW_ZONES - 1 - WINDOW_RECENTER_MARGIN ||
			playerZz < windowOriginZz + WINDOW_RECENTER_MARGIN ||
			playerZz > windowOriginZz + WINDOW_ZONES - 1 - WINDOW_RECENTER_MARGIN;
		if (needsRecenter) {
			windowOriginZx = clampWindowOrigin(playerZx - WINDOW_RADIUS_ZONES);
			windowOriginZz = clampWindowOrigin(playerZz - WINDOW_RADIUS_ZONES);
			invalidateCache();
		}

		if (environmentChanged())
			invalidateCache();

		final int plane = Math.max(0, Math.min(3, client.getPlane()));
		if (plane != lastPlane) {
			lastPlane = plane;
			invalidateCache();
		}

		final int cacheOriginWorldX = (windowOriginZx * Constants.CHUNK_SIZE - sceneOffset) * Perspective.LOCAL_TILE_SIZE;
		final int cacheOriginWorldZ = (windowOriginZz * Constants.CHUNK_SIZE - sceneOffset) * Perspective.LOCAL_TILE_SIZE;
		cacheUvPerWorldUnit = 1f / (CACHE_TILE_SPAN * (float) Perspective.LOCAL_TILE_SIZE);
		cacheCenterUvX = (focus.getX() - cacheOriginWorldX) * cacheUvPerWorldUnit;
		cacheCenterUvZ = (focus.getY() - cacheOriginWorldZ) * cacheUvPerWorldUnit;

		queueDirtyZones(ctx, playerZx, playerZz);

		active = true;
	}

	private void invalidateCache() {
		for (Zone[] row : cachedZoneRefs)
			Arrays.fill(row, null);
	}

	private boolean environmentChanged() {
		// Keep invalidating every frame while a lighting transition (e.g. seasonal/time-of-day change) is
		// actively running, plus one guaranteed extra invalidation on the frame it completes, so the cache
		// never settles on a mid-transition lighting/shadow state.
		final boolean transitioning = !environmentManager.isTransitionComplete();
		final boolean changed = transitioning || wasEnvironmentTransitioning;
		wasEnvironmentTransitioning = transitioning;
		return changed;
	}

	private void queueDirtyZones(WorldViewContext ctx, int playerZx, int playerZz) {
		final int zxEnd = Math.min(SceneManager.NUM_ZONES, windowOriginZx + WINDOW_ZONES);
		final int zzEnd = Math.min(SceneManager.NUM_ZONES, windowOriginZz + WINDOW_ZONES);

		List<int[]> dirty = new ArrayList<>();
		for (int zx = windowOriginZx; zx < zxEnd; zx++) {
			for (int zz = windowOriginZz; zz < zzEnd; zz++) {
				Zone zone = ctx.zones[zx][zz];
				if (zone != cachedZoneRefs[zx][zz])
					dirty.add(new int[] { zx, zz, sq(zx - playerZx) + sq(zz - playerZz) });
			}
		}

		if (dirty.isEmpty())
			return;

		dirty.sort((a, b) -> Integer.compare(a[2], b[2]));

		for (int[] entry : dirty) {
			if (pendingCount >= MAX_ZONE_UPDATES_PER_FRAME)
				break;

			final int zx = entry[0];
			final int zz = entry[1];
			final Zone zone = ctx.zones[zx][zz];
			if (!zone.initialized)
				continue;

			final CommandBuffer cmd = zoneCacheCmds[pendingCount];
			cmd.reset();

			positionCacheCamera(zx, zz, ctx);

			if (zone.sizeO != 0)
				zone.renderFloorLevel(cmd, lastPlane);
			if (!zone.visibleAlphaModels.isEmpty()) {
				final int offset = ctx.sceneContext.sceneOffset >> 3;
				zone.renderAlpha(cmd, zx - offset, zz - offset, lastPlane, ctx, null, false, false);
			}

			pendingZx[pendingCount] = zx;
			pendingZz[pendingCount] = zz;
			cachedZoneRefs[zx][zz] = zone;
			pendingCount++;
		}
	}

	private void positionCacheCamera(int zx, int zz, WorldViewContext ctx) {
		final int span = Constants.CHUNK_SIZE * Perspective.LOCAL_TILE_SIZE;
		final int zMinX = (zx * Constants.CHUNK_SIZE - ctx.sceneContext.sceneOffset) * Perspective.LOCAL_TILE_SIZE;
		final int zMinZ = (zz * Constants.CHUNK_SIZE - ctx.sceneContext.sceneOffset) * Perspective.LOCAL_TILE_SIZE;

		cacheCamera.setPosition(zMinX + span / 2f, plugin.cameraPosition[1], zMinZ + span / 2f);
		cacheCamera.setYaw(0);
		cacheCamera.setPitch((float) (Math.PI / 2));
		cacheCamera.setViewportWidth(span);
		cacheCamera.setViewportHeight(span);
		cacheCamera.setNearPlane(-20000);
		cacheCamera.setFarPlane(20000);
	}

	private static int clampZone(int zone) {
		return Math.max(0, Math.min(SceneManager.NUM_ZONES - 1, zone));
	}

	private static int clampWindowOrigin(int origin) {
		return Math.max(0, Math.min(SceneManager.NUM_ZONES - WINDOW_ZONES, origin));
	}

	private static int sq(int x) {
		return x * x;
	}

	@Override
	public void draw(RenderState renderState) {
		if (!active || fboMinimapCache == 0 || texMinimapMask == 0 || rootCtx == null)
			return;

		drawZoneCacheUpdates(renderState);
		drawDisplay(renderState);
	}

	private void drawZoneCacheUpdates(RenderState renderState) {
		if (pendingCount == 0)
			return;

		minimapProgram.use();

		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, fboMinimapCache);
		renderState.disable.set(GL_MULTISAMPLE);
		renderState.enable.set(GL_SCISSOR_TEST);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_BLEND);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		if (renderer.indirectDrawCmds != null)
			renderState.ido.set(renderer.indirectDrawCmds.id);

		for (int i = 0; i < pendingCount; i++) {
			final int zx = pendingZx[i];
			final int zz = pendingZz[i];
			final int cellX = (zx - windowOriginZx) * ZONE_PX;
			final int cellY = (zz - windowOriginZz) * ZONE_PX;

			renderState.viewport.set(cellX, cellY, ZONE_PX, ZONE_PX);
			renderState.apply();
			glScissor(cellX, cellY, ZONE_PX, ZONE_PX);

			glClearColor(0, 0, 0, 1);
			glClearDepth(0);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			positionCacheCamera(zx, zz, rootCtx);
			plugin.uboGlobal.sceneCamera.write(cacheCamera);
			plugin.uboGlobal.upload();

			zoneCacheCmds[i].execute(renderState);
		}

		glBindVertexArray(0);

		renderState.disable.set(GL_SCISSOR_TEST);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_BLEND);
		renderState.apply();
	}

	private void drawDisplay(RenderState renderState) {
		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));
		renderState.disable.set(GL_SCISSOR_TEST);
		renderState.disable.set(GL_MULTISAMPLE);
		renderState.viewport.set(viewportRect[0], viewportRect[1], viewportRect[2], viewportRect[3]);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.enable.set(GL_BLEND);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		renderState.apply();

		sampleProgram.use();
		sampleProgram.uniFboSize.set((float) fboWidth, (float) fboHeight);
		sampleProgram.uniCosYaw.set(displayCosYaw);
		sampleProgram.uniSinYaw.set(displaySinYaw);
		sampleProgram.uniPixelToWorld.set(displayPixelToWorld);
		sampleProgram.uniCacheCenterUv.set(cacheCenterUvX, cacheCenterUvZ);
		sampleProgram.uniCacheUvPerWorldUnit.set(cacheUvPerWorldUnit);

		glActiveTexture(TEXTURE_UNIT_MINIMAP_CACHE);
		glBindTexture(GL_TEXTURE_2D, texMinimapCacheColor);

		glActiveTexture(TEXTURE_UNIT_MINIMAP_MASK);
		glBindTexture(GL_TEXTURE_2D, texMinimapMask);

		glBindVertexArray(plugin.vaoTri);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBindVertexArray(0);

		renderState.disable.set(GL_BLEND);
		renderState.apply();
	}

	private void initializeCacheFbo() {
		fboMinimapCache = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboMinimapCache);

		texMinimapCacheColor = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_MINIMAP_CACHE);
		glBindTexture(GL_TEXTURE_2D, texMinimapCacheColor);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, CACHE_PX, CACHE_PX, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texMinimapCacheColor, 0);

		rboMinimapCacheDepth = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboMinimapCacheDepth);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT32F, CACHE_PX, CACHE_PX);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboMinimapCacheDepth);

		glBindFramebuffer(GL_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));

		for (Zone[] row : cachedZoneRefs)
			Arrays.fill(row, null);
	}

	private void destroyCacheFbo() {
		if (texMinimapCacheColor != 0)
			glDeleteTextures(texMinimapCacheColor);
		texMinimapCacheColor = 0;

		if (rboMinimapCacheDepth != 0)
			glDeleteRenderbuffers(rboMinimapCacheDepth);
		rboMinimapCacheDepth = 0;

		if (fboMinimapCache != 0)
			glDeleteFramebuffers(fboMinimapCache);
		fboMinimapCache = 0;
	}

	private void ensureMaskTexture() {
		final int resizedState = client.isResized() ? 1 : 0;
		if (texMinimapMask != 0 && maskResizedState == resizedState)
			return;

		final int spriteId = resizedState == 1 ?
			SpriteID.RESIZEABLE_MODE_MINIMAP_ALPHA_MASK :
			SpriteID.FIXED_MODE_MINIMAP_ALPHA_MASK;
		final BufferedImage image = spriteManager.getSprite(spriteId, 0);
		if (image == null)
			return;

		final int width = image.getWidth();
		final int height = image.getHeight();
		final int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

		final IntBuffer src = BufferUtils.createIntBuffer(width * height);
		src.put(pixels).flip();
		final IntBuffer flipped = BufferUtils.createIntBuffer(width * height);
		for (int y = height - 1; y >= 0; y--)
			for (int x = 0; x < width; x++)
				flipped.put(src.get(y * width + x));
		flipped.flip();

		if (texMinimapMask == 0)
			texMinimapMask = glGenTextures();

		glActiveTexture(TEXTURE_UNIT_MINIMAP_MASK);
		glBindTexture(GL_TEXTURE_2D, texMinimapMask);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, flipped);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

		maskResizedState = resizedState;
	}

	private void destroyMask() {
		if (texMinimapMask != 0)
			glDeleteTextures(texMinimapMask);
		texMinimapMask = 0;
		maskResizedState = -1;
	}

	@Override
	public RenderPassType getType() { return RenderPassType.MINIMAP; }
}
