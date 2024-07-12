package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.ColorUtil;
import org.apache.commons.lang3.tuple.Pair;
import rs117.hd.HdPlugin;
import rs117.hd.data.materials.Material;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Vector;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneUploader.SCENE_OFFSET;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.clamp;

@Slf4j
@Singleton
public class TileInfoOverlay extends Overlay implements MouseListener, MouseWheelListener {
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	private int[] mousePos;
	private boolean ctrlHeld;
	private boolean ctrlToggled;
	private boolean shiftHeld;
	private float fontSize = 12;
	private float zoom = 1;

	private int mode;
	private int aabbMarkingStage;
	private AABB pendingSelection;
	private final ArrayList<AABB> selections = new ArrayList<>();
	private int selectedAabb = -1;
	private int hoveredAabb = -1;
	private final int[][] markedWorldPoints = new int[2][3];
	private int[] hoveredWorldPoint = new int[3];
	private int targetPlane = MAX_Z - 1;

	public TileInfoOverlay() {
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean activate) {
		if (activate) {
			overlayManager.add(this);
			mouseManager.registerMouseListener(this);
			mouseManager.registerMouseWheelListener(this);
		} else {
			overlayManager.remove(this);
			mouseManager.unregisterMouseListener(this);
			mouseManager.unregisterMouseWheelListener(this);
		}
		tileOverrideManager.setTrackReplacements(activate);
	}

