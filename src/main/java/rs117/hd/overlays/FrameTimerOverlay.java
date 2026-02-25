package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import rs117.hd.HdPlugin;

import static rs117.hd.utils.MathUtils.*;

@Singleton
public class FrameTimerOverlay extends OverlayPanel implements FrameTimer.Listener {
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	private final ArrayDeque<FrameTimings> frames = new ArrayDeque<>();
	private final long[] timings = new long[Timer.TIMERS.length];
	private float cpuLoad;
	private final Map<String, LineComponent> componentMap = new HashMap<>();
	private final StringBuilder sb = new StringBuilder();

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
		} else {
			frameTimer.removeTimingsListener(this);
			overlayManager.remove(this);
			frames.clear();
		}
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
		} else {
			long cpuTime = timings[Timer.DRAW_FRAME.ordinal()];
			long asyncCpuTime = 0;
			addTiming("CPU", cpuTime, true);
			for (var t : Timer.TIMERS) {
				if (t.isCpuTimer() && t != Timer.DRAW_FRAME)
					addTiming(t, timings);
				if (t.isAsyncCpuTimer())
					asyncCpuTime += timings[t.ordinal()];
			}

			addTiming("Async", asyncCpuTime, true);
			for (var t : Timer.TIMERS)
				if (t.isAsyncCpuTimer())
					addTiming(t, timings);

			if (cpuLoad > 0) {
				children.add(LineComponent.builder()
					.left("CPU Load:")
					.right((int) (cpuLoad * 100) + "%")
					.build());
			}

			long gpuTime = timings[Timer.RENDER_FRAME.ordinal()];
			addTiming("GPU", gpuTime, true);
			for (var t : Timer.TIMERS)
				if (t.isGpuTimer() && t != Timer.RENDER_FRAME)
					addTiming(t, timings);

			children.add(LineComponent.builder()
				.leftFont(boldFont)
				.left("Estimated bottleneck:")
				.rightFont(boldFont)
				.right(cpuTime > gpuTime ? "CPU" : "GPU")
				.build());

			children.add(LineComponent.builder()
				.leftFont(boldFont)
				.left("Estimated FPS:")
				.rightFont(boldFont)
				.right(String.format("%.1f FPS", 1e9 / max(cpuTime, gpuTime)))
				.build());

			children.add(LineComponent.builder()
				.left("Error compensation:")
				.right(String.format("%d ns", frameTimer.errorCompensation))
				.build());
		}

		var result = super.render(g);
		frameTimer.cumulativeError += System.nanoTime() - time;
		return result;
	}

	private boolean getAverageTimings() {
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
			timings[i] = max(0, timings[i] / frames.size());
		cpuLoad /= frames.size();

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

		LineComponent component = componentMap.get(name);
		if (component == null) {
			var font = bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont();
			component = LineComponent.builder()
				.left(name + ":")
				.leftFont(font)
				.right(result)
				.rightFont(font)
				.build();
			componentMap.put(name, component);
		} else {
			component.setRight(result);
		}

		panelComponent.getChildren().add(component);
	}
}
