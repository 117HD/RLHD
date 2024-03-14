package rs117.hd.overlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import org.apache.commons.lang3.NotImplementedException;
import rs117.hd.HdPlugin;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightType;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.Vector;

import static rs117.hd.HdPlugin.NEAR_PLANE;

@Slf4j
@Singleton
public class LightGizmoOverlay extends Overlay implements MouseListener, KeyListener {
	private static final Color ORANGE = Color.decode("#ff9f2c");

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private LightManager lightManager;

	private Light selected;
	private Light hovered;

	private boolean hideInvisibleLights = true;
	private boolean hideRadiusRings = true;
	private boolean hideAnimLights;
	private boolean hideLabels;
	private boolean hideInfo;
	private boolean toggleBlackColor;
	private boolean liveInfo = false;

	public LightGizmoOverlay() {
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean activate) {
		if (activate) {
			overlayManager.add(this);
			mouseManager.registerMouseListener(this);
			keyManager.registerKeyListener(this);
		} else {
			overlayManager.remove(this);
			mouseManager.unregisterMouseListener(this);
			keyManager.unregisterKeyListener(this);
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return null;

		Point mousePos = client.getMouseCanvasPosition();
		if (mousePos != null && (mousePos.getX() == -1 || mousePos.getY() == -1))
			mousePos = null;

		g.setFont(FontManager.getRunescapeSmallFont());

		final int innerDotDiameter = 6;
		final int innerHandleRingDiameter = 19;
		final int outerHandleRingDiameter = 25;
		final int hoverDistanceMargin = 5;

		Stroke thinLine = new BasicStroke(1);
		Stroke thinnerLine = new BasicStroke(.75f);
		Stroke thinDashedLine = new BasicStroke(
			1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
			0, new float[] { 3 }, 0
		);
		Stroke thinLongDashedLine = new BasicStroke(
			1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
			0, new float[] { 10 }, 0
		);

		float[] projectionMatrix = Mat4.identity();
		int viewportWidth = client.getViewportWidth();
		int viewportHeight = client.getViewportHeight();
		Mat4.mul(projectionMatrix, Mat4.translate(client.getViewportXOffset(), client.getViewportYOffset(), 0));
		Mat4.mul(projectionMatrix, Mat4.scale(viewportWidth, viewportHeight, 1));
		Mat4.mul(projectionMatrix, Mat4.scale(.5f, -.5f, .5f));
		Mat4.mul(projectionMatrix, Mat4.translate(1, -1, 1));
		// NDC clip space
		Mat4.mul(projectionMatrix, Mat4.scale(client.getScale(), client.getScale(), 1));
		Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, NEAR_PLANE));
		Mat4.mul(projectionMatrix, Mat4.rotateX(plugin.cameraOrientation[1] - (float) Math.PI));
		Mat4.mul(projectionMatrix, Mat4.rotateY(plugin.cameraOrientation[0]));
		Mat4.mul(projectionMatrix, Mat4.translate(
			-plugin.cameraPosition[0],
			-plugin.cameraPosition[1],
			-plugin.cameraPosition[2]
		));

