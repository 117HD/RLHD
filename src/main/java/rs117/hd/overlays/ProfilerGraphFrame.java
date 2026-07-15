package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.components.GraphComponent;
import rs117.hd.profiling.ProfileSample;
import rs117.hd.profiling.ProfileSampleStore;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class ProfilerGraphFrame {
	private static final Color PANEL_BACKGROUND = new Color(30, 30, 30);
	private static final Color LEGEND_BACKGROUND = new Color(24, 24, 24);
	private static final Color LEGEND_BORDER = new Color(60, 60, 60);
	private static final Color LEGEND_TEXT = new Color(220, 220, 220);
	private static final Color LEGEND_HOVER_BG = new Color(50, 50, 50);
	private static final Color PLACEHOLDER_TEXT = new Color(160, 160, 160);

	private static final int PANEL_PADDING = 8;
	private static final int LEGEND_WIDTH = 200;
	private static final int LEGEND_SWATCH = 12;
	private static final int LEGEND_ROW_HEIGHT = 20;
	private static final int LEGEND_PADDING = 10;
	private static final int REPAINT_MS = 33;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ProfileSampleStore profileSampleStore;

	@Inject
	private ProfilerUI profilerUI;

	@Inject
	private ProfilerOverlay profilerOverlay;

	private ProfilerGraphs graphs;

	private JFrame frame;
	private GraphPanel graphPanel;
	private LegendPanel legendPanel;
	private javax.swing.Timer repaintTimer;

	@Nullable
	private Object hoveredLegendKey;

	@Getter
	private boolean active;

	private GraphComponent<ProfileSample> dragGraph;
	private Point dragStartPoint;
	private boolean closingToDock;

	private ProfilerGraphs graphs() {
		if (graphs == null)
			graphs = new ProfilerGraphs(profilerUI);
		return graphs;
	}

	public void setActive(boolean activate) {
		if (active == activate)
			return;

		active = activate;
		SwingUtilities.invokeLater(() -> {
			if (activate)
				showFrame();
			else
				hideFrame();
		});
	}

	private void showFrame() {
		if (frame == null)
			createFrame();

		ensureGraphs();
		applyGraphSizes();
		frame.pack();

		if (!frame.isVisible()) {
			frame.setLocationRelativeTo(client.getCanvas());
			JFrame runeLiteWindow = plugin.clientJFrame;
			if (runeLiteWindow != null && runeLiteWindow.isAlwaysOnTop())
				frame.setAlwaysOnTop(true);
			frame.setVisible(true);
		}

		if (repaintTimer == null) {
			repaintTimer = new javax.swing.Timer(REPAINT_MS, e -> {
				if (graphPanel != null)
					graphPanel.repaint();
				if (legendPanel != null)
					legendPanel.repaint();
			});
			repaintTimer.start();
		} else if (!repaintTimer.isRunning()) {
			repaintTimer.start();
		}
	}

	private void hideFrame() {
		if (repaintTimer != null)
			repaintTimer.stop();

		if (frame != null && frame.isVisible()) {
			closingToDock = true;
			frame.setVisible(false);
			closingToDock = false;
		}

		dragGraph = null;
		dragStartPoint = null;
		hoveredLegendKey = null;
	}

	private void createFrame() {
		frame = new JFrame("117 HD Graphs");
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				if (closingToDock)
					return;
				profilerUI.dockGraphs();
			}
		});

		try {
			BufferedImage logo = path(HdPlugin.class, "logo.png").loadImage();
			frame.setIconImage(logo);
		} catch (IOException ex) {
			log.debug("Unable to load HD logo for graph window", ex);
		}

		graphPanel = new GraphPanel();
		legendPanel = new LegendPanel();

		JPanel graphsContent = new JPanel(new BorderLayout());
		graphsContent.setBackground(PANEL_BACKGROUND);
		graphsContent.add(graphPanel, BorderLayout.CENTER);

		JScrollPane legendScroll = new JScrollPane(
			legendPanel,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
		);
		legendScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, LEGEND_BORDER));
		legendScroll.getViewport().setBackground(LEGEND_BACKGROUND);
		legendScroll.setPreferredSize(new Dimension(LEGEND_WIDTH, 200));
		graphsContent.add(legendScroll, BorderLayout.WEST);

		JPanel flamePanel = new JPanel(new BorderLayout());
		flamePanel.setBackground(PANEL_BACKGROUND);
		JLabel comingSoon = new JLabel("Coming soon", SwingConstants.CENTER);
		comingSoon.setForeground(PLACEHOLDER_TEXT);
		comingSoon.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
		flamePanel.add(comingSoon, BorderLayout.CENTER);

		JTabbedPane tabs = new JTabbedPane();
		tabs.setBackground(PANEL_BACKGROUND);
		tabs.setForeground(LEGEND_TEXT);
		tabs.addTab("Graphs", graphsContent);
		tabs.addTab("Flame graph", flamePanel);

		frame.setContentPane(tabs);
		frame.setMinimumSize(new Dimension(
			GraphComponent.MIN_GRAPH_WIDTH + LEGEND_WIDTH + PANEL_PADDING * 2
				+ GraphComponent.MARGIN_LEFT + GraphComponent.MARGIN_RIGHT,
			280
		));
		frame.setResizable(true);
	}

	private Object resolveHoveredKey() {
		if (hoveredLegendKey != null)
			return hoveredLegendKey;
		return profilerOverlay.getHoveredTimer();
	}

	private void ensureGraphs() {
		ProfilerGraphs g = graphs();
		if (!g.isEmpty())
			return;
		g.create(
			this::resolveHoveredKey,
			() -> graphPanel == null ? null : graphPanel.getMousePositionInPanel()
		);
	}

	private void applyGraphSizes() {
		Dimension preferred = graphs().applyPanelSizes(
			graphPanel != null ? Math.max(1, graphPanel.getWidth()) : 0,
			graphPanel != null ? Math.max(1, graphPanel.getHeight()) : 0,
			PANEL_PADDING,
			profilerUI.getGraphPlotWidth(),
			profilerUI.getGraphPlotHeight(),
			profilerUI.getMemoryGraphPlotHeight()
		);
		if (graphPanel != null)
			graphPanel.setPreferredSize(preferred);
	}

	private class GraphPanel extends JPanel {
		private Point lastMouse;

		GraphPanel() {
			setBackground(PANEL_BACKGROUND);
			setOpaque(true);

			MouseAdapter mouse = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (e.getButton() != MouseEvent.BUTTON1)
						return;
					lastMouse = e.getPoint();
					GraphComponent<ProfileSample> hit = graphs().findAtLocal(e.getX(), e.getY());
					if (hit != null) {
						dragGraph = hit;
						dragStartPoint = e.getPoint();
					}
				}

				@Override
				public void mouseDragged(MouseEvent e) {
					lastMouse = e.getPoint();
					if (dragGraph != null)
						dragGraph.setSelectionFromPoints(dragStartPoint, e.getPoint());
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					lastMouse = e.getPoint();
					if (dragGraph != null) {
						if (dragStartPoint.distance(e.getPoint()) <= 0.01)
							dragGraph.clearSelection();
						else
							dragGraph.setSelectionFromPoints(dragStartPoint, e.getPoint());
						dragGraph = null;
						dragStartPoint = null;
					}
					repaint();
				}

				@Override
				public void mouseMoved(MouseEvent e) {
					lastMouse = e.getPoint();
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					lastMouse = null;
					repaint();
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		Point getMousePositionInPanel() {
			return lastMouse;
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			ensureGraphs();
			applyGraphSizes();
			graphs().syncFrames(profileSampleStore);
			graphs().paintStacked((Graphics2D) g, PANEL_PADDING, PANEL_PADDING);
		}
	}

	private class LegendPanel extends JPanel {
		private final List<LegendHit> hits = new ArrayList<>();

		LegendPanel() {
			setBackground(LEGEND_BACKGROUND);
			setOpaque(true);
			setPreferredSize(new Dimension(LEGEND_WIDTH, 200));

			MouseAdapter mouse = new MouseAdapter() {
				@Override
				public void mouseMoved(MouseEvent e) {
					Object prev = hoveredLegendKey;
					hoveredLegendKey = findKeyAt(e.getPoint());
					setCursor(hoveredLegendKey != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
					if (prev != hoveredLegendKey) {
						repaint();
						if (graphPanel != null)
							graphPanel.repaint();
					}
				}

				@Override
				public void mouseExited(MouseEvent e) {
					if (hoveredLegendKey != null) {
						hoveredLegendKey = null;
						setCursor(Cursor.getDefaultCursor());
						repaint();
						if (graphPanel != null)
							graphPanel.repaint();
					}
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		@Nullable
		private Object findKeyAt(Point point) {
			for (LegendHit hit : hits) {
				if (hit.bounds.contains(point))
					return hit.key;
			}
			return null;
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			hits.clear();

			List<ProfilerGraphs.LegendCategory> categories = graphs().collectLegendCategories();
			Graphics2D g2d = (Graphics2D) g;
			g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 12);
			Font categoryFont = new Font(Font.SANS_SERIF, Font.BOLD, 11);
			Font rowFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
			g2d.setFont(titleFont);
			FontMetrics titleFm = g2d.getFontMetrics();

			int y = LEGEND_PADDING;
			g2d.setColor(LEGEND_TEXT);
			g2d.drawString("Key", LEGEND_PADDING, y + titleFm.getAscent());
			y += titleFm.getHeight() + 8;

			g2d.setFont(categoryFont);
			FontMetrics categoryFm = g2d.getFontMetrics();
			g2d.setFont(rowFont);
			FontMetrics fm = g2d.getFontMetrics();
			int rowPad = Math.max(0, (LEGEND_ROW_HEIGHT - fm.getHeight()) / 2);

			for (int c = 0; c < categories.size(); c++) {
				ProfilerGraphs.LegendCategory category = categories.get(c);
				if (c > 0)
					y += 6;

				g2d.setFont(categoryFont);
				g2d.setColor(new Color(180, 180, 180));
				g2d.drawString(category.getTitle(), LEGEND_PADDING, y + categoryFm.getAscent());
				y += categoryFm.getHeight() + 4;

				g2d.setFont(rowFont);
				for (GraphComponent.LegendEntry entry : category.getEntries()) {
					Rectangle rowBounds = new Rectangle(0, y, getWidth(), LEGEND_ROW_HEIGHT);
					hits.add(new LegendHit(rowBounds, entry.getKey()));

					boolean hovered = entry.getKey() != null && entry.getKey().equals(hoveredLegendKey);
					if (hovered) {
						g2d.setColor(LEGEND_HOVER_BG);
						g2d.fillRect(rowBounds.x, rowBounds.y, rowBounds.width, rowBounds.height);
					}

					int swatchX = LEGEND_PADDING;
					int swatchY = y + (LEGEND_ROW_HEIGHT - LEGEND_SWATCH) / 2;
					g2d.setColor(entry.getColor());
					g2d.fillRect(swatchX, swatchY, LEGEND_SWATCH, LEGEND_SWATCH);
					g2d.setColor(LEGEND_BORDER);
					g2d.drawRect(swatchX, swatchY, LEGEND_SWATCH, LEGEND_SWATCH);

					g2d.setColor(LEGEND_TEXT);
					String label = entry.getName();
					int textX = swatchX + LEGEND_SWATCH + 8;
					int maxTextWidth = getWidth() - textX - LEGEND_PADDING;
					if (fm.stringWidth(label) > maxTextWidth)
						label = truncate(fm, label, maxTextWidth);
					g2d.drawString(label, textX, y + rowPad + fm.getAscent());

					y += LEGEND_ROW_HEIGHT;
				}
			}

			int preferredHeight = Math.max(y + LEGEND_PADDING, getParent() != null ? getParent().getHeight() : y);
			Dimension preferred = new Dimension(LEGEND_WIDTH - 16, preferredHeight);
			if (!preferred.equals(getPreferredSize()))
				setPreferredSize(preferred);
		}

		private String truncate(FontMetrics fm, String text, int maxWidth) {
			if (maxWidth <= 0)
				return "";
			String ellipsis = "...";
			if (fm.stringWidth(ellipsis) > maxWidth)
				return "";
			StringBuilder sb = new StringBuilder(text);
			while (sb.length() > 0 && fm.stringWidth(sb + ellipsis) > maxWidth)
				sb.setLength(sb.length() - 1);
			return sb + ellipsis;
		}

		private final class LegendHit {
			final Rectangle bounds;
			final Object key;

			LegendHit(Rectangle bounds, Object key) {
				this.bounds = bounds;
				this.key = key;
			}
		}
	}
}
