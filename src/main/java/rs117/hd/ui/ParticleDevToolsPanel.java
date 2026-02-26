/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 */
package rs117.hd.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelListener;
import java.awt.GridLayout;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ListSelectionEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.ParticleGizmoOverlay;
import rs117.hd.scene.particles.ParticleDefinition;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.scene.particles.ParticleManager;

@Slf4j
public class ParticleDevToolsPanel extends PluginPanel {

	private static final String CARD_PLACEHOLDER = "placeholder";
	private static final String CARD_EDITOR = "editor";
	private static final int ANGLE_UNITS_PER_CIRCLE = 2048;
	private static final double RAD_TO_UNITS = ANGLE_UNITS_PER_CIRCLE / (2.0 * Math.PI);
	private static final double UNITS_TO_RAD = (2.0 * Math.PI) / ANGLE_UNITS_PER_CIRCLE;
	private static final int INPUT_HEIGHT = 22;

	private static void setSingleLineHeight(Component c) {
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, INPUT_HEIGHT));
	}

	private static void enableScrollWheel(JScrollPane scrollPane) {
		MouseWheelListener wheel = e -> {
			if (scrollPane.getVerticalScrollBar().isVisible()) {
				int delta = e.getUnitsToScroll() * Math.max(16, scrollPane.getVerticalScrollBar().getUnitIncrement());
				int newValue = scrollPane.getVerticalScrollBar().getValue() + delta;
				scrollPane.getVerticalScrollBar().setValue(newValue);
			}
		};
		scrollPane.addMouseWheelListener(wheel);
		scrollPane.getViewport().getView().addMouseWheelListener(wheel);
	}

	private final HdPlugin plugin;
	private final ParticleGizmoOverlay particleGizmoOverlay;
	private final JList<String> definitionList;
	private final DefaultListModel<String> definitionListModel;
	private final JPanel editorCardPanel;
	private final CardLayout editorCardLayout;

	// General
	private final JLabel idLabel;
	private final JTextArea descriptionField;
	private final JSpinner heightSpinner;
	private final JComboBox<String> textureCombo;
	private final JSpinner directionYawSpinner;
	private final JSpinner directionPitchSpinner;

	// Spread
	private final JSpinner spreadYawMinSpinner;
	private final JSpinner spreadYawMaxSpinner;
	private final JSpinner spreadPitchMinSpinner;
	private final JSpinner spreadPitchMaxSpinner;

	// Speed
	private final JSpinner minSpeedSpinner;
	private final JSpinner maxSpeedSpinner;
	private final JSpinner targetSpeedSpinner;
	private final JSpinner speedTransitionPctSpinner;

	// Scale
	private final JSpinner minScaleSpinner;
	private final JSpinner maxScaleSpinner;
	private final JSpinner targetScaleSpinner;
	private final JSpinner scaleTransitionSpinner;

	// Colors
	private final JButton minColourButton;
	private final JButton maxColourButton;
	private final JButton targetColourButton;
	private final JSpinner colorTransitionSpinner;
	private final JSpinner alphaTransitionSpinner;
	private final JCheckBox uniformColorVariationCheckBox;

	// Emission
	private final JSpinner minDelaySpinner;
	private final JSpinner maxDelaySpinner;
	private final JSpinner minSpawnSpinner;
	private final JSpinner maxSpawnSpinner;
	private final JSpinner initialSpawnSpinner;

	@Nullable
	private String selectedDefinitionId;
	private boolean loadingEditor;
	private boolean debugOverlaysEnabled;
	@Nullable
	private ParticleEmitter testEmitter;

	@Inject
	public ParticleDevToolsPanel(HdPlugin plugin, ParticleGizmoOverlay particleGizmoOverlay) {
		super(false);
		this.plugin = plugin;
		this.particleGizmoOverlay = particleGizmoOverlay;

		setLayout(new BorderLayout());

		DefaultListModel<String> listModel = new DefaultListModel<>();
		JList<String> list = new JList<>(listModel);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setVisibleRowCount(20);
		list.setCellRenderer((l, value, index, isSelected, cellHasFocus) -> {
			JLabel label = new JLabel();
			if (value != null) {
				ParticleManager pm = plugin.getParticleManager();
				ParticleDefinition def = pm != null ? pm.getDefinition(value) : null;
				String desc = def != null && def.description != null && !def.description.isEmpty() ? def.description : "";
				label.setText(desc.isEmpty() ? value : value + " – " + desc);
			} else {
				label.setText("(no config)");
			}
			if (isSelected) {
				label.setBackground(l.getSelectionBackground());
				label.setForeground(l.getSelectionForeground());
				label.setOpaque(true);
			}
			return label;
		});
		list.addListSelectionListener(this::onDefinitionSelected);

		JPopupMenu listPopup = new JPopupMenu();
		JMenuItem spawnItem = new JMenuItem("Spawn");
		spawnItem.addActionListener(e -> onSpawnDefinitionFromList());
		listPopup.add(spawnItem);
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent ev) {
				if (ev.isPopupTrigger())
					showListPopup(ev, list, listPopup);
			}
			@Override
			public void mouseReleased(MouseEvent ev) {
				if (ev.isPopupTrigger())
					showListPopup(ev, list, listPopup);
			}
		});

		final JScrollPane listScrollPane = new JScrollPane(list);
		listScrollPane.setPreferredSize(new Dimension(280, 500));
		enableScrollWheel(listScrollPane);

		JPanel placeholder = new JPanel(new BorderLayout());
		placeholder.add(new JLabel("Select a particle config from the list"), BorderLayout.CENTER);

		JPanel editorContent = new JPanel();
		editorContent.setLayout(new BoxLayout(editorContent, BoxLayout.Y_AXIS));

		// --- General ---
		JPanel generalSection = new JPanel(new GridLayout(0, 2, 4, 2));
		generalSection.setBorder(BorderFactory.createTitledBorder("General"));
		generalSection.add(new JLabel("ID:"));
		JLabel idLabel = new JLabel(" ");
		setSingleLineHeight(idLabel);
		generalSection.add(idLabel);
		generalSection.add(new JLabel("Description:"));
		JTextArea descriptionField = new JTextArea(1, 20);
		descriptionField.setLineWrap(true);
		setSingleLineHeight(descriptionField);
		generalSection.add(descriptionField);
		generalSection.add(new JLabel("Height:"));
		JSpinner height = new JSpinner(new SpinnerNumberModel(10, -500, 2000, 10));
		setSingleLineHeight(height);
		generalSection.add(height);
		generalSection.add(new JLabel("Direction Yaw:"));
		JSpinner directionYaw = new JSpinner(new SpinnerNumberModel(1248, 0, ANGLE_UNITS_PER_CIRCLE, 16));
		setSingleLineHeight(directionYaw);
		generalSection.add(directionYaw);
		generalSection.add(new JLabel("Direction Pitch:"));
		JSpinner directionPitch = new JSpinner(new SpinnerNumberModel(30, 0, ANGLE_UNITS_PER_CIRCLE, 2));
		setSingleLineHeight(directionPitch);
		generalSection.add(directionPitch);
		generalSection.add(new JLabel("Texture (textures/particles/):"));
		ParticleManager pmForTextures = plugin.getParticleManager();
		List<String> textureNames = pmForTextures != null ? pmForTextures.getAvailableTextureNames() : java.util.Collections.singletonList("");
		JComboBox<String> textureCombo = new JComboBox<>(textureNames.toArray(new String[0]));
		textureCombo.setEditable(true);
		setSingleLineHeight(textureCombo);
		generalSection.add(textureCombo);
		editorContent.add(generalSection);

		// --- Spread ---
		JPanel spreadSection = new JPanel(new GridLayout(0, 2, 4, 2));
		spreadSection.setBorder(BorderFactory.createTitledBorder("Spread (0–2048 units)"));
		spreadSection.add(new JLabel("Yaw Min:"));
		JSpinner spreadYawMin = new JSpinner(new SpinnerNumberModel(0, 0, ANGLE_UNITS_PER_CIRCLE, 1));
		setSingleLineHeight(spreadYawMin);
		spreadSection.add(spreadYawMin);
		spreadSection.add(new JLabel("Yaw Max:"));
		JSpinner spreadYawMax = new JSpinner(new SpinnerNumberModel(0, 0, ANGLE_UNITS_PER_CIRCLE, 1));
		setSingleLineHeight(spreadYawMax);
		spreadSection.add(spreadYawMax);
		spreadSection.add(new JLabel("Pitch Min:"));
		JSpinner spreadPitchMin = new JSpinner(new SpinnerNumberModel(0, 0, ANGLE_UNITS_PER_CIRCLE, 1));
		setSingleLineHeight(spreadPitchMin);
		spreadSection.add(spreadPitchMin);
		spreadSection.add(new JLabel("Pitch Max:"));
		JSpinner spreadPitchMax = new JSpinner(new SpinnerNumberModel(0, 0, ANGLE_UNITS_PER_CIRCLE, 1));
		setSingleLineHeight(spreadPitchMax);
		spreadSection.add(spreadPitchMax);
		editorContent.add(spreadSection);

		// --- Speed ---
		JPanel speedSection = new JPanel(new GridLayout(0, 2, 4, 2));
		speedSection.setBorder(BorderFactory.createTitledBorder("Speed"));
		speedSection.add(new JLabel("Min Speed:"));
		JSpinner minSpeed = new JSpinner(new SpinnerNumberModel(20.0, 0.0, 1000.0, 5.0));
		setSingleLineHeight(minSpeed);
		speedSection.add(minSpeed);
		speedSection.add(new JLabel("Max Speed:"));
		JSpinner maxSpeed = new JSpinner(new SpinnerNumberModel(60.0, 0.0, 1000.0, 5.0));
		setSingleLineHeight(maxSpeed);
		speedSection.add(maxSpeed);
		speedSection.add(new JLabel("Target Speed:"));
		JSpinner targetSpeed = new JSpinner(new SpinnerNumberModel(-1.0, -1.0, 1000.0, 5.0));
		setSingleLineHeight(targetSpeed);
		speedSection.add(targetSpeed);
		speedSection.add(new JLabel("Speed Transition %:"));
		JSpinner speedTransPct = new JSpinner(new SpinnerNumberModel(100.0, 0.0, 100.0, 5.0));
		setSingleLineHeight(speedTransPct);
		speedSection.add(speedTransPct);
		editorContent.add(speedSection);

		// --- Scale ---
		JPanel scaleSection = new JPanel(new GridLayout(0, 2, 4, 2));
		scaleSection.setBorder(BorderFactory.createTitledBorder("Scale"));
		scaleSection.add(new JLabel("Min Scale:"));
		JSpinner minScale = new JSpinner(new SpinnerNumberModel(2.0, 0.1, 100.0, 0.5));
		setSingleLineHeight(minScale);
		scaleSection.add(minScale);
		scaleSection.add(new JLabel("Max Scale:"));
		JSpinner maxScale = new JSpinner(new SpinnerNumberModel(6.0, 0.1, 100.0, 0.5));
		setSingleLineHeight(maxScale);
		scaleSection.add(maxScale);
		scaleSection.add(new JLabel("Target Scale:"));
		JSpinner targetScale = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100.0, 0.5));
		setSingleLineHeight(targetScale);
		scaleSection.add(targetScale);
		scaleSection.add(new JLabel("Scale Transition:"));
		JSpinner scaleTrans = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 20.0, 0.5));
		setSingleLineHeight(scaleTrans);
		scaleSection.add(scaleTrans);
		editorContent.add(scaleSection);

		// --- Colors ---
		JPanel colorsSection = new JPanel(new GridLayout(0, 2, 4, 2));
		colorsSection.setBorder(BorderFactory.createTitledBorder("Colors"));
		colorsSection.add(new JLabel("Min Colour:"));
		JButton minColourBtn = new JButton("   ");
		minColourBtn.setOpaque(true);
		minColourBtn.setBackground(Color.WHITE);
		minColourBtn.addActionListener(e -> chooseMinColour());
		setSingleLineHeight(minColourBtn);
		colorsSection.add(minColourBtn);
		colorsSection.add(new JLabel("Max Colour:"));
		JButton maxColourBtn = new JButton("   ");
		maxColourBtn.setOpaque(true);
		maxColourBtn.setBackground(Color.WHITE);
		maxColourBtn.addActionListener(e -> chooseMaxColour());
		setSingleLineHeight(maxColourBtn);
		colorsSection.add(maxColourBtn);
		colorsSection.add(new JLabel("Target Colour:"));
		JButton targetColourBtn = new JButton("   ");
		targetColourBtn.setOpaque(true);
		targetColourBtn.setBackground(Color.GRAY);
		targetColourBtn.setToolTipText("Optional end colour; gray = none");
		targetColourBtn.addActionListener(e -> chooseTargetColour());
		setSingleLineHeight(targetColourBtn);
		colorsSection.add(targetColourBtn);
		colorsSection.add(new JLabel("Color Transition:"));
		JSpinner colorTrans = new JSpinner(new SpinnerNumberModel(100.0, 0.0, 100.0, 5.0));
		setSingleLineHeight(colorTrans);
		colorsSection.add(colorTrans);
		colorsSection.add(new JLabel("Alpha Transition:"));
		JSpinner alphaTrans = new JSpinner(new SpinnerNumberModel(100.0, 0.0, 100.0, 5.0));
		setSingleLineHeight(alphaTrans);
		colorsSection.add(alphaTrans);
		colorsSection.add(new JLabel("Uniform color variation:"));
		JCheckBox uniformColor = new JCheckBox("", false);
		setSingleLineHeight(uniformColor);
		colorsSection.add(uniformColor);
		editorContent.add(colorsSection);

		// --- Emission ---
		JPanel emissionSection = new JPanel(new GridLayout(0, 2, 4, 2));
		emissionSection.setBorder(BorderFactory.createTitledBorder("Emission"));
		emissionSection.add(new JLabel("Min Delay:"));
		JSpinner minDelay = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 60.0, 0.1));
		setSingleLineHeight(minDelay);
		emissionSection.add(minDelay);
		emissionSection.add(new JLabel("Max Delay:"));
		JSpinner maxDelay = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 60.0, 0.1));
		setSingleLineHeight(maxDelay);
		emissionSection.add(maxDelay);
		emissionSection.add(new JLabel("Min Spawn:"));
		JSpinner minSpawn = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 1000.0, 1.0));
		setSingleLineHeight(minSpawn);
		emissionSection.add(minSpawn);
		emissionSection.add(new JLabel("Max Spawn:"));
		JSpinner maxSpawn = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 1000.0, 1.0));
		setSingleLineHeight(maxSpawn);
		emissionSection.add(maxSpawn);
		emissionSection.add(new JLabel("Initial Spawn:"));
		JSpinner initialSpawn = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1));
		setSingleLineHeight(initialSpawn);
		emissionSection.add(initialSpawn);
		editorContent.add(emissionSection);

		Runnable applyAll = () -> {
			if (!loadingEditor)
				applyAllToDefinition();
		};
		height.addChangeListener(ev -> applyAll.run());
		directionYaw.addChangeListener(ev -> applyAll.run());
		directionPitch.addChangeListener(ev -> applyAll.run());
		spreadYawMin.addChangeListener(ev -> applyAll.run());
		spreadYawMax.addChangeListener(ev -> applyAll.run());
		spreadPitchMin.addChangeListener(ev -> applyAll.run());
		spreadPitchMax.addChangeListener(ev -> applyAll.run());
		minSpeed.addChangeListener(ev -> applyAll.run());
		maxSpeed.addChangeListener(ev -> applyAll.run());
		targetSpeed.addChangeListener(ev -> applyAll.run());
		speedTransPct.addChangeListener(ev -> applyAll.run());
		minScale.addChangeListener(ev -> applyAll.run());
		maxScale.addChangeListener(ev -> applyAll.run());
		targetScale.addChangeListener(ev -> applyAll.run());
		scaleTrans.addChangeListener(ev -> applyAll.run());
		uniformColor.addItemListener(ev -> applyAll.run());
		colorTrans.addChangeListener(ev -> applyAll.run());
		alphaTrans.addChangeListener(ev -> applyAll.run());
		minDelay.addChangeListener(ev -> applyAll.run());
		maxDelay.addChangeListener(ev -> applyAll.run());
		minSpawn.addChangeListener(ev -> applyAll.run());
		maxSpawn.addChangeListener(ev -> applyAll.run());
		initialSpawn.addChangeListener(ev -> applyAll.run());

		JScrollPane editorScroll = new JScrollPane(editorContent);
		enableScrollWheel(editorScroll);
		CardLayout cardLayout = new CardLayout();
		JPanel cardPanel = new JPanel(cardLayout);
		cardPanel.add(placeholder, CARD_PLACEHOLDER);
		cardPanel.add(editorScroll, CARD_EDITOR);
		cardLayout.show(cardPanel, CARD_PLACEHOLDER);

		final JScrollPane editorScrollPane = new JScrollPane(cardPanel);
		editorScrollPane.setPreferredSize(new Dimension(400, 500));

		final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScrollPane, editorScrollPane);
		split.setResizeWeight(0.3);
		split.setDividerLocation(0.3);
		enableScrollWheel(editorScrollPane);
		add(split, BorderLayout.CENTER);

		final JPanel bottomPanel = new JPanel();
		add(bottomPanel, BorderLayout.SOUTH);
		final JButton refreshBtn = new JButton("Refresh");
		refreshBtn.addActionListener(e -> refreshDefinitionList());
		bottomPanel.add(refreshBtn);
		final JButton cycleBtn = new JButton("Cycle");
		cycleBtn.addActionListener(e -> onCycleClicked());
		bottomPanel.add(cycleBtn);
		final JButton debugOverlaysBtn = new JButton("Enable debug overlays");
		debugOverlaysBtn.addActionListener(e -> {
			debugOverlaysEnabled = !debugOverlaysEnabled;
			particleGizmoOverlay.setActive(debugOverlaysEnabled);
			debugOverlaysBtn.setText(debugOverlaysEnabled ? "Disable debug overlays" : "Enable debug overlays");
		});
		bottomPanel.add(debugOverlaysBtn);
		final JButton exportJsonBtn = new JButton("Export as JSON");
		exportJsonBtn.addActionListener(e -> onExportJsonClicked());
		bottomPanel.add(exportJsonBtn);

		this.definitionList = list;
		this.definitionListModel = listModel;
		this.editorCardPanel = cardPanel;
		this.editorCardLayout = cardLayout;
		this.idLabel = idLabel;
		this.descriptionField = descriptionField;
		this.heightSpinner = height;
		this.textureCombo = textureCombo;
		this.directionYawSpinner = directionYaw;
		this.directionPitchSpinner = directionPitch;
		this.spreadYawMinSpinner = spreadYawMin;
		this.spreadYawMaxSpinner = spreadYawMax;
		this.spreadPitchMinSpinner = spreadPitchMin;
		this.spreadPitchMaxSpinner = spreadPitchMax;
		this.minSpeedSpinner = minSpeed;
		this.maxSpeedSpinner = maxSpeed;
		this.targetSpeedSpinner = targetSpeed;
		this.speedTransitionPctSpinner = speedTransPct;
		this.minScaleSpinner = minScale;
		this.maxScaleSpinner = maxScale;
		this.targetScaleSpinner = targetScale;
		this.scaleTransitionSpinner = scaleTrans;
		this.minColourButton = minColourBtn;
		this.maxColourButton = maxColourBtn;
		this.targetColourButton = targetColourBtn;
		this.colorTransitionSpinner = colorTrans;
		this.alphaTransitionSpinner = alphaTrans;
		this.uniformColorVariationCheckBox = uniformColor;
		this.minDelaySpinner = minDelay;
		this.maxDelaySpinner = maxDelay;
		this.minSpawnSpinner = minSpawn;
		this.maxSpawnSpinner = maxSpawn;
		this.initialSpawnSpinner = initialSpawn;
	}

	@Override
	public void onActivate() {
		refreshDefinitionList();
		refreshTextureCombo();
	}

	private void refreshTextureCombo() {
		ParticleManager pm = plugin.getParticleManager();
		if (pm == null) return;
		Object current = textureCombo.getSelectedItem();
		List<String> names = pm.getAvailableTextureNames();
		textureCombo.removeAllItems();
		for (String name : names)
			textureCombo.addItem(name);
		if (current != null && names.contains(current.toString()))
			textureCombo.setSelectedItem(current);
	}

	private void refreshDefinitionList() {
		ParticleManager pm = plugin.getParticleManager();
		if (pm == null) return;
		definitionListModel.clear();
		for (String id : pm.getDefinitions().keySet()) {
			definitionListModel.addElement(id);
		}
	}

	private void onCycleClicked() {
		ParticleManager pm = plugin.getParticleManager();
		if (pm == null) return;
		List<String> idsOrdered = pm.getDefinitionIdsOrdered();
		if (idsOrdered.isEmpty()) return;
		net.runelite.api.Player player = plugin.client.getLocalPlayer();
		if (player == null) return;
		WorldPoint playerLoc = player.getWorldLocation();
		if (testEmitter == null) {
			testEmitter = pm.placeEmitter(playerLoc);
		} else {
			// Move test emitter to current player location so it stays visible
			testEmitter.at(playerLoc);
		}
		int currentIndex = -1;
		ParticleDefinition currentDef = testEmitter.getDefinition();
		if (currentDef != null && currentDef.id != null) {
			for (int i = 0; i < idsOrdered.size(); i++) {
				if (currentDef.id.equals(idsOrdered.get(i))) {
					currentIndex = i;
					break;
				}
			}
		}
		int nextIndex = (currentIndex + 1) % idsOrdered.size();
		String nextId = idsOrdered.get(nextIndex);
		ParticleDefinition nextDef = pm.getDefinition(nextId);
		if (nextDef == null) return;
		pm.applyDefinitionToEmitter(testEmitter, nextDef);
		selectedDefinitionId = nextId;
		definitionList.setSelectedValue(nextId, true);
		showEditorFor(nextDef);
	}

	private void showListPopup(MouseEvent ev, JList<String> list, JPopupMenu popup) {
		int index = list.locationToIndex(ev.getPoint());
		if (index >= 0 && index < list.getModel().getSize()) {
			list.setSelectedIndex(index);
			popup.show(list, ev.getX(), ev.getY());
		}
	}

	private void onSpawnDefinitionFromList() {
		String id = definitionList.getSelectedValue();
		if (id == null) return;
		ParticleManager pm = plugin.getParticleManager();
		if (pm == null) return;
		net.runelite.api.Player player = plugin.client.getLocalPlayer();
		if (player == null) return;
		WorldPoint playerLoc = player.getWorldLocation();
		if (pm.spawnEmitterFromDefinition(id, playerLoc) != null)
			log.debug("[Particles] Spawned emitter for definition {} at player", id);
	}

	private void onDefinitionSelected(ListSelectionEvent e) {
		if (e.getValueIsAdjusting()) return;
		String id = definitionList.getSelectedValue();
		selectedDefinitionId = id;
		if (id == null) {
			showPlaceholderEditor();
			return;
		}
		ParticleDefinition def = plugin.getParticleManager().getDefinition(id);
		if (def == null) {
			showPlaceholderEditor();
			return;
		}
		showEditorFor(def);
	}

	private void showPlaceholderEditor() {
		editorCardLayout.show(editorCardPanel, CARD_PLACEHOLDER);
	}

	private void showEditorFor(ParticleDefinition def) {
		loadingEditor = true;
		try {
		idLabel.setText(def.id != null ? def.id : "");
		descriptionField.setText(def.description != null ? def.description : "");
		heightSpinner.setValue(def.general.heightOffset);
		directionYawSpinner.setValue(def.general.directionYaw);
		directionPitchSpinner.setValue(def.general.directionPitch);
		spreadYawMinSpinner.setValue(def.spread.yawMin);
		spreadYawMaxSpinner.setValue(def.spread.yawMax);
		spreadPitchMinSpinner.setValue(def.spread.pitchMin);
		spreadPitchMaxSpinner.setValue(def.spread.pitchMax);
		minSpeedSpinner.setValue(def.speed.minSpeed);
		maxSpeedSpinner.setValue(def.speed.maxSpeed);
		targetSpeedSpinner.setValue(def.speed.targetSpeed >= 0 ? def.speed.targetSpeed : -1);
		speedTransitionPctSpinner.setValue(def.speed.speedTransitionPercent);
		minScaleSpinner.setValue(def.scale.minScale);
		maxScaleSpinner.setValue(def.scale.maxScale);
		targetScaleSpinner.setValue(def.scale.targetScale >= 0 ? def.scale.targetScale : -1);
		scaleTransitionSpinner.setValue(def.scale.scaleTransitionPercent);
		minColourButton.setBackground(toColor(ParticleDefinition.argbToFloat(def.colours.minColourArgb)));
		maxColourButton.setBackground(toColor(ParticleDefinition.argbToFloat(def.colours.maxColourArgb)));
		if (def.colours.targetColourArgb != 0) {
			targetColourButton.setBackground(toColor(ParticleDefinition.argbToFloat(def.colours.targetColourArgb)));
		} else {
			targetColourButton.setBackground(Color.GRAY);
		}
		colorTransitionSpinner.setValue(def.colours.colourTransitionPercent);
		alphaTransitionSpinner.setValue(def.colours.alphaTransitionPercent);
		uniformColorVariationCheckBox.setSelected(def.colours.uniformColourVariation);
		minDelaySpinner.setValue(def.emission.minDelay);
		maxDelaySpinner.setValue(def.emission.maxDelay);
		minSpawnSpinner.setValue(def.emission.minSpawn);
		maxSpawnSpinner.setValue(def.emission.maxSpawn);
		initialSpawnSpinner.setValue(def.emission.initialSpawn);
		String tex = def.texture.file;
		textureCombo.setSelectedItem(tex != null && !tex.isEmpty() ? tex : "");
		} finally {
			loadingEditor = false;
		}
		editorCardLayout.show(editorCardPanel, CARD_EDITOR);
	}

	private static Color toColor(float[] rgba) {
		if (rgba == null || rgba.length < 4) return Color.GRAY;
		return new Color(
			(float) Math.max(0, Math.min(1, rgba[0])),
			(float) Math.max(0, Math.min(1, rgba[1])),
			(float) Math.max(0, Math.min(1, rgba[2])),
			(float) Math.max(0, Math.min(1, rgba[3]))
		);
	}

	private static int fromColorToArgb(Color c) {
		return (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
	}

	private void chooseMinColour() {
		ParticleDefinition def = getSelectedDefinition();
		if (def == null) return;
		Color chosen = JColorChooser.showDialog(this, "Min Colour", minColourButton.getBackground());
		if (chosen == null) return;
		def.colours.minColourArgb = fromColorToArgb(chosen);
		minColourButton.setBackground(chosen);
		plugin.getParticleManager().recreateEmittersFromPlacements(null);
	}

	private void chooseMaxColour() {
		ParticleDefinition def = getSelectedDefinition();
		if (def == null) return;
		Color chosen = JColorChooser.showDialog(this, "Max Colour", maxColourButton.getBackground());
		if (chosen == null) return;
		def.colours.maxColourArgb = fromColorToArgb(chosen);
		maxColourButton.setBackground(chosen);
		plugin.getParticleManager().recreateEmittersFromPlacements(null);
	}

	private void chooseTargetColour() {
		ParticleDefinition def = getSelectedDefinition();
		if (def == null) return;
		Color current = targetColourButton.getBackground() instanceof Color ? (Color) targetColourButton.getBackground() : Color.GRAY;
		Color chosen = JColorChooser.showDialog(this, "Target Colour", current);
		if (chosen == null) return;
		def.colours.targetColourArgb = fromColorToArgb(chosen);
		def.colours.colourTransitionPercent = ((Number) colorTransitionSpinner.getValue()).intValue();
		def.colours.alphaTransitionPercent = ((Number) alphaTransitionSpinner.getValue()).intValue();
		targetColourButton.setBackground(chosen);
		plugin.getParticleManager().recreateEmittersFromPlacements(null);
	}

	@Nullable
	private ParticleDefinition getSelectedDefinition() {
		if (selectedDefinitionId == null) return null;
		ParticleManager pm = plugin.getParticleManager();
		return pm != null ? pm.getDefinition(selectedDefinitionId) : null;
	}

	private void onExportJsonClicked() {
		ParticleDefinition def = getSelectedDefinition();
		if (def == null) {
			JOptionPane.showMessageDialog(this, "Select a particle config from the list first.", "Export", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		applyAllToDefinition();
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", def.id);
		if (def.description != null && !def.description.isEmpty()) map.put("description", def.description);
		map.put("heightOffset", def.general.heightOffset);
		map.put("directionYaw", def.general.directionYaw);
		map.put("directionPitch", def.general.directionPitch);
		map.put("spreadYawMin", def.spread.yawMin);
		map.put("spreadYawMax", def.spread.yawMax);
		map.put("spreadPitchMin", def.spread.pitchMin);
		map.put("spreadPitchMax", def.spread.pitchMax);
		map.put("minSpeed", def.speed.minSpeed);
		map.put("maxSpeed", def.speed.maxSpeed);
		map.put("targetSpeed", def.speed.targetSpeed >= 0 ? def.speed.targetSpeed : -1);
		map.put("speedTransitionPercent", def.speed.speedTransitionPercent);
		map.put("minScale", def.scale.minScale);
		map.put("maxScale", def.scale.maxScale);
		map.put("targetScale", def.scale.targetScale >= 0 ? def.scale.targetScale : -1);
		map.put("scaleTransitionPercent", def.scale.scaleTransitionPercent);
		map.put("minColour", ParticleDefinition.argbToHex(def.colours.minColourArgb));
		map.put("maxColour", ParticleDefinition.argbToHex(def.colours.maxColourArgb));
		map.put("targetColour", def.colours.targetColourArgb != 0 ? ParticleDefinition.argbToHex(def.colours.targetColourArgb) : null);
		map.put("colourTransitionPercent", def.colours.colourTransitionPercent);
		map.put("alphaTransitionPercent", def.colours.alphaTransitionPercent);
		map.put("uniformColourVariation", def.colours.uniformColourVariation);
		map.put("minEmissionDelay", def.emission.minDelay);
		map.put("maxEmissionDelay", def.emission.maxDelay);
		map.put("minSpawnCount", def.emission.minSpawn);
		map.put("maxSpawnCount", def.emission.maxSpawn);
		map.put("initialSpawnCount", def.emission.initialSpawn);
		Object texObj = textureCombo.getSelectedItem();
		String texture = texObj != null ? texObj.toString().trim() : "";
		if (!texture.isEmpty()) map.put("texture", texture);
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		String json = gson.toJson(map);
		JDialog dialog = new JDialog((java.awt.Frame) null, "Export as JSON", true);
		JPanel content = new JPanel(new BorderLayout(4, 4));
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JTextArea area = new JTextArea(json, 18, 50);
		area.setEditable(true);
		area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		content.add(new JScrollPane(area), BorderLayout.CENTER);
		JPanel buttons = new JPanel();
		JButton copyBtn = new JButton("Copy to clipboard");
		copyBtn.addActionListener(e2 -> {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(new StringSelection(area.getText()), null);
		});
		JButton closeBtn = new JButton("Close");
		closeBtn.addActionListener(e2 -> dialog.dispose());
		buttons.add(copyBtn);
		buttons.add(closeBtn);
		content.add(buttons, BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private void applyAllToDefinition() {
		ParticleDefinition def = getSelectedDefinition();
		if (def == null) return;
		String desc = descriptionField.getText().trim();
		def.description = desc.isEmpty() ? null : desc;
		def.general.heightOffset = ((Number) heightSpinner.getValue()).intValue();
		def.general.directionYaw = ((Number) directionYawSpinner.getValue()).intValue();
		def.general.directionPitch = ((Number) directionPitchSpinner.getValue()).intValue();
		def.spread.yawMin = ((Number) spreadYawMinSpinner.getValue()).intValue();
		def.spread.yawMax = ((Number) spreadYawMaxSpinner.getValue()).intValue();
		def.spread.pitchMin = ((Number) spreadPitchMinSpinner.getValue()).intValue();
		def.spread.pitchMax = ((Number) spreadPitchMaxSpinner.getValue()).intValue();
		def.speed.minSpeed = ((Number) minSpeedSpinner.getValue()).intValue();
		def.speed.maxSpeed = ((Number) maxSpeedSpinner.getValue()).intValue();
		float ts = ((Number) targetSpeedSpinner.getValue()).floatValue();
		def.speed.targetSpeed = ts < 0f ? -1 : (int) ts;
		def.speed.speedTransitionPercent = ((Number) speedTransitionPctSpinner.getValue()).intValue();
		def.scale.minScale = ((Number) minScaleSpinner.getValue()).intValue();
		def.scale.maxScale = ((Number) maxScaleSpinner.getValue()).intValue();
		float tsc = ((Number) targetScaleSpinner.getValue()).floatValue();
		def.scale.targetScale = tsc < 0f ? -1 : (int) tsc;
		def.scale.scaleTransitionPercent = ((Number) scaleTransitionSpinner.getValue()).intValue();
		Color minC = minColourButton.getBackground() instanceof Color ? (Color) minColourButton.getBackground() : Color.WHITE;
		Color maxC = maxColourButton.getBackground() instanceof Color ? (Color) maxColourButton.getBackground() : Color.WHITE;
		def.colours.minColourArgb = fromColorToArgb(minC);
		def.colours.maxColourArgb = fromColorToArgb(maxC);
		Color tgt = targetColourButton.getBackground() instanceof Color ? (Color) targetColourButton.getBackground() : null;
		def.colours.targetColourArgb = (tgt != null && tgt != Color.GRAY) ? fromColorToArgb(tgt) : 0;
		def.colours.colourTransitionPercent = ((Number) colorTransitionSpinner.getValue()).intValue();
		def.colours.alphaTransitionPercent = ((Number) alphaTransitionSpinner.getValue()).intValue();
		def.colours.uniformColourVariation = uniformColorVariationCheckBox.isSelected();
		def.emission.minDelay = ((Number) minDelaySpinner.getValue()).intValue();
		def.emission.maxDelay = ((Number) maxDelaySpinner.getValue()).intValue();
		def.emission.minSpawn = ((Number) minSpawnSpinner.getValue()).intValue();
		def.emission.maxSpawn = ((Number) maxSpawnSpinner.getValue()).intValue();
		def.emission.initialSpawn = ((Number) initialSpawnSpinner.getValue()).intValue();
		Object texObj = textureCombo.getSelectedItem();
		def.texture.file = (texObj != null && !texObj.toString().trim().isEmpty()) ? texObj.toString().trim() : null;
		def.postDecode();
		plugin.getParticleManager().recreateEmittersFromPlacements(null);
	}
}