		hovered = null;
		final float[] lightToCamera = new float[3];
		for (Light l : sceneContext.lights) {
			if (hideInvisibleLights && !l.def.visibleFromOtherPlanes &&
				(l.plane < client.getPlane() && l.belowFloor || l.plane > client.getPlane() && l.aboveFloor))
				continue;

			if (hideAnimLights && !l.def.animationIds.isEmpty() && !l.visible)
				continue;

			float[] lightPos = new float[] { l.x, l.y, l.z, 1 };
			float[] point = projectPoint(projectionMatrix, lightPos);
			if (point[3] <= 0)
				continue;
			int x = Math.round(point[0]);
			int y = Math.round(point[1]);

			Vector.subtract(lightToCamera, plugin.cameraPosition, lightPos);
			float distanceFromCamera = Vector.length(lightToCamera);

			// Take perspective depth into account
			int currentDiameter = Math.round(l.radius * 2 / distanceFromCamera * client.getScale());
			float definedDiameter = l.def.radius * 2 / distanceFromCamera * client.getScale();
			float fRange = l.def.range / 100f;
			int minDiameter = Math.round(definedDiameter * (1 - fRange));
			int maxDiameter = Math.round(definedDiameter * (1 + fRange));

			if (mousePos != null && hovered == null) {
				float d = Vector.length(mousePos.getX() - x, mousePos.getY() - y);
				if (d <= outerHandleRingDiameter / 2f + hoverDistanceMargin ||
					!hideRadiusRings && Math.abs(d - currentDiameter / 2f) < hoverDistanceMargin * 2)
					hovered = l;
			}

			boolean isHovered = hovered == l;
			boolean isSelected = selected == l;

			int mainOpacity = l.visible ? 255 : 100;
			int rangeOpacity = 70;
			Color baseColor = toggleBlackColor ? Color.BLACK : Color.WHITE;
			Color radiusRingColor = alpha(baseColor, mainOpacity);
			Color rangeRingsColor = alpha(baseColor, rangeOpacity);
			Color handleRingsColor = radiusRingColor;
			Color textColor = alpha(Color.WHITE, mainOpacity);

			if (isSelected) {
				handleRingsColor = radiusRingColor = rangeRingsColor = ORANGE;
				textColor = Color.WHITE;
			} else if (isHovered) {
				radiusRingColor = Color.YELLOW;
			}

			// Draw handle rings
			drawRing(g, x, y, innerHandleRingDiameter, handleRingsColor, thinDashedLine);
			drawRing(g, x, y, outerHandleRingDiameter, handleRingsColor, thinDashedLine);

			// Draw radius rings
			if (!hideRadiusRings) {
				drawRing(g, x, y, currentDiameter, radiusRingColor, thinnerLine);
				if (l.def.type == LightType.PULSE && Math.abs(currentDiameter) > .001f) {
					drawRing(g, x, y, minDiameter, rangeRingsColor, thinLongDashedLine);
					drawRing(g, x, y, maxDiameter, rangeRingsColor, thinLongDashedLine);
				}
			}

			// Only the selected dot has a filled dot in the center
			if (isSelected) {
				fillOutlinedCircle(g, x, y, innerDotDiameter, ORANGE, handleRingsColor, thinLine);
			} else {
				drawCircleOutline(g, x, y, innerDotDiameter, handleRingsColor, thinLine);
			}

			g.setColor(textColor);
			if (!hideLabels || isSelected) {
				String info = l.def.description;
				if (isSelected && !hideInfo) {
					info += String.format("\nradius: %d", liveInfo ? l.radius : l.def.radius);
					info += String.format("\nstrength: %.1f", liveInfo ? l.strength : l.def.strength);
				}
				drawAlignedString(g, info, x, y + 25, TextAlignment.CENTER_ON_COLONS);
			}
		}

