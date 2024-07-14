package rs117.hd.tooling;

import java.awt.GridLayout;
import javax.inject.Inject;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import rs117.hd.tooling.impl.DeveloperToolsButton;

@Slf4j
public class HdDeveloperTools extends PluginPanel
{
	private final Client client;
	private final ClientThread clientThread;

	private final EventBus eventBus;

	@Getter
	private final DeveloperToolsButton tileInfoButton;

	@Inject
	private HdDeveloperTools(
		Client client,
		ClientThread clientThread,
		EventBus eventBus
	)
	{
		super();
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;

		tileInfoButton = new DeveloperToolsButton(eventBus,"Tile Info","tileinfo");

		setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(createOptionsPanel());
	}

	public void refresh() {
		tileInfoButton.invalidate();
		tileInfoButton.repaint();
		SwingUtilities.invokeLater(() -> {
			// Remove all components
			removeAll();

			// Add the new options panel
			add(createOptionsPanel());

			// Revalidate the panel to trigger layout manager
			revalidate();

			// Repaint the panel to update the UI
			repaint();
		});
		System.out.println("SIs Active: " + tileInfoButton.isActive());
	}

	@SuppressWarnings("PMD.DoubleBraceInitialization")
	private JPanel createOptionsPanel()
	{
		final JPanel container = new JPanel();
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setLayout(new GridLayout(0, 2, 3, 3));

		container.add(tileInfoButton);
		container.add(new DeveloperToolsButton(eventBus,"Timers","timers"));
		container.add(new DeveloperToolsButton(eventBus,"Shadow Map","shadowmap"));
		container.add(new DeveloperToolsButton(eventBus,"Lights","lights"));
		container.add(new DeveloperToolsButton(eventBus,"Freeze Frame","freeze"));

		return container;
	}
}