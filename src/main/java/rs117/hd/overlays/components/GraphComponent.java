package rs117.hd.overlays.components;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

@Slf4j
public class GraphComponent<T> implements LayoutableRenderableEntity {
	private static final Color GRID_COLOR = new Color(255, 255, 255, 50);
	private static final Color BACKGROUND_COLOR = new Color(0, 0, 0, 150);
	private static final Color AXIS_LABEL_COLOR = Color.LIGHT_GRAY;
	private static final Color TOOLTIP_BACKGROUND_COLOR = new Color(20, 20, 20, 230);
	private static final Color TOOLTIP_BORDER_COLOR = new Color(255, 255, 255, 80);
	private static final Color GUIDE_LINE_COLOR = new Color(255, 255, 255, 120);
	private static final Color SELECTION_FILL_COLOR = new Color(100, 180, 255, 55);
	private static final Color SELECTION_BORDER_COLOR = new Color(140, 200, 255, 170);

	public static final int DEFAULT_GRAPH_WIDTH = 800;
	public static final int DEFAULT_GRAPH_HEIGHT = 200;
	public static final int DEFAULT_MEMORY_GRAPH_HEIGHT = 75;
	public static final int MIN_GRAPH_WIDTH = 200;
	public static final int MAX_GRAPH_WIDTH = 2400;
	public static final int MIN_GRAPH_HEIGHT = 50;
	public static final int MAX_GRAPH_HEIGHT = 800;
	public static final int MIN_MEMORY_GRAPH_HEIGHT = 40;
	public static final int MAX_MEMORY_GRAPH_HEIGHT = 400;

	public static final int MARGIN_LEFT = 28;
	public static final int MARGIN_TOP = 10;
	public static final int MARGIN_RIGHT = 4;
	public static final int MARGIN_BOTTOM = 4;
	private static final int Y_AXIS_NAME_MARGIN = 32;
	private static final int X_AXIS_NAME_MARGIN = 8;

	private static final float TITLE_ALPHA = 0.15f;
	private static final int TOOLTIP_PADDING = 6;
	private static final int TOOLTIP_LINE_HEIGHT = 14;
	private static final int TOOLTIP_OFFSET = 12;
	private static final int HOVER_DOT_RADIUS = 3;

