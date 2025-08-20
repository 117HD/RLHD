package rs117.hd.utils.tooling.ui.impl;

import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.border.EmptyBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import rs117.hd.utils.tooling.DeveloperTool;
import rs117.hd.utils.tooling.DeveloperTools;

@Slf4j
public class DeveloperPanel extends JPanel {

	private final Map<DeveloperTool, JButton> toolButtons = new HashMap<>();
	private final Map<DeveloperTool.SubToggle, JCheckBox> subToggleCheckboxes = new HashMap<>();
	private final Map<DeveloperTool.SubToggle, JComboBox<String>> subToggleDropdowns = new HashMap<>();
	private final DeveloperTools developerTools;

	public DeveloperPanel(DeveloperTools developerTools) {
		this.developerTools = developerTools;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(15, 15, 15, 15));

		for (DeveloperTool tool : developerTools.getDeveloperTools()) {
			JPanel toolPanel = createToolPanel(tool);
			add(toolPanel);
			add(Box.createVerticalStrut(2));
		}

		startStateSync();
	}

	private void startStateSync() {
		javax.swing.Timer timer = new javax.swing.Timer(100, e -> syncUIState());
		timer.start();
	}

	private void syncUIState() {
		for (Map.Entry<DeveloperTool, JButton> entry : toolButtons.entrySet()) {
			DeveloperTool tool = entry.getKey();
			JButton button = entry.getValue();
			updateButtonState(button, tool);
		}

		for (Map.Entry<DeveloperTool.SubToggle, JCheckBox> entry : subToggleCheckboxes.entrySet()) {
			DeveloperTool.SubToggle subToggle = entry.getKey();
			JCheckBox checkBox = entry.getValue();
		}

		for (Map.Entry<DeveloperTool.SubToggle, JComboBox<String>> entry : subToggleDropdowns.entrySet()) {
			DeveloperTool.SubToggle subToggle = entry.getKey();
			JComboBox<String> comboBox = entry.getValue();

			if (subToggle.getValueSupplier() != null) {
				Object currentValue = subToggle.getValueSupplier().getValue();
				if (currentValue instanceof Integer) {
					int mode = (Integer) currentValue;
					if (mode < subToggle.getOptions().size() && mode != comboBox.getSelectedIndex()) {
						comboBox.setSelectedIndex(mode);
					}
				}
			}
		}
	}

	private JPanel createToolPanel(DeveloperTool tool) {
		JPanel toolPanel = new JPanel();
		toolPanel.setLayout(new BorderLayout(0, 5));
		toolPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Constrain the panel size to prevent stretching
		toolPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
		toolPanel.setPreferredSize(new Dimension(200, 40));

		if (tool.getSubToggles() != null && !tool.getSubToggles().isEmpty()) {
			toolPanel.setBorder(new CompoundBorder(
				new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1, true),
				new EmptyBorder(10, 10, 10, 10)
			));
			// Increase size for tools with sub-toggles
			toolPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
			toolPanel.setPreferredSize(new Dimension(200, 120));
		} else {
			toolPanel.setBorder(new EmptyBorder(1, 0, 1, 0));
		}

		JButton mainButton = createToolButton(tool);
		mainButton.setPreferredSize(new Dimension(200, 30));
		toolPanel.add(mainButton, BorderLayout.NORTH);

		toolButtons.put(tool, mainButton);

		if (tool.getSubToggles() != null && !tool.getSubToggles().isEmpty()) {
			JPanel subTogglesPanel = new JPanel();
			subTogglesPanel.setLayout(new BoxLayout(subTogglesPanel, BoxLayout.Y_AXIS));
			subTogglesPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			subTogglesPanel.setBorder(new CompoundBorder(
				new LineBorder(ColorScheme.LIGHT_GRAY_COLOR, 1, true),
				new EmptyBorder(8, 8, 8, 8)
			));

			// Add sub-toggles header
			JLabel subHeader = new JLabel("Options:");
			subHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			subHeader.setBorder(new EmptyBorder(0, 0, 5, 0));
			subTogglesPanel.add(subHeader);

			for (DeveloperTool.SubToggle subToggle : tool.getSubToggles()) {
				JPanel subTogglePanel = createSubTogglePanel(subToggle);
				subTogglesPanel.add(subTogglePanel);
				subTogglesPanel.add(Box.createVerticalStrut(3));
			}

			toolPanel.add(subTogglesPanel, BorderLayout.CENTER);
		}

		return toolPanel;
	}

	private JPanel createSubTogglePanel(DeveloperTool.SubToggle subToggle) {
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout(8, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(2, 0, 2, 0));

		switch (subToggle.getType()) {
			case BOOLEAN:
				JCheckBox checkBox = new JCheckBox(formatButtonName(subToggle.getName()));
				checkBox.setToolTipText(subToggle.getDescription());
				checkBox.setForeground(Color.WHITE);
				checkBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				checkBox.addActionListener(e -> {
					if (subToggle.getOnToggle() != null) {
						subToggle.getOnToggle().run();
					}
				});
				panel.add(checkBox, BorderLayout.WEST);
				
				// Store the checkbox for state syncing
				subToggleCheckboxes.put(subToggle, checkBox);
				break;

			case DROPDOWN:
				JLabel label = new JLabel(formatButtonName(subToggle.getName()) + ":");
				label.setForeground(Color.WHITE);
				panel.add(label, BorderLayout.WEST);
				
				JComboBox<String> comboBox = new JComboBox<>(subToggle.getOptions().toArray(new String[0]));
				comboBox.setToolTipText(subToggle.getDescription());
				comboBox.setPreferredSize(new Dimension(130, 22));
				comboBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
				comboBox.setForeground(Color.WHITE);
				
				// Set initial value if value supplier exists
				if (subToggle.getValueSupplier() != null) {
					Object currentValue = subToggle.getValueSupplier().getValue();
					if (currentValue instanceof Integer) {
						int mode = (Integer) currentValue;
						String modeName = subToggle.getOptions().get(mode);
						comboBox.setSelectedItem(modeName);
					}
				}
				
				comboBox.addActionListener(e -> {
					if (subToggle.getValueSetter() != null) {
						int selectedIndex = comboBox.getSelectedIndex();
						subToggle.getValueSetter().setValue(selectedIndex);
					}
				});
				panel.add(comboBox, BorderLayout.EAST);
				
				// Store the comboBox for state syncing
				subToggleDropdowns.put(subToggle, comboBox);
				break;
		}

		return panel;
	}

	private JButton createToolButton(DeveloperTool tool) {
		JButton button = new JButton(formatButtonName(tool.getName()));

		updateButtonState(button, tool);
		
		// Create tooltip with formatted name and description
		String tooltip = formatButtonName(tool.getName()) + "\n" + tool.getDisplayDescription();
		button.setToolTipText(tooltip);
		
		button.addActionListener(e -> {
			tool.toggle();
			updateButtonState(button, tool);
		});

		return button;
	}

	private String formatButtonName(String name) {
		String[] words = name.toLowerCase().split("_");
		StringBuilder result = new StringBuilder();
		
		for (int i = 0; i < words.length; i++) {
			if (i > 0) {
				result.append(" ");
			}
			if (!words[i].isEmpty()) {
				result.append(Character.toUpperCase(words[i].charAt(0)));
				result.append(words[i].substring(1));
			}
		}
		
		return result.toString();
	}

	private void updateButtonState(JButton button, DeveloperTool tool) {
		if (tool.isActive()) {
			button.setBackground(new Color(76, 175, 80));
			button.setForeground(Color.WHITE);
			button.setBorder(new LineBorder(new Color(56, 142, 60), 2, true));
		} else {
			button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			button.setForeground(Color.WHITE);
			button.setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1, true));
		}
	}

}
