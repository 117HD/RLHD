package rs117.hd.gui;

import com.google.inject.Inject;
import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.skybox.SkyboxManager;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class EnvironmentEditor extends JFrame {

	@Inject
	private ClientThread clientThread;
	@Inject
	private EnvironmentManager environmentManager;
	@Inject
	private Client client;

	@Inject
	private TextureManager textureManager;

	@Inject
	private SkyboxManager skyboxManager;

	private JPanel editorContent = new JPanel();

	private JPanel groundMaterialPanel = new JPanel();

	public RuneliteColorPicker colorPicker;

	@Inject
	public EnvironmentEditor() {
		// Call the JFrame constructor with a title
		super("Editor");
		setSize(760, 635);

		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);

	}

	public void setState(boolean state) {
		if (state) {
			open();
		} else {
			close();
		}
	}

	public void open() {
		SwingUtilities.invokeLater(() -> {
			if (!isVisible()) {
				JPanel display = new JPanel(new BorderLayout());
				MaterialTabGroup tabGroup = new MaterialTabGroup(display);

				JPanel comingSoonPanel = new JPanel(new BorderLayout());
				comingSoonPanel.add(new javax.swing.JLabel("Coming Soon", javax.swing.SwingConstants.CENTER), BorderLayout.CENTER);
				MaterialTab editorTab = new MaterialTab("Environment Editor", tabGroup, comingSoonPanel);

				JPanel skyboxEditorPanel = new SkyboxEditorPanel(clientThread,client,environmentManager,textureManager,skyboxManager);

				MaterialTab skyboxTab = new MaterialTab("Skybox Editor", tabGroup, skyboxEditorPanel);

				tabGroup.setBorder(new EmptyBorder(5, 0, 0, 0));
				tabGroup.addTab(editorTab);
				tabGroup.addTab(skyboxTab);
				tabGroup.select(editorTab);

				add(tabGroup, BorderLayout.NORTH);
				add(display, BorderLayout.CENTER);

				setVisible(true);
			}
		});
	}

	public void close() {
		SwingUtilities.invokeLater(() -> {
			setVisible(false);
		});
	}
}