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
import rs117.hd.profiling.ProfileSampleStore;
import rs117.hd.profiling.Profiler;
import rs117.hd.profiling.Timer;
import rs117.hd.utils.FrameTimingsRecorder;

import static rs117.hd.utils.MathUtils.*;

@Singleton
public class FrameTimerOverlay extends OverlayPanel {
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Profiler profiler;

	@Inject
	private ProfileSampleStore profileSampleStore;

	@Inject
	private FrameTimingsRecorder frameTimingsRecorder;

	private final StringBuilder sb = new StringBuilder();

	private long clientTime;
	private long cpuTime;
	private long asyncTime;
	private long gpuTime;
	private float cpuLoad;
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
			profiler.addTimingsListener(profileSampleStore);
			overlayManager.add(this);
		} else {
			profiler.removeTimingsListener(profileSampleStore);
			overlayManager.remove(this);
			clientTime = cpuTime = gpuTime = asyncTime = 0;
			cpuLoad = 0;
		}
	}

	private boolean updateTimings() {
		var frames = profileSampleStore.getFrames();
		if (frames.isEmpty())
			return false;

		clientTime = cpuTime = gpuTime = asyncTime = 0;
		cpuLoad = 0;
		for (var frame : frames) {
			clientTime += frame.timers[Timer.CLIENT.ordinal()];
			cpuTime += frame.timers[Timer.DRAW_FRAME.ordinal()];
			gpuTime += frame.timers[Timer.RENDER_FRAME.ordinal()];
			asyncTime += frame.totalAsyncTime;
			cpuLoad += frame.cpuLoad;
		}

		clientTime /= frames.size();
		cpuTime /= frames.size();
		gpuTime /= frames.size();
		asyncTime /= frames.size();
		cpuLoad /= frames.size();

		return true;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		var children = panelComponent.getChildren();

		if (!updateTimings()) {
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
			.left("Client:")
			.right(formatMillis(clientTime))
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
		children.add(LineComponent.builder()
			.leftFont(boldFont)
			.left("Estimated bottleneck:")
			.rightFont(boldFont)
			.right(clientTime > cpuTime + gpuTime ? "CLIENT" : cpuTime > gpuTime ? "CPU" : "GPU")
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
