package rs117.hd.overlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ColorUtil;
import org.apache.commons.lang3.NotImplementedException;
import rs117.hd.HdPlugin;
import rs117.hd.scene.lights.Alignment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightType;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.Vector;

import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.NEAR_PLANE;

@Slf4j
@Singleton
public class LightGizmoOverlay extends Overlay implements MouseListener, KeyListener {
	private static final Color ORANGE = Color.decode("#ff9f2c");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private HdPlugin plugin;

	private boolean hideInvisibleLights;
	private boolean hideRadiusRings = true;
	private boolean hideAnimLights;
	private boolean hideLabels;
	private boolean hideInfo = true;
	private boolean toggleBlackColor;
	private boolean liveInfo;
	private boolean showDuplicationInfo;
	private boolean toggleOpacity = true;
	private boolean followMouse;

	private Action action = Action.SELECT;
	private final double[] rawMousePos = new double[2];
	private final double[] rawMousePosPrev = new double[2];
	private final double[] mouseDelta = new double[2];
	private final float[] cameraOrientation = new float[2];
	private Alignment originalLightAlignment = Alignment.CUSTOM;
	private final int[] originalLightPosition = new int[3];
	private final int[] originalLightOffset = new int[3];
	private final int[] currentLightOffset = new int[3];
	private int freezeMode = 0;
	private final boolean[] frozenAxes = { false, true, false }; // by default, restrict movement to the same height
	private final ArrayList<Light> selections = new ArrayList<>();
	private final ArrayList<Light> hovers = new ArrayList<>();
	private boolean isProbablyRotatingCamera;

	private static final int RELATIVE_TO_CAMERA = 0;
	private static final int RELATIVE_TO_ORIGIN = 1;
	private static final int RELATIVE_TO_POSITION = 2;

	// TODO: implement undo & redo
	private ArrayDeque<Change> history = new ArrayDeque<>();

	interface Change {
		void undo();
		void redo();
	}

	enum Action {
		SELECT, GRAB, SCALE
	}

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
			action = Action.SELECT;
			selections.clear();
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return null;

		// If the orientation changed, don't consider mouse movement
		boolean wasCameraReoriented = isProbablyRotatingCamera;
		for (int j = 0; j < 2; j++) {
			if (cameraOrientation[j] != plugin.cameraOrientation[j]) {
				wasCameraReoriented = true;
				break;
			}
		}
		System.arraycopy(plugin.cameraOrientation, 0, cameraOrientation, 0, 2);

		boolean isCtrlHeld = client.isKeyPressed(KeyCode.KC_CONTROL);
		boolean isShiftHeld = client.isKeyPressed(KeyCode.KC_SHIFT);
		boolean isAltHeld = client.isKeyPressed(KeyCode.KC_ALT);

		var rawMouse = MouseInfo.getPointerInfo().getLocation();
		rawMousePos[0] = (float) rawMouse.getX();
		rawMousePos[1] = (float) rawMouse.getY();
		if (wasCameraReoriented) {
			if (action == Action.GRAB) {
				assert !selections.isEmpty();
				// Rotation & moving the light with the mouse don't mix very well, so apply the offset and reset mouseDelta when rotating
				if (mouseDelta[0] != 0 || mouseDelta[1] != 0) {
					Arrays.fill(mouseDelta, 0);
					var selection = selections.get(0);
					System.arraycopy(selection.offset, 0, currentLightOffset, 0, 3);
				}
			}
		} else if (!isAltHeld) {
			double scalingFactor = isShiftHeld ? .1 : 1;
			for (int j = 0; j < 2; j++)
				mouseDelta[j] += (rawMousePos[j] - rawMousePosPrev[j]) * scalingFactor;
		}
		System.arraycopy(rawMousePos, 0, rawMousePosPrev, 0, 2);

		var mousePoint = new java.awt.Point((int) Math.round(rawMousePos[0]), (int) Math.round(rawMousePos[1]));
		SwingUtilities.convertPointFromScreen(mousePoint, client.getCanvas());
		int[] mousePos = { mousePoint.x, mousePoint.y };

		Point mousePosCanvas = client.getMouseCanvasPosition();
		if (mousePosCanvas != null && (mousePosCanvas.getX() == -1 || mousePosCanvas.getY() == -1))
			mousePosCanvas = null;

		g.setFont(FontManager.getRunescapeSmallFont());

		final int innerDotDiameter = 6;
		final int innerHandleRingDiameter = 19;
		final int outerHandleRingDiameter = 25;
		final int hoverDistanceMargin = 5;

