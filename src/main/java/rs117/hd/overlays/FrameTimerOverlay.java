package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import rs117.hd.HdPlugin;
import rs117.hd.profiling.ProfileSample;
import rs117.hd.profiling.Profiler;
import rs117.hd.profiling.Timer;
import rs117.hd.utils.FrameTimingsRecorder;

import static rs117.hd.utils.MathUtils.*;

@Singleton
public class FrameTimerOverlay extends OverlayPanel implements Profiler.Listener {
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Profiler profiler;

	@Inject
	private FrameTimingsRecorder frameTimingsRecorder;

	private final StringBuilder sb = new StringBuilder();

	private volatile long cpuTime;
	private volatile long asyncTime;
	private volatile long gpuTime;
	private volatile float cpuLoad;
	private boolean active;

	@Inject
	public FrameTimerOverlay(HdPlugin plugin) {
		super(plugin);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.TOP_RIGHT);
		panelComponent.setPreferredSize(new Dimension(100, 0));
	}

	public void setActive(boolean active) {
		if (this.active == active)
			return;

		this.active = active;
		if (active) {
			profiler.addTimingsListener(this);
			overlayManager.add(this);
		} else {
			profiler.removeTimingsListener(this);
			overlayManager.remove(this);
			cpuTime = gpuTime = 0;
			cpuLoad = 0;
		}
	}

	@Override
	public void onFrameCompletion(ProfileSample sample) {
		cpuTime = sample.timers[Timer.DRAW_FRAME.ordinal()];
		gpuTime = sample.timers[Timer.RENDER_FRAME.ordinal()];
		asyncTime = sample.totalAsyncTime;
		cpuLoad = sample.cpuLoad;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		var children = panelComponent.getChildren();
		if (cpuTime <= 0 || gpuTime <= 0) {
			children.add(TitleComponent.builder()
				.text("Waiting for frame data...")
				.build());
			return super.render(graphics);
		}

		var boldFont = FontManager.getRunescapeBoldFont();
		children.add(LineComponent.builder()
			.leftFont(boldFont)
			.left("Estimated FPS:")
			.rightFont(boldFont)
			.right(String.format("%.1f", 1e9 / max(cpuTime, gpuTime)))
			.build());
		children.add(LineComponent.builder()
			.left("CPU:")
			.right(formatMillis(cpuTime))
			.build());
		children.add(LineComponent.builder()
			.left("Async:")
			.right(formatMillis(asyncTime))
			.build());
		children.add(LineComponent.builder()
			.left("GPU:")
			.right(formatMillis(gpuTime))
			.build());
		if (cpuLoad > 0) {
			children.add(LineComponent.builder()
				.left("CPU load:")
				.right((int) (cpuLoad * 100) + "%")
				.build());
		}
		if (frameTimingsRecorder.isCapturingSnapshot()) {
			children.add(LineComponent.builder()
				.left("Snapshot:")
				.right(frameTimingsRecorder.getProgressPercentage() + "%")
				.build());
		}

		return super.render(graphics);
	}

	private String formatMillis(long nanos) {
		String result = "~0 ms";
		if (abs(nanos) > 1e3) {
			sb.setLength(0);
			result = sb.append(round(nanos / 1e3) / 1e3).append(" ms").toString();
		}
		return result;
	}
}
