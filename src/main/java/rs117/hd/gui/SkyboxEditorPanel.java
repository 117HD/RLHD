package rs117.hd.gui;

import com.google.gson.GsonBuilder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.InputVerifier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeCellRenderer;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.skybox.SkyboxConfig;
import rs117.hd.scene.skybox.SkyboxManager;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

public class SkyboxEditorPanel extends JPanel {
	private JTree skyboxTree;
	private JPanel infoPanel;
	private rs117.hd.scene.skybox.SkyboxConfig.SkyboxEntry originalEntry;
	private rs117.hd.scene.skybox.SkyboxConfig.SkyboxEntry editedEntry;
	private JTextField nameField;
	private JComboBox<String> dirCombo;
	private DefaultComboBoxModel<String> dirComboModel;
	private JSpinner rotationSpinner;
	private JSpinner rotationSpeedSpinner;
	private JSlider[] postProcessingSliders;
	private JSpinner[] rgbSpinners;
	private JButton forceButton;
	private JButton resetButton;
	private boolean hasChanges = false;
	private JLabel[] sliderValueLabels;
	private JSpinner[] postProcessingSpinners;
	private String forcedSkyboxName;
	private boolean isUpdatingUI = false;

	public static final ResourcePath TEXTURE_SKYBOX_PATH = Props
		.getFolder("rlhd.texture-skybox-path", () -> path(TextureManager.class, "textures/skybox"));

	private EnvironmentManager environmentManager;
	private Client client;
	private ClientThread clientThread;
	private TextureManager textureManager;
	private SkyboxManager skyboxManager;


	public SkyboxEditorPanel(
		ClientThread clientThread, Client client,
		EnvironmentManager environmentManager,
		TextureManager textureManager,
		SkyboxManager skyboxManager
	) {

		super(new BorderLayout());

		this.skyboxManager = skyboxManager;
		this.environmentManager = environmentManager;
		this.client = client;
		this.clientThread = clientThread;
		this.textureManager = textureManager;

		forcedSkyboxName = environmentManager.currentSkybox != null ? environmentManager.currentSkybox.getName() : "";

		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Skyboxes");
		for (SkyboxConfig.SkyboxEntry skybox : skyboxManager.getSkyboxConfig().getSkyboxes()) {
			String skyboxName = skybox.getName();
			if (skyboxName.equalsIgnoreCase("debug")) continue;
			root.add(new DefaultMutableTreeNode(skyboxName));
		}

		skyboxTree = new JTree(root);

		dirComboModel = new DefaultComboBoxModel<>();

		for (var dirPath : TEXTURE_SKYBOX_PATH.listSubdirectories()) {
			String name = dirPath.toFile().getName();
			dirComboModel.addElement(name);
		}

		String currentSkyboxName = environmentManager.currentSkybox != null ? environmentManager.currentSkybox.getName() : "";
		JLabel activeLabel = new JLabel("Current active: " + currentSkyboxName);
		JPanel leftPanel = new JPanel();
		leftPanel.setLayout(new BorderLayout());
		leftPanel.add(activeLabel, BorderLayout.NORTH);
		leftPanel.add(new JScrollPane(skyboxTree), BorderLayout.CENTER);

		skyboxTree.setCellRenderer(new TreeCellRenderer() {
			DefaultTreeCellRenderer defaultRenderer = new DefaultTreeCellRenderer();

			@Override
			public Component getTreeCellRendererComponent(
				JTree tree,
				Object value,
				boolean selected,
				boolean expanded,
				boolean leaf,
				int row,
				boolean hasFocus
			) {
				Component c = defaultRenderer.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

				if (c instanceof DefaultTreeCellRenderer) {
					DefaultTreeCellRenderer r = (DefaultTreeCellRenderer) c;
					r.setLeafIcon(null);
					r.setClosedIcon(null);
					r.setOpenIcon(null);
				}
				if (leaf && value instanceof DefaultMutableTreeNode) {
					String nodeName = value.toString();
					if (nodeName.equals(currentSkyboxName)) {
						JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
						JLabel tick = new JLabel("\u2714"); // Unicode checkmark
						tick.setForeground(new java.awt.Color(0, 180, 0));
						tick.setFont(tick.getFont().deriveFont(tick.getFont().getStyle(), 16f));
						panel.setOpaque(false);
						panel.add(tick);
						panel.add(Box.createHorizontalStrut(4));
						panel.add(c);
						return panel;
					}
				}
				return c;
			}
		});

		// Info panel on the right
		infoPanel = new JPanel();
		infoPanel.setLayout(new BorderLayout());
		JLabel defaultLabel = new JLabel("Select a skybox to view details", SwingConstants.CENTER);
		infoPanel.add(defaultLabel, BorderLayout.CENTER);

		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, infoPanel);
		splitPane.setDividerLocation(200);
		splitPane.setResizeWeight(0.3);
		this.setLayout(new BorderLayout());
		this.add(splitPane, BorderLayout.CENTER);

