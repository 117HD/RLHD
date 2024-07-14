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

	@Getter
	private final DeveloperToolsButton timersButton;

	@Getter
	private final DeveloperToolsButton shadowButton;

	@Getter
	private final DeveloperToolsButton lightsButton;

	@Getter
	private final DeveloperToolsButton frezzeButton;

	@Getter
	private final DeveloperToolsButton aabbBoundingBoxesButton;

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
		timersButton = new DeveloperToolsButton(eventBus,"Timers","timers");
		shadowButton = new DeveloperToolsButton(eventBus,"Shadow Map","shadowmap");
		lightsButton = new DeveloperToolsButton(eventBus,"Lights","lights");
		frezzeButton = new DeveloperToolsButton(eventBus,"Freeze Frame","freeze");
		aabbBoundingBoxesButton = new DeveloperToolsButton(eventBus,"Bounding Boxes","freeze");

		setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(createOptionsPanel());
	}

	@SuppressWarnings("PMD.DoubleBraceInitialization")
	private JPanel createOptionsPanel()
	{
		final JPanel container = new JPanel();
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setLayout(new GridLayout(0, 2, 3, 3));

		container.add(tileInfoButton);
		container.add(timersButton);
		container.add(shadowButton);
		container.add(lightsButton);
		container.add(frezzeButton);

		return container;
	}
}