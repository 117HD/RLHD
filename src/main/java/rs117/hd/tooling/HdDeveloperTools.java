package rs117.hd.tooling;

import java.awt.GridLayout;
import javax.inject.Inject;
import javax.swing.JPanel;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

@Slf4j
public class HdDeveloperTools extends PluginPanel
{


	@Inject
	private HdDeveloperTools() {
		super();
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(createOptionsPanel());
	}

	@SuppressWarnings("PMD.DoubleBraceInitialization")
	private JPanel createOptionsPanel()
	{
		final JPanel container = new JPanel();
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setLayout(new GridLayout(0, 2, 3, 3));

		DeveloperTools.settings.forEach( (s, developerSettings) -> container.add(developerSettings.getButton()));

		return container;
	}
}