		// Add window listener to revert to forced skybox on close
		addHierarchyListener(e -> {
			if (e.getChangeFlags() == HierarchyEvent.SHOWING_CHANGED) {
				if (!isShowing() && forcedSkyboxName != null && !forcedSkyboxName.isEmpty()) {
					environmentManager.currentSkybox = skyboxManager.getSkybox(forcedSkyboxName);
				}
			}
		});

		skyboxTree.addTreeSelectionListener(e -> {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) skyboxTree.getLastSelectedPathComponent();
			if (node == null || node.isRoot()) {
				infoPanel.removeAll();
				infoPanel.add(defaultLabel, BorderLayout.CENTER);
				infoPanel.revalidate();
				infoPanel.repaint();
				return;
			}

			if (hasChanges) {
				int result = JOptionPane.showConfirmDialog(
					SkyboxEditorPanel.this,
					"You have unsaved changes. Do you want to discard them and switch skybox?",
					"Unsaved Changes",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE
				);
				if (result == JOptionPane.NO_OPTION) {
					// Do nothing, just close the popup
					return;
				}
			}

			String skyboxName = node.getUserObject().toString();
			rs117.hd.scene.skybox.SkyboxConfig.SkyboxEntry entry = skyboxManager.getSkybox(skyboxName);
			if (entry != null) {
				originalEntry = entry;
				editedEntry = deepCopySkyboxEntry(entry);
				environmentManager.currentSkybox = skyboxManager.getSkybox(skyboxName);
				showSkyboxDetails(editedEntry);
				hasChanges = false;
			} else {
				infoPanel.add(new JLabel("Skybox not found", SwingConstants.CENTER), BorderLayout.CENTER);
			}
			infoPanel.revalidate();
			infoPanel.repaint();
		});
	}

	private void refreshTree() {
		skyboxTree.repaint();
	}

	private void setSpinnerAndSlider(JSlider slider, JSpinner spinner, JLabel label, float value, float min, float max) {
		float clamped = Math.max(min, Math.min(max, value));
		slider.setValue((int) Math.round((clamped - min) * 100));
		label.setText(String.format("%.2f", clamped));
		if (spinner != null) {
			spinner.setValue((double) clamped);
		}
	}

	private void setSpinnerModel(JSpinner spinner, float value, float min, float max) {
		spinner.setModel(new SpinnerNumberModel(value, min, max, 0.01));
		spinner.setValue((double) value);
	}

	private void setRgbSpinner(JSpinner spinner, float value) {
		spinner.setModel(new SpinnerNumberModel(value, -2.0, 19.0, 0.01));
		spinner.setValue((double) value);
	}

	private boolean isEditingActiveSkybox() {
		return environmentManager.currentSkybox != null
			   && editedEntry != null
			   && environmentManager.currentSkybox.getName().equals(editedEntry.getName());
	}

	private void updateUIFieldsFromEditedEntry() {
		isUpdatingUI = true;
		nameField.setText(editedEntry.getName());
		dirCombo.setSelectedItem(editedEntry.getDir());
		rotationSpinner.setValue(Math.toDegrees(editedEntry.getRotation()));
		rotationSpeedSpinner.setValue(editedEntry.getRotationSpeed());
		if (editedEntry.getPostProcessing() != null && postProcessingSliders != null) {
			float[] sliderMins = { -1.0f, 0.0f, 0.0f, 0.0f };
			float[] sliderMaxs = { 1.0f, 2.0f, 2.0f, 360.0f };
			float[] sliderValues = {
				editedEntry.getPostProcessing().getLightness(),
				editedEntry.getPostProcessing().getContrast(),
				editedEntry.getPostProcessing().getSaturation(),
				editedEntry.getPostProcessing().getHue()
			};
			for (int i = 0; i < postProcessingSliders.length; i++) {
				setSpinnerAndSlider(
					postProcessingSliders[i],
					postProcessingSpinners[i],
					sliderValueLabels[i],
					sliderValues[i],
					sliderMins[i],
					sliderMaxs[i]
				);
				if (postProcessingSpinners != null && postProcessingSpinners.length > i) {
					setSpinnerModel(postProcessingSpinners[i], sliderValues[i], sliderMins[i], sliderMaxs[i]);
				}
			}
			if (rgbSpinners != null) {
				float[] tint = editedEntry.getPostProcessing().tintColor != null ?
					editedEntry.getPostProcessing().tintColor : new float[] { 0f, 0f, 0f };
				for (int i = 0; i < rgbSpinners.length; i++) {
					setRgbSpinner(rgbSpinners[i], tint[i]);
				}
			}
		}
		hasChanges = false;
		isUpdatingUI = false;
	}

	public static rs117.hd.scene.skybox.SkyboxConfig.SkyboxEntry deepCopySkyboxEntry(rs117.hd.scene.skybox.SkyboxConfig.SkyboxEntry original) {
		if (original == null) return null;
		rs117.hd.scene.skybox.SkyboxConfig config = new rs117.hd.scene.skybox.SkyboxConfig();
		rs117.hd.scene.skybox.SkyboxConfig.SkyboxEntry copy = config.new SkyboxEntry();
		copy.setName(original.getName());
		copy.setDir(original.getDir());
		copy.setRotation(original.getRotation());
		copy.setRotationSpeed(original.getRotationSpeed());
		if (original.getPostProcessing() != null) {
			copy.setPostProcessing(deepCopyPostProcessing(original.getPostProcessing()));
		}
		return copy;
	}

	public static rs117.hd.scene.skybox.SkyboxConfig.SkyboxPostProcessingConfig deepCopyPostProcessing(rs117.hd.scene.skybox.SkyboxConfig.SkyboxPostProcessingConfig original) {
		if (original == null) return null;
		rs117.hd.scene.skybox.SkyboxConfig.SkyboxPostProcessingConfig copy = new rs117.hd.scene.skybox.SkyboxConfig.SkyboxPostProcessingConfig();
		copy.setSaturation(original.getSaturation());
		copy.setHue(original.getHue());
		copy.setLightness(original.getLightness());
		copy.setContrast(original.getContrast());
		if (original.tintColor != null) {
			copy.tintColor = original.tintColor.clone();
		} else {
			copy.tintColor = null;
		}
		return copy;
	}

	private void showSkyboxDetails(rs117.hd.scene.skybox.SkyboxConfig.SkyboxEntry entry) {
		infoPanel.removeAll();
		if (entry != null) {
			originalEntry = entry;
			editedEntry = deepCopySkyboxEntry(entry);
			JPanel outerPanel = new JPanel(new BorderLayout());
			JPanel formPanel = new JPanel();
			formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
			java.util.function.BiFunction<String, Component, JPanel> createRow = (labelText, component) -> {
				JPanel row = new JPanel(new BorderLayout(10, 0));
				JLabel label = new JLabel(labelText);
				label.setPreferredSize(new Dimension(120, 25));
				row.add(label, BorderLayout.WEST);
				row.add(component, BorderLayout.CENTER);
				return row;
			};

			nameField = new JTextField(editedEntry.getName(), 16);
			nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
				public void changedUpdate(javax.swing.event.DocumentEvent e) {
					if (!isUpdatingUI) {
						hasChanges = true;
						editedEntry.setName(nameField.getText());
						if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
					}
				}

				public void removeUpdate(javax.swing.event.DocumentEvent e) {
					if (!isUpdatingUI) {
						hasChanges = true;
						editedEntry.setName(nameField.getText());
						if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
					}
				}

				public void insertUpdate(javax.swing.event.DocumentEvent e) {
					if (!isUpdatingUI) {
						hasChanges = true;
						editedEntry.setName(nameField.getText());
						if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
					}
				}
			});

			dirCombo = new JComboBox<>(dirComboModel);
			dirCombo.setSelectedItem(editedEntry.getDir());
			dirCombo.addActionListener(e1 -> {
				if (!isUpdatingUI) {
					hasChanges = true;
					editedEntry.setDir((String) dirCombo.getSelectedItem());
					if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
				}
			});
			formPanel.add(createRow.apply("Dir:", dirCombo));
			formPanel.add(Box.createVerticalStrut(5));

			rotationSpinner = new JSpinner(new SpinnerNumberModel(Math.toDegrees(editedEntry.getRotation()), 0.0, 360.0, 0.1));
			rotationSpinner.setToolTipText("Degrees (0-360)");
			rotationSpinner.setInputVerifier(new InputVerifier() {
				@Override
				public boolean verify(JComponent input) {
					Object value = rotationSpinner.getValue();
					if (value instanceof Number) {
						double v = ((Number) value).doubleValue();
						return v >= 0.0 && v <= 360.0;
					}
					return false;
				}
			});
			rotationSpinner.addChangeListener(e1 -> {
				if (!isUpdatingUI) {
					hasChanges = true;
					editedEntry.setRotation((float) Math.toRadians(((Number) rotationSpinner.getValue()).doubleValue()));
					if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
				}
			});
			formPanel.add(createRow.apply("Rotation:", rotationSpinner));
			formPanel.add(Box.createVerticalStrut(5));

			rotationSpeedSpinner = new JSpinner(new SpinnerNumberModel(editedEntry.getRotationSpeed(), 0.0, 1.0, 0.01));
			rotationSpeedSpinner.setToolTipText("Speed (0.0 - 1.0)");
			rotationSpeedSpinner.setInputVerifier(new InputVerifier() {
				@Override
				public boolean verify(JComponent input) {
					Object value = rotationSpeedSpinner.getValue();
					if (value instanceof Number) {
						double v = ((Number) value).doubleValue();
						return v >= 0.0 && v <= 1.0;
					}
					return false;
				}
			});
			rotationSpeedSpinner.addChangeListener(e1 -> {
				if (!isUpdatingUI) {
					hasChanges = true;
					editedEntry.setRotationSpeed(((Number) rotationSpeedSpinner.getValue()).floatValue());
					if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
				}
			});
			formPanel.add(createRow.apply("Rotation Speed:", rotationSpeedSpinner));
			formPanel.add(Box.createVerticalStrut(10));

			JLabel postProcessingLabel = new JLabel("Post Processing", SwingConstants.CENTER);
			postProcessingLabel.setFont(postProcessingLabel.getFont().deriveFont(postProcessingLabel.getFont().getStyle() | Font.BOLD));
			postProcessingLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			formPanel.add(postProcessingLabel);
			formPanel.add(Box.createVerticalStrut(5));

			JSeparator separator = new JSeparator();
			formPanel.add(separator);
			formPanel.add(Box.createVerticalStrut(5));

			if (editedEntry.getPostProcessing() != null) {

				String[] sliderLabels = { "Brightness", "Contrast", "Saturation", "Hue" };
				// Updated min/max values
				float[] sliderMins = { -1.0f, 0.0f, 0.0f, 0.0f }; // brightness, contrast, saturation, hueShift
				float[] sliderMaxs = { 1.0f, 2.0f, 2.0f, 360.0f };
				float[] sliderValues = {
					Math.max(sliderMins[0], Math.min(sliderMaxs[0], editedEntry.getPostProcessing().getLightness())),
					Math.max(sliderMins[1], Math.min(sliderMaxs[1], editedEntry.getPostProcessing().getContrast())),
					Math.max(sliderMins[2], Math.min(sliderMaxs[2], editedEntry.getPostProcessing().getSaturation())),
					Math.max(sliderMins[3], Math.min(sliderMaxs[3], editedEntry.getPostProcessing().getHue()))
				};
				postProcessingSliders = new JSlider[4];
				sliderValueLabels = new JLabel[4];
				postProcessingSpinners = new JSpinner[4];
				for (int i = 0; i < sliderLabels.length; i++) {
					// Create slider row
					JPanel sliderRow = new JPanel(new BorderLayout(10, 0));
					JLabel label = new JLabel(sliderLabels[i] + ":");
					label.setPreferredSize(new Dimension(120, 25));
					sliderRow.add(label, BorderLayout.WEST);

					JPanel sliderPanel = new JPanel();
					sliderPanel.setLayout(new BoxLayout(sliderPanel, BoxLayout.X_AXIS));

					int sliderMin = 0;
					int sliderMax = (int) Math.round((sliderMaxs[i] - sliderMins[i]) * 100);
					int sliderInit = (int) Math.round(
						(Math.max(sliderMins[i], Math.min(sliderMaxs[i], sliderValues[i])) - sliderMins[i]) * 100);
					postProcessingSliders[i] = new JSlider(sliderMin, sliderMax, sliderInit);
					postProcessingSliders[i].setMajorTickSpacing((sliderMax - sliderMin) / 4);
					postProcessingSliders[i].setMinorTickSpacing((sliderMax - sliderMin) / 20);
					postProcessingSliders[i].setPaintTicks(true);
					postProcessingSliders[i].setPaintLabels(false);

					// Update tooltips for new ranges
					if (i == 3) {
						postProcessingSliders[i].setToolTipText("Adjust hue shift (0 - 360 degrees)");
					} else if (i == 0) {
						postProcessingSliders[i].setToolTipText("Adjust brightness (-1.0 - 1.0)");
					} else if (i == 1) {
						postProcessingSliders[i].setToolTipText("Adjust contrast (0.0 - 2.0)");
					} else if (i == 2) {
						postProcessingSliders[i].setToolTipText("Adjust saturation (0.0 - 2.0)");
					}

					sliderValueLabels[i] = new JLabel(String.format("%.2f", sliderValues[i]));
					sliderValueLabels[i].setPreferredSize(new Dimension(40, 24));

					// Update spinner models for new ranges
					postProcessingSpinners[i] = new JSpinner(new SpinnerNumberModel(
						sliderValues[i],
						sliderMins[i],
						sliderMaxs[i],
						0.01
					));
					postProcessingSpinners[i].setPreferredSize(new Dimension(60, 24));
					postProcessingSpinners[i].setMinimumSize(new Dimension(60, 24));
					postProcessingSpinners[i].setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

					final int sliderIndex = i;

					postProcessingSliders[i].addChangeListener(e1 -> {
						if (!isUpdatingUI) {
							float value = (postProcessingSliders[sliderIndex].getValue() / 100.0f) + sliderMins[sliderIndex];
							sliderValueLabels[sliderIndex].setText(String.format("%.2f", value));
							postProcessingSpinners[sliderIndex].setValue((double) value);
							hasChanges = true;
							if (editedEntry.getPostProcessing() != null) {
								switch (sliderIndex) {
									case 0:
										editedEntry.getPostProcessing().setLightness(value);
										break; // Brightness
									case 1:
										editedEntry.getPostProcessing().setContrast(value);
										break;  // Contrast
									case 2:
										editedEntry.getPostProcessing().setSaturation(value);
										break;// Saturation
									case 3:
										editedEntry.getPostProcessing().setHue(value);
										break;       // Hue
								}
								if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
							}
						}
					});

					postProcessingSpinners[i].addChangeListener(e1 -> {
						if (!isUpdatingUI) {
							double value = (Double) postProcessingSpinners[sliderIndex].getValue();
							sliderValueLabels[sliderIndex].setText(String.format("%.2f", value));
							postProcessingSliders[sliderIndex].setValue((int) Math.round((value - sliderMins[sliderIndex]) * 100));
							hasChanges = true;
							if (editedEntry.getPostProcessing() != null) {
								switch (sliderIndex) {
									case 0:
										editedEntry.getPostProcessing().setLightness((float) value);
										break; // Brightness
									case 1:
										editedEntry.getPostProcessing().setContrast((float) value);
										break;  // Contrast
									case 2:
										editedEntry.getPostProcessing().setSaturation((float) value);
										break;// Saturation
									case 3:
										editedEntry.getPostProcessing().setHue((float) value);
										break;       // Hue
								}
								if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
							}
						}
					});
					sliderPanel.add(postProcessingSliders[i]);
					sliderPanel.add(Box.createRigidArea(new Dimension(8, 0)));
					sliderPanel.add(sliderValueLabels[i]);
					sliderPanel.add(Box.createRigidArea(new Dimension(8, 0)));
					sliderPanel.add(postProcessingSpinners[i]);
					sliderRow.add(sliderPanel, BorderLayout.CENTER);
					formPanel.add(sliderRow);
					formPanel.add(Box.createVerticalStrut(5));
				}

				// Tint color spinners: clamp to [0.0, 1.0]
				JPanel tintRow = new JPanel(new BorderLayout(10, 0));
				JLabel tintLabel = new JLabel("Tint Color:");
				tintLabel.setPreferredSize(new Dimension(120, 25));
				tintRow.add(tintLabel, BorderLayout.WEST);

				JPanel rgbPanel = new JPanel();
				rgbPanel.setLayout(new BoxLayout(rgbPanel, BoxLayout.X_AXIS));
				float[] tint = editedEntry.getPostProcessing().tintColor != null ?
					editedEntry.getPostProcessing().tintColor :
					new float[] { 1f, 1f, 1f };
				String[] rgbLabels = { "R:", "G:", "B:" };
				rgbSpinners = new JSpinner[3];
				for (int i = 0; i < 3; i++) {
					final int rgbIdx = i;
					JPanel rgbGroup = new JPanel();
					rgbGroup.setLayout(new BoxLayout(rgbGroup, BoxLayout.X_AXIS));
					JLabel lbl = new JLabel(rgbLabels[i]);
					lbl.setPreferredSize(new Dimension(18, 24));
					lbl.setMaximumSize(new Dimension(18, 24));
					rgbGroup.add(lbl);

					rgbSpinners[i] = new JSpinner(new SpinnerNumberModel(Math.max(0.0, Math.min(1.0, tint[i])), 0.0, 1.0, 0.01));
					rgbSpinners[i].setPreferredSize(new Dimension(100, 24));
					rgbSpinners[i].setMinimumSize(new Dimension(100, 24));
					rgbSpinners[i].setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
					// Add tooltips for RGB spinners
					rgbSpinners[i].setToolTipText(rgbLabels[i].substring(0, 1) + " component (0.0 - 1.0)");
					rgbSpinners[i].addChangeListener(e1 -> {
						if (!isUpdatingUI) {
							hasChanges = true;
							if (editedEntry.getPostProcessing() != null && editedEntry.getPostProcessing().tintColor != null) {
								editedEntry.getPostProcessing().tintColor[rgbIdx] = ((Number) rgbSpinners[rgbIdx].getValue()).floatValue();
							}
							if (isEditingActiveSkybox()) environmentManager.currentSkybox = editedEntry;
						}
					});
					rgbGroup.add(rgbSpinners[i]);
					rgbPanel.add(rgbGroup);
					if (i < 2) {
						rgbPanel.add(Box.createRigidArea(new Dimension(10, 0)));
					}
				}
				tintRow.add(rgbPanel, BorderLayout.CENTER);
				formPanel.add(tintRow);
			} else {

				JButton createPostProcessingButton = new JButton("Create Post Processing");
				createPostProcessingButton.setToolTipText("Add post processing configuration to this skybox");
				JPanel buttonCenterPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
				buttonCenterPanel.add(createPostProcessingButton);
				formPanel.add(buttonCenterPanel);

				createPostProcessingButton.addActionListener(e2 -> {
					editedEntry.setPostProcessing(new rs117.hd.scene.skybox.SkyboxConfig.SkyboxPostProcessingConfig());
					showSkyboxDetails(editedEntry);
					hasChanges = true;
				});
			}
			updateUIFieldsFromEditedEntry();
			outerPanel.add(formPanel, BorderLayout.NORTH);

			JPanel buttonPanel = new JPanel();
			buttonPanel.setLayout(new GridLayout(1, 2, 10, 0));

			JButton saveButton = new JButton("Save");
			saveButton.setToolTipText("Save changes to this skybox");
			saveButton.addActionListener(e -> {
				try (OutputStream out = SkyboxManager.SKYBOX_PATH.toOutputStream()) {
					List<SkyboxConfig.SkyboxEntry> skyboxes = skyboxManager.getSkyboxConfig().skyboxes;

					int index = IntStream.range(0, skyboxes.size())
						.filter(i -> editedEntry.getName().equals(skyboxes.get(i).getName()))
						.findFirst()
						.orElse(-1);

					if (index != -1) {
						skyboxes.set(index, editedEntry);
					}

					String json = new GsonBuilder().setPrettyPrinting().create().toJson(skyboxManager.getSkyboxConfig());
					out.write(json.getBytes(StandardCharsets.UTF_8));
					hasChanges = false;
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			});
			buttonPanel.add(saveButton);

			resetButton = new JButton("Reset Values");
			resetButton.setToolTipText("Reset all values to their original state");
			resetButton.addActionListener(e1 -> {
				if (hasChanges) {
					int result = JOptionPane.showConfirmDialog(
						SkyboxEditorPanel.this,
						"You have unsaved changes. Do you want to reset all values to their original state?",
						"Reset Values",
						JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE
					);
					if (result == JOptionPane.YES_OPTION) {
						editedEntry = deepCopySkyboxEntry(originalEntry);
						if (isEditingActiveSkybox()) environmentManager.currentSkybox = originalEntry;
						updateUIFieldsFromEditedEntry();
					}
				} else {
					editedEntry = deepCopySkyboxEntry(originalEntry);
					if (isEditingActiveSkybox()) environmentManager.currentSkybox = originalEntry;
					updateUIFieldsFromEditedEntry();
				}
			});

			buttonPanel.add(resetButton);
			outerPanel.add(buttonPanel, BorderLayout.SOUTH);
			infoPanel.setLayout(new BorderLayout());
			infoPanel.add(outerPanel, BorderLayout.CENTER);
		}
		infoPanel.revalidate();
		infoPanel.repaint();
	}
} 
