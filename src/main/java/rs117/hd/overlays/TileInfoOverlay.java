package rs117.hd.overlays;

import com.google.inject.Inject;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.function.Function;
import javax.annotation.Nonnull;

import net.runelite.api.*;

import static net.runelite.api.Perspective.*;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static net.runelite.api.Perspective.SCENE_SIZE;

import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import org.apache.commons.lang3.tuple.Pair;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.Overlay;
import rs117.hd.data.materials.Underlay;
import rs117.hd.utils.HDUtils;

public class TileInfoOverlay extends net.runelite.client.ui.overlay.Overlay
{
	private final Client client;
	private Point mousePos;

	@Inject
	HdPluginConfig config;

	@Inject
	public TileInfoOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			mousePos = client.getMouseCanvasPosition();
		}

		if (mousePos != null && mousePos.getX() == -1 && mousePos.getY() == -1)
		{
			return null;
		}

		g.setFont(FontManager.getRunescapeFont());
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));

		Scene scene = client.getScene();
		Tile[][][] tiles = scene.getTiles();
		for (int plane = client.getPlane(); plane >= 0; plane--)
		{
			for (int isBridge = 1; isBridge >= 0; isBridge--)
			{
				for (int x = 0; x < SCENE_SIZE; x++)
				{
					for (int y = 0; y < SCENE_SIZE; y++)
					{
						Tile tile = tiles[plane][x][y];
						boolean shouldDraw = tile != null && (isBridge == 0 || tile.getBridge() != null);
						if (shouldDraw && drawTileInfo(g, tile))
						{
							return null;
						}
					}
				}
			}
		}

		return null;
	}

	private boolean drawTileInfo(Graphics2D g, Tile tile)
	{
		boolean infoDrawn = false;

		if (tile != null)
		{
			Rectangle rect = null;
			Polygon poly;

			Tile bridge = tile.getBridge();
			if (bridge != null)
			{
				poly = getCanvasTilePoly(client, bridge);
				if (poly != null && poly.contains(mousePos.getX(), mousePos.getY()))
				{
					rect = drawTileInfo(g, bridge, poly, rect);
					if (rect != null)
					{
						infoDrawn = true;
					}
				}
			}

			poly = getCanvasTilePoly(client, tile);
			if (poly != null && poly.contains(mousePos.getX(), mousePos.getY()))
			{
				rect = drawTileInfo(g, tile, poly, rect);
				if (rect != null)
				{
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

		if ((paint == null || (paint.getNeColor() == 12345678 && tile.getBridge() == null)) && model == null)
		{
			return null;
		}

		Rectangle2D polyBounds = poly.getBounds2D();
		Point tileCenter = new Point((int) polyBounds.getCenterX(), (int) polyBounds.getCenterY());

		ArrayList<String> lines = new ArrayList<>();

		if (tile.getBridge() != null)
		{
			lines.add("Bridge");
		}

		int x = tile.getSceneLocation().getX();
		int y = tile.getSceneLocation().getY();
		int plane = tile.getRenderLevel();

		WorldPoint worldPoint = tile.getWorldLocation();
		if (client.isInInstancedRegion()) {
			LocalPoint localPoint = tile.getLocalLocation();
			worldPoint = WorldPoint.fromLocalInstance(client, localPoint);
		}
		String worldPointInfo = "World point: " + worldPoint.getX() + ", " + worldPoint.getY() + ", " + worldPoint.getPlane();
		lines.add(worldPointInfo);

		Scene scene = client.getScene();
		int overlayId = scene.getOverlayIds()[plane][x][y];
		Overlay overlay = Overlay.getOverlay(overlayId, tile, client, config);
		lines.add(String.format("Overlay: %s (%d)", overlay.name(), overlayId));

		int underlayId = scene.getOverlayIds()[plane][x][y];
		Underlay underlay = Underlay.getUnderlay(underlayId, tile, client, config);
		lines.add(String.format("Underlay: %s (%d)", underlay.name(), underlayId));

		Color polyColor;
		if (paint != null)
		{
			// TODO: separate H, S and L to hopefully more easily match tiles that are different shades of the same hue
			polyColor = Color.CYAN;
			lines.add("Tile type: Paint");
			Material material = Material.getTexture(paint.getTexture());
			lines.add(String.format("Material: %s (%d)", material.name(), paint.getTexture()));
			lines.add("JagexHSL: ");
			lines.add("NW: " + paint.getNwColor());
			lines.add("NE: " + paint.getNeColor());
			lines.add("SE: " + paint.getSeColor());
			lines.add("SW: " + paint.getSwColor());
		}
		else
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
					String material = String.format("%s (%d)", Material.getTexture(texture).name(), texture);
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
					lines.add("" + face + ": " + a);
				} else {
					lines.add("" + face + ": [ " + a + ", " + b + ", " + c + " ]");
				}
			}
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
	 * @param tile the tile
	 * @return a polygon representing the tile
	 */
	public static Polygon getCanvasTilePoly(@Nonnull Client client, Tile tile)
	{
		LocalPoint lp = tile.getLocalLocation();
		int plane = tile.getRenderLevel();
		if (!lp.isInScene())
		{
			return null;
		}

		final int swX = lp.getX() - LOCAL_TILE_SIZE / 2;
		final int swY = lp.getY() - LOCAL_TILE_SIZE / 2;

		final int neX = lp.getX() + LOCAL_TILE_SIZE / 2;
		final int neY = lp.getY() + LOCAL_TILE_SIZE / 2;

		final int swHeight = getHeight(client, swX, swY, plane);
		final int nwHeight = getHeight(client, neX, swY, plane);
		final int neHeight = getHeight(client, neX, neY, plane);
		final int seHeight = getHeight(client, swX, neY, plane);

		Point p1 = localToCanvas(client, swX, swY, swHeight);
		Point p2 = localToCanvas(client, neX, swY, nwHeight);
		Point p3 = localToCanvas(client, neX, neY, neHeight);
		Point p4 = localToCanvas(client, swX, neY, seHeight);

		if (p1 == null || p2 == null || p3 == null || p4 == null)
		{
			return null;
		}

		Polygon poly = new Polygon();
		poly.addPoint(p1.getX(), p1.getY());
		poly.addPoint(p2.getX(), p2.getY());
		poly.addPoint(p3.getX(), p3.getY());
		poly.addPoint(p4.getX(), p4.getY());

		return poly;
	}

	private static int getHeight(@Nonnull Client client, int localX, int localY, int plane) {
		int sceneX = localX >> LOCAL_COORD_BITS;
		int sceneY = localY >> LOCAL_COORD_BITS;
		if (sceneX >= 0 && sceneY >= 0 && sceneX < SCENE_SIZE && sceneY < SCENE_SIZE) {
			int[][][] tileHeights = client.getTileHeights();
			int x = localX & (LOCAL_TILE_SIZE - 1);
			int y = localY & (LOCAL_TILE_SIZE - 1);
			int var8 = x * tileHeights[plane][sceneX + 1][sceneY] + (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneX][sceneY] >> LOCAL_COORD_BITS;
			int var9 = tileHeights[plane][sceneX][sceneY + 1] * (LOCAL_TILE_SIZE - x) + x * tileHeights[plane][sceneX + 1][sceneY + 1] >> LOCAL_COORD_BITS;
			return (LOCAL_TILE_SIZE - y) * var8 + y * var9 >> 7;
		}
		return 0;
	}
}
