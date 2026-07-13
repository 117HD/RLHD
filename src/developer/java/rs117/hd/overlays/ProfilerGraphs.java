package rs117.hd.overlays;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import rs117.hd.overlays.components.GraphComponent;
import rs117.hd.profiling.Event;
import rs117.hd.profiling.ProfileSample;
import rs117.hd.profiling.ProfileSampleStore;
import rs117.hd.profiling.Timer;

import static rs117.hd.HdPlugin.GL_CAPS;

public class ProfilerGraphs {
	private static final Color HEAP_COLOR = new Color(80, 200, 255);
	private static final Color GPU_COLOR = new Color(80, 255, 95);
	private static final Color MEMORY_COLOR = new Color(255, 80, 179);

	private static final long BYTES_PER_KB = 1024L;
	private static final long KB_PER_MB = 1024L;

	public static final double DEFAULT_WIDTH_RATIO = 0.55;
	public static final double DEFAULT_HEIGHT_RATIO = 0.70;

	@RequiredArgsConstructor
	@Getter
	public static final class GraphEntry {
		private final ProfilerUI.Graph id;
		private final GraphComponent<ProfileSample> component;
	}

	private final ProfilerUI ui;
	private final List<GraphEntry> timerGraphs = new ArrayList<>();
	private final List<GraphEntry> memoryGraphs = new ArrayList<>();
	private final List<ProfileSample> frames = new ArrayList<>();

	public ProfilerGraphs(ProfilerUI ui) {
		this.ui = ui;
	}

	public boolean isEmpty() {
		return timerGraphs.isEmpty() && memoryGraphs.isEmpty();
	}

	public void create(Supplier<Object> hoveredKeySupplier, Supplier<Point> mousePositionSupplier) {
		Supplier<List<ProfileSample>> framesSupplier = () -> this.frames;

		timerGraphs.clear();
		memoryGraphs.clear();

		var cpuGpuGraph = setupFrameTimerGraph(new GraphComponent<>("CPU/GPU", framesSupplier, hoveredKeySupplier, mousePositionSupplier), ProfilerUI.Graph.CPU_GPU);
		var cpuGraph = setupFrameTimerGraph(new GraphComponent<>("CPU", framesSupplier, hoveredKeySupplier, mousePositionSupplier), ProfilerUI.Graph.CPU);
		var asyncGraph = setupFrameTimerGraph(new GraphComponent<>("ASYNC", framesSupplier, hoveredKeySupplier, mousePositionSupplier), ProfilerUI.Graph.ASYNC);
		var gpuGraph = setupFrameTimerGraph(new GraphComponent<>("GPU", framesSupplier, hoveredKeySupplier, mousePositionSupplier), ProfilerUI.Graph.GPU);
		var allocationGraph = setupMemoryGraph(new GraphComponent<>("Allocations", framesSupplier, hoveredKeySupplier, mousePositionSupplier), true, ProfilerUI.Graph.ALLOCATIONS);
		var heapMemoryGraph = setupMemoryGraph(new GraphComponent<>("Heap Memory", framesSupplier, () -> null, mousePositionSupplier), false, ProfilerUI.Graph.HEAP);
		var systemMemoryGraph = setupMemoryGraph(new GraphComponent<>("System Memory", framesSupplier, () -> null, mousePositionSupplier), false, ProfilerUI.Graph.SYSTEM_MEMORY);

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
	}

	public void syncFrames(ProfileSampleStore store) {
		if (hasSelection())
			return;
		frames.clear();
		frames.addAll(store.getFrames());
	}

	public boolean hasSelection() {
		for (GraphEntry entry : timerGraphs) {
			if (entry.component.isSelectionActive())
				return true;
		}
		for (GraphEntry entry : memoryGraphs) {
			if (entry.component.isSelectionActive())
				return true;
		}
		return false;
	}

	@Nullable
	public GraphComponent<ProfileSample> findAtLocal(int localX, int localY) {
		Point point = new Point(localX, localY);
		for (GraphEntry entry : timerGraphs) {
			if (ui.isGraphVisible(entry.id) && entry.component.getBounds().contains(point))
				return entry.component;
		}
		for (GraphEntry entry : memoryGraphs) {
			if (ui.isGraphVisible(entry.id) && entry.component.getBounds().contains(point))
				return entry.component;
		}
		return null;
	}

