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

	@Inject
	public HDOverlayPanel(HdPlugin plugin) {
		super(plugin);
	}

	public Dimension onRender(final Graphics2D graphics) {
		return super.render(graphics);
	}

	public Dimension render(final Graphics2D graphics) {
		final boolean ended = profiler.end(Timer.CLIENT);
		try {
			return onRender(graphics);
		}finally {
			if(ended)
				profiler.begin(Timer.CLIENT);
		}
	}
}
