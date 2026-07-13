package rs117.hd.overlays.flame;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import rs117.hd.profiling.ProfileSample;
import rs117.hd.profiling.Timer;

public class ProfilerFlamePanel extends JPanel {
	private static final Color BG = new Color(30, 30, 30);
	private static final Color TEXT = new Color(220, 220, 220);
	private static final Color MUTED = new Color(140, 140, 140);
	private static final Color BORDER = new Color(55, 55, 55);
	private static final Color HOVER_BORDER = new Color(255, 255, 255, 180);
	private static final Color TOOLBAR_BG = new Color(24, 24, 24);

	private static final int ROW_HEIGHT = 22;
	private static final int PAD = 8;
	private static final int SECTION_GAP = 14;
	private static final int SECTION_HEADER_HEIGHT = 18;
	private static final int MIN_BAR_WIDTH = 2;

	private final Supplier<List<ProfileSample>> framesSupplier;

	private final JTextField filterField = new JTextField(18);
	private final JLabel breadcrumbLabel = new JLabel(" ");
	private final JButton resetZoomButton = new JButton("Reset zoom");
	private final FlameCanvas canvas = new FlameCanvas();
	private final TopTableModel tableModel = new TopTableModel();
	private final JTable table = new JTable(tableModel);
	private final TableRowSorter<TopTableModel> tableSorter = new TableRowSorter<>(tableModel);

	private FlameGraphModel model = FlameGraphModel.fromFrames(List.of());
	@Nullable
	private String zoomedId;
	@Nullable
	private FlameGraphModel.Node hovered;
	@Nullable
	private String highlightName;
	@Nullable
	private Object liveHoverKey;
	@Nullable
	private Object tableHoverKey;
	private int tableHoverViewRow = -1;