	public void forEachVisible(Consumer<GraphComponent<ProfileSample>> consumer) {
		for (GraphEntry entry : timerGraphs) {
			if (ui.isGraphVisible(entry.id))
				consumer.accept(entry.component);
		}
		for (GraphEntry entry : memoryGraphs) {
			if (ui.isGraphVisible(entry.id))
				consumer.accept(entry.component);
		}
	}

	public void renderTooltips(Graphics2D g) {
		for (GraphEntry entry : timerGraphs) {
			if (ui.isGraphVisible(entry.id))
				entry.component.renderTooltip(g);
		}
		for (GraphEntry entry : memoryGraphs) {
			if (ui.isGraphVisible(entry.id))
				entry.component.renderTooltip(g);
		}
	}

	/** Paint graphs stacked at (x, y) for Swing panels. Returns the content size used. */
	public Dimension paintStacked(Graphics2D g, int x, int y) {
		int startY = y;
		int maxWidth = 0;

		for (GraphEntry entry : timerGraphs) {
			if (!ui.isGraphVisible(entry.id))
				continue;
			entry.component.setPreferredLocation(new Point(x, y));
			Dimension size = entry.component.render(g);
			maxWidth = Math.max(maxWidth, size.width);
			y += size.height;
		}
		for (GraphEntry entry : memoryGraphs) {
			if (!ui.isGraphVisible(entry.id))
				continue;
			entry.component.setPreferredLocation(new Point(x, y));
			Dimension size = entry.component.render(g);
			maxWidth = Math.max(maxWidth, size.width);
			y += size.height;
		}

		renderTooltips(g);
		return new Dimension(maxWidth, y - startY);
	}

	@RequiredArgsConstructor
	@Getter
	public static final class LegendCategory {
		private final String title;
		private final List<GraphComponent.LegendEntry> entries;
	}

	public List<GraphComponent.LegendEntry> collectLegendEntries() {
		Map<Object, GraphComponent.LegendEntry> unique = new LinkedHashMap<>();
		appendLegendEntries(unique, timerGraphs);
		appendLegendEntries(unique, memoryGraphs);
		return new ArrayList<>(unique.values());
	}

	public List<LegendCategory> collectLegendCategories() {
		List<GraphComponent.LegendEntry> cpu = new ArrayList<>();
		List<GraphComponent.LegendEntry> gpu = new ArrayList<>();
		List<GraphComponent.LegendEntry> memory = new ArrayList<>();

		for (GraphComponent.LegendEntry entry : collectLegendEntries()) {
			Object key = entry.getKey();
			if (key instanceof Timer) {
				Timer timer = (Timer) key;
				if (timer.isGpuTimer())
					gpu.add(entry);
				else
					cpu.add(entry);
			} else {
				memory.add(entry);
			}
		}

		List<LegendCategory> categories = new ArrayList<>(3);
		if (!cpu.isEmpty())
			categories.add(new LegendCategory("CPU", cpu));
		if (!gpu.isEmpty())
			categories.add(new LegendCategory("GPU", gpu));
		if (!memory.isEmpty())
			categories.add(new LegendCategory("Memory", memory));
		return categories;
	}

