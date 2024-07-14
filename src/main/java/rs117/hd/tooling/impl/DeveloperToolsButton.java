package rs117.hd.tooling.impl;

import java.awt.Color;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.swing.JButton;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.ColorScheme;

public class DeveloperToolsButton extends JButton {
	private boolean active;

	public DeveloperToolsButton(EventBus eventBus,String title, String key) {
		super(title);
		this.addActionListener((ev) -> {
			this.setActive(!this.active);
			eventBus.post(new CommandExecuted("117hd", new String[] { key }));
		});
		this.setToolTipText(title);
	}

	public void setActive(boolean active) {
		this.active = active;
		if (active) {
			this.setBackground(Color.GREEN);
		} else {
			this.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		}

	}


	public boolean isActive() {
		return this.active;
	}
}
