package rs117.hd.renderer.zone.passes;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
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
import rs117.hd.opengl.shader.MinimapLineShaderProgram;
import rs117.hd.opengl.shader.MinimapSampleShaderProgram;
import rs117.hd.opengl.shader.MinimapShadedShaderProgram;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
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

	@Inject
	private MinimapShadedShaderProgram shadedProgram;

	@Inject
	private MinimapLineShaderProgram lineProgram;

	public final Camera cacheCamera = new Camera().setOrthographic(true).setReverseZ(true);

	private static final int SHADED_VERTEX_FLOATS_CAPACITY = 16384;
	private static final int LINE_FRAME_VERTEX_FLOATS_CAPACITY = 131072;
	private final CommandBuffer[] zoneCacheCmds = new CommandBuffer[MAX_ZONE_UPDATES_PER_FRAME];
	private final FloatBuffer[] pendingShadedVertices = new FloatBuffer[MAX_ZONE_UPDATES_PER_FRAME];
	private final int[] pendingShadedVertexCounts = new int[MAX_ZONE_UPDATES_PER_FRAME];
	private MinimapType pendingMode;
	private int glShadedVao, glShadedVbo;
	private FloatBuffer frameLineVertices;
	private int frameLineVertexCount;
	private int glLineVao, glLineVbo;
	private float lineFocusWorldX, lineFocusWorldZ;

	public final int[] viewportRect = new int[4];
	public boolean active;

	private MinimapType tileDrawerMode;

	private int fboWidth, fboHeight;

	private int fboMinimapCache, texMinimapCacheColor, rboMinimapCacheDepth;

	private int texMinimapMask;
	private int maskResizedState = -1;

	private WorldViewContext rootCtx;
	private final Zone[][] cachedZoneRefs = new Zone[SceneManager.NUM_ZONES][SceneManager.NUM_ZONES];
	private int windowOriginZx = -1, windowOriginZz = -1;
	private int lastPlane = -1;
	private MinimapType lastCachedMode;
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
			pendingShadedVertices[i] = BufferUtils.createFloatBuffer(SHADED_VERTEX_FLOATS_CAPACITY);
		}

		glShadedVao = glGenVertexArrays();
		glShadedVbo = glGenBuffers();
		glBindVertexArray(glShadedVao);
		glBindBuffer(GL_ARRAY_BUFFER, glShadedVbo);
		glBufferData(GL_ARRAY_BUFFER, (long) SHADED_VERTEX_FLOATS_CAPACITY * Float.BYTES, GL_DYNAMIC_DRAW);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
		glEnableVertexAttribArray(1);
		glBindVertexArray(0);

		frameLineVertices = BufferUtils.createFloatBuffer(LINE_FRAME_VERTEX_FLOATS_CAPACITY);

		glLineVao = glGenVertexArrays();
		glLineVbo = glGenBuffers();
		glBindVertexArray(glLineVao);
		glBindBuffer(GL_ARRAY_BUFFER, glLineVbo);
		glBufferData(GL_ARRAY_BUFFER, (long) LINE_FRAME_VERTEX_FLOATS_CAPACITY * Float.BYTES, GL_DYNAMIC_DRAW);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 10 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, 10 * Float.BYTES, 3L * Float.BYTES);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(2, 2, GL_FLOAT, false, 10 * Float.BYTES, 6L * Float.BYTES);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(3, 2, GL_FLOAT, false, 10 * Float.BYTES, 8L * Float.BYTES);
		glEnableVertexAttribArray(3);
		glBindVertexArray(0);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		minimapProgram.compile(includes);
		sampleProgram.compile(includes);
		shadedProgram.compile(includes);
		lineProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		minimapProgram.destroy();
		sampleProgram.destroy();
		shadedProgram.destroy();
		lineProgram.destroy();
	}

	@Override
	public void destroy() {
		client.setMinimapTileDrawer(null);
		tileDrawerMode = null;

		if (glShadedVbo != 0)
			glDeleteBuffers(glShadedVbo);
		glShadedVbo = 0;

		if (glShadedVao != 0)
			glDeleteVertexArrays(glShadedVao);
		glShadedVao = 0;

		if (glLineVbo != 0)
			glDeleteBuffers(glLineVbo);
		glLineVbo = 0;

		if (glLineVao != 0)
			glDeleteVertexArrays(glLineVao);
		glLineVao = 0;

		destroyCacheFbo();
		destroyMask();
	}

	@Override
	public int preprocess() {
		final MinimapType type = config.minimapType();
		registerTileDrawer(type);

		if (fboMinimapCache == 0)
			initializeCacheFbo();

		return PASS_ENABLED;
	}

	private void registerTileDrawer(MinimapType type) {
		if (type == tileDrawerMode)
			return;

		client.setMinimapTileDrawer(this::drawPlaceholderTile);
		tileDrawerMode = type;
	}

	private void drawPlaceholderTile(Tile tile, int tx, int ty, int px0, int py0, int px1, int py1) {
		client.getRasterizer().fillRectangle(px0, py0, px1 - px0, py1 - py0, PLACEHOLDER_COLOR);
	}

	private static int blendShade(int hsl, int shade) {
		shade = (hsl & 127) * shade >> 7;
		shade = Math.max(2, shade);
		shade = Math.min(126, shade);
		return (hsl & 0xFF80) + shade;
	}

	private int buildZoneVertices(int zx, int zz, WorldViewContext ctx, FloatBuffer out, boolean flat) {
		out.clear();

		final Tile[][][] tiles = ctx.sceneContext.scene.getExtendedTiles();
		final int sceneOffset = ctx.sceneContext.sceneOffset;
		final float tileSize = Perspective.LOCAL_TILE_SIZE;

		for (int xoff = 0; xoff < Constants.CHUNK_SIZE; xoff++) {
			final int tx = zx * Constants.CHUNK_SIZE + xoff;
			for (int zoff = 0; zoff < Constants.CHUNK_SIZE; zoff++) {
				final int tz = zz * Constants.CHUNK_SIZE + zoff;
				final Tile tile = tiles[lastPlane][tx][tz];
				if (tile == null)
					continue;

				final SceneTilePaint paint = tile.getSceneTilePaint();
				if (paint != null) {
					final float x = (tx - sceneOffset) * tileSize;
					final float z = (tz - sceneOffset) * tileSize;
					if (flat)
						emitFlatPaintTile(out, paint, x, z, tileSize);
					else
						emitShadedPaintTile(out, paint, x, z, tileSize);
					continue;
				}

				final SceneTileModel model = tile.getSceneTileModel();
				if (model != null) {
					if (flat)
						emitFlatModelTile(out, tile, model);
					else
						emitShadedModelTile(out, model);
				}
			}
		}

		out.flip();
		return out.limit() / 6;
	}

	private void buildVisibleLineVertices(WorldViewContext ctx, LocalPoint focus, float radiusWorld) {
		frameLineVertices.clear();

		final Tile[][][] tiles = ctx.sceneContext.scene.getExtendedTiles();
		final Tile[][] planeTiles = tiles[lastPlane];
		final int sceneOffset = ctx.sceneContext.sceneOffset;
		final float tileSize = Perspective.LOCAL_TILE_SIZE;

		final int maxTx = planeTiles.length;
		final int maxTz = maxTx > 0 ? planeTiles[0].length : 0;
		final int centerTx = (int) Math.floor(focus.getX() / tileSize) + sceneOffset;
		final int centerTz = (int) Math.floor(focus.getY() / tileSize) + sceneOffset;
		final int tileRadius = (int) Math.ceil(radiusWorld / tileSize) + 1;

		final int txStart = Math.max(0, centerTx - tileRadius);
		final int txEnd = Math.min(maxTx, centerTx + tileRadius + 1);
		final int tzStart = Math.max(0, centerTz - tileRadius);
		final int tzEnd = Math.min(maxTz, centerTz + tileRadius + 1);

		for (int tx = txStart; tx < txEnd; tx++) {
			for (int tz = tzStart; tz < tzEnd; tz++) {
				final Tile tile = planeTiles[tx][tz];
				if (tile == null)
					continue;

				final float x = (tx - sceneOffset) * tileSize;
				final float z = (tz - sceneOffset) * tileSize;
				emitWallLines(frameLineVertices, tile, x, z, tileSize);
			}
		}

		frameLineVertices.flip();
		frameLineVertexCount = frameLineVertices.limit() / 10;
	}

	private static final float LINE_UNIT = Perspective.LOCAL_TILE_SIZE / 16f * 4f;
	private static final float LINE_HALF = LINE_UNIT / 2f;
	private static final float SEAM_OVERLAP = 2f;
	private static final float MIN_LINE_HALF_THICKNESS_PX = 0.9f;
	private static final float[] WALL_COLOR_RED = { 238 / 255f, 0f, 0f };
	private static final float[] WALL_COLOR_WHITE = { 238 / 255f, 238 / 255f, 238 / 255f };

	private void emitWallLines(FloatBuffer out, Tile tile, float x, float z, float span) {
		final WallObject wall = tile.getWallObject();
		if (wall != null) {
			final int config = wall.getConfig();
			final int shape = config & 31;
			final int rotation = config >> 6 & 3;
			final float[] color = (wall.getHash() >>> 19 & 1L) == 0L ? WALL_COLOR_RED : WALL_COLOR_WHITE;

			if (shape == 0 || shape == 2)
				emitWallEdge(out, rotation, x, z, span, color);
			if (shape == 2)
				emitWallEdge(out, (rotation + 1) % 4, x, z, span, color);
			if (shape == 3)
				emitWallCorner(out, rotation, x, z, span, color);
		}

		for (GameObject gameObject : tile.getGameObjects()) {
			if (gameObject == null || !gameObject.getSceneMinLocation().equals(tile.getSceneLocation()))
				continue;

			final int config = gameObject.getConfig();
			if ((config & 31) == 9) {
				final int rotation = config >> 6 & 3;
				final float[] color = (gameObject.getHash() >>> 19 & 1L) == 0L ? WALL_COLOR_RED : WALL_COLOR_WHITE;
				emitDiagonal(out, rotation, x, z, span, color);
			}
		}

		final DecorativeObject decor = tile.getDecorativeObject();
		if (decor != null) {
			final int config = decor.getConfig();
			if ((config & 31) == 9) {
				final int rotation = config >> 6 & 3;
				final float[] color = (decor.getHash() >>> 19 & 1L) == 0L ? WALL_COLOR_RED : WALL_COLOR_WHITE;
				emitDiagonal(out, rotation, x, z, span, color);
			}
		}
	}

	private void emitWallEdge(FloatBuffer out, int rotation, float x, float z, float span, float[] color) {
		switch (rotation) {
			case 0:
				emitLine(out, x + LINE_HALF, z, x + LINE_HALF, z + span, LINE_UNIT, color);
				break;
			case 1:
				emitLine(out, x, z + span - LINE_HALF, x + span, z + span - LINE_HALF, LINE_UNIT, color);
				break;
			case 2:
				emitLine(out, x + span - LINE_HALF, z, x + span - LINE_HALF, z + span, LINE_UNIT, color);
				break;
			default:
				emitLine(out, x, z + LINE_HALF, x + span, z + LINE_HALF, LINE_UNIT, color);
				break;
		}
	}

	private void emitWallCorner(FloatBuffer out, int rotation, float x, float z, float span, float[] color) {
		final boolean west = rotation == 0 || rotation == 3;
		final boolean north = rotation < 2;
		final float cx = west ? x : x + span - LINE_UNIT;
		final float cz = north ? z + span - LINE_UNIT : z;
		emitRect(out, cx, cz, cx + LINE_UNIT, cz + LINE_UNIT, color);
	}

	private static void emitDiagonal(FloatBuffer out, int rotation, float x, float z, float span, float[] color) {
		if (rotation == 1 || rotation == 3)
			emitLine(out, x, z + span, x + span, z, LINE_UNIT, color);
		else
			emitLine(out, x, z, x + span, z + span, LINE_UNIT, color);
	}

	private static void emitLine(FloatBuffer out, float x0, float z0, float x1, float z1, float thickness, float[] color) {
		final float dx = x1 - x0;
		final float dz = z1 - z0;
		final float len = (float) Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-4f)
			return;

		final float ux = dx / len, uz = dz / len;
		final float ex0 = x0 - ux * SEAM_OVERLAP, ez0 = z0 - uz * SEAM_OVERLAP;
		final float ex1 = x1 + ux * SEAM_OVERLAP, ez1 = z1 + uz * SEAM_OVERLAP;

		final float nx = -dz / len * (thickness / 2f);
		final float nz = dx / len * (thickness / 2f);

		putLineVertex(out, ex0, ez0, color, 0.5f, 0f, nx, nz);
		putLineVertex(out, ex0, ez0, color, 0.5f, 1f, -nx, -nz);
		putLineVertex(out, ex1, ez1, color, 0.5f, 0f, nx, nz);

		putLineVertex(out, ex0, ez0, color, 0.5f, 1f, -nx, -nz);
		putLineVertex(out, ex1, ez1, color, 0.5f, 1f, -nx, -nz);
		putLineVertex(out, ex1, ez1, color, 0.5f, 0f, nx, nz);
	}

	private static void emitRect(FloatBuffer out, float x0, float z0, float x1, float z1, float[] color) {
		putLineVertex(out, x0, z0, color, 0, 0);
		putLineVertex(out, x0, z1, color, 0, 1);
		putLineVertex(out, x1, z1, color, 1, 1);

		putLineVertex(out, x0, z0, color, 0, 0);
		putLineVertex(out, x1, z1, color, 1, 1);
		putLineVertex(out, x1, z0, color, 1, 0);
	}

	private static void putLineVertex(FloatBuffer out, float x, float z, float[] rgb, float u, float v) {
		putLineVertex(out, x, z, rgb, u, v, 0, 0);
	}

	private static void putLineVertex(FloatBuffer out, float x, float z, float[] rgb, float u, float v, float ox, float oz) {
		if (out.remaining() < 10)
			return;
		out.put(x).put(0).put(z).put(rgb[0]).put(rgb[1]).put(rgb[2]).put(u).put(v).put(ox).put(oz);
	}

	private void emitFlatPaintTile(FloatBuffer out, SceneTilePaint paint, float x, float z, float span) {
		final int color = paint.getRBG();
		if (color == 0)
			return;

		final float[] rgb = ColorUtils.srgb(color);

		putVertex(out, x, 0, z, rgb);
		putVertex(out, x, 0, z + span, rgb);
		putVertex(out, x + span, 0, z + span, rgb);

		putVertex(out, x, 0, z, rgb);
		putVertex(out, x + span, 0, z + span, rgb);
		putVertex(out, x + span, 0, z, rgb);
	}

	private void emitFlatModelTile(FloatBuffer out, Tile tile, SceneTileModel model) {
		final int overlay = model.getModelOverlay();
		final int underlay = model.getModelUnderlay();
		final int[] vertexX = model.getVertexX();
		final int[] vertexZ = model.getVertexZ();
		final int[] faceA = model.getFaceX();
		final int[] faceB = model.getFaceY();
		final int[] faceC = model.getFaceZ();

		for (int face = 0; face < faceA.length; face++) {
			final int color = ProceduralGenerator.isOverlayFace(tile, face) ? overlay : underlay;
			if (color == 0)
				continue;

			final float[] rgb = ColorUtils.srgb(color);
			final int idx1 = faceA[face];
			final int idx2 = faceB[face];
			final int idx3 = faceC[face];

			putVertex(out, vertexX[idx1], 0, vertexZ[idx1], rgb);
			putVertex(out, vertexX[idx2], 0, vertexZ[idx2], rgb);
			putVertex(out, vertexX[idx3], 0, vertexZ[idx3], rgb);
		}
	}

	private void emitShadedPaintTile(FloatBuffer out, SceneTilePaint paint, float x, float z, float span) {
		final int tex = paint.getTexture();
		final float[] nwRgb, neRgb, swRgb, seRgb;
		if (tex == -1) {
			final int nw = paint.getNwColor();
			if (nw == PLACEHOLDER_COLOR) {
				final float[] flat = ColorUtils.packedHslToSrgb(paint.getRBG());
				nwRgb = flat;
				neRgb = flat;
				swRgb = flat;
				seRgb = flat;
			} else {
				nwRgb = ColorUtils.packedHslToSrgb(nw);
				neRgb = ColorUtils.packedHslToSrgb(paint.getNeColor());
				swRgb = ColorUtils.packedHslToSrgb(paint.getSwColor());
				seRgb = ColorUtils.packedHslToSrgb(paint.getSeColor());
			}
		} else {
			final int hsl = client.getTextureProvider().getDefaultColor(tex);
			nwRgb = ColorUtils.packedHslToSrgb(blendShade(hsl, paint.getNwColor()));
			neRgb = ColorUtils.packedHslToSrgb(blendShade(hsl, paint.getNeColor()));
			swRgb = ColorUtils.packedHslToSrgb(blendShade(hsl, paint.getSwColor()));
			seRgb = ColorUtils.packedHslToSrgb(blendShade(hsl, paint.getSeColor()));
		}

		putVertex(out, x, 0, z, swRgb);
		putVertex(out, x, 0, z + span, nwRgb);
		putVertex(out, x + span, 0, z + span, neRgb);

		putVertex(out, x, 0, z, swRgb);
		putVertex(out, x + span, 0, z + span, neRgb);
		putVertex(out, x + span, 0, z, seRgb);
	}

	private void emitShadedModelTile(FloatBuffer out, SceneTileModel model) {
		final int[] vertexX = model.getVertexX();
		final int[] vertexZ = model.getVertexZ();
		final int[] faceA = model.getFaceX();
		final int[] faceB = model.getFaceY();
		final int[] faceC = model.getFaceZ();
		final int[] colorA = model.getTriangleColorA();
		final int[] colorB = model.getTriangleColorB();
		final int[] colorC = model.getTriangleColorC();
		final int[] textures = model.getTriangleTextureId();

		for (int face = 0; face < faceA.length; face++) {
			final int idx1 = faceA[face];
			final int idx2 = faceB[face];
			final int idx3 = faceC[face];
			int c1 = colorA[face];
			int c2 = colorB[face];
			int c3 = colorC[face];

			if (textures != null && textures[face] != -1) {
				final int hsl = client.getTextureProvider().getDefaultColor(textures[face]);
				c1 = blendShade(hsl, c1);
				c2 = blendShade(hsl, c2);
				c3 = blendShade(hsl, c3);
			} else if (c1 == PLACEHOLDER_COLOR) {
				continue;
			}

			putVertex(out, vertexX[idx1], 0, vertexZ[idx1], ColorUtils.packedHslToSrgb(c1));
			putVertex(out, vertexX[idx2], 0, vertexZ[idx2], ColorUtils.packedHslToSrgb(c2));
			putVertex(out, vertexX[idx3], 0, vertexZ[idx3], ColorUtils.packedHslToSrgb(c3));
		}
	}

	private static void putVertex(FloatBuffer out, float x, float y, float z, float[] rgb) {
		if (out.remaining() < 6)
			return;
		out.put(x).put(y).put(z).put(rgb[0]).put(rgb[1]).put(rgb[2]);
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

		final MinimapType type = config.minimapType();
		if (type != lastCachedMode) {
			lastCachedMode = type;
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

		if (type != MinimapType.HD || config.minimapShowLines()) {
			lineFocusWorldX = focus.getX();
			lineFocusWorldZ = focus.getY();
			final float halfDiagPx = 0.5f * (float) Math.sqrt((double) fboWidth * fboWidth + (double) fboHeight * fboHeight);
			buildVisibleLineVertices(ctx, focus, halfDiagPx * displayPixelToWorld * 1.1f);
		} else {
			frameLineVertexCount = 0;
		}

		active = true;
	}

	private void invalidateCache() {
		for (Zone[] row : cachedZoneRefs)
			Arrays.fill(row, null);
	}

	private boolean environmentChanged() {
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

		pendingMode = config.minimapType();

		for (int[] entry : dirty) {
			if (pendingCount >= MAX_ZONE_UPDATES_PER_FRAME)
				break;

			final int zx = entry[0];
			final int zz = entry[1];
			final Zone zone = ctx.zones[zx][zz];
			if (!zone.initialized)
				continue;

			positionCacheCamera(zx, zz, ctx);

			if (pendingMode == MinimapType.SHADED || pendingMode == MinimapType.VANILLA) {
				pendingShadedVertexCounts[pendingCount] = buildZoneVertices(
					zx, zz, ctx, pendingShadedVertices[pendingCount], pendingMode == MinimapType.VANILLA
				);
			} else {
				final CommandBuffer cmd = zoneCacheCmds[pendingCount];
				cmd.reset();

				if (zone.sizeO != 0)
					zone.renderFloorLevel(cmd, lastPlane);
				if (!zone.visibleAlphaModels.isEmpty()) {
					final int offset = ctx.sceneContext.sceneOffset >> 3;
					zone.renderAlpha(cmd, zx - offset, zz - offset, lastPlane, ctx, null, false, false);
				}
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
		drawLines(renderState);
	}

	private void drawZoneCacheUpdates(RenderState renderState) {
		if (pendingCount == 0)
			return;

		final boolean shaded = pendingMode == MinimapType.SHADED || pendingMode == MinimapType.VANILLA;

		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, fboMinimapCache);
		renderState.disable.set(GL_MULTISAMPLE);
		renderState.enable.set(GL_SCISSOR_TEST);

		if (shaded) {
			shadedProgram.use();
			renderState.disable.set(GL_DEPTH_TEST);
			renderState.disable.set(GL_CULL_FACE);
			renderState.disable.set(GL_BLEND);
			renderState.apply();
			glBindVertexArray(glShadedVao);
			glBindBuffer(GL_ARRAY_BUFFER, glShadedVbo);
		} else {
			minimapProgram.use();
			renderState.enable.set(GL_DEPTH_TEST);
			renderState.enable.set(GL_CULL_FACE);
			renderState.enable.set(GL_BLEND);
			renderState.depthFunc.set(GL_GEQUAL);
			renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
			if (renderer.indirectDrawCmds != null)
				renderState.ido.set(renderer.indirectDrawCmds.id);
		}

		for (int i = 0; i < pendingCount; i++) {
			final int zx = pendingZx[i];
			final int zz = pendingZz[i];
			final int cellX = (zx - windowOriginZx) * ZONE_PX;
			final int cellY = (zz - windowOriginZz) * ZONE_PX;

			renderState.viewport.set(cellX, cellY, ZONE_PX, ZONE_PX);
			renderState.apply();
			glScissor(cellX, cellY, ZONE_PX, ZONE_PX);

			glDrawBuffer(GL_COLOR_ATTACHMENT0);
			glClearColor(0, 0, 0, 1);
			if (!shaded)
				glClearDepth(0);
			glClear(GL_COLOR_BUFFER_BIT | (shaded ? 0 : GL_DEPTH_BUFFER_BIT));

			positionCacheCamera(zx, zz, rootCtx);

			if (shaded) {
				final int vertexCount = pendingShadedVertexCounts[i];
				if (vertexCount > 0) {
					shadedProgram.uniViewProjMatrix.set(cacheCamera.getViewProjMatrix());
					glBufferSubData(GL_ARRAY_BUFFER, 0, pendingShadedVertices[i]);
					glDrawArrays(GL_TRIANGLES, 0, vertexCount);
				}
			} else {
				plugin.uboGlobal.sceneCamera.write(cacheCamera);
				plugin.uboGlobal.upload();
				zoneCacheCmds[i].execute(renderState);
			}
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

	private void drawLines(RenderState renderState) {
		if (!active || texMinimapMask == 0 || frameLineVertexCount == 0)
			return;

		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));
		renderState.disable.set(GL_SCISSOR_TEST);
		renderState.disable.set(GL_MULTISAMPLE);
		renderState.viewport.set(viewportRect[0], viewportRect[1], viewportRect[2], viewportRect[3]);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.enable.set(GL_BLEND);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		renderState.apply();

		lineProgram.use();
		lineProgram.uniFocusWorld.set(lineFocusWorldX, lineFocusWorldZ);
		lineProgram.uniCosYaw.set(displayCosYaw);
		lineProgram.uniSinYaw.set(displaySinYaw);
		lineProgram.uniPixelToWorld.set(displayPixelToWorld);
		lineProgram.uniFboSize.set((float) fboWidth, (float) fboHeight);
		lineProgram.uniMinHalfThicknessPx.set(MIN_LINE_HALF_THICKNESS_PX);

		glActiveTexture(TEXTURE_UNIT_MINIMAP_MASK);
		glBindTexture(GL_TEXTURE_2D, texMinimapMask);

		glBindVertexArray(glLineVao);
		glBindBuffer(GL_ARRAY_BUFFER, glLineVbo);
		glBufferSubData(GL_ARRAY_BUFFER, 0, frameLineVertices);
		glDrawArrays(GL_TRIANGLES, 0, frameLineVertexCount);

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