	/**
	 * Size plots to fill an available panel area (JFrame).
	 * Returns preferred content size for the panel.
	 */
	public Dimension applyPanelSizes(int panelW, int panelH, int padding, int fallbackWidth, int fallbackTimerH, int fallbackMemoryH) {
		VisibleCounts counts = countVisible();
		if (counts.total == 0)
			return new Dimension(fallbackWidth, 120);

		int plotWidth;
		int timerHeight;
		int memoryHeight;

		if (panelW > padding * 2 && panelH > padding * 2) {
			int chromeHeight = counts.total * (GraphComponent.MARGIN_TOP + GraphComponent.MARGIN_BOTTOM);
			plotWidth = clamp(
				panelW - padding * 2 - GraphComponent.MARGIN_LEFT - GraphComponent.MARGIN_RIGHT,
				GraphComponent.MIN_GRAPH_WIDTH,
				GraphComponent.MAX_GRAPH_WIDTH
			);
			int totalPlotHeight = Math.max(GraphComponent.MIN_GRAPH_HEIGHT, panelH - padding * 2 - chromeHeight);
			int weight = weight(counts);
			timerHeight = counts.timers > 0
				? clamp((totalPlotHeight * 3) / weight, GraphComponent.MIN_GRAPH_HEIGHT, GraphComponent.MAX_GRAPH_HEIGHT)
				: GraphComponent.DEFAULT_GRAPH_HEIGHT;
			memoryHeight = counts.memory > 0
				? clamp(totalPlotHeight / weight, GraphComponent.MIN_MEMORY_GRAPH_HEIGHT, GraphComponent.MAX_MEMORY_GRAPH_HEIGHT)
				: GraphComponent.DEFAULT_MEMORY_GRAPH_HEIGHT;
		} else {
			plotWidth = fallbackWidth;
			timerHeight = fallbackTimerH;
			memoryHeight = fallbackMemoryH;
		}

		setSizes(plotWidth, timerHeight, memoryHeight);

		int contentWidth = plotWidth + GraphComponent.MARGIN_LEFT + GraphComponent.MARGIN_RIGHT + padding * 2 + 40;
		int contentHeight = padding * 2
			+ counts.timers * GraphComponent.outerHeight(timerHeight)
			+ counts.memory * GraphComponent.outerHeight(memoryHeight);
		return new Dimension(contentWidth, Math.max(contentHeight, 120));
	}

