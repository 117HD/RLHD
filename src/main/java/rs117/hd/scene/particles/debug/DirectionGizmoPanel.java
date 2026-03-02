/*
 * Copyright (c) 2025, Mark7625.
 * Direction gizmo: compass for yaw (drag to point) + vertical pitch bar (drag up/down).
 */
package rs117.hd.scene.particles.debug;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Supplier;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import net.runelite.client.ui.ColorScheme;

/**
 * Two controls that fill the panel width:
 * 1. Compass (left): drag to set yaw. N/E/S/W labels, arrow shows direction.
 * 2. Pitch bar (right): vertical strip — drag to set pitch (Up / Side / Down).
 */
public class DirectionGizmoPanel extends JPanel {

	private static final int PITCH_BAR_WIDTH = 24;
	private static final int GAP = 12;
	private static final int PREF_HEIGHT = 140;
	private static final int LABEL_OFFSET = 10;
	private static final double YAW_FULL = 2048.0;
	private static final double PITCH_FULL = 1024.0;
	private static final int PITCH_CORNER = 12;
	private static final int THUMB_CORNER = 4;

	private static final Color COMPASS_FILL = new Color(45, 45, 48);
	private static final Color COMPASS_STROKE = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color CARDINAL_COLOR = ColorScheme.LIGHT_GRAY_COLOR;

	// Single accent color used for base arrow and base pitch.
	private static final Color ACCENT = new Color(102, 187, 183);

	private static final Color ARROW_COLOR = ACCENT;
	private static final Color ARROW_BASE = ACCENT;
	private static final Color PITCH_TRACK = new Color(55, 58, 62);
	private static final Color PITCH_FILL = ACCENT;
	private static final Color PITCH_THUMB = ACCENT;

	// Spread visualization colors (yaw wedge + pitch band + legend).
	// Use a distinct, darker color so spread stands out but isn't bright yellow.
	private static final Color SPREAD_YAW_FILL = new Color(64, 120, 200, 170); // translucent dark blue wedge
	private static final Color SPREAD_PITCH_FILL = new Color(64, 120, 200);    // solid dark blue band over base

	private final JSpinner yawSpinner;
	private final JSpinner pitchSpinner;

	private int dragMode;
	private Supplier<SpreadValues> spreadSupplier;

