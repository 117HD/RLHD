package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Formatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.runelite.api.*;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.components.SwatchComponent;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.FrameTimingsRecorder;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.jobs.JobSystem;

import static rs117.hd.renderer.zone.SceneManager.MAX_WORLDVIEWS;
import static rs117.hd.utils.MathUtils.*;

@Singleton
public class FrameTimerOverlay extends OverlayPanel implements FrameTimer.Listener, MouseListener {
	private static final int PANEL_HORIZONTAL_PADDING = 8;
	private static final int SWATCH_WIDTH = 10;
	private static final int SWATCH_GAP = 4;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private Client client;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private FrameTimingsStore frameTimingsStore;

	@Inject
	private FrameTimerUI ui;

	@Inject
	private FrameTimingsRecorder frameTimingsRecorder;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private JobSystem jobSystem;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private FrameTimerGraphOverlay frameTimerGraphOverlay;

	private final HdPlugin plugin;
	private final long[] timings = new long[Timer.TIMERS.length];
	private float cpuLoad;
	private final LineCache lineCache = new LineCache();
	private Header header;
	private Header settingsHeader;
	private DetachedSettingsPanel detachedSettingsPanel;
	private final Map<FrameTimerUI.Tab, DetachedPanel> detachedPanels = new EnumMap<>(FrameTimerUI.Tab.class);

	private final StringBuilder sb = new StringBuilder();
	private final Formatter formatter = new Formatter(sb);

	@Getter
	@Nullable
	private Timer hoveredTimer;

	private boolean overlayActive;
	private int mainPanelWidth;
	private int mainContentLineWidth;
	private int mainPanelHeight;