		Stroke thickLine = new BasicStroke(2);
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
		Stroke thickDashedLine = new BasicStroke(
			1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
			0, new float[] { 3 }, 0
		);

		float[] projectionMatrix = Mat4.identity();
		int viewportWidth = client.getViewportWidth();
		int viewportHeight = client.getViewportHeight();
		Mat4.mul(projectionMatrix, Mat4.translate(client.getViewportXOffset(), client.getViewportYOffset(), 0));
		Mat4.mul(projectionMatrix, Mat4.scale(viewportWidth, viewportHeight, 1));
		Mat4.mul(projectionMatrix, Mat4.translate(.5f, .5f, .5f));
		Mat4.mul(projectionMatrix, Mat4.scale(.5f, -.5f, .5f));
		// NDC clip space
		Mat4.mul(projectionMatrix, Mat4.scale(client.getScale(), client.getScale(), 1));
		Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, NEAR_PLANE));
		Mat4.mul(projectionMatrix, Mat4.rotateX(plugin.cameraOrientation[1]));
		Mat4.mul(projectionMatrix, Mat4.rotateY(plugin.cameraOrientation[0]));
		Mat4.mul(projectionMatrix, Mat4.translate(
			-plugin.cameraPosition[0],
			-plugin.cameraPosition[1],
			-plugin.cameraPosition[2]
		));

		float[] inverseProjection = null;
		try {
			inverseProjection = Mat4.inverse(projectionMatrix);
		} catch (IllegalArgumentException ex) {
			System.out.println("Not invertible");
		}

		int numFrozenAxes = 0;
		if (freezeMode > 0)
			for (int i = 0; i < 3; i++)
				if (frozenAxes[i])
					numFrozenAxes++;

		hovers.clear();
		int counter = 0;
		final float[] lightToCamera = new float[3];
		var lights = sceneContext.lights;

		float[] point = new float[4];
		int selectedIndex = -1;
		for (int i = lights.size() - 1; i >= -1; i--) {
			// Draw the selected light last
			int lightIndex = i;
			if (i == -1) {
				lightIndex = selectedIndex;
				if (lightIndex == -1)
					continue;
			}

			Light l = lights.get(lightIndex);

			if (hideInvisibleLights && !l.def.visibleFromOtherPlanes &&
				(l.plane < client.getPlane() && l.belowFloor || l.plane > client.getPlane() && l.aboveFloor))
				continue;

			if (hideAnimLights && !l.def.animationIds.isEmpty() && !l.parentExists)
				continue;

			boolean isHovered = !hovers.isEmpty() && hovers.get(0) == l;
			boolean isSelected = selections.contains(l);

			// Skip the selected light until the end
			if (i != -1 && isSelected) {
				selectedIndex = i;
				continue;
			}

			if (isSelected && !wasCameraReoriented && inverseProjection != null) {
				if (action == Action.GRAB) {
					float[] oldLightPos = new float[4];
					float[] newLightPos = new float[4];

					float radians = (float) (l.orientation * UNIT);
					float sin = (float) Math.sin(radians);
					float cos = (float) Math.cos(radians);

					// Project the light's current position into screen space
					for (int j = 0; j < 3; j++)
						oldLightPos[j] = l.origin[j];
					float x = currentLightOffset[0];
					float z = currentLightOffset[2];
					oldLightPos[0] += -cos * x - sin * z;
					oldLightPos[1] += currentLightOffset[1];
					oldLightPos[2] += -cos * z + sin * x;
					oldLightPos[3] = 1;
					Mat4.projectVec(point, projectionMatrix, oldLightPos);

					if (followMouse) {
						// Move the light to the mouse position
						for (int j = 0; j < 2; j++)
							point[j] = mousePos[j];
					} else {
						// Shift the position with mouse movement
						for (int j = 0; j < 2; j++)
							point[j] += (float) mouseDelta[j];
					}

					if (numFrozenAxes == 0) { // restrict to same depth plane
						// Project the screen position back into the new light position
						Mat4.projectVec(newLightPos, inverseProjection, point);
						if (point[3] <= 0)
							continue;
					} else {
						// p1 & v1 = ray from the camera in the hovered direction
						var p1 = plugin.cameraPosition;
						var v1 = new float[3];

						// Compute a vector from the camera to the target mouse position
						Mat4.projectVec(point, inverseProjection, point);
						for (int j = 0; j < 3; j++)
							v1[j] = point[j] - p1[j];

						if (numFrozenAxes == 1) {
							// restrict to basis plane
							// ax + by + cz = d
							// n = (a, b, c)
							float[] n = new float[3];
							for (int j = 0; j < 3; j++) {
								if (frozenAxes[j]) {
									n[j] = 1;
									break;
								}
							}

							if (freezeMode == RELATIVE_TO_ORIGIN) {
								for (int j = 0; j < 3; j++)
									oldLightPos[j] = l.origin[j];
								oldLightPos[3] = 1;
								Mat4.projectVec(point, projectionMatrix, oldLightPos);
							}

							float d = Vector.dot(n, oldLightPos);

							// dot(p1 + v1 * t, n) = d
							// dot(p1, n) + dot(v1 * t, n) = d
							// dot(p1, n) + dot(v1, n) * t = d
							// t = (d - dot(p1, n)) / dot(v1, n)
							float t = (d - Vector.dot(p1, n)) / Vector.dot(v1, n);

							for (int j = 0; j < 3; j++)
								newLightPos[j] = p1[j] + v1[j] * t;
						} else if (numFrozenAxes == 2) {
							// restrict to axis
							int axis = 0;
							for (int j = 0; j < 3; j++) {
								if (!frozenAxes[j]) {
									axis = j;
									break;
								}
							}

							// p2 & v2 = ray from the light's origin in the direction of the target axis
							var p2 = new float[3];
							var origin = freezeMode == RELATIVE_TO_ORIGIN ? l.origin : originalLightPosition;
							for (int j = 0; j < 3; j++)
								p2[j] = origin[j];
							var v2 = new float[3];
							v2[axis] = 1;

							// v3 is the direction perpendicular to both v1 and v2, which is the direction
							// for the shortest path between two points on the two rays
							var v3 = new float[3];
							Vector.cross(v3, v1, v2);

							try {
								// Solve the following set of linear equations to find t2; the distance
								// from p2 along v2 until the closest point between the two rays:
								// p1 + v1 * t1 + v3 * t3 = p2 + v2 * t2

								// Solve for t2:
								float t2 = -p1[0] * v1[1] * v3[2] + p1[0] * v1[2] * v3[1] + p1[1] * v1[0] * v3[2] - p1[1] * v1[2] * v3[0]
										   - p1[2] * v1[0] * v3[1] + p1[2] * v1[1] * v3[0] + p2[0] * v1[1] * v3[2] - p2[0] * v1[2] * v3[1]
										   - p2[1] * v1[0] * v3[2] + p2[1] * v1[2] * v3[0] + p2[2] * v1[0] * v3[1] - p2[2] * v1[1] * v3[0];
								t2 /= v1[0] * v2[1] * v3[2] - v1[0] * v2[2] * v3[1] - v1[1] * v2[0] * v3[2] + v1[1] * v2[2] * v3[0]
									  + v1[2] * v2[0] * v3[1] - v1[2] * v2[1] * v3[0];

								for (int j = 0; j < 3; j++)
									newLightPos[j] = p2[j] + v2[j] * t2;
							} catch (IllegalArgumentException ex) {
								log.debug("No solution:", ex);
							}
						}
					}

					float gridSize = isCtrlHeld ? 128f / (isShiftHeld ? 8 : 1) : 1;

					float[] relativePos = new float[3];
					for (int j = 0; j < 3; j++)
						relativePos[j] = newLightPos[j] - l.origin[j];

					x = relativePos[0];
					z = relativePos[2];
					relativePos[0] = -cos * x + sin * z;
					relativePos[2] = -cos * z - sin * x;

					for (int j = 0; j < 3; j++)
						l.offset[j] = (int) (Math.round(relativePos[j] / gridSize) * gridSize);

					x = l.offset[0];
					z = l.offset[2];
					l.pos[0] = l.origin[0] + (int) (-cos * x - sin * z);
					l.pos[1] = l.origin[1] + l.offset[1];
					l.pos[2] = l.origin[2] + (int) (-cos * z + sin * x);
				}
			}

			for (int j = 0; j < 3; j++)
				point[j] = l.pos[j];
			point[3] = 1;

			Vector.subtract(lightToCamera, plugin.cameraPosition, point);
			float distanceFromCamera = Vector.length(lightToCamera);

			Mat4.projectVec(point, projectionMatrix, point);
			if (point[3] <= 0)
				continue;
			int x = Math.round(point[0]);
			int y = Math.round(point[1]);

			// Take perspective depth into account
			int currentDiameter = Math.round(l.radius * 2 / distanceFromCamera * client.getScale());
			float definedDiameter = l.def.radius * 2 / distanceFromCamera * client.getScale();
			float fRange = l.def.range / 100f;
			int minDiameter = Math.round(definedDiameter * (1 - fRange));
			int maxDiameter = Math.round(definedDiameter * (1 + fRange));

			if (mousePosCanvas != null) {
				float d = Vector.length(mousePosCanvas.getX() - x, mousePosCanvas.getY() - y);
				if (d <= outerHandleRingDiameter / 2f + hoverDistanceMargin ||
					!hideRadiusRings && Math.abs(d - currentDiameter / 2f) < hoverDistanceMargin * 2)
					hovers.add(l);
			}

			int mainOpacity = toggleOpacity ?
				(l.visible ? 255 : 100) :
				(l.visible ? 100 : 30);
			int rangeOpacity = 70;
			Color baseColor = toggleBlackColor ? Color.BLACK : Color.WHITE;
			Color radiusRingColor = alpha(baseColor, mainOpacity);
			Color rangeRingsColor = alpha(baseColor, rangeOpacity);
			Color handleRingsColor = radiusRingColor;
			Color textColor = Color.WHITE;
			Stroke handleRingsStroke = thinDashedLine;

			if (isSelected) {
				handleRingsColor = radiusRingColor = rangeRingsColor = ORANGE;
				handleRingsStroke = thickDashedLine;
			} else if (isHovered) {
				radiusRingColor = Color.YELLOW;
				handleRingsColor = Color.WHITE;
			} else {
				textColor = alpha(textColor, mainOpacity);
			}

			// Draw handle rings
			drawRing(g, x, y, innerHandleRingDiameter, handleRingsColor, handleRingsStroke);
			drawRing(g, x, y, outerHandleRingDiameter, handleRingsColor, handleRingsStroke);

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
			if (!hideLabels) {
				String info = l.def.description;
				if (showDuplicationInfo) {
					int newlines = (counter++ % 5) + 1;
					info += "\n".repeat(newlines);
					info += counter + ": " + l.hash;
					info += "\n".repeat(5 - newlines);
				}
				if (isSelected && !hideInfo) {
					info += String.format("\nradius: %d", liveInfo ? l.radius : l.def.radius);
					info += String.format("\nstrength: %.1f", liveInfo ? l.strength : l.def.strength);
					var color = ColorUtils.linearToSrgb(l.def.color);
					info += String.format("\ncolor: [%.0f, %.0f, %.0f]", color[0] * 255, color[1] * 255, color[2] * 255);
					// Technically negative Y is up, but invert this in the info shown
					info += String.format(
						"\norigin: [%d, %d%s, %d]",
						l.origin[0],
						-(l.origin[1] + l.def.height),
						l.def.height == 0 ? "" : " + " + l.def.height,
						l.origin[2]
					);
					info += String.format("\noffset: [%d, %d, %d]", l.offset[0], -l.offset[1], l.offset[2]);
					info += String.format("\norientation: %d", l.orientation);
				}
				drawAlignedString(g, info, x, y + 25, TextAlignment.CENTER_ON_COLONS);
			}
		}

		if (!selections.isEmpty()) {
			switch (action) {
				case GRAB:
					Light l = selections.get(0);
					var lightOrigin = freezeMode == RELATIVE_TO_ORIGIN ? l.origin : originalLightPosition;
					for (int i = 0; i < 3; i++)
						point[i] = lightOrigin[i];
					point[3] = 1;
					float[] origin = new float[4];
					Mat4.projectVec(origin, projectionMatrix, point);
					if (point[3] <= 0)
						break;

					if (numFrozenAxes > 0) {
						Color[] axisColors = {
							new Color(0xef738c),
							new Color(0x9fd853),
							new Color(0x75ace1),
						};
						g.setStroke(thickLine);

						float[] stepAlongAxis = new float[4];
						for (int i = 0; i < 3; i++) {
							if (!frozenAxes[i]) {
								int stepSize = 1000;
								point[i] += stepSize;
								Mat4.projectVec(stepAlongAxis, projectionMatrix, point);
								point[i] -= stepSize;

								g.setColor(axisColors[i]);
								drawLineSpan(g, origin, stepAlongAxis);
							}
						}
					}

					for (int i = 0; i < 3; i++)
						point[i] = l.pos[i];
					point[3] = 1;
					float[] pos = new float[4];
					Mat4.projectVec(pos, projectionMatrix, point);
					if (point[3] <= 0)
						break;

					g.setColor(Color.YELLOW);
					drawLineSegment(g, origin, pos);
					break;
				case SCALE:
					break;
			}
		}

		return null;
	}

	private void drawLineSegment(Graphics2D g, float[] a, float[] b) {
		g.drawLine(
			Math.round(a[0]),
			Math.round(a[1]),
			Math.round(b[0]),
			Math.round(b[1])
		);
	}

	private void drawLineSpan(Graphics2D g, float[] a, float[] b) {
		float[] v = new float[2];
		Vector.subtract(v, b, a);
		if (v[0] == 0 && v[1] == 0)
			return;

		float[] p = new float[2];
		System.arraycopy(a, 0, p, 0, 2);

		var clipBounds = g.getClipBounds();
		float[][] axisBounds = {
			{ 0, clipBounds.width },
			{ 0, clipBounds.height }
		};

		final float INF = Float.POSITIVE_INFINITY;
		final float EPS = 1f;

		// First intersection with an edge within the screen bounds
		float t = INF;
		int intersectedEdge = -1;
		outer:
		for (int axis = 0; axis < 2; axis++) {
			if (v[axis] == 0)
				continue;
			for (int edge = 0; edge < 2; edge++) {
				float d = (axisBounds[axis][edge] - p[axis]) / v[axis];
				int oppositeAxis = (axis + 1) % 2;
				float[] bounds = axisBounds[oppositeAxis];
				float coord = p[oppositeAxis] + v[oppositeAxis] * d;
				if (bounds[0] - EPS < coord && coord < bounds[1] + EPS) {
					t = d;
					intersectedEdge = axis * 2 + edge;
					break outer;
				}
			}
		}
		if (t == INF)
			return;

		// Move the point to the selected edge
		for (int i = 0; i < 2; i++)
			p[i] += v[i] * t;

		t = INF;
		outer:
		for (int axis = 0; axis < 2; axis++) {
			if (v[axis] == 0)
				continue;
			for (int edge = 0; edge < 2; edge++) {
				// Skip the edge we've already intersected with
				if (axis * 2 + edge == intersectedEdge)
					continue;

				float d = (axisBounds[axis][edge] - p[axis]) / v[axis];
				int oppositeAxis = (axis + 1) % 2;
				float[] bounds = axisBounds[oppositeAxis];
				float coord = p[oppositeAxis] + v[oppositeAxis] * d;
				if (bounds[0] - EPS < coord && coord < bounds[1] + EPS) {
					t = d;
					break outer;
				}
			}
		}
		if (t == INF)
			return;

		int x1 = Math.round(p[0]);
		int y1 = Math.round(p[1]);
		int x2 = Math.round(p[0] + v[0] * t);
		int y2 = Math.round(p[1] + v[1] * t);
		g.drawLine(x1, y1, x2, y2);
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

	private Color alpha(Color rgb, int alpha) {
		if (alpha == 255)
			return rgb;
		return new Color(rgb.getRed(), rgb.getGreen(), rgb.getBlue(), alpha);
	}

	private boolean applyPendingChange() {
		if (action == Action.SELECT || selections.isEmpty())
			return false;

		action = Action.SELECT;
		return true;
	}

	private boolean discardPendingChange() {
		if (action == Action.SELECT) {
			if (!selections.isEmpty())
				selections.clear();
			return false;
		}

		if (selections.isEmpty())
			return false;

		if (action == Action.GRAB) {
			// Reset the light back to its original offset
			var l = selections.get(0);
			l.alignment = originalLightAlignment;
			System.arraycopy(originalLightOffset, 0, l.offset, 0, 3);
		}

		action = Action.SELECT;
		return true;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (SwingUtilities.isMiddleMouseButton(e))
			isProbablyRotatingCamera = true;

		switch (action) {
			case SELECT:
				if (SwingUtilities.isLeftMouseButton(e) && e.isControlDown()) {
					e.consume();

					selections.clear();
					if (!hovers.isEmpty()) {
						selections.add(hovers.get(0));
					} else {
						action = Action.SELECT;
					}
				}
				break;
			case GRAB:
				if (SwingUtilities.isLeftMouseButton(e)) {
					if (applyPendingChange())
						e.consume();
				} else if (SwingUtilities.isRightMouseButton(e)) {
					if (discardPendingChange())
						e.consume();
				}
				break;
			case SCALE:
				break;
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		if (SwingUtilities.isMiddleMouseButton(e))
			isProbablyRotatingCamera = false;
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
//		if (e.isControlDown() && e.isShiftDown() && e.getKeyCode() == KeyCode.KC_S) {
//			// TODO: Save changes to JSON
//			// Every time the JSON is updated, either through the file system or exporting changes,
//			// create a checkpoint. Store all checkpoints in memory throughout the client session.
//			// Implement ctrl Z and ctrl shift Z to redo. Forget reverted checkpoints upon file change.
//		}

		// Interaction with selected object
		if (!selections.isEmpty()) {
			var l = selections.get(0);

			if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_C) {
				String str = "\n    \"offset\": [ " + l.offset[0] + ", " + -l.offset[1] + ", " + l.offset[2] + " ],";

				Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
				StringSelection string = new StringSelection(str);
				clipboard.setContents(string, null);
				clientThread.invoke(() -> client.addChatMessage(
					ChatMessageType.GAMEMESSAGE,
					"117 HD",
					ColorUtil.wrapWithColorTag("[117 HD] Copied offset (must remove alignment): " + str.trim(), Color.GREEN),
					"117 HD"
				));
			}

			if (action == Action.SELECT) {
				switch (e.getKeyCode()) {
					case KeyEvent.VK_G:
						action = Action.GRAB;
						Arrays.fill(mouseDelta, 0);

						originalLightAlignment = l.alignment;
						System.arraycopy(l.offset, 0, originalLightOffset, 0, 3);
						System.arraycopy(l.pos, 0, originalLightPosition, 0, 3);

						l.alignment = Alignment.CUSTOM;
						for (int i = 0; i < 3; i++)
							l.offset[i] = l.pos[i] - l.origin[i];
						System.arraycopy(l.offset, 0, currentLightOffset, 0, 3);
						break;
					case KeyEvent.VK_S:
						action = Action.SCALE;
						break;
				}
			} else if (action == Action.GRAB) {
				int axis = -1;
				boolean cycleFreezeMode = false;
				switch (e.getKeyCode()) {
					case KeyEvent.VK_X:
						axis = 0;
						break;
					case KeyEvent.VK_Y:
						axis = 1;
						break;
					case KeyEvent.VK_Z:
						axis = 2;
						break;
					case KeyEvent.VK_G:
						cycleFreezeMode = true;
						break;
				}
				if (axis != -1) {
					boolean invert = e.isShiftDown();
					boolean modified = false;
					for (int i = 0; i < 3; i++) {
						boolean shouldFreeze = i == axis == invert;
						if (shouldFreeze != frozenAxes[i])
							modified = true;
						frozenAxes[i] = shouldFreeze;
					}
					if (modified) {
						// Reset current offset
						if (freezeMode == 0)
							freezeMode = 1;
						System.arraycopy(originalLightOffset, 0, l.offset, 0, 3);
					} else {
						cycleFreezeMode = true;
					}
				}
				if (cycleFreezeMode) {
					// If the same combination is repeated, cycle through different modes
					freezeMode++;
					freezeMode %= 3;
				}
			}

			switch (e.getKeyCode()) {
				case KeyEvent.VK_ESCAPE:
					if (discardPendingChange())
						e.consume();
					break;
				case KeyEvent.VK_ENTER:
					if (applyPendingChange())
						e.consume();
					break;
				case KeyEvent.VK_BACK_SPACE:
					// Reset light back to its defined offset
					l.alignment = l.def.alignment;
					System.arraycopy(l.def.offset, 0, l.offset, 0, 3);
					System.arraycopy(l.offset, 0, currentLightOffset, 0, 3);
					System.arraycopy(l.offset, 0, originalLightOffset, 0, 3);
					Arrays.fill(mouseDelta, 0);
					break;
			}
		}

		// Toggles
		if (e.isControlDown()) {
			switch (e.getKeyCode()) {
				case KeyEvent.VK_A:
					hideAnimLights = !hideAnimLights;
					break;
				case KeyEvent.VK_B:
					toggleBlackColor = !toggleBlackColor;
					break;
				case KeyEvent.VK_D:
					showDuplicationInfo = !showDuplicationInfo;
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
				case KeyEvent.VK_M:
					followMouse = !followMouse;
					break;
				case KeyEvent.VK_O:
					toggleOpacity = !toggleOpacity;
					break;
				case KeyEvent.VK_R:
					hideRadiusRings = !hideRadiusRings;
					break;
				case KeyEvent.VK_U:
					liveInfo = !liveInfo;
					break;
			}
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {}
}