	@Override
	public Dimension render(Graphics2D g) {
		// Disable the overlay while loading a scene, since tile overrides aren't thread safe
		if (plugin.isLoadingScene())
			return null;

		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return null;

		boolean ctrlPressed = client.isKeyPressed(KeyCode.KC_CONTROL);
		if (ctrlHeld != ctrlPressed) {
			ctrlHeld = ctrlPressed;
			if (ctrlPressed)
				ctrlToggled = !ctrlToggled;
		}
		boolean shiftPressed = client.isKeyPressed(KeyCode.KC_SHIFT);
		if (shiftHeld != shiftPressed) {
			shiftHeld = shiftPressed;
			if (shiftPressed)
				mode = (mode + 1) % 3;
		}

		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		Point canvasMousePos = client.getMouseCanvasPosition();
		mousePos = null;
		if (canvasMousePos != null && canvasMousePos.getX() != -1 && canvasMousePos.getY() != -1)
			mousePos = new int[] { canvasMousePos.getX(), canvasMousePos.getY() };

		int maxPlane = sceneContext.getPlane();
		int minPlane = 0;
		if (ctrlHeld)
			minPlane = maxPlane = targetPlane;

		switch (mode) {
			case 0:
				if (mousePos != null) {
					g.setFont(FontManager.getRunescapeFont());
					g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));

					tileLoop:
					for (int z = maxPlane; z >= minPlane; z--) {
						for (int isBridge = 1; isBridge >= 0; isBridge--) {
							for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
								for (int y = 0; y < EXTENDED_SCENE_SIZE; y++) {
									Tile tile = tiles[z][x][y];
									boolean shouldDraw = tile != null && (isBridge == 0 || tile.getBridge() != null);
									if (shouldDraw && drawTileInfo(g, sceneContext, tile)) {
										int tileZ = tile.getRenderLevel();
										hoveredWorldPoint = sceneContext.extendedSceneToWorld(x, y, tileZ);
										break tileLoop;
									}
								}
							}
						}
					}
				}
				break;
			case 1:
				drawAllIds(g, sceneContext);
				break;
			case 2:
				if (sceneContext.area != null) {
					int[] center = sceneContext.loadingPosition;

					g.setColor(Color.CYAN);
					g.setFont(FontManager.getRunescapeSmallFont());

					var poly = getCanvasTilePoly(client, sceneContext.scene, center);
					if (poly != null)
						g.drawPolygon(poly);

					String str = "Loading position";
					var lp = LocalPoint.fromWorld(sceneContext.scene, center[0], center[1]);
					if (lp != null) {
						var pos = Perspective.getCanvasTextLocation(client, g, lp, str, 0);
						if (pos != null)
							OverlayUtil.renderTextLocation(g, pos, str, g.getColor());
					}

					g.setFont(FontManager.getRunescapeFont());

					AABB[] aabbs = sceneContext.area.aabbs;
					if (mousePos != null) {
						hoveredAabb = -1;
						int[] v = new int[2];
						for (int i = 0; i < aabbs.length; i++) {
							var aabb = aabbs[i];
							var p = getAabbCanvasCenter(sceneContext, aabb);
							if (p != null) {
								Vector.subtract(v, mousePos, p);
								if (Vector.dot(v, v) < 50 * 50) {
									hoveredAabb = i;
									break;
								}
							}
						}
					}

					for (int i = 0; i < aabbs.length; i++) {
						if (i == hoveredAabb && i == selectedAabb) {
							g.setColor(Color.decode("#00ff00"));
						} else if (i == hoveredAabb) {
							g.setColor(Color.WHITE);
						} else if (i == selectedAabb) {
							g.setColor(Color.decode("#00c200"));
						} else {
							g.setColor(Color.GRAY);
						}
						drawAabb(g, sceneContext, aabbs[i]);
						drawAabbLabel(g, sceneContext, aabbs[i], sceneContext.area.name + "[" + i + "]");
					}
				}
				break;
		}

		if (mousePos != null) {
			tileLoop:
			for (int z = maxPlane; z >= minPlane; z--) {
				for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
					for (int y = 0; y < EXTENDED_SCENE_SIZE; y++) {
						var poly = getCanvasTilePoly(client, sceneContext.scene, x - SCENE_OFFSET, y - SCENE_OFFSET, z);
						if (poly != null && poly.contains(mousePos[0], mousePos[1])) {
							hoveredWorldPoint = sceneContext.extendedSceneToWorld(x, y, z);
							g.setColor(Color.CYAN);
							g.drawPolygon(poly);
							break tileLoop;
						}
					}
				}
			}
		}

		// Update second selection point each frame
		if (aabbMarkingStage == 1) {
			markedWorldPoints[1] = hoveredWorldPoint;
			pendingSelection = new AABB(markedWorldPoints[0], markedWorldPoints[1]);
		}

		for (int i = 0; i < selections.size(); i++) {
			var aabb = selections.get(i);
			// Draw selection boxes
			g.setColor(Color.YELLOW);
			drawAabb(g, sceneContext, aabb);
			g.setFont(FontManager.getRunescapeFont());
			drawAabbLabel(g, sceneContext, aabb, "Selection[" + i + "]");
		}

		if (pendingSelection != null) {
			// Draw current selection box
			g.setColor(Color.YELLOW);
			drawAabb(g, sceneContext, pendingSelection);
			g.setFont(FontManager.getRunescapeFont());
			drawAabbLabel(g, sceneContext, pendingSelection, "Selection[" + selections.size() + "]");
		}

		return null;
	}

	private boolean drawTileInfo(Graphics2D g, SceneContext sceneContext, Tile tile) {
		boolean infoDrawn = false;

		if (tile != null) {
			Rectangle rect = null;
			Polygon poly;

			Tile bridge = tile.getBridge();
			if (bridge != null) {
				poly = getCanvasTilePoly(client, sceneContext.scene, bridge);
				if (poly != null && poly.contains(mousePos[0], mousePos[1])) {
					rect = drawTileInfo(g, sceneContext, bridge, poly, null);
					if (rect != null) {
						infoDrawn = true;
					}
				}
			}

			poly = getCanvasTilePoly(client, sceneContext.scene, tile);
			if (poly != null && poly.contains(mousePos[0], mousePos[1])) {
				rect = drawTileInfo(g, sceneContext, tile, poly, rect);
				if (rect != null) {
					infoDrawn = true;
				}
			}
		}

		return infoDrawn;
	}

	private Rectangle drawTileInfo(Graphics2D g, SceneContext sceneContext, Tile tile, Polygon poly, Rectangle dodgeRect)
	{
		SceneTilePaint paint = tile.getSceneTilePaint();
		SceneTileModel model = tile.getSceneTileModel();

		if (!ctrlHeld && (paint == null || (paint.getNeColor() == 12345678 && tile.getBridge() == null)) && model == null)
			return null;

		ArrayList<String> lines = new ArrayList<>();

		if (tile.getBridge() != null)
			lines.add("Bridge");

		Scene scene = sceneContext.scene;
		int tileX = tile.getSceneLocation().getX();
		int tileY = tile.getSceneLocation().getY();
		int tileZ = tile.getRenderLevel();
		int tileExX = tileX + SCENE_OFFSET;
		int tileExY = tileY + SCENE_OFFSET;
		int[] worldPos = sceneContext.sceneToWorld(tileX, tileY, tileZ);

		lines.add("Scene point: " + tileX + ", " + tileY + ", " + tileZ);
		lines.add("World point: " + Arrays.toString(worldPos));
		lines.add(String.format(
			"Region ID: %d (%d, %d)",
			HDUtils.worldToRegionID(worldPos),
			worldPos[0] >> 6,
			worldPos[1] >> 6
		));

		int overlayId = scene.getOverlayIds()[tileZ][tileExX][tileExY];
		var overlay = tileOverrideManager.getOverrideBeforeReplacements(worldPos, OVERLAY_FLAG | overlayId);
		var replacementPath = new StringBuilder(overlay.toString());
		while (true) {
			var replacement = tileOverrideManager.resolveNextReplacement(overlay, tile);
			if (replacement == overlay)
				break;
			replacementPath.append("\n\t⤷ ").append(replacement);
			overlay = replacement;
		}
		lines.add(String.format("Overlay: ID %d -> %s", overlayId, replacementPath));
		lines.add("GroundMaterial: " + overlay.groundMaterial);

		int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];
		var underlay = tileOverrideManager.getOverrideBeforeReplacements(worldPos, underlayId);
		replacementPath = new StringBuilder(underlay.toString());
		while (true) {
			var replacement = tileOverrideManager.resolveNextReplacement(underlay, tile);
			if (replacement == underlay)
				break;
			replacementPath.append("\n\t⤷ ").append(replacement);
			underlay = replacement;
		}
		lines.add(String.format("Underlay: ID %d -> %s", underlayId, replacementPath));
		lines.add("GroundMaterial: " + underlay.groundMaterial);

		Color polyColor = Color.LIGHT_GRAY;
		if (paint != null)
		{
			polyColor = Color.CYAN;
			lines.add("Tile type: Paint");
			Material material = Material.fromVanillaTexture(paint.getTexture());
			lines.add(String.format("Material: %s (%d)", material.name(), paint.getTexture()));
			int[] hsl = new int[3];
			HDUtils.getSouthWesternMostTileColor(hsl, tile);
			lines.add(String.format("HSL: %s", Arrays.toString(hsl)));

			var override = tileOverrideManager.getOverride(scene, tile, worldPos, OVERLAY_FLAG | overlayId, underlayId);
			lines.add("WaterType: " + proceduralGenerator.seasonalWaterType(override, paint.getTexture()));
		}
		else if (model != null)
		{
			polyColor = Color.ORANGE;
			lines.add("Tile type: Model");
			lines.add(String.format("Face count: %d", model.getFaceX().length));

			HashSet<String> uniqueMaterials = new HashSet<>();
			int numChars = 0;
			if (model.getTriangleTextureId() != null)
			{
				for (int texture : model.getTriangleTextureId())
				{
					String material = String.format("%s (%d)", Material.fromVanillaTexture(texture).name(), texture);
					boolean unique = uniqueMaterials.add(material);
					if (unique)
					{
						numChars += material.length();
					}
				}
			}

			ArrayList<String> materials = new ArrayList<>(uniqueMaterials);
			Collections.sort(materials);

			if (materials.size() <= 1 || numChars < 26)
			{
				StringBuilder sb = new StringBuilder("Materials: { ");
				if (materials.isEmpty())
				{
					sb.append("null");
				}
				else
				{
					String prefix = "";
					for (String m : materials)
					{
						sb.append(prefix).append(m);
						prefix = ", ";
					}
				}
				sb.append(" }");
				lines.add(sb.toString());
			}
			else
			{
				Iterator<String> iter = materials.iterator();
				lines.add("Materials: { " + iter.next() + ",");
				while (iter.hasNext())
				{
					lines.add("\t  " + iter.next() + (iter.hasNext() ? "," : " }"));
				}
			}

			int[] hsl = new int[3];
			HDUtils.getSouthWesternMostTileColor(hsl, tile);
			lines.add(String.format("HSL: %s", Arrays.toString(hsl)));
		}

		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null) {
			lines.add(String.format(
				"Ground Object: ID=%s preori=%d",
				getIdAndImpostorId(groundObject, groundObject.getRenderable()),
				HDUtils.getBakedOrientation(groundObject.getConfig())
			));
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject != null) {
			lines.add(String.format(
				"Wall Object: ID=%s bakedOri=%d oriA=%d oriB=%d",
				getIdAndImpostorId(wallObject, wallObject.getRenderable1()),
				HDUtils.getBakedOrientation(wallObject.getConfig()),
				wallObject.getOrientationA(),
				wallObject.getOrientationB()
			));
		}

		GameObject[] gameObjects = tile.getGameObjects();
		for (GameObject gameObject : gameObjects) {
			if (gameObject == null)
				continue;
			int height = -1;
			int animationId = -1;
			var renderable = gameObject.getRenderable();
			if (renderable != null) {
				height = renderable.getModelHeight();
				if (renderable instanceof DynamicObject) {
					var anim = ((DynamicObject) renderable).getAnimation();
					if (anim != null)
						animationId = anim.getId();
				}
			}

			lines.add(String.format(
				"%s: ID=%s preori=%d ori=%d height=%d anim=%d",
				ModelHash.getTypeName(ModelHash.getType(gameObject.getHash())),
				getIdAndImpostorId(gameObject, renderable),
				HDUtils.getBakedOrientation(gameObject.getConfig()),
				gameObject.getModelOrientation(),
				height,
				animationId
			));
		}

		for (GraphicsObject graphicsObject : sceneContext.getWorldView().getGraphicsObjects()) {
			var lp = graphicsObject.getLocation();
			if (lp.getSceneX() == tileX && lp.getSceneY() == tileY)
				lines.add(String.format("Graphics Object: ID=%s", graphicsObject.getId()));
		}

		for (int i = 0; i < lines.size(); i++) {
			var moreLines = lines.get(i).split("\\n");
			if (moreLines.length > 1) {
				//noinspection SuspiciousListRemoveInLoop
				lines.remove(i);
				lines.addAll(i, List.of(moreLines));
			}
		}

		int padding = 4;
		int xPadding = padding * 2;
		FontMetrics fm = g.getFontMetrics();
		int lineHeight = fm.getHeight();
		int totalHeight = lineHeight * lines.size() + padding * 3;
		int space = fm.stringWidth(": ");
		int indent = fm.stringWidth("{ ");

		int leftWidth = 0;
		int rightWidth = 0;

		Function<String, Pair<String, String>> splitter = line ->
		{
			int i = line.indexOf(":");
			String left = line;
			String right = "";
			if (left.startsWith("\t"))
			{
				right = left;
				left = "";
			} else if (i != -1)
			{
				left = line.substring(0, i);
				right = line.substring(i + 1);
			}

			return Pair.of(left, right);
		};

		for (String line : lines)
		{
			Pair<String, String> pair = splitter.apply(line);
			if (pair.getRight().isEmpty())
			{
				int halfWidth = fm.stringWidth(pair.getLeft()) / 2;
				leftWidth = Math.max(leftWidth, halfWidth);
				rightWidth = Math.max(rightWidth, halfWidth);
			}
			else
			{
				leftWidth = Math.max(leftWidth, fm.stringWidth(pair.getLeft()));
				rightWidth = Math.max(rightWidth, fm.stringWidth(pair.getRight()));
			}
		}

		Rectangle2D polyBounds = poly.getBounds2D();
		Point tileCenter = new Point((int) polyBounds.getCenterX(), (int) polyBounds.getCenterY());

		var bounds = g.getClipBounds();

		int totalWidth = leftWidth + rightWidth + space + xPadding * 2;
		Rectangle rect = new Rectangle(
			clamp(tileCenter.getX() - totalWidth / 2, bounds.x, bounds.x + bounds.width - totalWidth),
			clamp(tileCenter.getY() - totalHeight - padding, bounds.y, bounds.y + bounds.height - totalHeight),
			totalWidth,
			totalHeight
		);
		if (dodgeRect != null && dodgeRect.intersects(rect))
		{
			// Avoid overlapping with other tile info
			rect.y = dodgeRect.y - rect.height - padding;
		}

		if (tile.getBridge() != null)
		{
			polyColor = Color.MAGENTA;
		}
		g.setColor(polyColor);
		g.drawPolygon(poly);

		g.setColor(new Color(0, 0, 0, 100));
		g.fillRect(rect.x, rect.y, rect.width, rect.height);

		int offsetY = 0;
		for (String line : lines)
		{
			Pair<String, String> pair = splitter.apply(line);
			offsetY += lineHeight;
			Point p;
			if (pair.getRight().isEmpty())
			{
				// centered
				p = new Point(
					rect.x + rect.width / 2 - fm.stringWidth(pair.getLeft()) / 2,
					rect.y + padding + offsetY);
			}
			else
			{
				// left & right
				p = new Point(
					rect.x + xPadding + leftWidth - fm.stringWidth(pair.getLeft()) + (pair.getRight().startsWith("\t") ? indent : 0),
					rect.y + padding + offsetY);
			}
			OverlayUtil.renderTextLocation(g, p, line, Color.WHITE);
		}

		return rect;
	}

	private String getIdAndImpostorId(TileObject object, @Nullable Renderable renderable) {
		int id = object.getId();
		int impostorId = getIdOrImpostorId(object, renderable);
		return id + (id == impostorId ? "" : " -> " + impostorId);
	}

	private int getIdOrImpostorId(TileObject object, @Nullable Renderable renderable) {
		return ModelHash.getUuidId(ModelHash.generateUuid(client, object.getHash(), renderable));
	}

	public static Polygon getCanvasTilePoly(@Nonnull Client client, Scene scene, Tile tile) {
		if (tile == null)
			return null;
		var l = tile.getSceneLocation();
		return getCanvasTilePoly(client, scene, l.getX(), l.getY(), tile.getPlane());
	}

	public static Polygon getCanvasTilePoly(@Nonnull Client client, Scene scene, int... sceneXYplane) {
		int tileExX = sceneXYplane[0] + SCENE_OFFSET;
		int tileExY = sceneXYplane[1] + SCENE_OFFSET;
		if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
			return null;

		final int swX = sceneXYplane[0] * LOCAL_TILE_SIZE - LOCAL_TILE_SIZE / 2;
		final int swY = sceneXYplane[1] * LOCAL_TILE_SIZE - LOCAL_TILE_SIZE / 2;
		final int neX = sceneXYplane[0] * LOCAL_TILE_SIZE + LOCAL_TILE_SIZE / 2;
		final int neY = sceneXYplane[1] * LOCAL_TILE_SIZE + LOCAL_TILE_SIZE / 2;

		final int swHeight = getHeight(scene, swX, swY, sceneXYplane[2]);
		final int nwHeight = getHeight(scene, neX, swY, sceneXYplane[2]);
		final int neHeight = getHeight(scene, neX, neY, sceneXYplane[2]);
		final int seHeight = getHeight(scene, swX, neY, sceneXYplane[2]);

		int[] p1 = localToCanvas(client, swX, swY, swHeight);
		int[] p2 = localToCanvas(client, neX, swY, nwHeight);
		int[] p3 = localToCanvas(client, neX, neY, neHeight);
		int[] p4 = localToCanvas(client, swX, neY, seHeight);

		if (p1 == null || p2 == null || p3 == null || p4 == null)
			return null;

		Polygon poly = new Polygon();
		poly.addPoint(p1[0], p1[1]);
		poly.addPoint(p2[0], p2[1]);
		poly.addPoint(p3[0], p3[1]);
		poly.addPoint(p4[0], p4[1]);

		return poly;
	}

	private static int getHeight(Scene scene, int localX, int localY, int plane) {
		int sceneX = (localX >> LOCAL_COORD_BITS) + SCENE_OFFSET;
		int sceneY = (localY >> LOCAL_COORD_BITS) + SCENE_OFFSET;
		if (sceneX < 0 || sceneY < 0 || sceneX >= EXTENDED_SCENE_SIZE || sceneY >= EXTENDED_SCENE_SIZE)
			return 0;

		int[][][] tileHeights = scene.getTileHeights();
		int x = localX & (LOCAL_TILE_SIZE - 1);
		int y = localY & (LOCAL_TILE_SIZE - 1);
		int var8 =
			x * tileHeights[plane][sceneX + 1][sceneY] + (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneX][sceneY] >> LOCAL_COORD_BITS;
		int var9 = tileHeights[plane][sceneX][sceneY + 1] * (LOCAL_TILE_SIZE - x) + x * tileHeights[plane][sceneX + 1][sceneY + 1]
				   >> LOCAL_COORD_BITS;
		return (LOCAL_TILE_SIZE - y) * var8 + y * var9 >> 7;
	}

	private static int[] localToCanvas(@Nonnull Client client, int x, int y, int z) {
		// Using longs to support coordinates much larger than normal local coordinates
		x -= client.getCameraX();
		y -= client.getCameraY();
		z -= client.getCameraZ();
		int cameraPitch = client.getCameraPitch();
		int cameraYaw = client.getCameraYaw();
		long pitchSin = SINE[cameraPitch];
		long pitchCos = COSINE[cameraPitch];
		long yawSin = SINE[cameraYaw];
		long yawCos = COSINE[cameraYaw];
		long x1 = x * yawCos + y * yawSin >> 16;
		long y1 = y * yawCos - x * yawSin >> 16;
		long y2 = z * pitchCos - y1 * pitchSin >> 16;
		long z1 = y1 * pitchCos + z * pitchSin >> 16;
		if (z1 >= 1) {
			long scale = client.getScale();
			long pointX = client.getViewportWidth() / 2 + x1 * scale / z1;
			long pointY = client.getViewportHeight() / 2 + y2 * scale / z1;
			return new int[] {
				(int) (pointX + client.getViewportXOffset()),
				(int) (pointY + client.getViewportYOffset()),
				(int) Math.min(Integer.MAX_VALUE, z1)
			};
		}

		return null;
	}

	private static String hslString(int color) {
		if (color == 12345678)
			return "HIDDEN";
		return color + " (" + (color >> 10 & 0x3F) + ", " + (color >> 7 & 7) + ", " + (color & 0x7F) + ")";
	}

	private void drawAllIds(Graphics2D g, SceneContext ctx) {
		zoom = client.get3dZoom() / 1000.f;
		if (zoom > 1.2f) {
			fontSize = Math.min(16, 11 * zoom);
		} else {
			fontSize = Math.max(7.8f, 14 * (float) Math.sqrt(zoom));
		}
		g.setFont(FontManager.getDefaultFont().deriveFont(fontSize));
		g.setColor(new Color(255, 255, 255, 127));

		Tile[][][] tiles = ctx.scene.getExtendedTiles();
		int plane = ctrlHeld ? MAX_Z - 1 : ctx.getPlane();
		for (int z = plane; z >= 0; z--) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; y++) {
					Tile tile = tiles[z][x][y];
					if (tile == null)
						continue;

					var lp = tile.getLocalLocation();
					int lines = 0;
					for (int isBridge = 1; isBridge >= 0; isBridge--) {
						var t = tile;
						if (isBridge == 1) {
							t = tile.getBridge();
							if (t == null)
								continue;
						}

						GroundObject groundObject = t.getGroundObject();
						if (groundObject != null)
							drawTileObjectInfo(g, lp, groundObject, groundObject.getRenderable(), lines++);

						WallObject wallObject = t.getWallObject();
						if (wallObject != null)
							drawTileObjectInfo(g, lp, wallObject, wallObject.getRenderable1(), lines++);

						for (GameObject gameObject : t.getGameObjects())
							if (gameObject != null)
								drawTileObjectInfo(g, lp, gameObject, gameObject.getRenderable(), lines++);
					}
				}
			}
		}
	}

	private void drawTileObjectInfo(Graphics2D g, LocalPoint lp, TileObject object, Renderable renderable, int line) {
		int type = ModelHash.getType(object.getHash());
		String str;
		if (zoom > 1.2f) {
			str = ModelHash.getTypeName(type) + ": " + getIdAndImpostorId(object, renderable);
		} else {
			str = ModelHash.getTypeNameShort(type) + ": " + getIdOrImpostorId(object, renderable);
		}
		var p = Perspective.getCanvasTextLocation(client, g, lp, str, object.getPlane() * 240);
		if (p == null)
			return;
		g.drawString(str, p.getX(), p.getY() + line * fontSize);
	}

	/**
	 * Draw line given local scene coordinates
	 */
	private void drawLine(Graphics2D g, int x1, int y1, int z1, int x2, int y2, int z2) {
		// Using longs to support coordinates much larger than normal local coordinates
		int cameraPitch = client.getCameraPitch();
		int cameraYaw = client.getCameraYaw();
		long pitchSin = SINE[cameraPitch];
		long pitchCos = COSINE[cameraPitch];
		long yawSin = SINE[cameraYaw];
		long yawCos = COSINE[cameraYaw];

		x1 -= client.getCameraX();
		y1 -= client.getCameraY();
		z1 -= client.getCameraZ();
		x2 -= client.getCameraX();
		y2 -= client.getCameraY();
		z2 -= client.getCameraZ();

		long ax = x1 * yawCos + y1 * yawSin >> 16;
		long aUnpitchedZ = y1 * yawCos - x1 * yawSin >> 16;
		long ay = z1 * pitchCos - aUnpitchedZ * pitchSin >> 16;
		long az = aUnpitchedZ * pitchCos + z1 * pitchSin >> 16;

		long bx = x2 * yawCos + y2 * yawSin >> 16;
		long bUnpitchedZ = y2 * yawCos - x2 * yawSin >> 16;
		long by = z2 * pitchCos - bUnpitchedZ * pitchSin >> 16;
		long bz = bUnpitchedZ * pitchCos + z2 * pitchSin >> 16;

		// Both behind the near plane
		if (az < 1 && bz < 1)
			return;

		long vx = bx - ax;
		long vy = by - ay;
		long vz = bz - az;

		if (az < 1) {
			// A is behind the near plane
			// az + (bz - az) * t = 1
			// t = (1 - az) / (bz - az)
			double t = (1.f - az) / vz;
			ax += (long) (vx * t);
			ay += (long) (vy * t);
			az = 1;
		} else if (bz < 1) {
			// B is behind the near plane
			double t = (1.f - bz) / vz;
			bx += (long) (vx * t);
			by += (long) (vy * t);
			bz = 1;
		}

		int scale = client.getScale();
		ax = client.getViewportXOffset() + client.getViewportWidth() / 2 + ax * scale / az;
		ay = client.getViewportYOffset() + client.getViewportHeight() / 2 + ay * scale / az;
		bx = client.getViewportXOffset() + client.getViewportWidth() / 2 + bx * scale / bz;
		by = client.getViewportYOffset() + client.getViewportHeight() / 2 + by * scale / bz;

		g.drawLine((int) ax, (int) ay, (int) bx, (int) by);
	}

	private void drawAabb(Graphics2D g, SceneContext ctx, AABB aabb) {
		int baseX = ctx.scene.getBaseX();
		int baseY = ctx.scene.getBaseY();

		int x1 = (aabb.minX - baseX) * LOCAL_TILE_SIZE;
		int y1 = (aabb.minY - baseY) * LOCAL_TILE_SIZE;
		int x2 = (aabb.maxX + 1 - baseX) * LOCAL_TILE_SIZE;
		int y2 = (aabb.maxY + 1 - baseY) * LOCAL_TILE_SIZE;
		int z1 = HDUtils.clamp(aabb.minZ, 0, MAX_Z - 1);
		int z2 = HDUtils.clamp(aabb.maxZ + 1, 0, MAX_Z - 1);

		int minZ = (int) HDUtils.min(
			getHeight(ctx.scene, x1, y1, z1),
			getHeight(ctx.scene, x1, y2, z1),
			getHeight(ctx.scene, x2, y2, z1),
			getHeight(ctx.scene, x2, y1, z1)
		);
		int maxZ = (int) HDUtils.max(
			getHeight(ctx.scene, x1, y1, z2),
			getHeight(ctx.scene, x1, y2, z2),
			getHeight(ctx.scene, x2, y2, z2),
			getHeight(ctx.scene, x2, y1, z2)
		);

		// Draw bottom rect
		drawLine(g, x1, y1, minZ, x1, y2, minZ);
		drawLine(g, x1, y2, minZ, x2, y2, minZ);
		drawLine(g, x2, y2, minZ, x2, y1, minZ);
		drawLine(g, x2, y1, minZ, x1, y1, minZ);

		// Draw top rect
		drawLine(g, x1, y1, maxZ, x1, y2, maxZ);
		drawLine(g, x1, y2, maxZ, x2, y2, maxZ);
		drawLine(g, x2, y2, maxZ, x2, y1, maxZ);
		drawLine(g, x2, y1, maxZ, x1, y1, maxZ);

		// Connect corners
		drawLine(g, x1, y1, minZ, x1, y1, maxZ);
		drawLine(g, x1, y2, minZ, x1, y2, maxZ);
		drawLine(g, x2, y2, minZ, x2, y2, maxZ);
		drawLine(g, x2, y1, minZ, x2, y1, maxZ);

//		var bsw = localToCanvas(client, x1, y1, minZ);
//		var bnw = localToCanvas(client, x1, y2, minZ);
//		var bne = localToCanvas(client, x2, y2, minZ);
//		var bse = localToCanvas(client, x2, y1, minZ);
//
//		// Draw bottom rect
//		if (bsw != null && bnw != null)
//			g.drawLine(bsw.getX(), bsw.getY(), bnw.getX(), bnw.getY());
//		if (bnw != null && bne != null)
//			g.drawLine(bnw.getX(), bnw.getY(), bne.getX(), bne.getY());
//		if (bne != null && bse != null)
//			g.drawLine(bne.getX(), bne.getY(), bse.getX(), bse.getY());
//		if (bse != null && bsw != null)
//			g.drawLine(bse.getX(), bse.getY(), bsw.getX(), bsw.getY());
//
//		if (aabb.isVolume()) {
//			var tsw = localToCanvas(client, x1, y1, maxZ);
//			var tnw = localToCanvas(client, x1, y2, maxZ);
//			var tne = localToCanvas(client, x2, y2, maxZ);
//			var tse = localToCanvas(client, x2, y1, maxZ);
//
//			// Draw top rect
//			if (tsw != null && tnw != null)
//				g.drawLine(tsw.getX(), tsw.getY(), tnw.getX(), tnw.getY());
//			if (tnw != null && tne != null)
//				g.drawLine(tnw.getX(), tnw.getY(), tne.getX(), tne.getY());
//			if (tne != null && tse != null)
//				g.drawLine(tne.getX(), tne.getY(), tse.getX(), tse.getY());
//			if (tse != null && tsw != null)
//				g.drawLine(tse.getX(), tse.getY(), tsw.getX(), tsw.getY());
//
//			// Connect rect corners
//			if (bsw != null && tsw != null)
//				g.drawLine(bsw.getX(), bsw.getY(), tsw.getX(), tsw.getY());
//			if (bnw != null && tnw != null)
//				g.drawLine(bnw.getX(), bnw.getY(), tnw.getX(), tnw.getY());
//			if (bne != null && tne != null)
//				g.drawLine(bne.getX(), bne.getY(), tne.getX(), tne.getY());
//			if (bse != null && tse != null)
//				g.drawLine(bse.getX(), bse.getY(), tse.getX(), tse.getY());
//		}
	}

	private int[] getAabbCanvasCenter(SceneContext ctx, AABB aabb) {
		int baseX = ctx.scene.getBaseX();
		int baseY = ctx.scene.getBaseY();

		int x1 = (aabb.minX - baseX) * LOCAL_TILE_SIZE;
		int y1 = (aabb.minY - baseY) * LOCAL_TILE_SIZE;
		int x2 = (aabb.maxX + 1 - baseX) * LOCAL_TILE_SIZE;
		int y2 = (aabb.maxY + 1 - baseY) * LOCAL_TILE_SIZE;
		int z1 = HDUtils.clamp(aabb.minZ, 0, MAX_Z - 1);
		int z2 = HDUtils.clamp(aabb.maxZ + 1, 0, MAX_Z - 1);

		int x = (x1 + x2) / 2;
		int y = (y1 + y2) / 2;
		int z = (getHeight(ctx.scene, x, y, z1) + getHeight(ctx.scene, x, y, z2)) / 2;

		return localToCanvas(client, x, y, z);
	}

	private void drawAabbLabel(Graphics2D g, SceneContext ctx, AABB aabb, @Nullable String label) {
		var p = getAabbCanvasCenter(ctx, aabb);
		if (p == null)
			return;

		String str = aabb.toString();
		if (label != null)
			str = label + "\n" + str;
		String[] lines = str.split("\\n");

		Color c = g.getColor();
		Font f = g.getFont();
		float fontSize = Math.max(8, 50.f * (float) Math.pow((float) client.getScale() / p[2], 1 / 1.5f));
		g.setFont(FontManager.getDefaultFont().deriveFont(fontSize));

		FontMetrics fm = g.getFontMetrics();
		int lineHeight = fm.getHeight();
		int totalHeight = lineHeight * lines.length;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int width = fm.stringWidth(line);
			int px = p[0] - width / 2;
			int py = p[1] - totalHeight / 2 + lineHeight * i;
			g.setColor(Color.BLACK);
			g.drawString(line, px + 1, py + 1);
			g.setColor(c);
			g.drawString(line, px, py);
		}
	}

	private void copyToClipboard(String toCopy) {
		copyToClipboard(toCopy, null);
	}

	private void copyToClipboard(String toCopy, @Nullable String description) {
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		StringSelection string = new StringSelection(toCopy);
		clipboard.setContents(string, null);
		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"117 HD",
			ColorUtil.wrapWithColorTag("[117 HD] " + (
				description == null ?
					"Copied to clipboard: " + toCopy :
					description
			), Color.GREEN),
			"117 HD"
		));
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return e;

		if (e.isAltDown()) {
			e.consume();

			if (sceneContext.area != null) {
				if (SwingUtilities.isLeftMouseButton(e)) {
					int nextSelection = -1;
					if (selectedAabb != hoveredAabb) {
						nextSelection = hoveredAabb;
						if (nextSelection != -1)
							copyToClipboard(sceneContext.area.aabbs[nextSelection].toArgs());
					}

					if (nextSelection != selectedAabb) {
						selectedAabb = nextSelection;
						e.consume();
						return e;
					}
				}
			}

			if (SwingUtilities.isRightMouseButton(e)) {
				// Reset selection
				aabbMarkingStage = 0;
				selections.clear();
				pendingSelection = null;
			} else if (SwingUtilities.isLeftMouseButton(e)) {
				if (aabbMarkingStage == 0) {
					// Marking first
					markedWorldPoints[0] = hoveredWorldPoint;
				} else {
					// Done marking
					selections.add(pendingSelection);

					if (selections.size() == 1) {
						copyToClipboard(pendingSelection.toArgs());
					} else {
						StringBuilder sb = new StringBuilder();
						for (int i = 0; i < selections.size(); i++) {
							if (i > 0)
								sb.append(",\n");
							sb.append(selections.get(i).toArgs());
						}
						copyToClipboard(sb.toString(), "Copied " + selections.size() + " AABBs to clipboard");
					}

					pendingSelection = null;
				}
				aabbMarkingStage = (aabbMarkingStage + 1) % 2;
			}
		}

		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) {
		return e;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		if (ctrlHeld) {
			e.consume();
			targetPlane = HDUtils.clamp(targetPlane + e.getWheelRotation(), 0, MAX_Z - 1);
		}

		return e;
	}
}