	/** Size plots for the in-game overlay using canvas constraints / preferred size. */
	public void applyOverlaySizes(
		int canvasW,
		int canvasH,
		@Nullable Dimension preferred,
		int panelBorder,
		int screenMargin
	) {
		VisibleCounts counts = countVisible();
		if (counts.total == 0)
			return;

		int maxPlotWidth = Math.max(
			GraphComponent.MIN_GRAPH_WIDTH,
			canvasW - screenMargin - GraphComponent.MARGIN_LEFT - GraphComponent.MARGIN_RIGHT - panelBorder * 2
		);
		int maxContentHeight = Math.max(
			GraphComponent.MIN_GRAPH_HEIGHT,
			canvasH - screenMargin - panelBorder * 2
		);
		int chromeHeight = counts.total * (GraphComponent.MARGIN_TOP + GraphComponent.MARGIN_BOTTOM);
		int maxTotalPlotHeight = Math.max(GraphComponent.MIN_GRAPH_HEIGHT, maxContentHeight - chromeHeight);

		int plotWidth;
		int timerHeight;
		int memoryHeight;

		if (preferred != null && preferred.width > 0 && preferred.height > 0) {
			plotWidth = preferred.width - panelBorder * 2 - GraphComponent.MARGIN_LEFT - GraphComponent.MARGIN_RIGHT;
			int totalPlotHeight = preferred.height - panelBorder * 2 - chromeHeight;

			plotWidth = clamp(plotWidth, GraphComponent.MIN_GRAPH_WIDTH, Math.min(GraphComponent.MAX_GRAPH_WIDTH, maxPlotWidth));
			totalPlotHeight = clamp(totalPlotHeight, GraphComponent.MIN_GRAPH_HEIGHT, maxTotalPlotHeight);

			int weight = weight(counts);
			timerHeight = counts.timers > 0
				? clamp((totalPlotHeight * 3) / weight, GraphComponent.MIN_GRAPH_HEIGHT, GraphComponent.MAX_GRAPH_HEIGHT)
				: GraphComponent.DEFAULT_GRAPH_HEIGHT;
			memoryHeight = counts.memory > 0
				? clamp(totalPlotHeight / weight, GraphComponent.MIN_MEMORY_GRAPH_HEIGHT, GraphComponent.MAX_MEMORY_GRAPH_HEIGHT)
				: GraphComponent.DEFAULT_MEMORY_GRAPH_HEIGHT;
		} else {
			plotWidth = clamp(
				(int) (canvasW * DEFAULT_WIDTH_RATIO),
				GraphComponent.MIN_GRAPH_WIDTH,
				Math.min(GraphComponent.MAX_GRAPH_WIDTH, maxPlotWidth)
			);

			int preferredTimerHeight = defaultTimerHeight(canvasH, counts);
			int preferredMemoryHeight = defaultMemoryHeight(canvasH, counts);
			int preferredTotal = counts.timers * preferredTimerHeight + counts.memory * preferredMemoryHeight;

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

		setSizes(plotWidth, timerHeight, memoryHeight);
	}

	public boolean hasGpuMemoryGraph() {
		return GL_CAPS.GL_NVX_gpu_memory_info;
	}

	private void setSizes(int plotWidth, int timerHeight, int memoryHeight) {
		for (GraphEntry entry : timerGraphs)
			entry.component.setGraphSize(plotWidth, timerHeight);
		for (GraphEntry entry : memoryGraphs)
			entry.component.setGraphSize(plotWidth, memoryHeight);
	}

	private void addEventsToGraph(GraphComponent<ProfileSample> graph) {
		for(Event event : Event.EVENTS)
			graph.addEventMarker(event.name, event.color,  (f) -> f.events != null && Arrays.binarySearch(f.events, event) >= 0);
	}

	private GraphComponent<ProfileSample> setupFrameTimerGraph(GraphComponent<ProfileSample> graph, ProfilerUI.Graph graphId) {
		graph
			.setYAxisName("ms")
			.setAxisFormat("%.3f")
			.setAppendAxisNameToTooltip(true);
		addEventsToGraph(graph);
		timerGraphs.add(new GraphEntry(graphId, graph));
		return graph;
	}

	private GraphComponent<ProfileSample> setupMemoryGraph(GraphComponent<ProfileSample> graph, boolean isKB, ProfilerUI.Graph graphId) {
		graph
			.setRoundStep(50.0)
			.setYAxisName(isKB ? "KB" : "MB")
			.setAppendAxisNameToTooltip(true);
		addEventsToGraph(graph);
		memoryGraphs.add(new GraphEntry(graphId, graph));
		return graph;
	}

	private void addTimerSeries(GraphComponent<ProfileSample> graph, Timer t) {
		graph.addSeries(t.name, t.color, f -> f.timers[t.ordinal()] / 1e6, false, t);
	}

	private void addMemorySeries(GraphComponent<ProfileSample> graph, Timer t) {
		graph.addSeries(t.name, t.color, f -> f.allocations[t.ordinal()] / (double) BYTES_PER_KB, false, t);
	}

	private boolean isInGraph(GraphComponent<ProfileSample> graph, Timer t) {
		return graph.getSeries(t) != null;
	}

	private void appendLegendEntries(Map<Object, GraphComponent.LegendEntry> unique, List<GraphEntry> graphs) {
		for (GraphEntry entry : graphs) {
			if (!ui.isGraphVisible(entry.id))
				continue;
			for (GraphComponent.LegendEntry legend : entry.component.getLegendEntries())
				unique.putIfAbsent(legend.getKey(), legend);
		}
	}

	private VisibleCounts countVisible() {
		int timers = 0;
		int memory = 0;
		for (GraphEntry entry : timerGraphs) {
			if (ui.isGraphVisible(entry.id))
				timers++;
		}
		for (GraphEntry entry : memoryGraphs) {
			if (ui.isGraphVisible(entry.id))
				memory++;
		}
		return new VisibleCounts(timers, memory, timers + memory);
	}

	private static int weight(VisibleCounts counts) {
		int weight = counts.timers * 3 + counts.memory;
		return weight <= 0 ? 1 : weight;
	}

	private static int defaultTimerHeight(int canvasH, VisibleCounts counts) {
		int usable = (int) (canvasH * DEFAULT_HEIGHT_RATIO);
		int chrome = counts.total * (GraphComponent.MARGIN_TOP + GraphComponent.MARGIN_BOTTOM);
		int plotBudget = Math.max(GraphComponent.MIN_GRAPH_HEIGHT, usable - chrome);
		return Math.max(GraphComponent.MIN_GRAPH_HEIGHT, (plotBudget * 3) / weight(counts));
	}

	private static int defaultMemoryHeight(int canvasH, VisibleCounts counts) {
		int usable = (int) (canvasH * DEFAULT_HEIGHT_RATIO);
		int chrome = counts.total * (GraphComponent.MARGIN_TOP + GraphComponent.MARGIN_BOTTOM);
		int plotBudget = Math.max(GraphComponent.MIN_MEMORY_GRAPH_HEIGHT, usable - chrome);
		return Math.max(GraphComponent.MIN_MEMORY_GRAPH_HEIGHT, plotBudget / weight(counts));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	@RequiredArgsConstructor
	private static final class VisibleCounts {
		final int timers;
		final int memory;
		final int total;
	}
}
