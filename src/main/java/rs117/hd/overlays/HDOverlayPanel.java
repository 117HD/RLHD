package rs117.hd.overlays;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import rs117.hd.HdPlugin;
import rs117.hd.profiling.Profiler;
import rs117.hd.profiling.Timer;

public abstract class HDOverlayPanel extends OverlayPanel {

	@Inject
	private Profiler profiler;

	private final boolean[] pausedTimers = new boolean[Timer.TIMERS.length];

	@Inject
	public HDOverlayPanel(HdPlugin plugin) {
		super(plugin);
	}

	public Dimension onRender(final Graphics2D graphics) {
		return super.render(graphics);
	}

	public Dimension render(final Graphics2D graphics) {
		for(int i = 0; i < pausedTimers.length; i++) {
			final Timer timer = Timer.TIMERS[i];
			if(timer.isCpuTimer())
				pausedTimers[i] = profiler.end(Timer.TIMERS[i]);
		}
		try {
			return onRender(graphics);
		}finally {
			for(int i = 0; i < pausedTimers.length; i++) {
				final Timer timer = Timer.TIMERS[i];
				if(timer.isAsyncCpuTimer() && pausedTimers[i])
					profiler.begin(timer);
			}
		}
	}
}