	private static final Font axisNameFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
	private static final Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 32);
	private static final Font tooltipFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font tooltipHeaderFont = new Font(Font.SANS_SERIF, Font.BOLD, 12);

	private static final Composite TITLE_COMPOSITE = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, TITLE_ALPHA);

	private static final float[] EVENT_MARKER_DASH = { 4f, 3f };
	private static final Stroke EVENT_MARKER_STROKE = new BasicStroke(
		1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, EVENT_MARKER_DASH, 0f);

	private final String title;
	private final Supplier<List<T>> dataSupplier;
	private final Supplier<Object> hoveredKeySupplier;
	private final Supplier<Point> mousePositionSupplier;

	private final List<Series<T>> series = new ArrayList<>();
	private final List<EventMarker<T>> eventMarkers = new ArrayList<>();

	private final Rectangle bounds = new Rectangle();

	private int yAxisNameWidth;
	private String xAxisName;
	private String yAxisName;
	private boolean appendAxisNameToTooltip = false;

	private double roundStep = 5.0;
	private String axisFormat = "%.0f";

	private BufferedImage backgroundCache;
	private Graphics2D backgroundGraphics;
	private int cachedWidth = -1, cachedHeight = -1;
	private double cachedMaxValue = -1;
	private String cachedTitle, cachedAxisFormat, cachedXAxisName, cachedYAxisName;

	private AffineTransform lastTransform;
	private AffineTransform lastInverse;

	@Getter
	private int graphWidth = DEFAULT_GRAPH_WIDTH;
	@Getter
	private int graphHeight = DEFAULT_GRAPH_HEIGHT;

	private int[] xPoints = new int[2];
	private int[] yPoints = new int[2];
	private int pointCount = 0;

	// The data index currently under the mouse cursor, or null if not hovering the plot area.
	@Getter
	private Integer hoveredIndex = null;

	private Integer selectionStartIndex = null;
	private Integer selectionEndIndex = null;

	private Point dragStartPoint = null;
	private Point dragEndPoint = null;

	private final List<Series<T>> toolTipSeries = new ArrayList<>();
	private final List<String> toolTipLines = new ArrayList<>();
	private final List<Color> toolTipColors = new ArrayList<>();

	private final StringBuilder sb = new StringBuilder();
	private final Formatter formatter = new Formatter(sb);

	public GraphComponent(String title, Supplier<List<T>> dataSupplier) {
		this(title, dataSupplier, () -> null, () -> null);
	}

	public GraphComponent(String title, Supplier<List<T>> dataSupplier, Supplier<Object> hoveredKeySupplier) {
		this(title, dataSupplier, hoveredKeySupplier, () -> null);
	}

	public GraphComponent(
		String title,
		Supplier<List<T>> dataSupplier,
		Supplier<Object> hoveredKeySupplier,
		Supplier<Point> mousePositionSupplier
	) {
		this.title = title;
		this.dataSupplier = dataSupplier;
		this.hoveredKeySupplier = hoveredKeySupplier;
		this.mousePositionSupplier = mousePositionSupplier;
	}

	public static int outerWidth(int plotWidth) {
		return MARGIN_LEFT + plotWidth + MARGIN_RIGHT;
	}

	public static int outerHeight(int plotHeight) {
		return MARGIN_TOP + plotHeight + MARGIN_BOTTOM;
	}

	public GraphComponent<T> setXAxisName(String xAxisName) {
		this.xAxisName = xAxisName;
		return this;
	}

	public GraphComponent<T> setYAxisName(String yAxisName) {
		this.yAxisName = yAxisName;
		return this;
	}

	public GraphComponent<T> setAppendAxisNameToTooltip(boolean appendAxisNameToTooltip) {
		this.appendAxisNameToTooltip = appendAxisNameToTooltip;
		return this;
	}

	private int getPlotX() {
		return bounds.x + MARGIN_LEFT + (yAxisName != null ? (Y_AXIS_NAME_MARGIN + yAxisNameWidth) : 0);
	}

	private int getPlotY() {
		return bounds.y + MARGIN_TOP;
	}

	public GraphComponent<T> setGraphSize(int width, int height) {
		graphWidth = width;
		graphHeight = height;
		return this;
	}

	public GraphComponent<T> addSeries(String name, Color color, ToDoubleFunction<T> valueExtractor, boolean alwaysShow) {
		return addSeries(name, color, valueExtractor, alwaysShow, null);
	}

	public GraphComponent<T> addSeries(String name, Color color, ToDoubleFunction<T> valueExtractor, boolean alwaysShow, Object key) {
		Color dimmed = new Color(
			Math.max((int) (color.getRed() * 0.5f), 0),
			Math.max((int) (color.getGreen() * 0.5f), 0),
			Math.max((int) (color.getBlue() * 0.5f), 0),
			color.getAlpha()
		);
		series.add(new Series<>(name, color, dimmed, valueExtractor, alwaysShow, key));
		return this;
	}

	public GraphComponent<T> removeSeries(String name) {
		series.removeIf(s -> s.name.equals(name));
		return this;
	}

	public GraphComponent<T> removeSeries(Object key) {
		series.removeIf(s -> s.key == key);
		return this;
	}

	public GraphComponent<T> getSeries(String name) {
		for (Series<T> s : series) {
			if (s.name.equals(name))
				return this;
		}

		return null;
	}

	public GraphComponent<T> getSeries(Object key) {
		for (Series<T> s : series) {
			if (s.key != null && s.key == key)
				return this;
		}

		return null;
	}

	public List<LegendEntry> getLegendEntries() {
		List<LegendEntry> entries = new ArrayList<>(series.size());
		for (Series<T> s : series) {
			Object key = s.key != null ? s.key : s.name;
			entries.add(new LegendEntry(s.name, s.color, key));
		}
		return entries;
	}

	private static boolean matchesHoverKey(Object hoveredKey, Series<?> s) {
		if (s.key != null)
			return hoveredKey.equals(s.key);
		return hoveredKey.equals(s.name);
	}

	public GraphComponent<T> setRoundStep(double roundStep) {
		this.roundStep = roundStep;
		return this;
	}

	public GraphComponent<T> setAxisFormat(String axisFormat) {
		this.axisFormat = axisFormat;
		return this;
	}

	public GraphComponent<T> addEventMarker(String name, Color color, Predicate<T> predicate) {
		return addEventMarker(name, color, predicate, null);
	}

	public GraphComponent<T> addEventMarker(String name, Color color, Predicate<T> predicate, Function<T, String> labelExtractor) {
		eventMarkers.add(new EventMarker<>(name, color, predicate, labelExtractor));
		return this;
	}

	public GraphComponent<T> removeEventMarker(String name) {
		eventMarkers.removeIf(m -> m.name.equals(name));
		return this;
	}

	public List<LegendEntry> getEventMarkerLegendEntries() {
		List<LegendEntry> entries = new ArrayList<>(eventMarkers.size());
		for (EventMarker<T> m : eventMarkers)
			entries.add(new LegendEntry(m.name, m.color, m.name));
		return entries;
	}

	public T getHoveredData() {
		if (hoveredIndex == null)
			return null;

		var data = dataSupplier.get();
		if (data == null || hoveredIndex >= data.size())
			return null;

		return data.get(hoveredIndex);
	}

	public Double getHoveredValue(String seriesName) {
		T point = getHoveredData();
		if (point == null)
			return null;

		for (var s : series) {
			if (s.name.equals(seriesName))
				return s.valueExtractor.applyAsDouble(point);
		}

		return null;
	}

	public void setSelectionFromPoints(Point start, Point end) {
		dragStartPoint = start;
		dragEndPoint = end;
	}

	public void clearSelection() {
		dragStartPoint = null;
		dragEndPoint = null;
		selectionStartIndex = null;
		selectionEndIndex = null;
	}

	public boolean isSelectionActive() {
		return selectionStartIndex != null && selectionEndIndex != null;
	}

	public int getSelectionCount() {
		if (!isSelectionActive())
			return 0;

		return selectionEndIndex - selectionStartIndex + 1;
	}

	public Double getSelectionAverage(String seriesName) {
		if (!isSelectionActive())
			return null;

		var data = dataSupplier.get();
		if (data == null)
			return null;

		for (var s : series) {
			if (s.name.equals(seriesName))
				return computeAverage(data, s, selectionStartIndex, selectionEndIndex);
		}

		return null;
	}

	private String format(String format, Object... args) {
		sb.setLength(0);
		formatter.format(format, args);
		return sb.toString();
	}

	@Override
	public Dimension render(Graphics2D g) {
		Object hoveredKey = hoveredKeySupplier.get();

		// Resolve yAxisNameWidth before computing plotX, since getPlotX() depends on it
		if (yAxisName != null && (yAxisNameWidth == 0 || !yAxisName.equals(cachedYAxisName))) {
			Font oldFont = g.getFont();
			g.setFont(axisNameFont);
			yAxisNameWidth = g.getFontMetrics().stringWidth(yAxisName);
			g.setFont(oldFont);
		}

		int totalWidth = outerWidth(graphWidth) + (yAxisName != null ? (Y_AXIS_NAME_MARGIN + yAxisNameWidth) : 0);
		int totalHeight = outerHeight(graphHeight) + (xAxisName != null ? X_AXIS_NAME_MARGIN : 0);

		bounds.setSize(totalWidth, totalHeight);

		var data = dataSupplier.get();
		if (data == null || data.size() < 2) {
			hoveredIndex = null;
			return new Dimension(totalWidth, totalHeight);
		}

		RenderingHints oldHints = g.getRenderingHints();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

		int plotX = getPlotX();
		int plotY = getPlotY();

		for (int s = 0; s < series.size(); s++)
			series.get(s).show = series.get(s).alwaysShow;

		double maxValue = 1;
		for (int i = 0; i < data.size(); i++) {
			final T point = data.get(i);
			for (int s = 0; s < series.size(); s++) {
				final Series<T> ser = series.get(s);
				double value = ser.valueExtractor.applyAsDouble(point);
				if (!ser.show && value != 0)
					ser.show = true;
				maxValue = Math.max(maxValue, value);
			}
		}
		maxValue = Math.ceil(maxValue / roundStep) * roundStep;

		BufferedImage bg = getOrRenderBackground(maxValue);
		g.drawImage(bg, bounds.x, bounds.y, null);

		if (dragStartPoint != null && dragEndPoint != null) {
			Integer dragStartIndex = computeIndexForPoint(g, data, dragStartPoint, plotX, plotY, false);
			Integer dragEndIndex = computeIndexForPoint(g, data, dragEndPoint, plotX, plotY, false);
			if (dragStartIndex != null && dragEndIndex != null && !dragStartIndex.equals(dragEndIndex)) {
				selectionStartIndex = Math.min(dragStartIndex, dragEndIndex);
				selectionEndIndex = Math.max(dragStartIndex, dragEndIndex);
			} else {
				selectionStartIndex = null;
				selectionEndIndex = null;
			}
			dragStartPoint = null;
			dragEndPoint = null;
		}

		if (selectionStartIndex != null && selectionEndIndex != null) {
			int count = data.size();
			int startIdx = Math.max(0, Math.min(count - 1, selectionStartIndex));
			int endIdx = Math.max(0, Math.min(count - 1, selectionEndIndex));
			selectionStartIndex = Math.min(startIdx, endIdx);
			selectionEndIndex = Math.max(startIdx, endIdx);

			int selX1 = plotX + (int) ((double) selectionStartIndex / (count - 1) * graphWidth);
			int selX2 = plotX + (int) ((double) selectionEndIndex / (count - 1) * graphWidth);

			g.setColor(SELECTION_FILL_COLOR);
			g.fillRect(selX1, plotY, Math.max(1, selX2 - selX1), graphHeight);
			g.setColor(SELECTION_BORDER_COLOR);
			g.drawLine(selX1, plotY, selX1, plotY + graphHeight);
			g.drawLine(selX2, plotY, selX2, plotY + graphHeight);
		}

		drawEventMarkers(g, data, plotX, plotY);

		boolean isolate = hoveredKey != null && series.stream().anyMatch(s -> matchesHoverKey(hoveredKey, s));

		Series<T> hoveredSeries = null;
		for (int i = 0; i < series.size(); i++) {
			final Series<T> s = series.get(i);
			if (!s.show)
				continue;

			Color color = s.color;
			if (isolate) {
				if (matchesHoverKey(hoveredKey, s)) {
					hoveredSeries = s;
					continue;
				}
				color = s.dimmedColor;
			}

			drawSeriesLine(g, data, s, color, plotX, plotY, maxValue);
		}

		if (isolate && hoveredSeries != null)
			drawSeriesLine(g, data, hoveredSeries, hoveredSeries.color, plotX, plotY, maxValue);

		hoveredIndex = computeHoveredIndex(g, data, plotX, plotY);

		g.setRenderingHints(oldHints);

		return new Dimension(totalWidth, totalHeight);
	}

	private void drawEventMarkers(Graphics2D g, List<T> data, int plotX, int plotY) {
		if (eventMarkers.isEmpty())
			return;

		int count = data.size();
		Stroke oldStroke = g.getStroke();
		g.setStroke(EVENT_MARKER_STROKE);

		for (int i = 0; i < count; i++) {
			final T point = data.get(i);
			int x = -1;

			for (int m = 0; m < eventMarkers.size(); m++) {
				final EventMarker<T> marker = eventMarkers.get(m);
				if (!marker.predicate.test(point))
					continue;

				if (x < 0)
					x = plotX + (int) ((double) i / (count - 1) * graphWidth);

				g.setColor(marker.color);
				g.drawLine(x, plotY, x, plotY + graphHeight);
			}
		}

		g.setStroke(oldStroke);
	}

	public void renderTooltip(Graphics2D g) {
		var data = dataSupplier.get();
		if (data == null)
			return;

		boolean hasSelection = selectionStartIndex != null && selectionEndIndex != null;
		if (!hasSelection && hoveredIndex == null)
			return;
		if (!hasSelection && hoveredIndex >= data.size())
			return;

		int plotX = getPlotX();
		int plotY = getPlotY();

		int count = data.size();

		// Anchor the tooltip to the trailing edge of the selection (or the hovered index otherwise)
		int anchorIndex = hasSelection ? selectionEndIndex : hoveredIndex;
		int x = plotX + (int) ((double) anchorIndex / (count - 1) * graphWidth);

		// Header + value lines shown in the tooltip box
		String header = null;
		toolTipLines.clear();
		toolTipColors.clear();

		if (hasSelection) {
			int selectionCount = selectionEndIndex - selectionStartIndex + 1;
			header = selectionCount + (selectionCount == 1 ? " sample avg" : " samples avg");

			for (int i = 0; i < series.size(); i++) {
				final Series<T> s = series.get(i);
				if (!s.show)
					continue;

				double avg = computeAverage(data, s, selectionStartIndex, selectionEndIndex);
				String value = format(axisFormat, avg);
				if (appendAxisNameToTooltip && yAxisName != null)
					value += " " + yAxisName;

				toolTipColors.add(s.color);
				toolTipLines.add(s.name + ": " + value);
			}

			// Show how many times each event marker fired within the selected range
			for (int m = 0; m < eventMarkers.size(); m++) {
				final EventMarker<T> marker = eventMarkers.get(m);
				int occurrences = 0;
				for (int i = selectionStartIndex; i <= selectionEndIndex; i++) {
					if (marker.predicate.test(data.get(i)))
						occurrences++;
				}
				if (occurrences == 0)
					continue;

				toolTipColors.add(marker.color);
				toolTipLines.add(marker.name + ": " + occurrences + (occurrences == 1 ? "x" : "x"));
			}
		} else {
			double maxValue = 1;
			for (int i = 0; i < data.size(); i++) {
				final T point = data.get(i);
				for (int s = 0; s < series.size(); s++)
					maxValue = Math.max(maxValue, series.get(s).valueExtractor.applyAsDouble(point));
			}
			maxValue = Math.ceil(maxValue / roundStep) * roundStep;

			final T point = data.get(hoveredIndex);

			// Vertical guide line through the hovered index
			g.setColor(GUIDE_LINE_COLOR);
			g.drawLine(x, plotY, x, plotY + graphHeight);

			// Dot on each series at the hovered index
			for (int i = 0; i < series.size(); i++) {
				final Series<T> s = series.get(i);
				double value = s.valueExtractor.applyAsDouble(point);
				int y = plotY + graphHeight - (int) Math.min(graphHeight, value / maxValue * graphHeight);

				g.setColor(s.color);
				g.fillOval(x - HOVER_DOT_RADIUS, y - HOVER_DOT_RADIUS, HOVER_DOT_RADIUS * 2, HOVER_DOT_RADIUS * 2);
			}

			for (int i = 0; i < series.size(); i++) {
				final Series<T> s = series.get(i);
				if(!s.show)
					continue;

				String value = format(axisFormat, s.valueExtractor.applyAsDouble(point));
				if (appendAxisNameToTooltip && yAxisName != null)
					value += " " + yAxisName;
				toolTipColors.add(s.color);
				toolTipLines.add(s.name + ": " + value);
			}

			// Append labels for any event markers matching the hovered point
			for (int m = 0; m < eventMarkers.size(); m++) {
				final EventMarker<T> marker = eventMarkers.get(m);
				if (!marker.predicate.test(point))
					continue;

				String label = marker.labelExtractor != null ? marker.labelExtractor.apply(point) : marker.name;
				toolTipColors.add(marker.color);
				toolTipLines.add(label);
			}
		}

		Font oldFont = g.getFont();
		g.setFont(tooltipFont);
		FontMetrics fm = g.getFontMetrics();

		int textWidth = 0;
		for (String line : toolTipLines)
			textWidth = Math.max(textWidth, fm.stringWidth(line));

		int headerWidth = 0;
		FontMetrics headerFm = null;
		if (header != null) {
			g.setFont(tooltipHeaderFont);
			headerFm = g.getFontMetrics();
			headerWidth = headerFm.stringWidth(header);
			g.setFont(tooltipFont);
		}

		int boxWidth = Math.max(textWidth, headerWidth) + TOOLTIP_PADDING * 2;
		int lineCount = toolTipLines.size() + (header != null ? 1 : 0);
		int boxHeight = TOOLTIP_LINE_HEIGHT * lineCount + TOOLTIP_PADDING * 2;

		int boxX = x + TOOLTIP_OFFSET;
		int boxY = plotY;

		// Keep the tooltip inside the plot area horizontally
		if (boxX + boxWidth > plotX + graphWidth)
			boxX = x - TOOLTIP_OFFSET - boxWidth;

		g.setColor(TOOLTIP_BACKGROUND_COLOR);
		g.fillRect(boxX, boxY, boxWidth, boxHeight);
		g.setColor(TOOLTIP_BORDER_COLOR);
		g.drawRect(boxX, boxY, boxWidth, boxHeight);

		int textY = boxY + TOOLTIP_PADDING + fm.getAscent();
		int lineIndex = 0;

		if (header != null) {
			g.setFont(tooltipHeaderFont);
			g.setColor(AXIS_LABEL_COLOR);
			g.drawString(header, boxX + TOOLTIP_PADDING, textY);
			g.setFont(tooltipFont);
			lineIndex++;
		}

		for (int i = 0; i < toolTipLines.size(); i++) {
			g.setColor(toolTipColors.get(i));
			g.drawString(toolTipLines.get(i), boxX + TOOLTIP_PADDING, textY + (lineIndex + i) * TOOLTIP_LINE_HEIGHT);
		}

		g.setFont(oldFont);
	}

	private BufferedImage getOrRenderBackground(double maxValue) {
		boolean dirty = backgroundCache == null
		                || cachedWidth != graphWidth
		                || cachedHeight != graphHeight
		                || cachedMaxValue != maxValue
		                || !java.util.Objects.equals(cachedTitle, title)
		                || !java.util.Objects.equals(cachedAxisFormat, axisFormat)
		                || !java.util.Objects.equals(cachedXAxisName, xAxisName)
		                || !java.util.Objects.equals(cachedYAxisName, yAxisName);

		if (!dirty)
			return backgroundCache;

		int imgWidth = outerWidth(graphWidth) + (yAxisName != null ? (Y_AXIS_NAME_MARGIN + yAxisNameWidth) : 0);
		int imgHeight = outerHeight(graphHeight) + (xAxisName != null ? X_AXIS_NAME_MARGIN : 0);

		if(backgroundCache == null || backgroundCache.getWidth() != imgWidth || backgroundCache.getHeight() != imgHeight) {
			if(backgroundGraphics != null)
				backgroundGraphics.dispose();

			backgroundCache = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB_PRE);
			backgroundGraphics = backgroundCache.createGraphics();
			backgroundGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		}

		// Local plot offset within the cached image (image origin is always 0,0)
		int plotX = MARGIN_LEFT + (yAxisName != null ? (Y_AXIS_NAME_MARGIN + yAxisNameWidth) : 0);
		int plotY = MARGIN_TOP;

		final var g = backgroundGraphics;
		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, imgWidth, imgHeight);
		g.setComposite(AlphaComposite.SrcOver);


		g.setColor(BACKGROUND_COLOR);
		g.fillRect(plotX, plotY, graphWidth, graphHeight);

		g.setColor(GRID_COLOR);
		g.drawRect(plotX, plotY, graphWidth, graphHeight);

		// Title
		g.setComposite(TITLE_COMPOSITE);
		g.setFont(titleFont);
		FontMetrics fm = g.getFontMetrics();
		int titleWidth = fm.stringWidth(title);
		int titleX = plotX + (graphWidth - titleWidth) / 2;
		int titleY = plotY + (graphHeight + fm.getAscent() - fm.getDescent()) / 2;
		g.setColor(Color.WHITE);
		g.drawString(title, titleX, titleY);
		g.setComposite(AlphaComposite.SrcOver);

		// Gridlines + axis value labels
		g.setFont(tooltipFont);
		for (int i = 0; i <= 2; i++) {
			int y = plotY + graphHeight - (graphHeight * i / 2);

			g.setColor(GRID_COLOR);
			g.drawLine(plotX, y, plotX + graphWidth, y);

			String label = format(axisFormat, maxValue * i / 2.0);
			int labelWidth = g.getFontMetrics().stringWidth(label);

			g.setColor(AXIS_LABEL_COLOR);
			g.drawString(label, plotX - labelWidth - 4, y + 4);
		}

		// Axis names
		if (yAxisName != null || xAxisName != null) {
			g.setFont(axisNameFont);
			FontMetrics nameFm = g.getFontMetrics();

			if (yAxisName != null) {
				int nameWidth = nameFm.stringWidth(yAxisName);
				int labelX = nameFm.getAscent();
				int labelY = plotY + (graphHeight + nameWidth) / 2;

				g.setColor(AXIS_LABEL_COLOR);
				g.rotate(-Math.PI / 2, labelX, labelY);
				g.drawString(yAxisName, labelX, labelY);
				g.rotate(Math.PI / 2, labelX, labelY); // rotate back, since this Graphics2D isn't reused elsewhere
				yAxisNameWidth = nameWidth;
			}

			if (xAxisName != null) {
				int nameWidth = nameFm.stringWidth(xAxisName);
				int labelX = plotX + (graphWidth - nameWidth) / 2;
				int labelY = plotY + graphHeight + MARGIN_BOTTOM + nameFm.getAscent();

				g.setColor(AXIS_LABEL_COLOR);
				g.drawString(xAxisName, labelX, labelY);
			}
		}

		cachedWidth = graphWidth;
		cachedHeight = graphHeight;
		cachedMaxValue = maxValue;
		cachedTitle = title;
		cachedAxisFormat = axisFormat;
		cachedXAxisName = xAxisName;
		cachedYAxisName = yAxisName;

		return backgroundCache;
	}

	private AffineTransform getInverseTransform(Graphics2D g) throws NoninvertibleTransformException {
		AffineTransform current = g.getTransform();
		if (lastInverse == null || !current.equals(lastTransform)) {
			lastTransform = current;
			lastInverse = current.createInverse();
		}
		return lastInverse;
	}

	private Integer computeHoveredIndex(Graphics2D g, List<T> data, int plotX, int plotY) {
		return computeIndexForPoint(g, data, mousePositionSupplier.get(), plotX, plotY, true);
	}

	private Integer computeIndexForPoint(Graphics2D g, List<T> data, Point canvasPoint, int plotX, int plotY, boolean requireInsidePlot) {
		if (canvasPoint == null)
			return null;

		Point2D local;
		try {
			local = getInverseTransform(g).transform(
				new Point2D.Double(canvasPoint.x, canvasPoint.y), null);
		} catch (NoninvertibleTransformException e) {
			return null;
		}

		if (requireInsidePlot && (local.getX() < plotX || local.getX() > plotX + graphWidth || local.getY() < plotY || local.getY() > plotY + graphHeight))
			return null;

		int count = data.size();
		double fraction = (local.getX() - plotX) / graphWidth;
		int idx = (int) Math.round(fraction * (count - 1));
		return Math.max(0, Math.min(count - 1, idx));
	}

	private double computeAverage(List<T> data, Series<T> s, int startIdx, int endIdx) {
		double sum = 0;
		int count = 0;
		for (int i = startIdx; i <= endIdx; i++) {
			sum += s.valueExtractor.applyAsDouble(data.get(i));
			count++;
		}
		return count == 0 ? 0 : sum / count;
	}

	private void drawSeriesLine(Graphics2D g, List<T> data, Series<T> s, Color color, int plotX, int plotY, double maxValue) {
		int count = data.size();
		if (count < 2)
			return;

		int lastX = Integer.MIN_VALUE;
		int colMinY = 0, colMaxY = 0;

		for (int i = 0; i < count; i++) {
			final T point = data.get(i);
			final double value = s.valueExtractor.applyAsDouble(point);

			int x = plotX + (int) ((double) i / (count - 1) * graphWidth);
			int y = plotY + graphHeight - (int) Math.min(graphHeight, value / maxValue * graphHeight);

			if (x == lastX) {
				colMinY = Math.min(colMinY, y);
				colMaxY = Math.max(colMaxY, y);
				continue;
			}

			if (lastX != Integer.MIN_VALUE && colMinY != colMaxY) {
				appendPoint(lastX, colMinY);
				appendPoint(lastX, colMaxY);
			}

			appendPoint(x, y);
			lastX = x;
			colMinY = colMaxY = y;
		}

		if (colMinY != colMaxY)
			appendPoint(lastX, colMaxY);

		if (pointCount > 2) {
			g.setColor(color);
			g.drawPolyline(xPoints, yPoints, pointCount);
		}
		pointCount = 0;
	}

	private void appendPoint(int x, int y) {
		if (pointCount > 2 && yPoints[pointCount - 2] == y && yPoints[pointCount - 1] == y) {
			xPoints[pointCount - 1] = x;
			return;
		}
		if (pointCount >= xPoints.length) {
			xPoints = Arrays.copyOf(xPoints, pointCount * 2);
			yPoints = Arrays.copyOf(yPoints, pointCount * 2);
		}
		xPoints[pointCount] = x;
		yPoints[pointCount] = y;
		pointCount++;
	}

	@Override
	public Rectangle getBounds() {
		return bounds;
	}

	@Override
	public void setPreferredLocation(Point position) {
		bounds.setLocation(position);
	}

	@Override
	public void setPreferredSize(Dimension dimension) {
		bounds.setSize(dimension);
	}

	public static class LegendEntry {
		@Getter
		private final String name;
		@Getter
		private final Color color;
		@Getter
		private final Object key;

		public LegendEntry(String name, Color color, Object key) {
			this.name = name;
			this.color = color;
			this.key = key;
		}
	}

	@RequiredArgsConstructor
	private static class Series<T> {
		private final String name;
		private final Color color;
		private final Color dimmedColor;
		private final ToDoubleFunction<T> valueExtractor;
		private final boolean alwaysShow;
		private final Object key;

		private boolean show;
	}

	@RequiredArgsConstructor
	private static class EventMarker<T> {
		private final String name;
		private final Color color;
		private final Predicate<T> predicate;
		private final Function<T, String> labelExtractor; // nullable, falls back to name
	}
}