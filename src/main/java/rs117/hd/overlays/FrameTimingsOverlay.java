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
public class FrameTimingsOverlay extends OverlayPanel implements FrameTimer.Listener {
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FrameTimer frameTimer;

	private final ArrayDeque<FrameTimings> frames = new ArrayDeque<>();

	@Inject
	public FrameTimingsOverlay(HdPlugin plugin) {
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
		while (frames.size() > 0) {
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
			long cpuTime = timings[Timer.DRAW_SCENE.ordinal()];
			addTiming("CPU", cpuTime, true);
			for (var t : Timer.values())
				if (!t.isGpuTimer)
					addTiming(t, timings);

			long gpuTime =
				timings[Timer.UPLOAD_GEOMETRY.ordinal()] +
				timings[Timer.UPLOAD_UI.ordinal()] +
				timings[Timer.COMPUTE.ordinal()] +
				timings[Timer.RENDER_SHADOWS.ordinal()] +
				timings[Timer.RENDER_SCENE.ordinal()] +
				timings[Timer.RENDER_UI.ordinal()];
			addTiming("GPU", gpuTime, true);
			for (var t : Timer.values())
				if (t.isGpuTimer)
					addTiming(t, timings);

			panelComponent.getChildren().add(LineComponent.builder()
				.leftFont(FontManager.getRunescapeBoldFont())
				.left("Max Frame Rate:")
				.rightFont(FontManager.getRunescapeBoldFont())
				.right(String.format("%.1f FPS", 1 / (Math.max(cpuTime, gpuTime) / 1e9)))
				.build());
		}

		return super.render(g);
	}

	private long[] getAverageTimings() {
		if (frames.size() == 0)
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