	public DirectionGizmoPanel(JSpinner yawSpinner, JSpinner pitchSpinner) {
		this.yawSpinner = yawSpinner;
		this.pitchSpinner = pitchSpinner;
		this.dragMode = 0;
		setOpaque(false);
		setPreferredSize(new Dimension(0, PREF_HEIGHT));
		setMinimumSize(new Dimension(120, PREF_HEIGHT));
		if (yawSpinner != null) yawSpinner.addChangeListener(e -> repaint());
		if (pitchSpinner != null) pitchSpinner.addChangeListener(e -> repaint());

		MouseAdapter ma = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				Layout layout = computeLayout();
				if (layout.compassContains(e.getX(), e.getY())) {
					dragMode = 1;
					applyYawFromPoint(e.getX(), e.getY(), layout);
				} else if (layout.pitchBarContains(e.getX(), e.getY())) {
					dragMode = 2;
					applyPitchFromY(e.getY(), layout);
				} else {
					dragMode = 0;
				}
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				dragMode = 0;
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				Layout layout = computeLayout();
				if (dragMode == 1) applyYawFromPoint(e.getX(), e.getY(), layout);
				else if (dragMode == 2) applyPitchFromY(e.getY(), layout);
			}
		};
		addMouseListener(ma);
		addMouseMotionListener(ma);
	}

	public void setSpreadSupplier(Supplier<SpreadValues> spreadSupplier) {
		this.spreadSupplier = spreadSupplier;
		repaint();
	}

	public static final class SpreadValues {
		public final int yawMinGame;
		public final int yawMaxGame;
		public final int pitchMinGame;
		public final int pitchMaxGame;

		public SpreadValues(int yawMinGame, int yawMaxGame, int pitchMinGame, int pitchMaxGame) {
			this.yawMinGame = yawMinGame;
			this.yawMaxGame = yawMaxGame;
			this.pitchMinGame = pitchMinGame;
			this.pitchMaxGame = pitchMaxGame;
		}
	}

	private static final class Layout {
		final double compassCx, compassCy, compassRadius;
		final double pitchBarX, pitchBarY, pitchBarW, pitchBarH;

		Layout(double compassCx, double compassCy, double compassRadius,
		       double pitchBarX, double pitchBarY, double pitchBarW, double pitchBarH) {
			this.compassCx = compassCx;
			this.compassCy = compassCy;
			this.compassRadius = compassRadius;
			this.pitchBarX = pitchBarX;
			this.pitchBarY = pitchBarY;
			this.pitchBarW = pitchBarW;
			this.pitchBarH = pitchBarH;
		}

		boolean compassContains(int x, int y) {
			double dx = x - compassCx;
			double dy = y - compassCy;
			return dx * dx + dy * dy <= compassRadius * compassRadius;
		}

		boolean pitchBarContains(int x, int y) {
			return x >= pitchBarX && x <= pitchBarX + pitchBarW && y >= pitchBarY && y <= pitchBarY + pitchBarH;
		}
	}

	private Layout computeLayout() {
		int w = getWidth();
		int h = getHeight();
		if (w < 80) w = 80;
		if (h < 60) h = 60;

		double compassAreaWidth = w - PITCH_BAR_WIDTH - GAP - 8;
		double maxRadius = Math.min(compassAreaWidth * 0.5, (h - 20) * 0.5) - LABEL_OFFSET;
		double compassRadius = Math.max(20, maxRadius);
		double compassCx = compassRadius + LABEL_OFFSET + 4;
		double compassCy = h * 0.5;

		double pitchBarX = w - PITCH_BAR_WIDTH - 6;
		double pitchBarY = 10;
		double pitchBarH = h - 20;
		return new Layout(compassCx, compassCy, compassRadius, pitchBarX, pitchBarY, PITCH_BAR_WIDTH, pitchBarH);
	}

	private void applyYawFromPoint(int mx, int my, Layout layout) {
		if (yawSpinner == null) return;
		double dx = mx - layout.compassCx;
		double dy = my - layout.compassCy;
		double angle = Math.atan2(dx, -dy);
		if (angle < 0) angle += 2 * Math.PI;
		int yaw = (int) Math.round((angle / (2 * Math.PI)) * YAW_FULL) % (int) YAW_FULL;
		if (yaw < 0) yaw += (int) YAW_FULL;
		SpinnerNumberModel m = (SpinnerNumberModel) yawSpinner.getModel();
		int min = m.getMinimum() != null ? ((Number) m.getMinimum()).intValue() : 0;
		int max = m.getMaximum() != null ? ((Number) m.getMaximum()).intValue() : (int) YAW_FULL;
		yaw = Math.max(min, Math.min(max, yaw));
		yawSpinner.setValue(yaw);
	}

	private int pitchDisplay(int backendPitch) {
		return (int) PITCH_FULL - backendPitch;
	}

	private int gameToUiYaw(int yawGame) {
		int full = (int) YAW_FULL;
		int ui = full / 2 - yawGame;
		ui %= full;
		if (ui < 0) ui += full;
		return ui;
	}

	private void applyPitchFromY(int my, Layout layout) {
		if (pitchSpinner == null) return;
		double t = (my - layout.pitchBarY) / layout.pitchBarH;
		t = Math.max(0, Math.min(1, t));
		int displayPitch = (int) Math.round((1 - t) * PITCH_FULL);
		int backendPitch = (int) PITCH_FULL - displayPitch;
		SpinnerNumberModel m = (SpinnerNumberModel) pitchSpinner.getModel();
		int min = m.getMinimum() != null ? ((Number) m.getMinimum()).intValue() : 0;
		int max = m.getMaximum() != null ? ((Number) m.getMaximum()).intValue() : (int) PITCH_FULL;
		backendPitch = Math.max(min, Math.min(max, backendPitch));
		pitchSpinner.setValue(backendPitch);
	}

	private int intValue(JSpinner s, int def) {
		if (s == null) return def;
		Object v = s.getValue();
		if (v instanceof Number) return ((Number) v).intValue();
		return def;
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

		Layout layout = computeLayout();
		double cx = layout.compassCx;
		double cy = layout.compassCy;
		double radius = layout.compassRadius;

		// Compass: fill + thin stroke
		g2.setColor(COMPASS_FILL);
		g2.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
		g2.setColor(COMPASS_STROKE);
		g2.setStroke(new BasicStroke(1.2f));
		g2.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));

		// Cardinal labels
		double labelRadius = radius + 8;
		g2.setColor(CARDINAL_COLOR);
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
		FontRenderContext frc = g2.getFontRenderContext();
		String n = "N";
		g2.drawString(n, (float) (cx - g2.getFont().getStringBounds(n, frc).getWidth() / 2), (float) (cy - labelRadius));
		String s = "S";
		g2.drawString(s, (float) (cx - g2.getFont().getStringBounds(s, frc).getWidth() / 2), (float) (cy + labelRadius + 8));
		g2.drawString("E", (float) (cx + labelRadius - 4), (float) (cy + 4));
		String west = "W";
		g2.drawString(west, (float) (cx - labelRadius - g2.getFont().getStringBounds(west, frc).getWidth() + 4), (float) (cy + 4));

		// Spread yaw wedge (under base arrow), if provided.
		// Spread yaw values are provided in UI yaw space (0 = North, 512 = East, etc.).
		SpreadValues spreadForYaw = spreadSupplier != null ? spreadSupplier.get() : null;
		if (spreadForYaw != null) {
			int full = (int) YAW_FULL;
			int uiMin = spreadForYaw.yawMinGame;
			int uiMax = spreadForYaw.yawMaxGame;
			double aMin = (uiMin / (double) full) * 2 * Math.PI;
			double aMax = (uiMax / (double) full) * 2 * Math.PI;
			if (aMax < aMin) {
				aMax += 2 * Math.PI;
			}

			double rSpread = radius * 0.8;
			Path2D wedge = new Path2D.Double();
			wedge.moveTo(cx, cy);
			int steps = 24;
			for (int i = 0; i <= steps; i++) {
				double t = aMin + (aMax - aMin) * (i / (double) steps);
				double px = cx + Math.sin(t) * rSpread;
				double py = cy - Math.cos(t) * rSpread;
				wedge.lineTo(px, py);
			}
			wedge.closePath();
			g2.setColor(SPREAD_YAW_FILL);
			g2.fill(wedge);
		}

		// Direction arrow (drawn above yaw spread)
		int yaw = intValue(yawSpinner, 0);
		double yawRad = (yaw / YAW_FULL) * 2 * Math.PI;
		double horizontalLength = radius * 0.68;
		double tipX = cx + Math.sin(yawRad) * horizontalLength;
		double tipY = cy - Math.cos(yawRad) * horizontalLength;

		g2.setColor(ARROW_COLOR);
		g2.setStroke(new BasicStroke(2.2f));
		g2.draw(new Line2D.Double(cx, cy, tipX, tipY));
		double dx = tipX - cx;
		double dy = tipY - cy;
		double len = Math.hypot(dx, dy);
		if (len > 1e-6) {
			double ux = dx / len;
			double uy = dy / len;
			double headLen = 10;
			double perpX = -uy;
			double perpY = ux;
			Path2D head = new Path2D.Double();
			head.moveTo(tipX, tipY);
			head.lineTo(tipX - ux * headLen + perpX * 4, tipY - uy * headLen + perpY * 4);
			head.lineTo(tipX - ux * headLen - perpX * 4, tipY - uy * headLen - perpY * 4);
			head.closePath();
			g2.fill(head);
		}
		g2.setColor(ARROW_BASE);
		g2.fill(new Ellipse2D.Double(cx - 3, cy - 3, 6, 6));

		// Pitch bar: rounded track
		double bx = layout.pitchBarX;
		double by = layout.pitchBarY;
		double bw = layout.pitchBarW;
		double bh = layout.pitchBarH;
		g2.setColor(PITCH_TRACK);
		g2.fill(new RoundRectangle2D.Double(bx, by, bw, bh, PITCH_CORNER, PITCH_CORNER));

		int backendPitch = intValue(pitchSpinner, 0);
		int pitch1 = pitchDisplay(backendPitch);
		double fillH = (pitch1 / PITCH_FULL) * bh;
		g2.setColor(PITCH_FILL);
		g2.fill(new RoundRectangle2D.Double(bx + 2, by + bh - fillH, bw - 4, Math.max(fillH, 4), 4, 4));

		double thumbH = 8;
		double thumbY = by + bh - fillH - thumbH / 2;
		thumbY = Math.max(by + 2, Math.min(by + bh - thumbH - 2, thumbY));
		g2.setColor(PITCH_THUMB);
		g2.fill(new RoundRectangle2D.Double(bx + 4, thumbY, bw - 8, thumbH, THUMB_CORNER, THUMB_CORNER));

		// Spread visualization (pitch band), if provided
		if (spreadSupplier != null) {
			SpreadValues sv = spreadSupplier.get();
			if (sv != null) {
				// Pitch spread band
				int pitchMinDisp = pitchDisplay(sv.pitchMinGame);
				int pitchMaxDisp = pitchDisplay(sv.pitchMaxGame);
				double fMin = Math.max(0, Math.min(1, pitchMinDisp / PITCH_FULL));
				double fMax = Math.max(0, Math.min(1, pitchMaxDisp / PITCH_FULL));
				double fLo = Math.min(fMin, fMax);
				double fHi = Math.max(fMin, fMax);
				double yLo = by + bh - fLo * bh;
				double yHi = by + bh - fHi * bh;
				double bandY = Math.min(yLo, yHi);
				double bandH = Math.max(4, Math.abs(yLo - yHi));

				g2.setColor(SPREAD_PITCH_FILL);
				g2.fill(new RoundRectangle2D.Double(bx + 3, bandY, bw - 6, bandH, 4, 4));
			}
		}

		// Labels and legend
		g2.setColor(CARDINAL_COLOR);
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
		g2.drawString("Up", (float) (bx - 22), (float) (by + 2));
		g2.drawString("Down", (float) (bx - 30), (float) (by + bh + 2));

		// Legend (Base / Spread) – show above the gizmo, side by side
		int legendX = (int) (cx - radius);
		int legendY = (int) (cy - radius) - 18;
		int box = 10;
		int gap = 4;

		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));

		// Base legend item
		int baseX = legendX;
		int baseY = legendY;
		g2.setColor(ARROW_COLOR);
		g2.fill(new Ellipse2D.Double(baseX, baseY, box, box));
		g2.setColor(CARDINAL_COLOR);
		g2.drawString("Base", baseX + box + gap, baseY + box - 1);

		// Spread legend item, laid out to the right of Base
		int spreadX = legendX + 80;
		int spreadY = legendY;
		g2.setColor(SPREAD_PITCH_FILL);
		g2.fill(new Ellipse2D.Double(spreadX, spreadY, box, box));
		g2.setColor(CARDINAL_COLOR);
		g2.drawString("Spread", spreadX + box + gap, spreadY + box - 1);

		g2.dispose();
	}
}
