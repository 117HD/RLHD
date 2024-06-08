package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
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
import rs117.hd.utils.AABB;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;

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

	private Point mousePos;
	private boolean ctrlHeld;
	private boolean ctrlToggled;
	private boolean shiftHeld;
	private boolean shiftToggled;
	private float fontSize = 12;
	private float zoom = 1;

	private int aabbMarkingStage = 0;
	private final int[][] markedWorldPoints = new int[2][];
	private final int[] markedHeights = new int[2];
	private int[] hoveredWorldPoint = new int[3];
	private int hoveredHeight;
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
				shiftToggled = !shiftToggled;
		}

		if (shiftToggled) {
			drawAllIds(g, sceneContext);
			return null;
		}

		mousePos = client.getMouseCanvasPosition();
		if (mousePos == null || mousePos.getX() != -1 || mousePos.getY() != -1) {
			g.setFont(FontManager.getRunescapeFont());
			g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));

			Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
			var heights = sceneContext.scene.getTileHeights();
			int maxPlane = client.getPlane();
			int minPlane = 0;
			if (ctrlHeld)
				minPlane = maxPlane = targetPlane;
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
								hoveredHeight = heights[tileZ][x][y];
								break tileLoop;
							}
						}
					}
				}
			}
		}

		// Update second selection point each frame
		if (aabbMarkingStage == 1) {
			markedWorldPoints[1] = hoveredWorldPoint;
			markedHeights[1] = hoveredHeight;
		}

		// Draw selection box
		if (markedWorldPoints[0] != null) {
			g.setColor(Color.RED);
			int[] from = sceneContext.worldToLocal(markedWorldPoints[0]);
			int[] to = sceneContext.worldToLocal(markedWorldPoints[1]);
			int x1 = Math.min(from[0], to[0]);
			int y1 = Math.min(from[1], to[1]);
			int z1 = Math.min(markedHeights[0], markedHeights[1]);
			int x2 = Math.max(from[0], to[0]) + LOCAL_TILE_SIZE;
			int y2 = Math.max(from[1], to[1]) + LOCAL_TILE_SIZE;
			int z2 = Math.max(markedHeights[0], markedHeights[1]);
			var bsw = localToCanvas(client, x1, y1, z1);
			var bnw = localToCanvas(client, x1, y2, z1);
			var bne = localToCanvas(client, x2, y2, z1);
			var bse = localToCanvas(client, x2, y1, z1);
			var tsw = localToCanvas(client, x1, y1, z2);
			var tnw = localToCanvas(client, x1, y2, z2);
			var tne = localToCanvas(client, x2, y2, z2);
			var tse = localToCanvas(client, x2, y1, z2);
			// Draw bottom rect
			if (bsw != null && bnw != null)
				g.drawLine(bsw.getX(), bsw.getY(), bnw.getX(), bnw.getY());
			if (bnw != null && bne != null)
				g.drawLine(bnw.getX(), bnw.getY(), bne.getX(), bne.getY());
			if (bne != null && bse != null)
				g.drawLine(bne.getX(), bne.getY(), bse.getX(), bse.getY());
			if (bse != null && bsw != null)
				g.drawLine(bse.getX(), bse.getY(), bsw.getX(), bsw.getY());
			// Draw top rect
			if (tsw != null && tnw != null)
				g.drawLine(tsw.getX(), tsw.getY(), tnw.getX(), tnw.getY());
			if (tnw != null && tne != null)
				g.drawLine(tnw.getX(), tnw.getY(), tne.getX(), tne.getY());
			if (tne != null && tse != null)
				g.drawLine(tne.getX(), tne.getY(), tse.getX(), tse.getY());
			if (tse != null && tsw != null)
				g.drawLine(tse.getX(), tse.getY(), tsw.getX(), tsw.getY());
			// Connect rect corners
			if (bsw != null && tsw != null)
				g.drawLine(bsw.getX(), bsw.getY(), tsw.getX(), tsw.getY());
			if (bnw != null && tnw != null)
				g.drawLine(bnw.getX(), bnw.getY(), tnw.getX(), tnw.getY());
			if (bne != null && tne != null)
				g.drawLine(bne.getX(), bne.getY(), tne.getX(), tne.getY());
			if (bse != null && tse != null)
				g.drawLine(bse.getX(), bse.getY(), tse.getX(), tse.getY());
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
				if (poly != null && poly.contains(mousePos.getX(), mousePos.getY())) {
					rect = drawTileInfo(g, sceneContext, bridge, poly, null);
					if (rect != null) {
						infoDrawn = true;
					}
				}
			}

			poly = getCanvasTilePoly(client, sceneContext.scene, tile);
			if (poly != null && poly.contains(mousePos.getX(), mousePos.getY())) {
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
			var renderable = gameObject.getRenderable();
			if (renderable != null)
				height = renderable.getModelHeight();

			lines.add(String.format(
				"%s: ID=%s preori=%d ori=%d height=%d",
				ModelHash.getTypeName(ModelHash.getType(gameObject.getHash())),
				getIdAndImpostorId(gameObject, renderable),
				HDUtils.getBakedOrientation(gameObject.getConfig()),
				gameObject.getModelOrientation(),
				height
			));
		}

		for (GraphicsObject graphicsObject : client.getGraphicsObjects()) {
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

	/**
	 * Returns a polygon representing a tile.
	 *
	 * @param client the game client
	 * @param tile   the tile
	 * @return a polygon representing the tile
	 */
	public static Polygon getCanvasTilePoly(@Nonnull Client client, Scene scene, Tile tile) {
		LocalPoint lp = tile.getLocalLocation();
		int tileExX = lp.getSceneX() + SCENE_OFFSET;
		int tileExY = lp.getSceneY() + SCENE_OFFSET;
		int plane = tile.getRenderLevel();
		if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE) {
			return null;
		}

		final int swX = lp.getX() - LOCAL_TILE_SIZE / 2;
		final int swY = lp.getY() - LOCAL_TILE_SIZE / 2;

		final int neX = lp.getX() + LOCAL_TILE_SIZE / 2;
		final int neY = lp.getY() + LOCAL_TILE_SIZE / 2;

		final int swHeight = getHeight(scene, swX, swY, plane);
		final int nwHeight = getHeight(scene, neX, swY, plane);
		final int neHeight = getHeight(scene, neX, neY, plane);
		final int seHeight = getHeight(scene, swX, neY, plane);

		Point p1 = localToCanvas(client, swX, swY, swHeight);
		Point p2 = localToCanvas(client, neX, swY, nwHeight);
		Point p3 = localToCanvas(client, neX, neY, neHeight);
		Point p4 = localToCanvas(client, swX, neY, seHeight);

		if (p1 == null || p2 == null || p3 == null || p4 == null) {
			return null;
		}

		Polygon poly = new Polygon();
		poly.addPoint(p1.getX(), p1.getY());
		poly.addPoint(p2.getX(), p2.getY());
		poly.addPoint(p3.getX(), p3.getY());
		poly.addPoint(p4.getX(), p4.getY());

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

	private static Point localToCanvas(@Nonnull Client client, int x, int y, int z) {
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
			return new Point((int) (pointX + client.getViewportXOffset()), (int) (pointY + client.getViewportYOffset()));
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
		int plane = ctrlHeld ? MAX_Z - 1 : client.getPlane();
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

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (shiftToggled)
			return e;

		if (e.isAltDown()) {
			e.consume();

			if (SwingUtilities.isRightMouseButton(e)) {
				// Reset selection
				aabbMarkingStage = 0;
				markedWorldPoints[0] = null;
			} else if (SwingUtilities.isLeftMouseButton(e)) {
				if (aabbMarkingStage == 0) {
					// Marking first
					markedWorldPoints[0] = hoveredWorldPoint;
					markedHeights[0] = hoveredHeight;
				} else {
					// Done marking
					var markedAabb = new AABB(markedWorldPoints[0], markedWorldPoints[1]);
					Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
					StringSelection string = new StringSelection("new AABB(" + markedAabb.toArgs() + "),\n");
					clipboard.setContents(string, null);
					clientThread.invoke(() -> client.addChatMessage(
						ChatMessageType.GAMEMESSAGE,
						"117 HD",
						ColorUtil.wrapWithColorTag("[117 HD] Copied AABB to clipboard: " + markedAabb.toArgs(), Color.GREEN),
						"117 HD"
					));
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
		if (ctrlHeld && !shiftToggled) {
			e.consume();
			targetPlane = HDUtils.clamp(targetPlane + e.getWheelRotation(), 0, MAX_Z - 1);
		}

		return e;
	}
}