	public ProfilerFlamePanel(Supplier<List<ProfileSample>> framesSupplier) {
		super(new BorderLayout());
		this.framesSupplier = framesSupplier;
		setBackground(BG);

		JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		toolbar.setBackground(TOOLBAR_BG);
		toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));

		JLabel filterLabel = new JLabel("Filter");
		filterLabel.setForeground(TEXT);
		toolbar.add(filterLabel);

		filterField.setBackground(new Color(40, 40, 40));
		filterField.setForeground(TEXT);
		filterField.setCaretColor(TEXT);
		filterField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER),
			BorderFactory.createEmptyBorder(3, 6, 3, 6)
		));
		filterField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { onFilterChanged(); }
			@Override public void removeUpdate(DocumentEvent e) { onFilterChanged(); }
			@Override public void changedUpdate(DocumentEvent e) { onFilterChanged(); }
		});
		toolbar.add(filterField);

		resetZoomButton.setFocusable(false);
		resetZoomButton.addActionListener(e -> {
			zoomedId = null;
			updateBreadcrumb();
			canvas.repaint();
		});
		toolbar.add(resetZoomButton);

		breadcrumbLabel.setForeground(MUTED);
		toolbar.add(breadcrumbLabel);

		JLabel hint = new JLabel("  Click flame to zoom · Hover table / key / overlay to highlight");
		hint.setForeground(MUTED);
		toolbar.add(hint);

		add(toolbar, BorderLayout.NORTH);

		table.setRowSorter(tableSorter);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setAutoCreateRowSorter(false);
		table.setFillsViewportHeight(true);
		table.setBackground(BG);
		table.setForeground(TEXT);
		table.setGridColor(BORDER);
		table.setSelectionBackground(new Color(60, 80, 110));
		table.setSelectionForeground(TEXT);
		table.setRowHeight(20);
		table.getTableHeader().setBackground(TOOLBAR_BG);
		table.getTableHeader().setForeground(TEXT);
		table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
			{
				setHorizontalAlignment(SwingConstants.LEFT);
			}

			@Override
			public java.awt.Component getTableCellRendererComponent(
				JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column
			) {
				Object display = value;
				if (value instanceof Double) {
					double d = (Double) value;
					display = column == 4
						? String.format(Locale.US, "%.1f", d)
						: String.format(Locale.US, "%.3f", d);
				}
				boolean hoveredRow = row == tableHoverViewRow;
				var c = super.getTableCellRendererComponent(t, display, isSelected, hasFocus, row, column);
				c.setBackground(hoveredRow ? table.getSelectionBackground() : (isSelected ? new Color(45, 45, 45) : BG));
				c.setForeground(TEXT);
				if (column == 0) {
					int modelRow = table.convertRowIndexToModel(row);
					FlameGraphModel.TopRow top = tableModel.rowAt(modelRow);
					if (top != null)
						setForeground(top.getColor());
				}
				if (column >= 2)
					setHorizontalAlignment(SwingConstants.RIGHT);
				else
					setHorizontalAlignment(SwingConstants.LEFT);
				return c;
			}
		});
		table.setDefaultRenderer(Double.class, table.getDefaultRenderer(Object.class));

		MouseAdapter tableHover = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				int viewRow = table.rowAtPoint(e.getPoint());
				setTableHoverRow(viewRow);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				setTableHoverRow(-1);
			}
		};
		table.addMouseListener(tableHover);
		table.addMouseMotionListener(tableHover);

		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.getViewport().setBackground(BG);
		tableScroll.setBorder(BorderFactory.createEmptyBorder());

		JScrollPane flameScroll = new JScrollPane(
			canvas,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
		);
		flameScroll.getViewport().setBackground(BG);
		flameScroll.setBorder(BorderFactory.createEmptyBorder());

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, flameScroll, tableScroll);
		split.setResizeWeight(0.62);
		split.setDividerLocation(320);
		split.setBorder(null);
		split.setBackground(BG);
		add(split, BorderLayout.CENTER);

		updateBreadcrumb();
	}

	/** Active highlight for graph isolation: table hover wins. */
	@Nullable
	public Object getHighlightKey() {
		if (tableHoverKey != null)
			return tableHoverKey;
		Object pinned = keyForName(highlightName);
		if (pinned != null)
			return pinned;
		return liveHoverKey;
	}

	/** Live hover from legend key or overlay timer rows. */
	public void setLiveHoverKey(@Nullable Object key) {
		Object normalized = normalizeKey(key);
		if (java.util.Objects.equals(liveHoverKey, normalized))
			return;
		liveHoverKey = normalized;
		canvas.repaint();
	}

	private void setTableHoverRow(int viewRow) {
		if (viewRow == tableHoverViewRow)
			return;
		tableHoverViewRow = viewRow;
		if (viewRow < 0) {
			tableHoverKey = null;
		} else {
			int modelRow = table.convertRowIndexToModel(viewRow);
			FlameGraphModel.TopRow row = tableModel.rowAt(modelRow);
			tableHoverKey = row == null ? null : (row.getTimer() != null ? row.getTimer() : row.getName());
		}
		table.repaint();
		canvas.repaint();
	}

	public void clearHighlight() {
		highlightName = null;
		tableHoverKey = null;
		tableHoverViewRow = -1;
		table.clearSelection();
		canvas.repaint();
	}

	@Nullable
	private static Object normalizeKey(@Nullable Object key) {
		if (key == null)
			return null;
		if (key instanceof Timer)
			return key;
		String name = String.valueOf(key);
		for (Timer t : Timer.TIMERS) {
			if (t.name.equalsIgnoreCase(name) || t.name().equalsIgnoreCase(name))
				return t;
		}
		return key;
	}

	@Nullable
	private static Object keyForName(@Nullable String name) {
		if (name == null || name.isBlank())
			return null;
		for (Timer t : Timer.TIMERS) {
			if (t.name.equalsIgnoreCase(name) || t.name().equalsIgnoreCase(name))
				return t;
		}
		return name;
	}

	@Nullable
	private static String nameForKey(@Nullable Object key) {
		if (key == null)
			return null;
		if (key instanceof Timer)
			return ((Timer) key).name;
		return String.valueOf(key);
	}

	public void refresh() {
		String keepZoom = zoomedId;
		int keepTableHover = tableHoverViewRow;

		List<ProfileSample> frames = framesSupplier.get();
		model = FlameGraphModel.fromFrames(frames);
		zoomedId = keepZoom != null && model.findById(keepZoom) != null ? keepZoom : null;

		tableModel.setRows(model.getTopRows());
		applyTableFilter();
		// Re-resolve hover against the new model rows.
		if (keepTableHover >= 0 && keepTableHover < table.getRowCount())
			setTableHoverRow(keepTableHover);
		else
			setTableHoverRow(-1);
		updateBreadcrumb();
		canvas.repaint();
	}

	private void onFilterChanged() {
		applyTableFilter();
		canvas.repaint();
	}

	private void applyTableFilter() {
		String q = filterField.getText();
		if (q == null || q.isBlank()) {
			tableSorter.setRowFilter(null);
			return;
		}
		String needle = q.trim().toLowerCase(Locale.ROOT);
		tableSorter.setRowFilter(new RowFilter<>() {
			@Override
			public boolean include(Entry<? extends TopTableModel, ? extends Integer> entry) {
				String name = String.valueOf(entry.getValue(0)).toLowerCase(Locale.ROOT);
				String cat = String.valueOf(entry.getValue(1)).toLowerCase(Locale.ROOT);
				return name.contains(needle) || cat.contains(needle);
			}
		});
	}

	private void updateBreadcrumb() {
		FlameGraphModel.Node zoomed = zoomedNode();
		if (zoomed == null) {
			breadcrumbLabel.setText("CPU · Async · GPU");
			resetZoomButton.setEnabled(false);
			return;
		}
		List<FlameGraphModel.Node> path = model.breadcrumb(zoomed);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < path.size(); i++) {
			if (i > 0)
				sb.append("  >  ");
			sb.append(path.get(i).getName());
		}
		breadcrumbLabel.setText(sb.toString());
		resetZoomButton.setEnabled(true);
	}

	@Nullable
	private FlameGraphModel.Node zoomedNode() {
		if (zoomedId == null)
			return null;
		FlameGraphModel.Node node = model.findById(zoomedId);
		if (node == null)
			zoomedId = null;
		return node;
	}

	/** Local root for a category section — zoomed subtree if zoom is inside this tree. */
	private FlameGraphModel.Node sectionRoot(FlameGraphModel.Node category) {
		FlameGraphModel.Node zoomed = zoomedNode();
		if (zoomed == null)
			return category;
		for (FlameGraphModel.Node n = zoomed; n != null; n = n.getParent()) {
			if (n == category || n.getId().equals(category.getId()))
				return zoomed;
		}
		return category;
	}

	@Nullable
	private String filterText() {
		String q = filterField.getText();
		return q == null || q.isBlank() ? null : q;
	}

	private class FlameCanvas extends JPanel {
		private final List<Bar> bars = new ArrayList<>();

		FlameCanvas() {
			setBackground(BG);
			setOpaque(true);
			setPreferredSize(new Dimension(800, 260));

			MouseAdapter mouse = new MouseAdapter() {
				@Override
				public void mouseMoved(MouseEvent e) {
					Bar hit = findBar(e.getPoint());
					FlameGraphModel.Node next = hit == null ? null : hit.node;
					if (next != hovered) {
						hovered = next;
						setToolTipText(next == null ? null : formatTooltip(next));
						setCursor(next == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
						repaint();
					}
				}

				@Override
				public void mouseExited(MouseEvent e) {
					if (hovered != null) {
						hovered = null;
						setToolTipText(null);
						setCursor(Cursor.getDefaultCursor());
						repaint();
					}
				}

				@Override
				public void mouseClicked(MouseEvent e) {
					if (SwingUtilities.isRightMouseButton(e)) {
						zoomOut();
						return;
					}
					if (e.getButton() != MouseEvent.BUTTON1)
						return;
					Bar hit = findBar(e.getPoint());
					if (hit == null)
						return;

					// Zoom only — highlight comes from hover (table / key / overlay).
					zoomedId = hit.node.getId();
					updateBreadcrumb();
					repaint();
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		private void zoomOut() {
			FlameGraphModel.Node current = zoomedNode();
			if (current == null)
				return;
			FlameGraphModel.Node parent = current.getParent();
			zoomedId = parent == null ? null : parent.getId();
			updateBreadcrumb();
			repaint();
		}

		@Nullable
		private Bar findBar(Point p) {
			// Prefer deepest (last painted) bar under the cursor.
			for (int i = bars.size() - 1; i >= 0; i--) {
				Bar bar = bars.get(i);
				if (bar.bounds.contains(p))
					return bar;
			}
			return null;
		}

		private String formatTooltip(FlameGraphModel.Node node) {
			return String.format(
				Locale.US,
				"<html><b>%s</b> (%s)<br/>Avg: %.3f ms<br/>Max: %.3f ms<br/>Exclusive: %.3f ms</html>",
				node.getName(),
				node.getCategory(),
				node.valueMs(),
				node.maxMs(),
				node.exclusiveNs() / 1e6
			);
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			bars.clear();

			Graphics2D g2d = (Graphics2D) g;
			g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

			List<FlameGraphModel.Node> categories = model.getCategories();
			if (categories.isEmpty()) {
				g2d.setColor(MUTED);
				g2d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
				String msg = "No timing samples yet";
				FontMetrics fm = g2d.getFontMetrics();
				g2d.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
				return;
			}

			Font headerFont = new Font(Font.SANS_SERIF, Font.BOLD, 12);
			Font barFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
			FontMetrics headerFm = g2d.getFontMetrics(headerFont);
			FontMetrics barFm = g2d.getFontMetrics(barFont);
			String filter = filterText();
			int width = Math.max(1, getWidth() - PAD * 2);
			int y = PAD;

			for (int i = 0; i < categories.size(); i++) {
				FlameGraphModel.Node category = categories.get(i);
				FlameGraphModel.Node section = sectionRoot(category);

				if (i > 0)
					y += SECTION_GAP;

				g2d.setFont(headerFont);
				g2d.setColor(category.getColor());
				String header = String.format(Locale.US, "%s   %.2f ms", category.getName(), section.valueMs());
				g2d.drawString(header, PAD, y + headerFm.getAscent());
				y += SECTION_HEADER_HEIGHT;

				int sectionTop = y;
				layoutNode(section, PAD, sectionTop, width, 0, filter);
				y = sectionTop + (depth(section) + 1) * ROW_HEIGHT;
			}

			g2d.setFont(barFont);
			for (Bar bar : bars) {
				boolean matched = isHighlighted(bar.node);
				boolean dim = shouldDim(bar.node);
				Color fill = bar.node.getColor();
				if (dim)
					fill = dim(fill);
				else if (matched)
					fill = brighten(fill);
				g2d.setColor(fill);
				g2d.fillRect(bar.bounds.x, bar.bounds.y, bar.bounds.width, bar.bounds.height);

				boolean isHover = bar.node == hovered;
				g2d.setColor(isHover || matched ? HOVER_BORDER : BORDER);
				g2d.drawRect(bar.bounds.x, bar.bounds.y, bar.bounds.width - 1, bar.bounds.height - 1);
				if (matched) {
					g2d.setColor(new Color(255, 220, 80));
					g2d.drawRect(bar.bounds.x + 1, bar.bounds.y + 1, bar.bounds.width - 3, bar.bounds.height - 3);
				}

				if (bar.bounds.width > 28) {
					String label = String.format(Locale.US, "%s  %.2fms", bar.node.getName(), bar.node.valueMs());
					int maxW = bar.bounds.width - 6;
					if (barFm.stringWidth(label) > maxW)
						label = truncate(barFm, bar.node.getName(), maxW);
					g2d.setColor(dim ? MUTED : Color.WHITE);
					g2d.drawString(label, bar.bounds.x + 3, bar.bounds.y + (ROW_HEIGHT + barFm.getAscent()) / 2 - 2);
				}
			}

			int contentH = Math.max(y + PAD, 160);
			if (getPreferredSize().height != contentH) {
				setPreferredSize(new Dimension(Math.max(getWidth(), 800), contentH));
				revalidate();
			}
		}

		private boolean isHighlighted(FlameGraphModel.Node node) {
			return matchesKey(node, tableHoverKey)
				|| matchesHighlight(node, highlightName)
				|| matchesKey(node, liveHoverKey);
		}

		private boolean matchesHighlight(FlameGraphModel.Node node, @Nullable String name) {
			if (name == null)
				return false;
			if (name.equalsIgnoreCase(node.getName()))
				return true;
			Timer timer = node.getTimer();
			return timer != null && (
				name.equalsIgnoreCase(timer.name) || name.equalsIgnoreCase(timer.name())
			);
		}

		private boolean matchesKey(FlameGraphModel.Node node, @Nullable Object key) {
			if (key == null)
				return false;
			if (key instanceof Timer) {
				Timer timer = (Timer) key;
				return node.getTimer() == timer
					|| timer.name.equalsIgnoreCase(node.getName())
					|| timer.name().equalsIgnoreCase(node.getId());
			}
			return matchesHighlight(node, String.valueOf(key));
		}

		private boolean shouldDim(FlameGraphModel.Node node) {
			String filter = filterText();
			if (filter != null && !model.matchesFilter(node, filter))
				return true;
			if (tableHoverKey == null && highlightName == null && liveHoverKey == null)
				return false;
			return !isHighlighted(node);
		}

		private Color brighten(Color c) {
			return new Color(
				Math.min(255, c.getRed() + 40),
				Math.min(255, c.getGreen() + 40),
				Math.min(255, c.getBlue() + 40),
				255
			);
		}

		private void layoutNode(
			FlameGraphModel.Node node,
			int x,
			int y,
			int width,
			int depth,
			@Nullable String filter
		) {
			if (width < MIN_BAR_WIDTH)
				return;

			Rectangle bounds = new Rectangle(x, y + depth * ROW_HEIGHT, width, ROW_HEIGHT - 1);
			bars.add(new Bar(node, bounds));

			List<FlameGraphModel.Node> kids = node.getChildren();
			if (kids.isEmpty())
				return;

			long total = 0;
			for (FlameGraphModel.Node child : kids) {
				if (filter == null || model.matchesFilter(child, filter))
					total += Math.max(1, child.getValueNs());
			}
			if (total <= 0)
				return;

			int cursor = x;
			for (int i = 0; i < kids.size(); i++) {
				FlameGraphModel.Node child = kids.get(i);
				if (filter != null && !model.matchesFilter(child, filter))
					continue;
				long v = Math.max(1, child.getValueNs());
				int w = (int) Math.round((double) v / total * width);
				if (i == kids.size() - 1)
					w = Math.max(MIN_BAR_WIDTH, x + width - cursor);
				w = Math.max(MIN_BAR_WIDTH, w);
				if (cursor + w > x + width)
					w = Math.max(MIN_BAR_WIDTH, x + width - cursor);
				if (w <= 0)
					break;
				layoutNode(child, cursor, y, w, depth + 1, filter);
				cursor += w;
			}
		}

		private int depth(FlameGraphModel.Node node) {
			int max = 0;
			for (FlameGraphModel.Node child : node.getChildren())
				max = Math.max(max, 1 + depth(child));
			return max;
		}

		private Color dim(Color c) {
			return new Color(
				Math.max(0, c.getRed() / 3),
				Math.max(0, c.getGreen() / 3),
				Math.max(0, c.getBlue() / 3),
				200
			);
		}

		private String truncate(FontMetrics fm, String text, int maxWidth) {
			if (fm.stringWidth(text) <= maxWidth)
				return text;
			String ellipsis = "…";
			StringBuilder sb = new StringBuilder(text);
			while (sb.length() > 0 && fm.stringWidth(sb + ellipsis) > maxWidth)
				sb.setLength(sb.length() - 1);
			return sb.length() == 0 ? ellipsis : sb + ellipsis;
		}

		private final class Bar {
			final FlameGraphModel.Node node;
			final Rectangle bounds;

			Bar(FlameGraphModel.Node node, Rectangle bounds) {
				this.node = node;
				this.bounds = bounds;
			}
		}
	}

	private static class TopTableModel extends AbstractTableModel {
		private final String[] cols = { "Timer", "Type", "Avg ms", "Max ms", "% frame" };
		private List<FlameGraphModel.TopRow> rows = List.of();

		void setRows(List<FlameGraphModel.TopRow> rows) {
			this.rows = rows;
			fireTableDataChanged();
		}

		@Nullable
		FlameGraphModel.TopRow rowAt(int index) {
			if (index < 0 || index >= rows.size())
				return null;
			return rows.get(index);
		}

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return cols.length;
		}

		@Override
		public String getColumnName(int column) {
			return cols[column];
		}

		@Override
		public Class<?> getColumnClass(int columnIndex) {
			return columnIndex >= 2 ? Double.class : String.class;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			FlameGraphModel.TopRow row = rows.get(rowIndex);
			switch (columnIndex) {
				case 0:
					return row.getName();
				case 1:
					return row.getCategory();
				case 2:
					return row.getAvgMs();
				case 3:
					return row.getMaxMs();
				case 4:
					return row.getPercent();
				default:
					return "";
			}
		}
	}
}
