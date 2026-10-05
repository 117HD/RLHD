package rs117.hd.overlays;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.utils.collections.ConcurrentPool;

@Singleton
public class DebugPrintOverlay extends Overlay {
	private static final ConcurrentPool<Message> POOL = new ConcurrentPool<>(Message::new);
	private static DebugPrintOverlay INSTANCE;

	private static final int MAX_MESSAGES = 64;
	private static final int LINE_PADDING = 2;

	@Inject
	private OverlayManager overlayManager;

	private final ArrayList<Message> messages = new ArrayList<>();

	@Inject
	public DebugPrintOverlay() {
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);

		INSTANCE = this;
	}

	public void setActive(boolean active) {
		if(active){
			overlayManager.add(this);
		} else {
			overlayManager.remove(this);
		}
	}

	public static void Submit(float elapsedTime, Color color, String message) {
		if (message == null)
			return;

		final Message msg = POOL.acquire();
		msg.text = message;
		msg.color = color;
		msg.expireNanos = elapsedTime > 0 ? System.nanoTime() + (long) (elapsedTime * 1e9) : 0;

		synchronized (INSTANCE.messages){
			INSTANCE.messages.add(msg);

			while (INSTANCE.messages.size() > MAX_MESSAGES)
				INSTANCE.messages.remove(0);
		}
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (messages.isEmpty())
			return null;

		final FontMetrics fm = graphics.getFontMetrics();
		final long now = System.nanoTime();
		final int lineHeight = fm.getHeight() + LINE_PADDING;

		int y = fm.getAscent();
		int maxWidth = 0;
		int keep = 0;

		for (int i = 0; i < messages.size(); i++) {
			Message msg = messages.get(i);

			boolean singleFrame = msg.expireNanos == 0;
			boolean expired = !singleFrame && now - msg.expireNanos >= 0;

			if (!expired) {
				String text = msg.text;
				for (int start = 0, k = 0; k <= text.length(); k++) {
					if (k == text.length() || text.charAt(k) == '\n') {
						String line = text.substring(start, k);

						// Drop shadow for readability on any background
						graphics.setColor(Color.BLACK);
						graphics.drawString(line, 1, y + 1);

						graphics.setColor(msg.color);
						graphics.drawString(line, 0, y);

						maxWidth = Math.max(maxWidth, fm.stringWidth(line));
						y += lineHeight;

						start = k + 1;
					}
				}
			}

			if (singleFrame || expired) {
				POOL.recycle(msg);
			} else {
				messages.set(keep++, msg);
			}
		}

		for (int i = messages.size() - 1; i >= keep; i--)
			messages.remove(i);

		return new Dimension(maxWidth, y);
	}

	private static final class Message {
		String text;
		Color color;
		long expireNanos;
	}
}