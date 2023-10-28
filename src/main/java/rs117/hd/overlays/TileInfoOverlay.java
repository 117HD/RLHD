package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.function.Function;
import javax.annotation.Nonnull;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import org.apache.commons.lang3.tuple.Pair;
import rs117.hd.HdPlugin;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.Overlay;
import rs117.hd.data.materials.Underlay;
import rs117.hd.scene.SceneUploader;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;

@Singleton
public class TileInfoOverlay extends net.runelite.client.ui.overlay.Overlay {
	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	private Point mousePos;
	private boolean ctrlPressed;

	@Inject
	public TileInfoOverlay() {
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	public void setActive(boolean active) {
		if (active)
			overlayManager.add(this);
		else
			overlayManager.remove(this);
	}

	@Override
	public Dimension render(Graphics2D g) {
		ctrlPressed = client.isKeyPressed(KeyCode.KC_CONTROL);
		mousePos = client.getMouseCanvasPosition();
		if (mousePos != null && mousePos.getX() == -1 && mousePos.getY() == -1) {
			return null;
		}

		g.setFont(FontManager.getRunescapeFont());
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));

		Scene scene = client.getScene();
		Tile[][][] tiles = scene.getExtendedTiles();
		int plane = ctrlPressed ? MAX_Z - 1 : client.getPlane();
		for (int z = plane; z >= 0; z--) {
			for (int isBridge = 1; isBridge >= 0; isBridge--) {
				for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
					for (int y = 0; y < EXTENDED_SCENE_SIZE; y++) {
						Tile tile = tiles[z][x][y];
						boolean shouldDraw = tile != null && (isBridge == 0 || tile.getBridge() != null);
						if (shouldDraw && drawTileInfo(g, scene, tile)) {
							return null;
						}
					}
				}
			}
		}

		ctrlPressed = true;
		for (int z = plane; z >= 0; z--) {
			for (int isBridge = 1; isBridge >= 0; isBridge--) {
				for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
					for (int y = 0; y < EXTENDED_SCENE_SIZE; y++) {
						Tile tile = tiles[z][x][y];
						boolean shouldDraw = tile != null && (isBridge == 0 || tile.getBridge() != null);
						if (shouldDraw && drawTileInfo(g, scene, tile)) {
							return null;
						}
					}
				}
			}
		}

		return null;
	}

	private boolean drawTileInfo(Graphics2D g, Scene scene, Tile tile) {
		boolean infoDrawn = false;

		if (tile != null) {
			Rectangle rect = null;
			Polygon poly;

			Tile bridge = tile.getBridge();
			if (bridge != null) {
				poly = getCanvasTilePoly(client, scene, bridge);
				if (poly != null && poly.contains(mousePos.getX(), mousePos.getY())) {
					rect = drawTileInfo(g, bridge, poly, null);
					if (rect != null) {
						infoDrawn = true;
					}
				}
			}

			poly = getCanvasTilePoly(client, scene, tile);
			if (poly != null && poly.contains(mousePos.getX(), mousePos.getY())) {
				rect = drawTileInfo(g, tile, poly, rect);
				if (rect != null) {
					infoDrawn = true;
				}
			}
		}

		return infoDrawn;
	}

	private Rectangle drawTileInfo(Graphics2D g, Tile tile, Polygon poly, Rectangle dodgeRect)
	{
		SceneTilePaint paint = tile.getSceneTilePaint();
		SceneTileModel model = tile.getSceneTileModel();

		if (!ctrlPressed && (paint == null || (paint.getNeColor() == 12345678 && tile.getBridge() == null)) && model == null)
		{
			return null;
		}

		Rectangle2D polyBounds = poly.getBounds2D();
		Point tileCenter = new Point((int) polyBounds.getCenterX(), (int) polyBounds.getCenterY());

		ArrayList<String> lines = new ArrayList<>();

		if (tile.getBridge() != null) {
			lines.add("Bridge");
		}

		int tileX = tile.getSceneLocation().getX();
		int tileY = tile.getSceneLocation().getY();
		int tileExX = tileX + SceneUploader.SCENE_OFFSET;
		int tileExY = tileY + SceneUploader.SCENE_OFFSET;
		int plane = tile.getRenderLevel();

		lines.add("Scene point: " + tileX + ", " + tileY + ", " + plane);

		int[] worldPoint = null;
		var sceneContext = plugin.getSceneContext();
		if (sceneContext != null) {
			worldPoint = sceneContext.sceneToWorld(tileX, tileY, plane);
		}
		if (worldPoint != null) {
			lines.add("World point: " + Arrays.toString(worldPoint));
			lines.add("Region ID: " + HDUtils.worldToRegionID(worldPoint));
		}

		Scene scene = client.getScene();
		short overlayId = scene.getOverlayIds()[plane][tileExX][tileExY];
		Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
		lines.add(String.format("Overlay: %s (%d)", overlay.name(), overlayId));

		short underlayId = scene.getUnderlayIds()[plane][tileExX][tileExY];
		Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
		lines.add(String.format("Underlay: %s (%d)", underlay.name(), underlayId));

		Color polyColor = Color.LIGHT_GRAY;
		if (paint != null)
		{
			// TODO: separate H, S and L to hopefully more easily match tiles that are different shades of the same hue
			polyColor = Color.CYAN;
			lines.add("Tile type: Paint");
			Material material = Material.fromVanillaTexture(paint.getTexture());
			lines.add(String.format("Material: %s (%d)", material.name(), paint.getTexture()));
			lines.add("JagexHSL: ");
			lines.add("NW: " + (paint.getNwColor() == 12345678 ? "HIDDEN" : paint.getNwColor()));
			lines.add("NE: " + (paint.getNeColor() == 12345678 ? "HIDDEN" : paint.getNeColor()));
			lines.add("SE: " + (paint.getSeColor() == 12345678 ? "HIDDEN" : paint.getSeColor()));
			lines.add("SW: " + (paint.getSwColor() == 12345678 ? "HIDDEN" : paint.getSwColor()));
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
				if (materials.size() == 0)
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

			lines.add("JagexHSL: ");
			int[] CA = model.getTriangleColorA();
			int[] CB = model.getTriangleColorB();
			int[] CC = model.getTriangleColorC();
			for (int face = 0; face < model.getFaceX().length; face++)
			{
				int a = CA[face];
				int b = CB[face];
				int c = CC[face];
				if (a == b && b == c) {
					lines.add(face + ": " + (a == 12345678 ? "HIDDEN" : a));
				} else {
					lines.add(
						face + ": [ " +
						(a == 12345678 ? "HIDDEN" : a) + ", " +
						(b == 12345678 ? "HIDDEN" : b) + ", " +
						(c == 12345678 ? "HIDDEN" : c) +
						" ]");
				}
			}
		}

		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null) {
			lines.add(String.format(
				"Ground Object: ID=%d x=%d y=%d ori=%d",
				groundObject.getId(),
				ModelHash.getSceneX(groundObject.getHash()),
				ModelHash.getSceneY(groundObject.getHash()),
				HDUtils.getBakedOrientation(groundObject.getConfig())
			));
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject != null) {
			lines.add(String.format(
				"Wall Object: ID=%d x=%d y=%d bakedOri=%d oriA=%d oriB=%d",
				wallObject.getId(),
				ModelHash.getSceneX(wallObject.getHash()),
				ModelHash.getSceneY(wallObject.getHash()),
				HDUtils.getBakedOrientation(wallObject.getConfig()),
				wallObject.getOrientationA(),
				wallObject.getOrientationB()
			));
		}

		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects.length > 0) {
			int counter = 0;
			for (GameObject gameObject : gameObjects) {
				if (gameObject == null)
					continue;
				counter++;
				lines.add(String.format(
					"ID %d: x=%d y=%d bakedOri=%d ori=%d",
					gameObject.getId(),
					ModelHash.getSceneX(gameObject.getHash()),
					ModelHash.getSceneY(gameObject.getHash()),
					HDUtils.getBakedOrientation(gameObject.getConfig()),
					gameObject.getModelOrientation()
				));
			}
			if (counter > 0)
				lines.add(lines.size() - counter, "Game objects: ");
		}

		int padding = 4;
		int xPadding = padding * 2;
		FontMetrics fm = g.getFontMetrics();
		int lineHeight = fm.getHeight();
		int totalHeight = lineHeight * lines.size() + padding * 3;
		int space = fm.charWidth(':');
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
			if (pair.getRight().length() == 0)
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

		int totalWidth = leftWidth + rightWidth + space + xPadding * 2;
		Rectangle rect = new Rectangle(
			tileCenter.getX() - totalWidth / 2,
			tileCenter.getY() - totalHeight - padding, totalWidth, totalHeight);
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

		g.setColor(new Color(0, 0, 0, 150));
		g.fillRect(rect.x, rect.y, rect.width, rect.height);

		int offsetY = 0;
		for (String line : lines)
		{
			Pair<String, String> pair = splitter.apply(line);
			offsetY += lineHeight;
			Point p;
			if (pair.getRight().length() == 0)
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

	/**
	 * Returns a polygon representing a tile.
	 *
	 * @param client the game client
	 * @param tile   the tile
	 * @return a polygon representing the tile
	 */
	public static Polygon getCanvasTilePoly(@Nonnull Client client, Scene scene, Tile tile) {
		LocalPoint lp = tile.getLocalLocation();
		int tileExX = lp.getSceneX() + SceneUploader.SCENE_OFFSET;
		int tileExY = lp.getSceneY() + SceneUploader.SCENE_OFFSET;
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
		int sceneX = (localX >> LOCAL_COORD_BITS) + SceneUploader.SCENE_OFFSET;
		int sceneY = (localY >> LOCAL_COORD_BITS) + SceneUploader.SCENE_OFFSET;
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
		int pitchSin = SINE[cameraPitch];
		int pitchCos = COSINE[cameraPitch];
		int yawSin = SINE[cameraYaw];
		int yawCos = COSINE[cameraYaw];
		int x1 = x * yawCos + y * yawSin >> 16;
		int y1 = y * yawCos - x * yawSin >> 16;
		int y2 = z * pitchCos - y1 * pitchSin >> 16;
		int z1 = y1 * pitchCos + z * pitchSin >> 16;
		if (z1 >= 50) {
			int scale = client.getScale();
			int pointX = client.getViewportWidth() / 2 + x1 * scale / z1;
			int pointY = client.getViewportHeight() / 2 + y2 * scale / z1;
			return new Point(pointX + client.getViewportXOffset(), pointY + client.getViewportYOffset());
		}

		return null;
	}
}
