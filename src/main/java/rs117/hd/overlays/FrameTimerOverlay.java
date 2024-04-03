package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayDeque;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import rs117.hd.HdPlugin;

@Singleton
public class FrameTimerOverlay extends OverlayPanel implements FrameTimer.Listener {
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FrameTimer frameTimer;

	private final ArrayDeque<FrameTimings> frames = new ArrayDeque<>();

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
		long now = System.nanoTime();
		while (!frames.isEmpty()) {
			if (now - frames.peekFirst().frameTimestamp < 3e9) // remove entries older than 3 seconds
				break;
			frames.removeFirst();
		}
		frames.addLast(timings);
	}

	@Override
	public Dimension render(Graphics2D g) {
		var timings = getAverageTimings();
		if (timings.length != Timer.values().length) {
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("Waiting for data...")
				.build());
		} else {
			long cpuTime = timings[Timer.DRAW_FRAME.ordinal()];
			addTiming("CPU", cpuTime, true);
			for (var t : Timer.values())
				if (!t.isGpuTimer && t != Timer.DRAW_FRAME)
					addTiming(t, timings);

			long gpuTime = 0;
			for (var t : Timer.values())
				if (t.isGpuTimer)
					gpuTime += timings[t.ordinal()];
			addTiming("GPU", gpuTime, true);
			for (var t : Timer.values())
				if (t.isGpuTimer)
					addTiming(t, timings);

			panelComponent.getChildren().add(LineComponent.builder()
				.leftFont(FontManager.getRunescapeBoldFont())
				.left("Estimated bottleneck:")
				.rightFont(FontManager.getRunescapeBoldFont())
				.right(cpuTime > gpuTime ? "CPU" : "GPU")
				.build());

			panelComponent.getChildren().add(LineComponent.builder()
				.leftFont(FontManager.getRunescapeBoldFont())
				.left("Estimated FPS:")
				.rightFont(FontManager.getRunescapeBoldFont())
				.right(String.format("%.1f FPS", 1 / (Math.max(cpuTime, gpuTime) / 1e9)))
				.build());
		}

		return super.render(g);
	}

	private long[] getAverageTimings() {
		if (frames.isEmpty())
			return new long[0];

		long[] timers = new long[Timer.values().length];
		for (var frame : frames)
			for (int i = 0; i < frame.timers.length; i++)
				timers[i] += frame.timers[i];

		for (int i = 0; i < timers.length; i++)
			timers[i] /= frames.size();

		return timers;
	}

	private void addTiming(Timer timer, long[] timings) {
		addTiming(timer.name, timings[timer.ordinal()], false);
	}

	private void addTiming(String name, long nanos, boolean bold) {
		if (nanos == 0)
			return;

		// Round timers to zero if they are less than a microsecond off
		String formatted = nanos < 3e3 && nanos > -1e5 ? "~0 ms" : String.format("%.3f ms", nanos / 1e6);
		var font = bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont();
		panelComponent.getChildren().add(LineComponent.builder()
			.left(name + ":")
			.leftFont(font)
			.right(formatted)
			.rightFont(font)
			.build());
	}
}
