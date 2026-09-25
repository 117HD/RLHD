package rs117.hd.gui.components;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JToggleButton;
import javax.swing.plaf.basic.BasicButtonUI;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;
import rs117.hd.gui.HdSidebar;

public class ToggleSwitch extends JToggleButton {
	private static final ImageIcon ON_SWITCHER;
	private static final ImageIcon OFF_SWITCHER;

	static {
		BufferedImage onSwitcher = ImageUtil.loadImageResource(HdSidebar.class, "switcher_on.png");
		ON_SWITCHER = new ImageIcon(onSwitcher);
		OFF_SWITCHER = new ImageIcon(ImageUtil.flipImage(
			ImageUtil.luminanceScale(ImageUtil.grayscaleImage(onSwitcher), 0.61f),
			true, false
		));
	}

	public ToggleSwitch(boolean selected) {
		super(OFF_SWITCHER);
		setSelectedIcon(ON_SWITCHER);
		setSelected(selected);
		SwingUtil.removeButtonDecorations(this);
		setContentAreaFilled(false);
		setBorderPainted(false);
		setFocusPainted(false);
		setOpaque(false);
		setUI(new BasicButtonUI());
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		Dimension size = new Dimension(ON_SWITCHER.getIconWidth(), ON_SWITCHER.getIconHeight());
		setPreferredSize(size);
		setMinimumSize(size);
		setMaximumSize(size);
	}
}
