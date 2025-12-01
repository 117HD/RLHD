package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import rs117.hd.HdPlugin;
import rs117.hd.utils.FrameTimingsRecorder;
import rs117.hd.utils.NpcDisplacementCache;

import static rs117.hd.utils.MathUtils.*;

@Singleton
public class FrameTimerOverlay extends OverlayPanel implements FrameTimer.Listener, MouseListener {

	private static final FrameTimerSubCategory[] TABS = FrameTimerSubCategory.values();
	private static final int TAB_HEIGHT = 20;
	private static final int TAB_PADDING = 2;
	private static final int PANEL_BORDER = 4;

	private static final Color TAB_ACTIVE_COLOR = ComponentConstants.STANDARD_BACKGROUND_COLOR;

	private static final Color TAB_INACTIVE_COLOR = new Color(
		ComponentConstants.STANDARD_BACKGROUND_COLOR.getRed() - 10,
		ComponentConstants.STANDARD_BACKGROUND_COLOR.getGreen() - 10,
		ComponentConstants.STANDARD_BACKGROUND_COLOR.getBlue() - 10
	);

	private static final Color TAB_HOVER_COLOR = new Color(
		ComponentConstants.STANDARD_BACKGROUND_COLOR.getRed() - 5,
		ComponentConstants.STANDARD_BACKGROUND_COLOR.getGreen() - 5,
		ComponentConstants.STANDARD_BACKGROUND_COLOR.getBlue() - 5
	);

	private static final Color TAB_TEXT_COLOR = Color.WHITE;

	private static final Color TAB_BORDER_COLOR = new Color(0, 0, 0, 100);

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private FrameTimingsRecorder frameTimingsRecorder;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	private final ArrayDeque<FrameTimings> frames = new ArrayDeque<>();
	private final long[] timings = new long[Timer.TIMERS.length];
	private final StringBuilder sb = new StringBuilder();
	private FrameTimerSubCategory selectedTab = FrameTimerSubCategory.TIMINGS;
	private FrameTimerSubCategory hoveredTab = null;

