package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.WorldPerspective;

@Slf4j
@Singleton
public class PolygonAreaWorldMapOverlay extends Overlay {
	private static final Color LINE_COLOR = new Color(0, 255, 255, 200); // Cyan for lines
	private static final Color DOT_COLOR = Color.YELLOW; // Yellow for dots

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	private boolean active;

	public PolygonAreaWorldMapOverlay() {
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean activate) {
		this.active = activate;
		if (activate) {
			overlayManager.add(this);
		} else {
			overlayManager.remove(this);
		}
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!active) {
			return null;
		}

		WorldPoint playerPos = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		if (playerPos == null) {
			return null;
		}

		// Find which polygon area the player is in
		Area currentArea = null;
		for (Area area : AreaManager.AREAS) {
			if (area.polygon != null && area.polygon.contains(playerPos)) {
				currentArea = area;
				break;
			}
		}

		// Draw only current area on world map
		if (currentArea != null && currentArea.polygon != null) {
			if (client.getWorldMap() != null && client.getWorldMap().getWorldMapRenderer().isLoaded()) {
				List<WorldPoint> worldPoints = convertIntArrayToWorldPoints(currentArea.polygon.getPoints());
				// Close the polygon
				if (!worldPoints.isEmpty()) {
					worldPoints.add(worldPoints.get(0));
				}
				createWorldMapLines(graphics, client, worldPoints, LINE_COLOR, currentArea.name);
			}
		}

		return null;
	}

	public static List<WorldPoint> convertIntArrayToWorldPoints(int[][] points) {
		List<WorldPoint> worldPoints = new ArrayList<>();

		for (int[] p : points) {
			worldPoints.add(new WorldPoint(p[0], p[1], 0));
		}

		return worldPoints;
	}

	public static void createWorldMapLines(Graphics2D graphics, Client client, List<WorldPoint> linePoints, Color color, String areaName) {
		Rectangle mapViewArea = WorldPerspective.getWorldMapClipArea(client);
		if (mapViewArea == null) {
			return;
		}

		// Get original points count (before closing the polygon)
		int pointCount = linePoints.size() - 1; // Subtract 1 because we added the first point at the end to close it
		if (pointCount <= 0) {
			pointCount = linePoints.size();
		}

		// Draw lines first
		for (int i = 0; i < linePoints.size() - 1; i++) {
			net.runelite.api.Point startPoint = WorldPerspective.mapWorldPointToGraphicsPoint(client, linePoints.get(i));
			net.runelite.api.Point endPoint = WorldPerspective.mapWorldPointToGraphicsPoint(client, linePoints.get(i + 1));

			renderWorldMapLine(graphics, client, mapViewArea, startPoint, endPoint, color);
		}

		// Then draw dots on top of lines
		for (int i = 0; i < pointCount; i++) {
			net.runelite.api.Point point = WorldPerspective.mapWorldPointToGraphicsPoint(client, linePoints.get(i));
			if (point != null && mapViewArea.contains(point.getX(), point.getY())) {
				drawYellowDot(graphics, point.getX(), point.getY());
			}
		}

		// Draw area name and point count text at polygon center
		drawPolygonText(graphics, client, linePoints, pointCount, mapViewArea, areaName);
	}

	private static void drawPolygonText(Graphics2D graphics, Client client, List<WorldPoint> linePoints, int pointCount, Rectangle mapViewArea, String areaName) {
		if (linePoints.isEmpty()) {
			return;
		}

		// Calculate center of polygon
		double centerX = 0;
		double centerY = 0;
		int validPoints = 0;

		for (int i = 0; i < linePoints.size() - 1; i++) {
			net.runelite.api.Point point = WorldPerspective.mapWorldPointToGraphicsPoint(client, linePoints.get(i));
			if (point != null && mapViewArea != null && mapViewArea.contains(point.getX(), point.getY())) {
				centerX += point.getX();
				centerY += point.getY();
				validPoints++;
			}
		}

		if (validPoints == 0) {
			return;
		}

		centerX /= validPoints;
		centerY /= validPoints;

		// Draw text
		graphics.setFont(net.runelite.client.ui.FontManager.getRunescapeFont());
		java.awt.FontMetrics fm = graphics.getFontMetrics();
		String line1 = areaName != null ? areaName : "Area";
		String line2 = pointCount + " points";
		int line1Width = fm.stringWidth(line1);
		int line2Width = fm.stringWidth(line2);
		int textWidth = Math.max(line1Width, line2Width);
		int textHeight = fm.getHeight() * 2; // Two lines
		int padding = 4;

		// Draw backdrop
		graphics.setColor(new Color(0, 0, 0, 150));
		graphics.fillRect((int)centerX - textWidth / 2 - padding, (int)centerY - textHeight / 2 - padding, textWidth + padding * 2, textHeight + padding * 2);

		// Draw text lines
		graphics.setColor(Color.WHITE);
		graphics.drawString(line1, (int)centerX - line1Width / 2, (int)centerY - textHeight / 4 + fm.getHeight() / 2);
		graphics.drawString(line2, (int)centerX - line2Width / 2, (int)centerY + textHeight / 4 + fm.getHeight() / 2);
	}

	public static void renderWorldMapLine(Graphics2D graphics, Client client, Rectangle mapViewArea, net.runelite.api.Point startPoint, net.runelite.api.Point endPoint, Color color) {
		if (mapViewArea == null || startPoint == null || endPoint == null) {
			return;
		}
		if (!mapViewArea.contains(startPoint.getX(), startPoint.getY()) && !mapViewArea.contains(endPoint.getX(), endPoint.getY())) {
			return;
		}

		Line2D.Double line = new Line2D.Double(startPoint.getX(), startPoint.getY(), endPoint.getX(), endPoint.getY());
		drawLine(graphics, line, color, WorldPerspective.getWorldMapClipArea(client));
	}

	public static void drawLine(Graphics2D graphics, Line2D.Double line, Color color, Rectangle clippingRegion) {
		graphics.setStroke(new BasicStroke(2));
		graphics.setClip(clippingRegion);
		graphics.setColor(color);
		graphics.draw(line);
	}

	public static void drawYellowDot(Graphics2D g, int x, int y) {
		g.setColor(DOT_COLOR);
		int dotSize = 8; // Bigger dots
		g.fillOval(x - dotSize / 2, y - dotSize / 2, dotSize, dotSize);
		// Add a border for better visibility
		g.setColor(Color.BLACK);
		g.setStroke(new BasicStroke(1));
		g.drawOval(x - dotSize / 2, y - dotSize / 2, dotSize, dotSize);
	}
}

