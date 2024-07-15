package rs117.hd.tooling.impl;

import java.awt.Color;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JButton;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.ColorScheme;
import rs117.hd.tooling.DeveloperOverlay;
import rs117.hd.tooling.DeveloperSettings;

public class DeveloperToolsButton extends JButton {

	public DeveloperToolsButton(String title, DeveloperSettings developerSettings) {
		super(title);
		this.setToolTipText(title);
		this.addActionListener((ev) -> developerSettings.toggle());

	}

	public void setActive(boolean active) {
		this.setBackground(active ? Color.GREEN : ColorScheme.DARKER_GRAY_COLOR);
	}

}