	@Inject
	public FrameTimerOverlay(HdPlugin plugin) {
		super(plugin);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.TOP_RIGHT);
		panelComponent.setPreferredSize(new Dimension(215, 200));
	}

	public void setActive(boolean activate) {
		if (activate) {
			frameTimer.addTimingsListener(this);
			overlayManager.add(this);
			mouseManager.registerMouseListener(this);
		} else {
			frameTimer.removeTimingsListener(this);
			overlayManager.remove(this);
			mouseManager.unregisterMouseListener(this);
			frames.clear();
		}
	}

	@Override
	public Rectangle getBounds() {
		var bounds = super.getBounds();

		if (bounds != null) {
			var preferredSize = getPreferredSize();
			if (preferredSize != null && bounds.height < preferredSize.height) {
				bounds.height = preferredSize.height;
			}
		}
		return bounds;
	}

	@Override
	public void onFrameCompletion(FrameTimings timings) {
		long now = System.currentTimeMillis();
		while (!frames.isEmpty()) {
			if (now - frames.peekFirst().frameTimestamp < 10e3) // remove older entries
				break;
			frames.removeFirst();
		}
		frames.addLast(timings);
	}

	@Override
	public Dimension render(Graphics2D g) {
		long time = System.nanoTime();
		var boldFont = FontManager.getRunescapeBoldFont();

		var children = panelComponent.getChildren();
		if (!getAverageTimings()) {
			children.add(TitleComponent.builder()
				.text("Waiting for data...")
				.build());
			Dimension result = renderTabsAndPanel(g);
			frameTimer.cumulativeError += System.nanoTime() - time;
			return result;
		}

		long totalCpuTime = timings[Timer.DRAW_FRAME.ordinal()];
		long totalGpuTime = timings[Timer.RENDER_FRAME.ordinal()];
		for (var t : Timer.TIMERS) {
			if (t != Timer.DRAW_FRAME && !t.isGpuTimer) {
				totalCpuTime += timings[t.ordinal()];
			}
			if (t != Timer.RENDER_FRAME && t.isGpuTimer) {
				totalGpuTime += timings[t.ordinal()];
			}
		}

		long cpuTime = 0;
		long gpuTime = 0;
		boolean hasCpuTimers = false;
		boolean hasGpuTimers = false;

		for (var t : Timer.TIMERS) {
			if (t.subCategory == selectedTab) {
				if (t.isGpuTimer) {
					hasGpuTimers = true;
					gpuTime += timings[t.ordinal()];
				} else {
					hasCpuTimers = true;
					cpuTime += timings[t.ordinal()];
				}
			}
		}

		if (hasCpuTimers && cpuTime > 0) {
			addTiming("CPU", cpuTime, true);
			for (var t : Timer.TIMERS) {
				if (t.subCategory == selectedTab && !t.isGpuTimer) {
					addTiming(t, timings);
				}
			}
		}

		if (hasGpuTimers && gpuTime > 0) {
			addTiming("GPU", gpuTime, true);
			for (var t : Timer.TIMERS) {
				if (t.subCategory == selectedTab && t.isGpuTimer) {
					addTiming(t, timings);
				}
			}
		}

		if (selectedTab == FrameTimerSubCategory.STATS) {
			children.add(LineComponent.builder()
				.left("Scene Stats:")
				.right("")
				.leftFont(boldFont)
				.build());

			if (plugin.getSceneContext() != null) {
				var sceneContext = plugin.getSceneContext();
				String lightsText = String.format("%d/%d", sceneContext.numVisibleLights, sceneContext.lights.size());
				children.add(LineComponent.builder()
					.left("Lights:")
					.right(lightsText)
					.build());
			}

			children.add(LineComponent.builder()
				.left("Tiles:")
				.right(String.valueOf(plugin.getDrawnTileCount()))
				.build());
			children.add(LineComponent.builder()
				.left("Static Renderables:")
				.right(String.valueOf(plugin.getDrawnStaticRenderableCount()))
				.build());
			children.add(LineComponent.builder()
				.left("Dynamic Renderables:")
				.right(String.valueOf(plugin.getDrawnDynamicRenderableCount()))
				.build());
			children.add(LineComponent.builder()
				.left("NPC Displacement Cache Size:")
				.right(String.valueOf(npcDisplacementCache.size()))
				.build());
		}

		if (hasCpuTimers || hasGpuTimers) {
			children.add(LineComponent.builder()
				.left("───────────────────")
				.right("")
				.build());

			children.add(LineComponent.builder()
				.left("Estimated bottleneck:")
				.right(totalCpuTime > totalGpuTime ? "CPU" : "GPU")
				.leftFont(boldFont)
				.rightFont(boldFont)
				.build());

			String fpsText = String.format("%.1f FPS", 1e9 / max(totalCpuTime, totalGpuTime));
			children.add(LineComponent.builder()
				.left("Estimated FPS:")
				.right(fpsText)
				.leftFont(boldFont)
				.rightFont(boldFont)
				.build());

			String errorText = String.format("%d ns", frameTimer.errorCompensation);
			children.add(LineComponent.builder()
				.left("Error compensation:")
				.right(errorText)
				.build());

			if (frameTimingsRecorder.isCapturingSnapshot()) {
				String snapshotText = String.format("%d%%", frameTimingsRecorder.getProgressPercentage());
				children.add(LineComponent.builder()
					.left("Capturing Snapshot...")
					.right(snapshotText)
					.leftFont(boldFont)
					.rightFont(boldFont)
					.build());
			}
		}

		Dimension result = renderTabsAndPanel(g);
		frameTimer.cumulativeError += System.nanoTime() - time;
		return result;
	}

	private Dimension renderTabsAndPanel(Graphics2D g) {
		var preferredSize = getPreferredSize();
		if (preferredSize == null) {
			preferredSize = panelComponent.getPreferredSize();
			if (preferredSize == null) {
				preferredSize = new Dimension(215, 200);
			}
		}
		
		var width = preferredSize.width;
		var boldFont = FontManager.getRunescapeBoldFont();

		var oldTransform = g.getTransform();

		int tabAreaWidth = width - (PANEL_BORDER * 2) - (TAB_PADDING * 2);
		int tabWidth = tabAreaWidth / TABS.length;
		int tabAreaX = PANEL_BORDER + TAB_PADDING;
		
		for (int i = 0; i < TABS.length; i++) {
			int x = tabAreaX + i * tabWidth;
			FrameTimerSubCategory tab = TABS[i];
			boolean isSelected = tab == selectedTab;
			boolean isHovered = tab == hoveredTab;

			if (isSelected) {
				g.setColor(TAB_ACTIVE_COLOR);
			} else if (isHovered) {
				g.setColor(TAB_HOVER_COLOR);
			} else {
				g.setColor(TAB_INACTIVE_COLOR);
			}
			g.fillRect(x, PANEL_BORDER + TAB_PADDING, tabWidth, TAB_HEIGHT);

			g.setColor(TAB_BORDER_COLOR);
			if (i > 0) {
				g.drawLine(x, PANEL_BORDER + TAB_PADDING, x, PANEL_BORDER + TAB_PADDING + TAB_HEIGHT);
			}
			g.drawRect(x, PANEL_BORDER + TAB_PADDING, tabWidth - 1, TAB_HEIGHT - 1);

			g.setColor(TAB_TEXT_COLOR);
			g.setFont(isSelected ? boldFont : FontManager.getRunescapeFont());
			var fm = g.getFontMetrics();
			String tabName = tab.name().charAt(0) + tab.name().substring(1).toLowerCase();
			var textWidth = fm.stringWidth(tabName);
			var textX = x + (tabWidth - textWidth) / 2;
			var textY = PANEL_BORDER + TAB_PADDING + (TAB_HEIGHT + fm.getAscent()) / 2 - 2;
			g.drawString(tabName, textX, textY);
		}

		g.translate(0, TAB_HEIGHT + (TAB_PADDING * 2) + PANEL_BORDER);

		Dimension result = super.render(g);

		g.setTransform(oldTransform);

		if (result != null) {
			result = new Dimension(result.width, result.height + TAB_HEIGHT + (TAB_PADDING * 2) + PANEL_BORDER);
		}
		
		return result;
	}

	private boolean getAverageTimings() {
		if (frames.isEmpty())
			return false;

		Arrays.fill(timings, 0);
		for (var frame : frames)
			for (int i = 0; i < frame.timers.length; i++)
				timings[i] += frame.timers[i];

		for (int i = 0; i < timings.length; i++)
			timings[i] = max(0, timings[i] / frames.size());

		return true;
	}

	private void addTiming(Timer timer, long[] timings) {
		addTiming(timer.name, timings[timer.ordinal()], false);
	}

	private void addTiming(String name, long nanos, boolean bold) {
		if (nanos == 0)
			return;

		// Round timers to zero if they are less than a microsecond off
		String result = "~0 ms";
		if (abs(nanos) > 1e3) {
			result = sb.append(round(nanos / 1e3) / 1e3).append(" ms").toString();
			sb.setLength(0);
		}
		var font = bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont();
		panelComponent.getChildren().add(LineComponent.builder()
			.left(name + ":")
			.right(result)
			.leftFont(font)
			.rightFont(font)
			.build());
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		var bounds = getBounds();
		if (bounds == null)
			return e;

		int clickableHeight = TAB_HEIGHT + (TAB_PADDING * 2);
		Rectangle header = new Rectangle(bounds.x, bounds.y, bounds.width, clickableHeight);
		if (header.contains(e.getX(), e.getY())) {
			int tabAreaWidth = bounds.width - (PANEL_BORDER * 2) - (TAB_PADDING * 2);
			int tabWidth = tabAreaWidth / TABS.length;
			int tabAreaX = bounds.x + PANEL_BORDER + TAB_PADDING;
			int clickedTabIndex = (e.getX() - tabAreaX) / tabWidth;
			if (clickedTabIndex >= 0 && clickedTabIndex < TABS.length) {
				selectedTab = TABS[clickedTabIndex];
				e.consume();
			}
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e) {
		hoveredTab = null;
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) {
		var bounds = getBounds();
		if (bounds == null) {
			hoveredTab = null;
			return e;
		}

		int hoverableHeight = TAB_HEIGHT + (TAB_PADDING * 2);
		Rectangle header = new Rectangle(bounds.x, bounds.y, bounds.width, hoverableHeight);
		if (header.contains(e.getX(), e.getY())) {
			int tabAreaWidth = bounds.width - (PANEL_BORDER * 2) - (TAB_PADDING * 2);
			int tabWidth = tabAreaWidth / TABS.length;
			int tabAreaX = bounds.x + PANEL_BORDER + TAB_PADDING;
			int hoveredTabIndex = (e.getX() - tabAreaX) / tabWidth;
			if (hoveredTabIndex >= 0 && hoveredTabIndex < TABS.length) {
				hoveredTab = TABS[hoveredTabIndex];
			} else {
				hoveredTab = null;
			}
		} else {
			hoveredTab = null;
		}
		return e;
	}
}
