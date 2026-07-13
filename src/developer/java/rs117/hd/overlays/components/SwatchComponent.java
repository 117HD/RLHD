package rs117.hd.overlays.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

public class SwatchComponent implements LayoutableRenderableEntity {
	private final Color color;
	private final Rectangle bounds = new Rectangle();

	public SwatchComponent(Color color) {
		this.color = color;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if(color != null) {
			graphics.setColor(color);
			graphics.fillRect(
				bounds.x,
				bounds.y,
				10,
				10
			);
		}

		bounds.setSize(10, 10);
		return new Dimension(10, 10);
	}

	@Override
	public void setPreferredLocation(Point point) {
		bounds.setLocation(point);
	}

	@Override
	public void setPreferredSize(Dimension dimension) {}

	@Override
	public Rectangle getBounds() {
		return bounds;
	}
}