		return null;
	}

	private void fillCircle(Graphics2D g, int centerX, int centerY, int diameter, Color color) {
		int r = diameter / 2;
		g.setColor(color);
		g.fillOval(centerX - r, centerY - r, diameter, diameter);
	}

	private void drawRing(Graphics2D g, int centerX, int centerY, int diameter, Color strokeColor, Stroke stroke) {
		// Round down to an odd number
		diameter = (int) Math.ceil(diameter / 2.f) * 2 - 1;
		int r = (int) Math.ceil(diameter / 2.f);
		g.setColor(strokeColor);
		g.setStroke(stroke);
		g.drawOval(centerX - r, centerY - r, diameter, diameter);
	}

	private void fillOutlinedCircle(
		Graphics2D g, int centerX, int centerY, int diameter, Color fillColor, Color strokeColor, Stroke stroke
	) {
		fillCircle(g, centerX, centerY, diameter - 2, fillColor);
		drawCircleOutline(g, centerX, centerY, diameter, strokeColor, stroke);
	}

	private void drawCircleOutline(
		Graphics2D g, int centerX, int centerY, int diameter, Color strokeColor, Stroke stroke
	) {
		int r = (int) Math.ceil(diameter / 2.f);
		int s = diameter - 1;
		g.setColor(strokeColor);
		g.setStroke(stroke);
		g.drawRoundRect(centerX - r, centerY - r, s, s, s - 1, s - 1);
	}

	private enum TextAlignment {
		LEFT, RIGHT, CENTER, CENTER_ON_COLONS
	}

	private void drawCenteredString(Graphics g, String text, int centerX, int centerY, TextAlignment alignment) {
		drawCenteredString(g, text.split("\\n"), centerX, centerY, alignment);
	}

	private void drawCenteredString(Graphics g, String[] lines, int centerX, int centerY, TextAlignment alignment) {
		FontMetrics metrics = g.getFontMetrics();
		int yOffset = metrics.getAscent() - (lines.length * metrics.getHeight()) / 2;
		drawAlignedString(g, lines, centerX, centerY + yOffset, alignment);
	}

	private void drawAlignedString(Graphics g, String text, int centerX, int topY, TextAlignment alignment) {
		drawAlignedString(g, text.split("\\n"), centerX, topY, alignment);
	}

	private void drawAlignedString(Graphics g, String[] lines, int centerX, int topY, TextAlignment alignment) {
		var color = g.getColor();
		var shadow = alpha(Color.BLACK, color.getAlpha());
		FontMetrics metrics = g.getFontMetrics();
		int fontHeight = metrics.getHeight();
		int yOffset = 0;

		if (alignment == TextAlignment.CENTER_ON_COLONS) {
			int longestLeft = 0, longestRight = 0;
			for (String line : lines) {
				int dotIndex = line.indexOf(":");
				String left, right;
				if (dotIndex == -1) {
					left = line;
					right = "";
				} else {
					left = line.substring(0, dotIndex);
					right = line.substring(dotIndex + 1);
				}
				int leftLen = metrics.stringWidth(left);
				if (leftLen > longestLeft) {
					longestLeft = leftLen;
				}
				int rightLen = metrics.stringWidth(right);
				if (rightLen > longestRight) {
					longestRight = rightLen;
				}
			}

			int dotOffset = -metrics.stringWidth(":") / 2;

			for (String line : lines) {
				int dotIndex = line.indexOf(":");
				int xOffset = dotOffset;
				if (dotIndex == -1) {
					xOffset -= metrics.stringWidth(line) / 2;
				} else {
					xOffset -= metrics.stringWidth(line.substring(0, dotIndex));
				}
				g.setColor(shadow);
				g.drawString(line, centerX + xOffset + 1, topY + yOffset + 1);
				g.setColor(color);
				g.drawString(line, centerX + xOffset, topY + yOffset);
				yOffset += fontHeight;
			}
		} else {
			int longestLine = 0;
			if (alignment != TextAlignment.CENTER) {
				for (String line : lines) {
					int length = metrics.stringWidth(line);
					if (longestLine < length) {
						longestLine = length;
					}
				}
			}
			for (String line : lines) {
				int xOffset;
				switch (alignment) {
					case LEFT:
						xOffset = -longestLine / 2;
						break;
					case RIGHT:
						int length = metrics.stringWidth(line);
						xOffset = longestLine / 2 - length;
						break;
					case CENTER:
						xOffset = -metrics.stringWidth(line) / 2;
						break;
					default:
						throw new NotImplementedException("Alignment " + alignment + " has not been implemented");
				}
				g.setColor(shadow);
				g.drawString(line, centerX + xOffset + 1, topY + yOffset + 1);
				g.setColor(color);
				g.drawString(line, centerX + xOffset, topY + yOffset);
				yOffset += fontHeight;
			}
		}
	}

	private void flipYZ(float[] xyz) {
		float temp = xyz[1];
		xyz[1] = xyz[2];
		xyz[2] = temp;
	}

	private float[] projectPoint(float[] m, float[] xyzw) {
		flipYZ(xyzw);
		float[] result = new float[4];
		Mat4.projectVec(result, m, xyzw);
		return result;
	}

	private Color alpha(Color rgb, int alpha) {
		if (alpha == 255)
			return rgb;
		return new Color(rgb.getRed(), rgb.getGreen(), rgb.getBlue(), alpha);
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (SwingUtilities.isRightMouseButton(e)) {
			if (hovered != null && selected != hovered)
				e.consume();
			selected = hovered;
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
	public void keyTyped(KeyEvent e) {}

	@Override
	public void keyPressed(KeyEvent e) {
		switch (e.getKeyCode()) {
			case KeyEvent.VK_A:
				hideAnimLights = !hideAnimLights;
				break;
			case KeyEvent.VK_B:
				toggleBlackColor = !toggleBlackColor;
				break;
			case KeyEvent.VK_H:
				hideInvisibleLights = !hideInvisibleLights;
				break;
			case KeyEvent.VK_I:
				hideInfo = !hideInfo;
				break;
			case KeyEvent.VK_L:
				hideLabels = !hideLabels;
				break;
			case KeyEvent.VK_R:
				hideRadiusRings = !hideRadiusRings;
				break;
			case KeyEvent.VK_U:
				liveInfo = !liveInfo;
				break;
		}

//		if (e.isControlDown() && e.isShiftDown() && e.getKeyCode() == KeyCode.KC_S) {
//			// TODO: Save changes to JSON
//			// Every time the JSON is updated, either through the file system or exporting changes,
//			// create a checkpoint. Store all checkpoints in memory throughout the client session.
//			// Implement ctrl Z and ctrl shift Z to redo. Forget reverted checkpoints upon file change.
//		}
//
//		// TODO: Implement grabbing and scaling like in Blender
//		switch (e.getKeyCode()) {
//			case KeyCode.KC_G: // grab
//				break;
//			case KeyCode.KC_S: // scale
//				break;
//		}
	}

	@Override
	public void keyReleased(KeyEvent e) {}
}
