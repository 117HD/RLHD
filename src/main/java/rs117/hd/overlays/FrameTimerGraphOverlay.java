package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.components.GraphComponent;

import static rs117.hd.HdPlugin.GL_CAPS;

@Slf4j
@Singleton
public class FrameTimerGraphOverlay extends OverlayPanel implements MouseListener {
	private static final Color HEAP_COLOR = new Color(80, 200, 255);
	private static final Color GPU_COLOR = new Color(80, 255, 95);
	private static final Color MEMORY_COLOR = new Color(255, 80, 179);

	private static final long BYTES_PER_KB = 1024L;
	private static final long KB_PER_MB = 1024L;
	private static final int SCREEN_MARGIN = 24;
	private static final int PANEL_BORDER = 4;
	private static final double DEFAULT_WIDTH_RATIO = 0.55;
	private static final double DEFAULT_HEIGHT_RATIO = 0.70;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Client client;

	@Inject
	private FrameTimerOverlay frameTimerOverlay;

	@Inject
	private FrameTimingsStore frameTimingsStore;

	@Inject
	private FrameTimerUI ui;

	@Inject
	private EventBus eventBus;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private FrameTimer frameTimer;

	private final List<GraphEntry> timerGraphs = new ArrayList<>();
	private final List<GraphEntry> memoryGraphs = new ArrayList<>();
	private final List<FrameTimings> frames = new ArrayList<>();

	@Getter
	private boolean active;

	private GraphComponent<FrameTimings> dragGraph;
	private Point dragStartPoint;

	private static final class GraphEntry {
		final FrameTimerUI.Graph id;
		final GraphComponent<FrameTimings> component;

		GraphEntry(FrameTimerUI.Graph id, GraphComponent<FrameTimings> component) {
			this.id = id;
			this.component = component;
		}
	}