	@Inject
	public FrameTimerOverlay(HdPlugin plugin) {
		super(plugin);
		this.plugin = plugin;
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.TOP_RIGHT);
		panelComponent.setPreferredSize(new Dimension(280, 200));
		hoveredTimer = null;
	}

	private Header getHeader() {
		if (header == null) {
			header = new Header(
				ui,
				frameTimingsRecorder,
				frameTimingsStore,
				false,
				ui::selectTab,
				ui::toggleSettings,
				ui::detachSettings,
				() -> ui.setSettingsDetached(false),
				tab -> ui.setDetached(tab, true),
				ui::toggleGraph,
				ui::toggleHidden,
				tab -> ui.setDetached(tab, false)
			);
		}
		return header;
	}

	private Header getSettingsHeader() {
		if (settingsHeader == null) {
			settingsHeader = new Header(
				ui,
				frameTimingsRecorder,
				frameTimingsStore,
				true,
				ui::selectTab,
				ui::toggleSettings,
				ui::detachSettings,
				() -> ui.setSettingsDetached(false),
				tab -> ui.setDetached(tab, true),
				ui::toggleGraph,
				ui::toggleHidden,
				tab -> ui.setDetached(tab, false)
			);
		}
		return settingsHeader;
	}

	public void setActive(boolean activate) {
		if (overlayActive == activate)
			return;

		overlayActive = activate;
		if (activate) {
			frameTimer.addTimingsListener(frameTimingsStore);
			overlayManager.add(this);
			mouseManager.registerMouseListener(0, this);
			ui.setChangeListener(u -> syncDetachedPanels());
			syncDetachedPanels();
		} else {
			frameTimer.removeTimingsListener(frameTimingsStore);
			overlayManager.remove(this);
			mouseManager.unregisterMouseListener(this);
			frameTimingsStore.clear();
			deactivateDetachedPanels();
		}
	}

	private void syncDetachedPanels() {
		int detachedIndex = 0;
		for (FrameTimerUI.Tab tab : FrameTimerUI.Tab.values()) {
			if (!tab.isDetachable())
				continue;

			if (ui.isDetached(tab)) {
				DetachedPanel panel = detachedPanels.computeIfAbsent(tab, DetachedPanel::new);
				if (!panel.isActive()) {
					panel.setLocationOffset(detachedIndex);
					panel.setActive(true);
				}
				detachedIndex++;
			} else {
				DetachedPanel panel = detachedPanels.get(tab);
				if (panel != null)
					panel.setActive(false);
			}
		}

		if (ui.isSettingsDetached()) {
			if (detachedSettingsPanel == null)
				detachedSettingsPanel = new DetachedSettingsPanel();
			if (!detachedSettingsPanel.isActive()) {
				detachedSettingsPanel.setLocationOffset(detachedIndex);
				detachedSettingsPanel.setActive(true);
			}
		} else if (detachedSettingsPanel != null) {
			detachedSettingsPanel.setActive(false);
		}
	}

	private void deactivateDetachedPanels() {
		for (DetachedPanel panel : detachedPanels.values())
			panel.setActive(false);
		if (detachedSettingsPanel != null)
			detachedSettingsPanel.setActive(false);
	}

	@Override
	public void onFrameCompletion(FrameTimings timings) {
		frameTimingsStore.onFrameCompletion(timings);
	}

	@Override
	public Dimension render(Graphics2D g) {
		long time = System.nanoTime();

		lineCache.syncGraphOverlayState(ui.isGraphEnabled());

		int contentLineWidth = getHeader().computeContentLineWidth(g);
		int panelWidth = getHeader().computePanelWidth(g) + PANEL_HORIZONTAL_PADDING;
		ui.setLineWidth(contentLineWidth);
		panelComponent.setPreferredSize(new Dimension(panelWidth, panelComponent.getPreferredSize().height));
		getHeader().setPreferredSize(new Dimension(panelWidth - PANEL_HORIZONTAL_PADDING, 0));

		var children = panelComponent.getChildren();
		children.add(getHeader());

		if (!getAverageTimings()) {
			children.add(TitleComponent.builder()
				.text("Waiting for data...")
				.build());
		} else if (!ui.isInlineSettingsOpen()) {
			renderTab(ui.getSelectedTab(), panelComponent, lineCache, timings, cpuLoad, contentLineWidth);
		}

		var result = super.render(g);
		mainPanelWidth = panelWidth;
		mainContentLineWidth = contentLineWidth;
		if (result.height > 0)
			mainPanelHeight = result.height;
		updateHoveredLine();
		frameTimer.cumulativeError += System.nanoTime() - time;
		return result;
	}

	private void updateHoveredLine() {
		hoveredTimer = null;

		var mouse = client.getMouseCanvasPosition();
		if (mouse.getX() < 0 || mouse.getY() < 0)
			return;

		Rectangle panelBounds = getBounds();
		if (panelBounds.width <= 0 || panelBounds.height <= 0)
			return;

		hoveredTimer = lineCache.getHoveredTimer(
			panelComponent,
			panelBounds.x,
			panelBounds.y,
			mouse.getX(),
			mouse.getY()
		);
	}

	private boolean getAverageTimings() {
		var frames = frameTimingsStore.getFrames();
		if (frames.isEmpty())
			return false;

		Arrays.fill(timings, 0);
		cpuLoad = 0;
		for (var frame : frames) {
			for (int i = 0; i < frame.timers.length; i++)
				timings[i] += frame.timers[i];
			cpuLoad += frame.cpuLoad;
		}

		for (int i = 0; i < timings.length; i++)
			timings[i] = Math.max(0, timings[i] / frames.size());
		cpuLoad /= frames.size();

		return true;
	}

	private void renderTab(
		FrameTimerUI.Tab tab,
		PanelComponent panel,
		LineCache cache,
		long[] timings,
		float cpuLoad,
		int lineWidth
	) {
		cache.syncLineWidth(lineWidth);

		switch (tab) {
			case ALL:
				buildCpu(panel, cache, lineWidth, timings);
				buildAsync(panel, cache, lineWidth, timings);
				buildGpu(panel, cache, lineWidth, timings);
				buildStats(panel, cache, lineWidth);
				break;
			case CPU:
				buildCpu(panel, cache, lineWidth, timings);
				break;
			case ASYNC:
				buildAsync(panel, cache, lineWidth, timings);
				break;
			case GPU:
				buildGpu(panel, cache, lineWidth, timings);
				break;
			case STATS:
				buildStats(panel, cache, lineWidth);
				break;
		}

		panel.getChildren().add(LineComponent.builder()
			.preferredSize(new Dimension(lineWidth, 6))
			.build());

		buildSummary(panel, lineWidth, timings, cpuLoad);
		buildSnapshot(panel, lineWidth);
	}

	private void buildStats(PanelComponent panel, LineCache cache, int lineWidth) {
		var boldFont = FontManager.getRunescapeBoldFont();
		addLine(panel, lineWidth, LineComponent.builder()
			.leftFont(boldFont)
			.left("Stats:"));

		buildSceneContent(panel, lineWidth);
		buildStreamingContent(panel, cache, lineWidth);
	}

	private void addLine(PanelComponent panel, int lineWidth, LineComponent.LineComponentBuilder builder) {
		panel.getChildren().add(builder.preferredSize(new Dimension(lineWidth, 0)).build());
	}

	private void buildSummary(PanelComponent panel, int lineWidth, long[] timings, float cpuLoad) {
		var boldFont = FontManager.getRunescapeBoldFont();
		long cpuTime = timings[Timer.DRAW_FRAME.ordinal()];
		long gpuTime = timings[Timer.RENDER_FRAME.ordinal()];

		addLine(panel, lineWidth, LineComponent.builder()
			.leftFont(boldFont)
			.left("Estimated bottleneck:")
			.rightFont(boldFont)
			.right(cpuTime > gpuTime ? "CPU" : "GPU"));

		addLine(panel, lineWidth, LineComponent.builder()
			.leftFont(boldFont)
			.left("Estimated FPS:")
			.rightFont(boldFont)
			.right(format("%.1f FPS", 1e9 / max(cpuTime, gpuTime))));

		addLine(panel, lineWidth, LineComponent.builder()
			.left("Error compensation:")
			.right(format("%d ns", frameTimer.errorCompensation)));

		if (cpuLoad > 0) {
			addLine(panel, lineWidth, LineComponent.builder()
				.left("CPU Load:")
				.right((int) (cpuLoad * 100) + "%"));
		}

		addLine(panel, lineWidth, LineComponent.builder()
			.left("Pooled array size:")
			.right(formatBytes(PooledArrayType.getCurrentTotalCacheSize())));

		addLine(panel, lineWidth, LineComponent.builder()
			.left("Garbage collection count:")
			.right(String.valueOf(plugin.getGarbageCollectionCount())));

		addLine(panel, lineWidth, LineComponent.builder()
			.left("Power saving mode:")
			.right(plugin.isPowerSaving ? "ON" : "OFF"));

		if (!frameTimingsStore.isCapturing()) {
			var boldFont2 = FontManager.getRunescapeBoldFont();
			addLine(panel, lineWidth, LineComponent.builder()
				.leftFont(boldFont2)
				.left("Live capture:")
				.rightFont(boldFont2)
				.right("FROZEN"));
		}
	}

	private void buildCpu(PanelComponent panel, LineCache cache, int lineWidth, long[] timings) {
		long cpuTime = timings[Timer.DRAW_FRAME.ordinal()];
		addTiming(panel, cache, lineWidth, "CPU", cpuTime, true, Timer.DRAW_FRAME.color, Timer.DRAW_FRAME);
		for (var t : Timer.TIMERS) {
			if (t.isCpuTimer() && t != Timer.DRAW_FRAME)
				addTiming(panel, cache, lineWidth, t, timings);
		}
	}

	private void buildAsync(PanelComponent panel, LineCache cache, int lineWidth, long[] timings) {
		long asyncCpuTime = 0;
		for (var t : Timer.TIMERS)
			if (t.isAsyncCpuTimer())
				asyncCpuTime += timings[t.ordinal()];

		addTiming(panel, cache, lineWidth, "Async", asyncCpuTime, true, null, null);
		for (var t : Timer.TIMERS)
			if (t.isAsyncCpuTimer())
				addTiming(panel, cache, lineWidth, t, timings);
	}

	private void buildGpu(PanelComponent panel, LineCache cache, int lineWidth, long[] timings) {
		long gpuTime = timings[Timer.RENDER_FRAME.ordinal()];
		addTiming(panel, cache, lineWidth, "GPU", gpuTime, true, Timer.RENDER_FRAME.color, Timer.RENDER_FRAME);
		for (var t : Timer.TIMERS)
			if (t.isGpuTimer() && t != Timer.RENDER_FRAME)
				addTiming(panel, cache, lineWidth, t, timings);
	}

	private void buildSceneContent(PanelComponent panel, int lineWidth) {
		if (plugin.getSceneContext() != null) {
			var sceneContext = plugin.getSceneContext();
			addLine(panel, lineWidth, LineComponent.builder()
				.left("Lights:")
				.right(format("%d/%d", sceneContext.numVisibleLights, sceneContext.lights.size())));
		}

		if (plugin.renderer instanceof ZoneRenderer) {
			addLine(panel, lineWidth, LineComponent.builder()
				.left("Dynamic renderables:")
				.right(String.valueOf(plugin.getDrawnDynamicRenderableCount())));

			addLine(panel, lineWidth, LineComponent.builder()
				.left("Temp renderables:")
				.right(String.valueOf(plugin.getDrawnTempRenderableCount())));
		} else {
			addLine(panel, lineWidth, LineComponent.builder()
				.left("Tiles:")
				.right(String.valueOf(plugin.getDrawnTileCount())));

			addLine(panel, lineWidth, LineComponent.builder()
				.left("Static renderables:")
				.right(String.valueOf(plugin.getDrawnStaticRenderableCount())));

			addLine(panel, lineWidth, LineComponent.builder()
				.left("Dynamic renderables:")
				.right(String.valueOf(plugin.getDrawnDynamicRenderableCount())));

			addLine(panel, lineWidth, LineComponent.builder()
				.left("NPC displacement cache size:")
				.right(String.valueOf(npcDisplacementCache.size())));
		}
	}

	private void buildStreamingContent(PanelComponent panel, LineCache cache, int lineWidth) {
		WorldViewContext root = sceneManager.getRoot();
		addTiming(panel, cache, lineWidth, "Root Scene Load", root.loadTime, false, null, null);
		addTiming(panel, cache, lineWidth, "Root Scene Upload", root.uploadTime, false, null, null);
		addTiming(panel, cache, lineWidth, "Root Scene Swap", root.sceneSwapTime, false, null, null);

		int subSceneCount = 0;
		long subSceneLoadTime = 0;
		long subSceneUploadTime = 0;
		long subSceneSwapTime = 0;

		for (int worldViewId = 0; worldViewId < MAX_WORLDVIEWS; worldViewId++) {
			WorldViewContext subscene = sceneManager.getContext(worldViewId);
			if (subscene != null) {
				subSceneCount++;
				subSceneLoadTime += subscene.loadTime;
				subSceneUploadTime += subscene.uploadTime;
				subSceneSwapTime += subscene.sceneSwapTime;
			}
		}

		if (subSceneCount > 0) {
			addTiming(panel, cache, lineWidth, "Avg SubScene Load", subSceneLoadTime / subSceneCount, false, null, null);
			addTiming(panel, cache, lineWidth, "Avg SubScene Upload", subSceneUploadTime / subSceneCount, false, null, null);
			addTiming(panel, cache, lineWidth, "Avg SubScene Swap", subSceneSwapTime / subSceneCount, false, null, null);
		}

		addLine(panel, lineWidth, LineComponent.builder()
			.left("Sub Scene Count:")
			.right(String.valueOf(subSceneCount)));

		addLine(panel, lineWidth, LineComponent.builder()
			.left("Streaming Zones:")
			.right(String.valueOf(jobSystem.getWorkQueueSize())));
	}

	private void buildSnapshot(PanelComponent panel, int lineWidth) {
		if (!frameTimingsRecorder.isCapturingSnapshot())
			return;

		var boldFont = FontManager.getRunescapeBoldFont();
		addLine(panel, lineWidth, LineComponent.builder()
			.leftFont(boldFont)
			.left("Recording...")
			.rightFont(boldFont)
			.right(format(
				"%ds left (%d%%)",
				frameTimingsRecorder.getRemainingSeconds(),
				frameTimingsRecorder.getProgressPercentage()
			)));
	}

	private void addTiming(PanelComponent panel, LineCache cache, int lineWidth, Timer timer, long[] timings) {
		addTiming(panel, cache, lineWidth, timer.name, timings[timer.ordinal()], false, timer.color, timer);
	}

	private void addTiming(
		PanelComponent panel,
		LineCache cache,
		int lineWidth,
		String name,
		long nanos,
		boolean bold,
		@Nullable Color swatchColor,
		@Nullable Timer timer
	) {
		if (nanos == 0)
			return;

		String result = "~0 ms";
		if (abs(nanos) > 1e3) {
			sb.setLength(0);
			result = sb.append(round(nanos / 1e3) / 1e3).append(" ms").toString();
		}

		boolean showSwatch = swatchColor != null && frameTimerGraphOverlay.isActive();
		int textLineWidth = showSwatch ? lineWidth - SWATCH_WIDTH - SWATCH_GAP : lineWidth;

		TimerLineEntry entry = cache.lineEntries.get(name);
		if (entry == null || entry.hasSwatch != showSwatch) {
			var font = bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont();
			LineComponent lineComponent = LineComponent.builder()
				.leftFont(font)
				.right(result)
				.rightFont(font)
				.left(name + ":")
				.preferredSize(new Dimension(textLineWidth, 0))
				.build();

			LayoutableRenderableEntity component = lineComponent;

			if (showSwatch) {
				component = SplitComponent.builder()
					.orientation(ComponentOrientation.HORIZONTAL)
					.first(new SwatchComponent(swatchColor))
					.second(lineComponent)
					.gap(new Point(SWATCH_GAP, 0))
					.preferredSize(new Dimension(lineWidth, 0))
					.build();
			}

			entry = new TimerLineEntry(timer, component, lineComponent, showSwatch, textLineWidth);
			cache.lineEntries.put(name, entry);
		} else {
			entry.lineComponent.setRight(result);
			entry.lineComponent.setPreferredSize(new Dimension(textLineWidth, 0));
		}

		panel.getChildren().add(entry.component);
	}

	private String format(String format, Object... args) {
		sb.setLength(0);
		formatter.format(format, args);
		return sb.toString();
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (!overlayActive)
			return e;

		Rectangle panelBounds = getBounds();
		if (panelBounds.width <= 0 || panelBounds.height <= 0)
			return e;

		var mouse = client.getMouseCanvasPosition();
		if (mouse.getX() < 0 || mouse.getY() < 0)
			return e;

		int localX = mouse.getX() - panelBounds.x;
		int localY = mouse.getY() - panelBounds.y;

		Rectangle headerBounds = getHeader().getBounds();
		if (headerBounds.width <= 0 || headerBounds.height <= 0)
			return e;

		int headerX = localX - headerBounds.x;
		int headerY = localY - headerBounds.y;
		if (headerX < 0 || headerY < 0 || headerX >= headerBounds.width || headerY >= headerBounds.height)
			return e;

		boolean middleClick = SwingUtilities.isMiddleMouseButton(e);

		if (getHeader().handleClick(headerX, headerY, middleClick))
			e.consume();

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

	static final class LineCache {
		private final IdentityHashMap<String, TimerLineEntry> lineEntries = new IdentityHashMap<>();
		private boolean graphOverlayActive;
		private int lineWidth;

		void syncLineWidth(int lineWidth) {
			if (lineWidth != this.lineWidth) {
				this.lineWidth = lineWidth;
				lineEntries.clear();
			}
		}

		void syncGraphOverlayState(boolean graphActive) {
			if (graphActive != graphOverlayActive) {
				lineEntries.clear();
				graphOverlayActive = graphActive;
			}
		}

		@Nullable
		Timer getHoveredTimer(PanelComponent panel, int panelX, int panelY, int mouseX, int mouseY) {
			for (var entry : lineEntries.values()) {
				if (entry.timer == null)
					continue;

				Rectangle lineBounds = entry.component.getBounds();
				if (lineBounds.width <= 0 || lineBounds.height <= 0)
					continue;

				lineBounds = new Rectangle(
					panelX + lineBounds.x,
					panelY + lineBounds.y,
					lineBounds.width,
					lineBounds.height
				);

				if (lineBounds.contains(mouseX, mouseY))
					return entry.timer;
			}
			return null;
		}
	}

	@RequiredArgsConstructor
	static final class TimerLineEntry {
		final Timer timer;
		final LayoutableRenderableEntity component;
		final LineComponent lineComponent;
		final boolean hasSwatch;
		final int textLineWidth;
	}

	private static class Header implements LayoutableRenderableEntity {
		private static final int BUTTON_PADDING_X = 6;
		private static final int TAB_GAP = 2;
		private static final int SETTINGS_GAP = 4;
		static final int SETTINGS_MENU_MIN_WIDTH = 230;
		private static final int MENU_PADDING = 6;
		private static final int SECTION_GAP = 4;

		static final Color SETTINGS_BACKGROUND = new Color(25, 25, 25, 230);
		private static final Color SETTINGS_BORDER = new Color(100, 100, 100, 200);
		private static final Color MENU_TEXT = Color.WHITE;
		private static final Color MENU_MUTED = new Color(160, 160, 160);
		private static final Color MENU_ON = new Color(120, 220, 120);
		private static final Color MENU_OFF = new Color(220, 120, 120);
		private static final Color MENU_ACTION = new Color(140, 190, 255);

		private final FrameTimerUI state;
		private final FrameTimingsRecorder recorder;
		private final FrameTimingsStore timingsStore;
		private final boolean settingsOverlay;
		private final Consumer<FrameTimerUI.Tab> onTabSelected;
		private final Runnable onSettingsToggle;
		private final Runnable onSettingsDetach;
		private final Runnable onSettingsDock;
		private final Consumer<FrameTimerUI.Tab> onTabDetached;
		private final Runnable onGraphToggle;
		private final Consumer<FrameTimerUI.Tab> onTabVisibilityToggle;
		private final Consumer<FrameTimerUI.Tab> onTabAttach;

		private final Rectangle bounds = new Rectangle();
		private final List<HitRegion> hitRegions = new ArrayList<>();

		@Getter
		private final List<Button> tabButtons = new ArrayList<>();

		Header(
			FrameTimerUI state,
			FrameTimingsRecorder recorder,
			FrameTimingsStore timingsStore,
			boolean settingsOverlay,
			Consumer<FrameTimerUI.Tab> onTabSelected,
			Runnable onSettingsToggle,
			Runnable onSettingsDetach,
			Runnable onSettingsDock,
			Consumer<FrameTimerUI.Tab> onTabDetached,
			Runnable onGraphToggle,
			Consumer<FrameTimerUI.Tab> onTabVisibilityToggle,
			Consumer<FrameTimerUI.Tab> onTabAttach
		) {
			this.state = state;
			this.recorder = recorder;
			this.timingsStore = timingsStore;
			this.settingsOverlay = settingsOverlay;
			this.onTabSelected = onTabSelected;
			this.onSettingsToggle = onSettingsToggle;
			this.onSettingsDetach = onSettingsDetach;
			this.onSettingsDock = onSettingsDock;
			this.onTabDetached = onTabDetached;
			this.onGraphToggle = onGraphToggle;
			this.onTabVisibilityToggle = onTabVisibilityToggle;
			this.onTabAttach = onTabAttach;
		}

		int computeContentLineWidth(Graphics2D graphics) {
			return measureTabRowWidth(graphics);
		}

		int computePanelWidth(Graphics2D graphics) {
			int tabRowWidth = measureTabRowWidth(graphics);
			if (state.isInlineSettingsOpen())
				return Math.max(tabRowWidth, SETTINGS_MENU_MIN_WIDTH);
			return tabRowWidth;
		}

		private int measureTabRowWidth(Graphics2D graphics) {
			Font font = FontManager.getRunescapeFont();
			var fm = graphics.getFontMetrics(font);
			int x = 0;

			for (FrameTimerUI.Tab tab : state.getTabBarTabs())
				x += buttonWidth(fm, tab.getLabel()) + TAB_GAP;

			String settingsLabel = settingsButtonLabel();
			x += SETTINGS_GAP + buttonWidth(fm, settingsLabel);
			return x;
		}

		private String settingsButtonLabel() {
			if (state.isSettingsDetached())
				return "...";
			return state.isSettingsOpen() ? "X" : "...";
		}

		private static int buttonWidth(FontMetrics fm, String label) {
			return fm.stringWidth(label) + BUTTON_PADDING_X * 2;
		}

		@Override
		public Dimension render(Graphics2D graphics) {
			hitRegions.clear();
			tabButtons.clear();

			if (settingsOverlay) {
				int menuWidth = Math.max(SETTINGS_MENU_MIN_WIDTH, bounds.width);
				int menuHeight = renderSettingsMenu(graphics, 0, 0, menuWidth);
				bounds.setSize(menuWidth, menuHeight);
				return new Dimension(menuWidth, menuHeight);
			}

			int x = 0;
			int y = 0;
			int rowHeight = 0;

			for (FrameTimerUI.Tab tab : state.getTabBarTabs()) {
				var button = new Button(tab.getLabel());
				button.setSelected(tab == state.getSelectedTab());
				button.setPreferredLocation(new Point(x, y));
				tabButtons.add(button);

				Dimension size = button.render(graphics);
				hitRegions.add(new HitRegion(
					new Rectangle(x, y, size.width, size.height),
					() -> onTabSelected.accept(tab),
					tab.isDetachable() ? () -> onTabDetached.accept(tab) : null
				));

				rowHeight = Math.max(rowHeight, size.height);
				x += size.width + TAB_GAP;
			}

			var settingsButton = new Button(settingsButtonLabel());
			settingsButton.setSelected(state.isInlineSettingsOpen() || state.isSettingsDetached());
			settingsButton.setPreferredLocation(new Point(x + SETTINGS_GAP, y));
			Dimension settingsSize = settingsButton.render(graphics);
			hitRegions.add(new HitRegion(
				new Rectangle(x + SETTINGS_GAP, y, settingsSize.width, settingsSize.height),
				onSettingsToggle,
				state.isSettingsDetached() ? null : onSettingsDetach
			));

			rowHeight = Math.max(rowHeight, settingsSize.height);
			int tabRowWidth = x + SETTINGS_GAP + settingsSize.width;
			int totalWidth = tabRowWidth;
			int totalHeight = rowHeight;

			if (state.isInlineSettingsOpen()) {
				int menuY = y + rowHeight + 4;
				int menuWidth = Math.max(tabRowWidth, SETTINGS_MENU_MIN_WIDTH);
				int menuHeight = renderSettingsMenu(graphics, 0, menuY, menuWidth);
				totalHeight = menuY + menuHeight;
				totalWidth = Math.max(totalWidth, menuWidth);
			}

			bounds.setSize(totalWidth, totalHeight);
			return new Dimension(totalWidth, totalHeight);
		}

		private int renderSettingsMenu(Graphics2D graphics, int menuX, int menuY, int menuWidth) {
			Font font = FontManager.getRunescapeFont();
			Font boldFont = FontManager.getRunescapeBoldFont();
			var fm = graphics.getFontMetrics(font);
			var boldFm = graphics.getFontMetrics(boldFont);
			int lineHeight = Math.max(fm.getHeight(), boldFm.getHeight()) + 3;

			int menuHeight = measureSettingsMenuHeight(lineHeight);

			if (!settingsOverlay) {
				graphics.setColor(SETTINGS_BACKGROUND);
				graphics.fillRect(menuX, menuY, menuWidth, menuHeight);
				graphics.setColor(SETTINGS_BORDER);
				graphics.drawRect(menuX, menuY, menuWidth, menuHeight);
			}

			int contentBottom = menuY + MENU_PADDING;

			graphics.setFont(boldFont);
			contentBottom = drawMenuTitle(graphics, menuX, contentBottom, menuWidth, boldFm, lineHeight);

			graphics.setFont(font);
			contentBottom = drawSectionHeader(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Overlays");
			contentBottom = drawToggleRow(
				graphics, menuX, contentBottom, menuWidth, fm, lineHeight,
				"Graph overlay",
				state.isGraphEnabled(),
				onGraphToggle
			);
			contentBottom = drawToggleRow(
				graphics, menuX, contentBottom, menuWidth, fm, lineHeight,
				"Live capture",
				timingsStore.isCapturing(),
				timingsStore::toggleCapturing
			);

			contentBottom += SECTION_GAP;
			contentBottom = drawSectionHeader(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Recording");
			if (recorder.isCapturingSnapshot()) {
				contentBottom = drawStatusRow(
					graphics, menuX, contentBottom, menuWidth, fm, lineHeight,
					"Session active",
					recorder.getRemainingSeconds() + "s left"
				);
			} else {
				contentBottom = drawActionRow(
					graphics, menuX, contentBottom, menuWidth, fm, lineHeight,
					"Start " + recorder.getSnapshotDurationSeconds() + "s recording",
					recorder::recordSnapshot
				);
			}
			contentBottom = drawActionRow(
				graphics, menuX, contentBottom, menuWidth, fm, lineHeight,
				"Open snapshots folder",
				recorder::openSnapshotsDirectory
			);

			contentBottom += SECTION_GAP;
			contentBottom = drawSectionHeader(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Graphs");
			contentBottom = drawHintLine(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Click row to show or hide");

			for (FrameTimerUI.Graph graph : FrameTimerUI.Graph.values())
				contentBottom = drawGraphRow(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, graph);

			contentBottom += SECTION_GAP;
			contentBottom = drawSectionHeader(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Tab bar");
			contentBottom = drawHintLine(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Click row to show or hide");

			for (FrameTimerUI.Tab tab : FrameTimerUI.Tab.values()) {
				if (tab == FrameTimerUI.Tab.ALL)
					continue;
				contentBottom = drawTabRow(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, tab);
			}

			if (!state.getDetachedTabs().isEmpty()) {
				contentBottom += SECTION_GAP;
				contentBottom = drawSectionHeader(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Detached panels");
				contentBottom = drawHintLine(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Click to dock back");

				for (FrameTimerUI.Tab tab : state.getDetachedTabs()) {
					contentBottom = drawActionRow(
						graphics, menuX, contentBottom, menuWidth, fm, lineHeight,
						"Dock " + tab.getLabel(),
						() -> onTabAttach.accept(tab)
					);
				}
			}

			contentBottom += SECTION_GAP;
			if (settingsOverlay) {
				contentBottom = drawActionRow(
					graphics, menuX, contentBottom, menuWidth, fm, lineHeight,
					"Dock settings",
					onSettingsDock
				);
			} else if (!state.isSettingsDetached()) {
				contentBottom = drawActionRow(
					graphics, menuX, contentBottom, menuWidth, fm, lineHeight,
					"Detach settings",
					onSettingsDetach
				);
				contentBottom = drawHintLine(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Middle-click ... to detach");
			}
			drawHintLine(graphics, menuX, contentBottom, menuWidth, fm, lineHeight, "Middle-click tab to detach");

			return menuHeight;
		}

		private int measureSettingsMenuHeight(int lineHeight) {
			int lines = 1;
			lines += 3; // Overlays section
			lines += 3; // Recording section
			lines += 2 + getVisibleGraphCount(); // Graphs section
			lines += 2; // Tab bar section header + hint
			lines += FrameTimerUI.Tab.values().length - 1;
			if (!state.getDetachedTabs().isEmpty())
				lines += 2 + state.getDetachedTabs().size();
			lines += 1; // tip: middle-click tab
			if (settingsOverlay) {
				lines += 1; // dock settings
			} else if (!state.isSettingsDetached()) {
				lines += 2; // detach settings + tip
			}

			int sectionGaps = 4 * SECTION_GAP;
			if (!state.getDetachedTabs().isEmpty())
				sectionGaps += SECTION_GAP;
			sectionGaps += SECTION_GAP; // detach/dock tip section

			return MENU_PADDING * 2 + lines * lineHeight + sectionGaps;
		}

		private int getVisibleGraphCount() {
			return FrameTimerUI.Graph.values().length;
		}

		private int drawMenuTitle(Graphics2D g, int menuX, int y, int menuWidth, FontMetrics fm, int lineHeight) {
			g.setColor(MENU_TEXT);
			g.drawString("Frame Timer Settings", menuX + MENU_PADDING, y + fm.getAscent());
			return y + lineHeight;
		}

		private int drawSectionHeader(Graphics2D g, int menuX, int y, int menuWidth, FontMetrics fm, int lineHeight, String text) {
			g.setColor(MENU_MUTED);
			g.drawString(text, menuX + MENU_PADDING, y + fm.getAscent());
			return y + lineHeight;
		}

		private int drawHintLine(Graphics2D g, int menuX, int y, int menuWidth, FontMetrics fm, int lineHeight, String text) {
			g.setColor(MENU_MUTED);
			g.drawString(text, menuX + MENU_PADDING + 4, y + fm.getAscent());
			return y + lineHeight;
		}

		private int drawToggleRow(
			Graphics2D g,
			int menuX,
			int y,
			int menuWidth,
			FontMetrics fm,
			int lineHeight,
			String label,
			boolean enabled,
			Runnable onClick
		) {
			g.setColor(MENU_TEXT);
			g.drawString(label, menuX + MENU_PADDING + 4, y + fm.getAscent());

			String status = enabled ? "[ ON ]" : "[ OFF ]";
			g.setColor(enabled ? MENU_ON : MENU_OFF);
			int statusWidth = fm.stringWidth(status);
			g.drawString(status, menuX + menuWidth - MENU_PADDING - statusWidth, y + fm.getAscent());

			hitRegions.add(new HitRegion(new Rectangle(menuX, y, menuWidth, lineHeight), onClick, null));
			return y + lineHeight;
		}

		private int drawStatusRow(
			Graphics2D g,
			int menuX,
			int y,
			int menuWidth,
			FontMetrics fm,
			int lineHeight,
			String label,
			String status
		) {
			g.setColor(MENU_TEXT);
			g.drawString(label, menuX + MENU_PADDING + 4, y + fm.getAscent());

			g.setColor(MENU_ON);
			int statusWidth = fm.stringWidth(status);
			g.drawString(status, menuX + menuWidth - MENU_PADDING - statusWidth, y + fm.getAscent());
			return y + lineHeight;
		}

		private int drawTabRow(Graphics2D g, int menuX, int y, int menuWidth, FontMetrics fm, int lineHeight, FrameTimerUI.Tab tab) {
			boolean detached = state.isDetached(tab);
			boolean hidden = state.isHidden(tab);
			boolean visible = !detached && !hidden;

			String check = visible ? "[x]" : "[ ]";
			String label = check + " " + tab.getLabel();
			if (detached)
				label = "[-] " + tab.getLabel() + " (detached)";

			g.setColor(detached ? MENU_MUTED : visible ? MENU_TEXT : MENU_OFF);
			g.drawString(label, menuX + MENU_PADDING + 4, y + fm.getAscent());

			if (visible && tab.isDetachable()) {
				String action = "Detach";
				g.setColor(MENU_ACTION);
				int actionWidth = fm.stringWidth(action);
				int actionX = menuX + menuWidth - MENU_PADDING - actionWidth;
				g.drawString(action, actionX, y + fm.getAscent());

				int labelWidth = fm.stringWidth(label);
				hitRegions.add(new HitRegion(
					new Rectangle(menuX + MENU_PADDING + 4, y, labelWidth + 8, lineHeight),
					() -> onTabVisibilityToggle.accept(tab),
					null
				));
				hitRegions.add(new HitRegion(
					new Rectangle(actionX - 4, y, actionWidth + 8, lineHeight),
					() -> onTabDetached.accept(tab),
					null
				));
			} else {
				hitRegions.add(new HitRegion(
					new Rectangle(menuX, y, menuWidth, lineHeight),
					() -> onTabVisibilityToggle.accept(tab),
					null
				));
			}

			return y + lineHeight;
		}

		private int drawGraphRow(Graphics2D g, int menuX, int y, int menuWidth, FontMetrics fm, int lineHeight, FrameTimerUI.Graph graph) {
			boolean visible = state.isGraphVisible(graph);
			String check = visible ? "[x]" : "[ ]";
			String label = check + " " + graph.getLabel();

			g.setColor(visible ? MENU_TEXT : MENU_OFF);
			g.drawString(label, menuX + MENU_PADDING + 4, y + fm.getAscent());

			hitRegions.add(new HitRegion(
				new Rectangle(menuX, y, menuWidth, lineHeight),
				() -> state.toggleGraphHidden(graph),
				null
			));
			return y + lineHeight;
		}

		private int drawActionRow(
			Graphics2D g,
			int menuX,
			int y,
			int menuWidth,
			FontMetrics fm,
			int lineHeight,
			String label,
			Runnable onClick
		) {
			g.setColor(MENU_ACTION);
			g.drawString("> " + label, menuX + MENU_PADDING + 4, y + fm.getAscent());
			hitRegions.add(new HitRegion(new Rectangle(menuX, y, menuWidth, lineHeight), onClick, null));
			return y + lineHeight;
		}

		boolean handleClick(int mouseX, int mouseY, boolean middleClick) {
			for (HitRegion region : hitRegions) {
				if (!region.bounds.contains(mouseX, mouseY))
					continue;

				if (middleClick && region.middleAction != null) {
					region.middleAction.run();
					return true;
				}

				if (!middleClick && region.leftAction != null) {
					region.leftAction.run();
					return true;
				}
			}
			return false;
		}

		@Override
		public Rectangle getBounds() {
			return bounds;
		}

		@Override
		public void setPreferredLocation(Point point) {
			bounds.setLocation(point);
		}

		@Override
		public void setPreferredSize(Dimension dimension) {
			bounds.setSize(dimension);
		}

		@RequiredArgsConstructor
		private static final class HitRegion {
			private final Rectangle bounds;
			private final Runnable leftAction;
			private final Runnable middleAction;
		}
	}

	private static final class Button implements LayoutableRenderableEntity {
		private static final Color BACKGROUND = new Color(35, 35, 35, 200);
		private static final Color BACKGROUND_HOVER = new Color(55, 55, 55, 220);
		private static final Color BACKGROUND_SELECTED = new Color(70, 90, 120, 230);
		private static final Color BORDER = new Color(120, 120, 120, 180);
		private static final Color TEXT = Color.WHITE;

		private final String label;
		private final Rectangle bounds = new Rectangle();
		private int paddingX = 6;
		private int paddingY = 2;

		@Getter
		@Setter
		private boolean selected;

		@Getter
		@Setter
		private boolean hovered;

		@Getter
		@Setter
		private int stretchWidth;

		Button(String label) {
			this.label = label;
		}

		@Override
		public Dimension render(Graphics2D graphics) {
			Font font = FontManager.getRunescapeFont();
			graphics.setFont(font);
			var fm = graphics.getFontMetrics();
			int textWidth = fm.stringWidth(label);
			int width = stretchWidth > 0 ? stretchWidth : textWidth + paddingX * 2;
			int height = fm.getHeight() + paddingY * 2;

			Color background = selected ? BACKGROUND_SELECTED : hovered ? BACKGROUND_HOVER : BACKGROUND;
			graphics.setColor(background);
			graphics.fillRect(bounds.x, bounds.y, width, height);
			graphics.setColor(BORDER);
			graphics.drawRect(bounds.x, bounds.y, width, height);

			graphics.setColor(TEXT);
			int textX = bounds.x + paddingX;
			int textY = bounds.y + paddingY + fm.getAscent();
			graphics.drawString(label, textX, textY);

			bounds.setSize(width, height);
			return new Dimension(width, height);
		}

		@Override
		public Rectangle getBounds() {
			return bounds;
		}

		@Override
		public void setPreferredLocation(Point point) {
			bounds.setLocation(point);
		}

		@Override
		public void setPreferredSize(Dimension dimension) {
			bounds.setSize(dimension);
		}
	}

	private class DetachedSettingsPanel extends OverlayPanel implements MouseListener {
		private boolean active;

		DetachedSettingsPanel() {
			super(FrameTimerOverlay.this.plugin);
			setLayer(OverlayLayer.ABOVE_WIDGETS);
			setPosition(OverlayPosition.TOP_RIGHT);
			panelComponent.setBackgroundColor(Header.SETTINGS_BACKGROUND);
			panelComponent.setBorder(new Rectangle(0, 0, 0, 0));
			panelComponent.setPreferredSize(new Dimension(Header.SETTINGS_MENU_MIN_WIDTH, 0));
		}

		void setLocationOffset(int locationOffset) {
			Rectangle mainBounds = FrameTimerOverlay.this.getBounds();
			int headerHeight = FrameTimerOverlay.this.getHeader().getBounds().height;
			int yOffset = headerHeight > 0 ? headerHeight + 4 : 0;

			if (mainBounds.width > 0 && mainBounds.height > 0) {
				setPreferredLocation(new Point(
					mainBounds.x + locationOffset * 16,
					mainBounds.y + yOffset + locationOffset * 24
				));
			} else {
				setPreferredLocation(new Point(240 + locationOffset * 16, 10 + locationOffset * 24));
			}
		}

		boolean isActive() {
			return active;
		}

		void setActive(boolean activate) {
			if (active == activate)
				return;

			active = activate;
			if (activate) {
				overlayManager.add(this);
				mouseManager.registerMouseListener(0, this);
			} else {
				overlayManager.remove(this);
				mouseManager.unregisterMouseListener(this);
			}
		}

		@Override
		public Dimension render(Graphics2D g) {
			Header settings = getSettingsHeader();
			Dimension preferred = getPreferredSize();
			int width = preferred != null && preferred.width > 0
				? Math.max(Header.SETTINGS_MENU_MIN_WIDTH, preferred.width)
				: Header.SETTINGS_MENU_MIN_WIDTH;

			settings.setPreferredSize(new Dimension(width, 0));
			panelComponent.setPreferredSize(new Dimension(width, 0));
			panelComponent.getChildren().add(settings);

			Dimension size = super.render(g);
			if (preferred == null || preferred.width <= 0 || preferred.height <= 0)
				setPreferredSize(new Dimension(size.width, size.height));
			return size;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e) {
			return e;
		}

		@Override
		public MouseEvent mousePressed(MouseEvent e) {
			if (!active)
				return e;

			Rectangle panelBounds = getBounds();
			if (panelBounds.width <= 0 || panelBounds.height <= 0)
				return e;

			var mouse = client.getMouseCanvasPosition();
			if (mouse.getX() < 0 || mouse.getY() < 0)
				return e;

			int localX = mouse.getX() - panelBounds.x;
			int localY = mouse.getY() - panelBounds.y;

			Header settings = getSettingsHeader();
			Rectangle headerBounds = settings.getBounds();
			if (headerBounds.width <= 0 || headerBounds.height <= 0)
				return e;

			int headerX = localX - headerBounds.x;
			int headerY = localY - headerBounds.y;
			if (headerX < 0 || headerY < 0 || headerX >= headerBounds.width || headerY >= headerBounds.height)
				return e;

			boolean middleClick = SwingUtilities.isMiddleMouseButton(e);
			if (settings.handleClick(headerX, headerY, middleClick))
				e.consume();
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
	}

	private class DetachedPanel extends OverlayPanel implements MouseListener {
		private static final int PANEL_HORIZONTAL_PADDING = 8;

		private final FrameTimerUI.Tab tab;
		private final long[] timings = new long[Timer.TIMERS.length];
		private float cpuLoad;
		private final LineCache lineCache = new LineCache();

		private Button dockButton;
		private boolean active;
		private int snapshotPanelWidth;
		private int snapshotContentLineWidth;
		private int snapshotPanelHeight;

		DetachedPanel(FrameTimerUI.Tab tab) {
			super(FrameTimerOverlay.this.plugin);
			this.tab = tab;
			setLayer(OverlayLayer.ABOVE_WIDGETS);
			setPosition(OverlayPosition.TOP_RIGHT);
		}

		void captureSizeFromMain() {
			int width = FrameTimerOverlay.this.mainPanelWidth;
			int lineWidth = FrameTimerOverlay.this.mainContentLineWidth;
			int height = FrameTimerOverlay.this.mainPanelHeight;
			if (width > 0)
				snapshotPanelWidth = width;
			if (lineWidth > 0)
				snapshotContentLineWidth = lineWidth;
			if (height > 0)
				snapshotPanelHeight = height;
		}

		void setLocationOffset(int locationOffset) {
			Rectangle mainBounds = FrameTimerOverlay.this.getBounds();
			int headerHeight = FrameTimerOverlay.this.getHeader().getBounds().height;
			int yOffset = headerHeight > 0 ? headerHeight + 4 : 0;

			if (mainBounds.width > 0 && mainBounds.height > 0) {
				setPreferredLocation(new Point(
					mainBounds.x + locationOffset * 16,
					mainBounds.y + yOffset + locationOffset * 24
				));
			} else {
				setPreferredLocation(new Point(240 + locationOffset * 16, 10 + locationOffset * 24));
			}
		}

		boolean isActive() {
			return active;
		}

		void setActive(boolean activate) {
			if (active == activate)
				return;

			active = activate;
			if (activate) {
				captureSizeFromMain();
				overlayManager.add(this);
				mouseManager.registerMouseListener(0, this);
			} else {
				overlayManager.remove(this);
				mouseManager.unregisterMouseListener(this);
			}
		}

		private int getPanelWidth() {
			if (snapshotPanelWidth > 0)
				return snapshotPanelWidth;
			return ui.getLineWidth() + PANEL_HORIZONTAL_PADDING;
		}

		private int getContentLineWidth() {
			if (snapshotContentLineWidth > 0)
				return snapshotContentLineWidth;
			return ui.getLineWidth();
		}

		private int getPanelHeight() {
			if (snapshotPanelHeight > 0)
				return snapshotPanelHeight;
			return 200;
		}

		@Override
		public Dimension render(Graphics2D g) {
			if (snapshotPanelWidth <= 0)
				captureSizeFromMain();

			int lineWidth = getContentLineWidth();
			int panelWidth = getPanelWidth();
			int panelHeight = getPanelHeight();
			panelComponent.setPreferredSize(new Dimension(panelWidth, panelHeight));

			if (!getAverageTimings()) {
				panelComponent.getChildren().add(TitleComponent.builder()
					.text(tab.getLabel() + " - Waiting for data...")
					.build());
				return super.render(g);
			}

			lineCache.syncGraphOverlayState(ui.isGraphEnabled());

			FrameTimerOverlay.this.renderTab(tab, panelComponent, lineCache, timings, cpuLoad, lineWidth);

			panelComponent.getChildren().add(LineComponent.builder()
				.preferredSize(new Dimension(lineWidth, 6))
				.build());

			dockButton = new Button("Dock " + tab.getLabel());
			dockButton.setStretchWidth(lineWidth);
			panelComponent.getChildren().add(dockButton);

			return super.render(g);
		}

		private boolean getAverageTimings() {
			var frames = frameTimingsStore.getFrames();
			if (frames.isEmpty())
				return false;

			Arrays.fill(timings, 0);
			cpuLoad = 0;
			for (var frame : frames) {
				for (int i = 0; i < frame.timers.length; i++)
					timings[i] += frame.timers[i];
				cpuLoad += frame.cpuLoad;
			}

			for (int i = 0; i < timings.length; i++)
				timings[i] = Math.max(0, timings[i] / frames.size());
			cpuLoad /= frames.size();

			return true;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e) {
			return e;
		}

		@Override
		public MouseEvent mousePressed(MouseEvent e) {
			if (!active || dockButton == null || !SwingUtilities.isLeftMouseButton(e))
				return e;

			Rectangle panelBounds = getBounds();
			if (panelBounds.width <= 0 || panelBounds.height <= 0)
				return e;

			var mouse = client.getMouseCanvasPosition();
			if (mouse.getX() < 0 || mouse.getY() < 0)
				return e;

			int localX = mouse.getX() - panelBounds.x;
			int localY = mouse.getY() - panelBounds.y;

			Rectangle buttonBounds = dockButton.getBounds();
			if (buttonBounds.width <= 0 || buttonBounds.height <= 0)
				return e;

			if (buttonBounds.contains(localX, localY)) {
				ui.setDetached(tab, false);
				e.consume();
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
	}
}
