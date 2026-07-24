package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import javax.annotation.Nullable;
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
import rs117.hd.profiling.ProfileSample;
import rs117.hd.profiling.ProfileSampleStore;
import rs117.hd.profiling.Profiler;

@Slf4j
@Singleton
public class ProfilerGraphOverlay extends OverlayPanel implements MouseListener {
	private static final int SCREEN_MARGIN = 24;
	private static final int PANEL_BORDER = 4;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Client client;

	@Inject
	private ProfilerOverlay profilerOverlay;

	@Inject
	private ProfileSampleStore profileSampleStore;

	@Inject
	private ProfilerUI ui;

	@Inject
	private EventBus eventBus;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private Profiler profiler;

	private ProfilerGraphs graphs;

	@Getter
	private boolean active;

	private GraphComponent<ProfileSample> dragGraph;
	private Point dragStartPoint;

	@Inject
	public ProfilerGraphOverlay(HdPlugin plugin) {
		super(plugin);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.TOP_RIGHT);
		setPreferredLocation(new Point(50, 10));
		setMinimumSize(GraphComponent.MIN_GRAPH_WIDTH / 2);
	}

	private ProfilerGraphs graphs() {
		if (graphs == null)
			graphs = new ProfilerGraphs(ui);
		return graphs;
	}

	void ensureGraphs() {
		ProfilerGraphs g = graphs();
		if (!g.isEmpty())
			return;

		g.create(
			() -> profilerOverlay.getHoveredTimer(),
			() -> {
				var p = client.getMouseCanvasPosition();
				return p == null ? null : new Point(p.getX(), p.getY());
			}
		);
	}

	public boolean hasGpuMemoryGraph() {
		return graphs().hasGpuMemoryGraph();
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
		ensureGraphs();
		graphs().applyOverlaySizes(
			Math.max(1, client.getCanvasWidth()),
			Math.max(1, client.getCanvasHeight()),
			getPreferredSize(),
			PANEL_BORDER,
			SCREEN_MARGIN
		);
		graphs().syncFrames(profileSampleStore);
		graphs().forEachVisible(panelComponent.getChildren()::add);

		Dimension dimension = super.render(g);
		graphs().renderTooltips(g);
		return dimension;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event) {
		if (active && event.getButton() == MouseEvent.BUTTON1) {
			GraphComponent<ProfileSample> hit = findGraphAt(event.getPoint());
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
		if (dragGraph != null) {
			if (dragStartPoint.distance(event.getPoint()) <= 0.01)
				dragGraph.clearSelection();
			else
				dragGraph.setSelectionFromPoints(dragStartPoint, event.getPoint());
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

	@Nullable
	private GraphComponent<ProfileSample> findGraphAt(Point canvasPoint) {
		var panelBounds = getBounds();
		if (panelBounds == null || panelBounds.width <= 0 || panelBounds.height <= 0)
			return null;

		return graphs().findAtLocal(
			canvasPoint.x - panelBounds.x,
			canvasPoint.y - panelBounds.y
		);
	}
}