	@Inject
	public FrameTimerGraphOverlay(HdPlugin plugin) {
		super(plugin);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.TOP_RIGHT);
		setPreferredLocation(new Point(50, 10));
		setMinimumSize(GraphComponent.MIN_GRAPH_WIDTH / 2);
	}

	boolean hasSelection() {
		for(int i = 0; i < timerGraphs.size(); i++){
			if(timerGraphs.get(i).component.isSelectionActive())
				return true;
		}
		for(int i = 0; i < memoryGraphs.size(); i++){
			if(memoryGraphs.get(i).component.isSelectionActive())
				return true;
		}

		return false;
	}

	void createGraphs() {
		Supplier<List<FrameTimings>> frames = () -> this.frames;
		Supplier<Object> hoveredTimer = () -> frameTimerOverlay.getHoveredTimer();
		Supplier<Point> mousePosition = () -> {
			var p = client.getMouseCanvasPosition();
			return p == null ? null : new Point(p.getX(), p.getY());
		};

		timerGraphs.clear();
		memoryGraphs.clear();

		var cpuGpuGraph = setupFrameTimerGraph(new GraphComponent<>("CPU/GPU", frames, hoveredTimer, mousePosition), FrameTimerUI.Graph.CPU_GPU);
		var cpuGraph = setupFrameTimerGraph(new GraphComponent<>("CPU", frames, hoveredTimer, mousePosition), FrameTimerUI.Graph.CPU);
		var asyncGraph = setupFrameTimerGraph(new GraphComponent<>("ASYNC", frames, hoveredTimer, mousePosition), FrameTimerUI.Graph.ASYNC);
		var gpuGraph = setupFrameTimerGraph(new GraphComponent<>("GPU", frames, hoveredTimer, mousePosition), FrameTimerUI.Graph.GPU);
		var allocationGraph = setupMemoryGraph(new GraphComponent<>("Allocations", frames, hoveredTimer, mousePosition), true, FrameTimerUI.Graph.ALLOCATIONS);
		var heapMemoryGraph = setupMemoryGraph(new GraphComponent<>("Heap Memory", frames, () -> null, mousePosition), false, FrameTimerUI.Graph.HEAP);
		var systemMemoryGraph = setupMemoryGraph(new GraphComponent<>("System Memory", frames, () -> null, mousePosition), false, FrameTimerUI.Graph.SYSTEM_MEMORY);

		if (GL_CAPS.GL_NVX_gpu_memory_info)
			systemMemoryGraph.addSeries("GPU Memory", GPU_COLOR, f -> f.gpuUsageKB / (double) KB_PER_MB, false);
		systemMemoryGraph.addSeries("Avail System Memory", MEMORY_COLOR, f -> f.freeSystemMemoryKB / (double) KB_PER_MB, false);

		heapMemoryGraph.addSeries("Heap Memory", HEAP_COLOR, f -> (f.heapUsageKB) / (double) KB_PER_MB, false);

		addMemorySeries(allocationGraph, Timer.CLIENT);
		addTimerSeries(cpuGpuGraph, Timer.CLIENT);
		addTimerSeries(cpuGpuGraph, Timer.DRAW_FRAME);
		addTimerSeries(cpuGpuGraph, Timer.RENDER_FRAME);

		for (Timer t : Timer.TIMERS) {
			if (t.isCpuTimer() && !isInGraph(cpuGpuGraph, t)) {
				addTimerSeries(cpuGraph, t);
				addMemorySeries(allocationGraph, t);
			}

			if (t.isAsyncCpuTimer()) {
				addTimerSeries(asyncGraph, t);
				addMemorySeries(allocationGraph, t);
			}

			if (t.isGpuTimer())
				addTimerSeries(gpuGraph, t);
		}

		applyGraphSizes();
	}

	private GraphComponent<FrameTimings> setupFrameTimerGraph(GraphComponent<FrameTimings> graph, FrameTimerUI.Graph graphId) {
		graph
			.setYAxisName("ms")
			.setAxisFormat("%.3f")
			.setAppendAxisNameToTooltip(true);
		timerGraphs.add(new GraphEntry(graphId, graph));
		return graph;
	}

	private GraphComponent<FrameTimings> setupMemoryGraph(GraphComponent<FrameTimings> graph, boolean isKB, FrameTimerUI.Graph graphId) {
		graph
			.setRoundStep(50.0)
			.setYAxisName(isKB ? "KB" : "MB")
			.setAppendAxisNameToTooltip(true);
		memoryGraphs.add(new GraphEntry(graphId, graph));
		return graph;
	}

	private void applyGraphSizes() {
		if (timerGraphs.isEmpty() && memoryGraphs.isEmpty())
			return;

		int visibleTimers = countVisible(timerGraphs);
		int visibleMemory = countVisible(memoryGraphs);
		int visibleTotal = visibleTimers + visibleMemory;
		if (visibleTotal == 0)
			return;

		int canvasW = Math.max(1, client.getCanvasWidth());
		int canvasH = Math.max(1, client.getCanvasHeight());

		int maxPlotWidth = Math.max(
			GraphComponent.MIN_GRAPH_WIDTH,
			canvasW - SCREEN_MARGIN - GraphComponent.MARGIN_LEFT - GraphComponent.MARGIN_RIGHT - PANEL_BORDER * 2
		);
		int maxContentHeight = Math.max(
			GraphComponent.MIN_GRAPH_HEIGHT,
			canvasH - SCREEN_MARGIN - PANEL_BORDER * 2
		);
		int chromeHeight = visibleTotal * (GraphComponent.MARGIN_TOP + GraphComponent.MARGIN_BOTTOM);
		int maxTotalPlotHeight = Math.max(GraphComponent.MIN_GRAPH_HEIGHT, maxContentHeight - chromeHeight);

		Dimension preferred = getPreferredSize();
		int plotWidth;
		int timerHeight;
		int memoryHeight;

		if (preferred != null && preferred.width > 0 && preferred.height > 0) {
			plotWidth = preferred.width - PANEL_BORDER * 2 - GraphComponent.MARGIN_LEFT - GraphComponent.MARGIN_RIGHT;
			int totalPlotHeight = preferred.height - PANEL_BORDER * 2 - chromeHeight;

			plotWidth = clamp(plotWidth, GraphComponent.MIN_GRAPH_WIDTH, Math.min(GraphComponent.MAX_GRAPH_WIDTH, maxPlotWidth));
			totalPlotHeight = clamp(totalPlotHeight, GraphComponent.MIN_GRAPH_HEIGHT, maxTotalPlotHeight);

			int weight = visibleTimers * 3 + visibleMemory;
			if (weight <= 0)
				weight = 1;

			timerHeight = visibleTimers > 0
				? clamp((totalPlotHeight * 3) / weight, GraphComponent.MIN_GRAPH_HEIGHT, GraphComponent.MAX_GRAPH_HEIGHT)
				: GraphComponent.DEFAULT_GRAPH_HEIGHT;
			memoryHeight = visibleMemory > 0
				? clamp(totalPlotHeight / weight, GraphComponent.MIN_MEMORY_GRAPH_HEIGHT, GraphComponent.MAX_MEMORY_GRAPH_HEIGHT)
				: GraphComponent.DEFAULT_MEMORY_GRAPH_HEIGHT;
		} else {
			plotWidth = clamp(
				(int) (canvasW * DEFAULT_WIDTH_RATIO),
				GraphComponent.MIN_GRAPH_WIDTH,
				Math.min(GraphComponent.MAX_GRAPH_WIDTH, maxPlotWidth)
			);

			int preferredTimerHeight = defaultTimerHeight(canvasH, visibleTimers, visibleMemory);
			int preferredMemoryHeight = defaultMemoryHeight(canvasH, visibleTimers, visibleMemory);
			int preferredTotal = visibleTimers * preferredTimerHeight + visibleMemory * preferredMemoryHeight;

			if (preferredTotal > maxTotalPlotHeight && preferredTotal > 0) {
				double scale = (double) maxTotalPlotHeight / preferredTotal;
				timerHeight = clamp(
					(int) Math.round(preferredTimerHeight * scale),
					GraphComponent.MIN_GRAPH_HEIGHT,
					GraphComponent.MAX_GRAPH_HEIGHT
				);
				memoryHeight = clamp(
					(int) Math.round(preferredMemoryHeight * scale),
					GraphComponent.MIN_MEMORY_GRAPH_HEIGHT,
					GraphComponent.MAX_MEMORY_GRAPH_HEIGHT
				);
			} else {
				timerHeight = preferredTimerHeight;
				memoryHeight = preferredMemoryHeight;
			}
		}

		for (var entry : timerGraphs)
			entry.component.setGraphSize(plotWidth, timerHeight);

		for (var entry : memoryGraphs)
			entry.component.setGraphSize(plotWidth, memoryHeight);
	}

	private int defaultTimerHeight(int canvasH, int visibleTimers, int visibleMemory) {
		int usable = (int) (canvasH * DEFAULT_HEIGHT_RATIO);
		int chrome = (visibleTimers + visibleMemory) * (GraphComponent.MARGIN_TOP + GraphComponent.MARGIN_BOTTOM);
		int plotBudget = Math.max(GraphComponent.MIN_GRAPH_HEIGHT, usable - chrome);
		int weight = visibleTimers * 3 + visibleMemory;
		if (weight <= 0)
			return GraphComponent.DEFAULT_GRAPH_HEIGHT;
		return Math.max(GraphComponent.MIN_GRAPH_HEIGHT, (plotBudget * 3) / weight);
	}

	private int defaultMemoryHeight(int canvasH, int visibleTimers, int visibleMemory) {
		int usable = (int) (canvasH * DEFAULT_HEIGHT_RATIO);
		int chrome = (visibleTimers + visibleMemory) * (GraphComponent.MARGIN_TOP + GraphComponent.MARGIN_BOTTOM);
		int plotBudget = Math.max(GraphComponent.MIN_MEMORY_GRAPH_HEIGHT, usable - chrome);
		int weight = visibleTimers * 3 + visibleMemory;
		if (weight <= 0)
			return GraphComponent.DEFAULT_MEMORY_GRAPH_HEIGHT;
		return Math.max(GraphComponent.MIN_MEMORY_GRAPH_HEIGHT, plotBudget / weight);
	}

	private int countVisible(List<GraphEntry> graphs) {
		int count = 0;
		for (var entry : graphs) {
			if (ui.isGraphVisible(entry.id))
				count++;
		}
		return count;
	}

	private void addTimerSeries(GraphComponent<FrameTimings> graph, Timer t) {
		graph.addSeries(t.name(), t.color, f -> f.timers[t.ordinal()] / 1e6, false, t);
	}

	private void addMemorySeries(GraphComponent<FrameTimings> graph, Timer t) {
		graph.addSeries(t.name(), t.color, f -> f.allocations[t.ordinal()] / (double) BYTES_PER_KB, false, t);
	}

	private boolean isInGraph(GraphComponent<FrameTimings> graph, Timer t) {
		return graph.getSeries(t) != null;
	}

	public boolean hasGpuMemoryGraph() {
		return GL_CAPS.GL_NVX_gpu_memory_info;
	}

	public void setActive(boolean activate) {
		active = activate;
		if (activate) {
			overlayManager.add(this);
			eventBus.register(this);
			mouseManager.registerMouseListener(this);
		} else {
			overlayManager.remove(this);
			eventBus.unregister(this);
			mouseManager.unregisterMouseListener(this);
			dragGraph = null;
			dragStartPoint = null;
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		if (timerGraphs.isEmpty() && memoryGraphs.isEmpty())
			createGraphs();
		else
			applyGraphSizes();

		if(!hasSelection()) {
			frames.clear();
			frames.addAll(frameTimingsStore.getFrames());
		}

		var children = panelComponent.getChildren();
		for (var entry : timerGraphs) {
			if (ui.isGraphVisible(entry.id))
				children.add(entry.component);
		}
		for (var entry : memoryGraphs) {
			if (ui.isGraphVisible(entry.id))
				children.add(entry.component);
		}

		Dimension dimension = super.render(g);

		for (var entry : timerGraphs) {
			if (ui.isGraphVisible(entry.id))
				entry.component.renderTooltip(g);
		}
		for (var entry : memoryGraphs) {
			if (ui.isGraphVisible(entry.id))
				entry.component.renderTooltip(g);
		}

		return dimension;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private GraphComponent<FrameTimings> findGraphAt(Point canvasPoint) {
		var panelBounds = getBounds();
		if (panelBounds == null || panelBounds.width <= 0 || panelBounds.height <= 0)
			return null;

		int localX = canvasPoint.x - panelBounds.x;
		int localY = canvasPoint.y - panelBounds.y;

		for (var entry : timerGraphs)
			if (ui.isGraphVisible(entry.id) && entry.component.getBounds().contains(localX, localY))
				return entry.component;
		for (var entry : memoryGraphs)
			if (ui.isGraphVisible(entry.id) && entry.component.getBounds().contains(localX, localY))
				return entry.component;
		return null;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event) {
		if (active && event.getButton() == MouseEvent.BUTTON1) {
			GraphComponent<FrameTimings> hit = findGraphAt(event.getPoint());
			if (hit != null) {
				dragGraph = hit;
				dragStartPoint = event.getPoint();
				event.consume();
			}
		}
		return event;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event) {
		if (dragGraph != null) {
			dragGraph.setSelectionFromPoints(dragStartPoint, event.getPoint());
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event) {
		if(dragGraph != null) {
			if(dragStartPoint.distance(event.getPoint()) <= 0.01) {
				dragGraph.clearSelection();
			} else {
				dragGraph.setSelectionFromPoints(dragStartPoint, event.getPoint());
			}
			dragGraph = null;
			dragStartPoint = null;
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event) { return event; }

	@Override
	public MouseEvent mouseEntered(MouseEvent event) { return event; }

	@Override
	public MouseEvent mouseExited(MouseEvent event) { return event; }

	@Override
	public MouseEvent mouseMoved(MouseEvent event) { return event; }
}