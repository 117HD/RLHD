package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
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
public class PolygonAreaInGameOverlay extends Overlay {
	private static final Color POLYGON_LINE_COLOR = new Color(0, 255, 255, 200); // Cyan for lines
	private static final Color CURRENT_AREA_LINE_COLOR = new Color(0, 255, 0, 200); // Green for current area lines
	private static final Color DOT_COLOR = Color.YELLOW; // Yellow for dots
	private static final Color TEXT_COLOR = Color.WHITE;
	private static final Color TEXT_BACKDROP = new Color(0, 0, 0, 150);

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	private boolean active;

	public PolygonAreaInGameOverlay() {
		setLayer(OverlayLayer.ABOVE_SCENE);
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

		Area currentArea = null;
		for (Area area : AreaManager.AREAS) {
			if (area.polygon != null && area.polygon.contains(playerPos)) {
				currentArea = area;
				break;
			}
		}


		// Draw all polygon areas on minimap (world view)
		for (Area area : AreaManager.AREAS) {
			if (area.polygon == null) {
				continue;
			}
			List<WorldPoint> points = convertIntArrayToWorldPoints(area.polygon.getPoints());
			int pointCount = points.size();
			// Close the polygon
			if (!points.isEmpty()) {
				points.add(points.get(0));
			}
			Color lineColor = (area == currentArea) ? CURRENT_AREA_LINE_COLOR : POLYGON_LINE_COLOR;
			
			// Draw lines first
			drawLinesOnWorld(graphics, client, points, lineColor, 0);
			
			// Then draw dots on top
			drawPolygonDots(graphics, client, points, 0, pointCount);
			
			// Draw area name and point count text inside polygon (only for current area)
			if (area == currentArea) {
				drawPolygonText(graphics, client, points, pointCount, 0, area.name);
			}
		}

		return null;
	}

	public static void drawLinesOnWorld(Graphics2D graphics, Client client, List<WorldPoint> linePoints, Color color, int z) {
		if (linePoints == null || linePoints.size() < 2) {
			return;
		}

		for (int i = 0; i < linePoints.size() - 1; i++) {
			WorldPoint startWp = linePoints.get(i);
			WorldPoint endWp = linePoints.get(i + 1);

			if (startWp == null || endWp == null) continue;
			if (startWp.equals(new WorldPoint(0, 0, 0))) continue;
			if (endWp.equals(new WorldPoint(0, 0, 0))) continue;
			if (startWp.getPlane() != endWp.getPlane()) continue;

			List<WorldPoint> interpolated = interpolateLine(startWp, endWp);
			if (interpolated.isEmpty()) continue;

			for (int j = 0; j < interpolated.size() - 1; j++) {
				WorldPoint wp1 = interpolated.get(j);
				WorldPoint wp2 = interpolated.get(j + 1);

				List<net.runelite.api.Point> points1 = WorldPerspective.worldToCanvasWithOffset(client, wp1, z);
				List<net.runelite.api.Point> points2 = WorldPerspective.worldToCanvasWithOffset(client, wp2, z);

				if (points1.isEmpty() || points2.isEmpty()) {
					continue;
				}

				net.runelite.api.Point p1 = points1.get(0);
				net.runelite.api.Point p2 = points2.get(0);

				if (p1 == null || p2 == null) {
					continue;
				}

				graphics.setColor(color);
				graphics.setStroke(new BasicStroke(2f));
				graphics.drawLine(p1.getX(), p1.getY(), p2.getX(), p2.getY());
			}
		}
	}

	private static List<WorldPoint> interpolateLine(WorldPoint start, WorldPoint end) {
		List<WorldPoint> result = new ArrayList<>();

		int steps = Math.max(start.distanceTo(end), 1);

		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;

			int x = (int) Math.round(lerp((double)start.getX(), (double)end.getX(), t));
			int y = (int) Math.round(lerp((double)start.getY(), (double)end.getY(), t));
			int plane = start.getPlane();

			result.add(new WorldPoint(x, y, plane));
		}

		return result;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	public static List<WorldPoint> convertIntArrayToWorldPoints(int[][] points) {
		List<WorldPoint> worldPoints = new ArrayList<>();

		for (int[] p : points) {
			worldPoints.add(new WorldPoint(p[0], p[1], 0));
		}

		return worldPoints;
	}


	private void drawPolygonDots(Graphics2D graphics, Client client, List<WorldPoint> points, int z, int pointCount) {
		// Only draw dots for original points (not the closing point)
		for (int i = 0; i < pointCount && i < points.size(); i++) {
			WorldPoint wp = points.get(i);
			List<net.runelite.api.Point> canvasPoints = WorldPerspective.worldToCanvasWithOffset(client, wp, z);
			if (!canvasPoints.isEmpty()) {
				net.runelite.api.Point p = canvasPoints.get(0);
				if (p != null) {
					drawYellowDot(graphics, p.getX(), p.getY());
				}
			}
		}
	}

	private void drawPolygonText(Graphics2D graphics, Client client, List<WorldPoint> points, int pointCount, int z, String areaName) {
		if (points.isEmpty() || pointCount <= 0) {
			return;
		}

		// Calculate center of polygon in world coordinates
		double centerWorldX = 0;
		double centerWorldY = 0;
		int validPoints = 0;

		for (int i = 0; i < pointCount && i < points.size(); i++) {
			WorldPoint wp = points.get(i);
			centerWorldX += wp.getX();
			centerWorldY += wp.getY();
			validPoints++;
		}

		if (validPoints == 0) {
			return;
		}

		centerWorldX /= validPoints;
		centerWorldY /= validPoints;

		// Convert center to canvas coordinates
		WorldPoint centerWorldPoint = new WorldPoint((int)centerWorldX, (int)centerWorldY, points.get(0).getPlane());
		List<net.runelite.api.Point> canvasPoints = WorldPerspective.worldToCanvasWithOffset(client, centerWorldPoint, z);

		if (canvasPoints.isEmpty()) {
			return;
		}

		net.runelite.api.Point centerCanvas = canvasPoints.get(0);
		if (centerCanvas == null) {
			return;
		}

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
		graphics.setColor(TEXT_BACKDROP);
		graphics.fillRect(centerCanvas.getX() - textWidth / 2 - padding, centerCanvas.getY() - textHeight / 2 - padding, textWidth + padding * 2, textHeight + padding * 2);

		// Draw text lines
		graphics.setColor(TEXT_COLOR);
		graphics.drawString(line1, centerCanvas.getX() - line1Width / 2, centerCanvas.getY() - textHeight / 4 + fm.getHeight() / 2);
		graphics.drawString(line2, centerCanvas.getX() - line2Width / 2, centerCanvas.getY() + textHeight / 4 + fm.getHeight() / 2);
	}

	private void drawYellowDot(Graphics2D g, int x, int y) {
		g.setColor(DOT_COLOR);
		int dotSize = 8; // Bigger dots
		g.fillOval(x - dotSize / 2, y - dotSize / 2, dotSize, dotSize);
		// Add a border for better visibility
		g.setColor(Color.BLACK);
		g.setStroke(new BasicStroke(1));
		g.drawOval(x - dotSize / 2, y - dotSize / 2, dotSize, dotSize);
	}
}

