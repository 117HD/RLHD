/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 */
package rs117.hd.overlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.utils.Mat4;

import static rs117.hd.utils.MathUtils.round;

@Singleton
public class ParticleGizmoOverlay extends Overlay implements MouseListener {
	/** Match light gizmo handle dimensions. */
	private static final int INNER_HANDLE_RING = 19;
	private static final int OUTER_HANDLE_RING = 25;
	private static final int INNER_DOT = 6;
	private static final int HOVER_MARGIN = 5;

	private static final Color ACTIVE_COLOR = Color.WHITE;
	private static final Color INACTIVE_COLOR = Color.RED;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ParticleManager particleManager;

	/** Emitter under mouse (from last render). Used so Shift+click can toggle without interacting with game. */
	private final ArrayList<ParticleEmitter> hovers = new ArrayList<>();

	public ParticleGizmoOverlay() {
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean activate) {
		if (activate) {
			overlayManager.add(this);
			mouseManager.registerMouseListener(this);
		} else {
			overlayManager.remove(this);
			mouseManager.unregisterMouseListener(this);
			hovers.clear();
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		SceneContext ctx = plugin.getSceneContext();
		if (ctx == null || ctx.sceneBase == null)
			return null;

		int currentPlane = client.getTopLevelWorldView() != null ? client.getTopLevelWorldView().getPlane() : 0;
		float[] projectionMatrix = Mat4.identity();
		int vw = client.getViewportWidth();
		int vh = client.getViewportHeight();
		Mat4.mul(projectionMatrix, Mat4.translate(client.getViewportXOffset(), client.getViewportYOffset(), 0));
		Mat4.mul(projectionMatrix, Mat4.scale(vw, vh, 1));
		Mat4.mul(projectionMatrix, Mat4.translate(.5f, .5f, .5f));
		Mat4.mul(projectionMatrix, Mat4.scale(.5f, -.5f, .5f));
		Mat4.mul(projectionMatrix, plugin.viewProjMatrix);

		Point mouseCanvas = client.getMouseCanvasPosition();
		int mouseX = mouseCanvas != null ? mouseCanvas.getX() : -1;
		int mouseY = mouseCanvas != null ? mouseCanvas.getY() : -1;

		Stroke thinDashed = new BasicStroke(
			1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
			0, new float[] { 3 }, 0
		);
		Stroke thinLine = new BasicStroke(1);

		float[] pos = new float[3];
		int[] planeOut = new int[1];
		float[] point = new float[4];
		hovers.clear();

		for (ParticleEmitter em : particleManager.getSceneEmitters()) {
			if (!particleManager.getEmitterSpawnPosition(ctx, em, pos, planeOut))
				continue;
			if (planeOut[0] != currentPlane)
				continue;
			point[0] = pos[0];
			point[1] = pos[1];
			point[2] = pos[2];
			point[3] = 1;
			Mat4.projectVec(point, projectionMatrix, point);
			if (point[3] <= 0) continue;
			int sx = round(point[0]);
			int sy = round(point[1]);

			if (mouseX >= 0 && mouseY >= 0) {
				double d = Math.hypot(mouseX - sx, mouseY - sy);
				if (d <= OUTER_HANDLE_RING / 2.0 + HOVER_MARGIN)
					hovers.add(em);
			}

			Color ringColor = em.isActive() ? ACTIVE_COLOR : INACTIVE_COLOR;
			g.setColor(ringColor);
			g.setStroke(thinDashed);
			drawRing(g, sx, sy, INNER_HANDLE_RING);
			drawRing(g, sx, sy, OUTER_HANDLE_RING);
			g.setStroke(thinLine);
			drawCircleOutline(g, sx, sy, INNER_DOT);

			String name = em.getParticleId();
			if (name != null && !name.isEmpty()) {
				g.setColor(Color.WHITE);
				g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
				FontMetrics fm = g.getFontMetrics();
				int tw = fm.stringWidth(name);
				g.drawString(name, sx - tw / 2, sy + OUTER_HANDLE_RING / 2 + fm.getAscent() + 2);
			}
		}

		return null;
	}

	private void drawRing(Graphics2D g, int centerX, int centerY, int diameter) {
		int d = (int) (Math.ceil(diameter / 2.0) * 2 - 1);
		int r = (int) Math.ceil(d / 2.0);
		g.drawOval(centerX - r, centerY - r, d, d);
	}

	private void drawCircleOutline(Graphics2D g, int centerX, int centerY, int diameter) {
		int r = (int) Math.ceil(diameter / 2.0);
		int s = diameter - 1;
		g.drawRoundRect(centerX - r, centerY - r, s, s, s - 1, s - 1);
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (SwingUtilities.isLeftMouseButton(e) && e.isShiftDown() && !hovers.isEmpty()) {
			ParticleEmitter em = hovers.get(0);
			em.active(!em.isActive());
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseClicked(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseEntered(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseExited(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseDragged(MouseEvent e) { return e; }
	@Override
	public MouseEvent mouseMoved(MouseEvent e) { return e; }
}
