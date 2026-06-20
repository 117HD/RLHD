package rs117.hd.utils.devtools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.JsonAdapter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import rs117.hd.HdPlugin;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class WaterTypeDevEditor {
	private static final ResourcePath WATER_TYPES_PATH = Props
		.getFile("rlhd.water-types-path", () -> path(WaterTypeManager.class, "water_types.json"));

	private static final ResourcePath TEXTURE_PATH = Props
		.getFolder("rlhd.texture-path", () -> path(TextureManager.class, "textures"));

	private static final float FLOAT_EPS = 1e-4f;

	private static final List<Field> WATER_TYPE_FIELDS;
	private static final Map<String, Field> WATER_TYPE_FIELD_BY_NAME;
	private static final String[] ALL_PROPERTY_KEYS;

	static {
		List<Field> fields = new ArrayList<>();
		Map<String, Field> byName = new LinkedHashMap<>();
		for (Field field : WaterType.class.getDeclaredFields()) {
			int mod = field.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isTransient(mod))
				continue;
			field.setAccessible(true);
			fields.add(field);
			byName.put(field.getName(), field);
		}
		WATER_TYPE_FIELDS = Collections.unmodifiableList(fields);
		WATER_TYPE_FIELD_BY_NAME = Collections.unmodifiableMap(byName);
		ALL_PROPERTY_KEYS = WATER_TYPE_FIELDS.stream().map(Field::getName).toArray(String[]::new);
	}

	private static final class SliderConfig {
		final float min;
		final float max;
		final float scale;
		final String format;

		SliderConfig(float min, float max, float scale, String format) {
			this.min = min;
			this.max = max;
			this.scale = scale;
			this.format = format;
		}

		static SliderConfig forInt(String fieldName) {
			if ("vanillaTextureIndex".equals(fieldName))
				return new SliderConfig(-1, 255, 1, "%d");
			if ("fishingSpotRecolor".equals(fieldName))
				return new SliderConfig(-1, 65535, 1, "%d");
			if ("depth".equals(fieldName))
				return new SliderConfig(0, 4095, 1, "%d");
			return new SliderConfig(-9999, 9999, 1, "%d");
		}

		static SliderConfig forFloat(String fieldName) {
			if ("specularStrength".equals(fieldName)
				|| "normalStrength".equals(fieldName)
				|| "baseOpacity".equals(fieldName)
				|| "fresnelAmount".equals(fieldName)
				|| "scatteringAnisotropy".equals(fieldName)
			) {
				return new SliderConfig(0, 1, 1, "%.3f");
			}
			if ("specularGloss".equals(fieldName))
				return new SliderConfig(0, 500, 1, "%.0f");
			if ("duration".equals(fieldName))
				return new SliderConfig(0, 10, 10, "%.1f");
			if ("waveHeight".equals(fieldName))
				return new SliderConfig(0, 10, 1, "%.3f");
			if ("waveSpeed".equals(fieldName))
				return new SliderConfig(0, 0.05f, 1, "%.3f");
			return new SliderConfig(0, 1000, 100, "%.3f");
		}
	}

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private ColorPickerManager colorPickerManager;

	@Inject
	private ConfigManager configManager;

	private JFrame frame;
	private final Map<String, RuneliteColorPicker> waterColorPickers = new LinkedHashMap<>();
	private JList<WaterType> waterTypeList;
	private JPanel fieldsPanel;
	private JLabel statusLabel;
	private WaterType selected;
	private String selectedName;
	private boolean suppressEvents;
	private List<String> textureNames;
	private Timer saveDebounce;
	private final LinkedHashMap<String, WaterType> editDrafts = new LinkedHashMap<>();

	public void toggle() {
		if (!Props.DEVELOPMENT)
			return;

		SwingUtilities.invokeLater(() -> {
			if (frame == null)
				buildFrame();

			if (frame.isVisible()) {
				frame.setVisible(false);
			} else {
				textureNames = null;
				resetEditDrafts();
				refreshWaterTypeList();
				frame.setVisible(true);
				frame.toFront();
			}
		});
	}

	private void buildFrame() {
		frame = new JFrame("Water Type Dev Editor (temp)");
		frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		frame.setMinimumSize(new Dimension(920, 580));

		waterTypeList = new JList<>();
		waterTypeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		waterTypeList.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(
				JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
			) {
				var label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof WaterType) {
					WaterType type = (WaterType) value;
					label.setText(type.name + " [" + type.index + "]");
				}
				return label;
			}
		});
		waterTypeList.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting() || suppressEvents)
				return;
			var type = waterTypeList.getSelectedValue();
			if (type != null)
				populateFields(type);
		});

		fieldsPanel = new JPanel(new GridBagLayout());
		var fieldsScroll = new JScrollPane(fieldsPanel);
		fieldsScroll.setBorder(BorderFactory.createTitledBorder("Properties"));

		var split = new JPanel(new BorderLayout(8, 8));
		var listScroll = new JScrollPane(waterTypeList);
		listScroll.setPreferredSize(new Dimension(220, 0));
		listScroll.setBorder(BorderFactory.createTitledBorder("Water Types"));
		split.add(listScroll, BorderLayout.WEST);
		split.add(fieldsScroll, BorderLayout.CENTER);

		statusLabel = new JLabel(" ");
		statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

		var saveButton = new JButton("Save JSON");
		saveButton.addActionListener(e -> saveJsonQuiet());

		var closeButton = new JButton("Close");
		closeButton.addActionListener(e -> frame.setVisible(false));

		var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(statusLabel);
		buttonPanel.add(saveButton);
		buttonPanel.add(closeButton);

		var root = new JPanel(new BorderLayout(8, 8));
		root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		root.add(split, BorderLayout.CENTER);
		root.add(buttonPanel, BorderLayout.SOUTH);

		frame.setContentPane(root);
		frame.pack();
		frame.setLocationRelativeTo(client.getCanvas());
		frame.setAlwaysOnTop(true);
		frame.requestFocus();
	}

	private void refreshWaterTypeList() {
		var types = new ArrayList<WaterType>();
		for (var type : WaterTypeManager.WATER_TYPES)
			if (type != WaterType.NONE)
				types.add(type);

		String reselect = selectedName;
		if (reselect == null && selected != null)
			reselect = selected.name;

		suppressEvents = true;
		waterTypeList.setListData(types.toArray(WaterType[]::new));

		WaterType toSelect = null;
		if (reselect != null) {
			for (var type : types) {
				if (reselect.equals(type.name)) {
					toSelect = type;
					break;
				}
			}
		}
		if (toSelect == null && !types.isEmpty())
			toSelect = types.get(0);

		if (toSelect != null) {
			waterTypeList.setSelectedValue(toSelect, true);
			populateFields(toSelect);
		} else {
			fieldsPanel.removeAll();
			fieldsPanel.revalidate();
			fieldsPanel.repaint();
		}
		suppressEvents = false;
	}

	private void resetEditDrafts() {
		editDrafts.clear();
		for (var type : WaterTypeManager.WATER_TYPES) {
			if (type != WaterType.NONE)
				editDrafts.put(type.name, cloneWaterType(type));
		}
	}

	private static WaterType cloneWaterType(WaterType src) {
		var copy = new WaterType();
		try {
			for (var field : WATER_TYPE_FIELDS) {
				Object value = field.get(src);
				if (value instanceof float[]) {
					value = cloneFloat3((float[]) value);
				}
				field.set(copy, value);
			}
			
		} catch (IllegalAccessException ex) {
			throw new RuntimeException("Failed to clone WaterType", ex);
		}
		return copy;
	}

	private static float[] cloneFloat3(float[] color) {
		return color == null ? null : color.clone();
	}

	private WaterType draftFor(WaterType managerType) {
		return editDrafts.computeIfAbsent(managerType.name, n -> cloneWaterType(managerType));
	}

	private void populateFields(WaterType managerType) {
		closeWaterColorsPicker();
		WaterType type = draftFor(managerType);
		selected = type;
		selectedName = type.name;
		fieldsPanel.removeAll();

		var gbc = newGridConstraints();

		addReadOnlyRow(gbc, "name", type.name);
		addReadOnlyRow(gbc, "index", String.valueOf(type.index));

		for (var field : WATER_TYPE_FIELDS)
			addFieldRow(gbc, type, field);

		gbc.gridx = 0;
		gbc.gridy++;
		gbc.gridwidth = 4;
		gbc.weighty = 1;
		fieldsPanel.add(new JPanel(), gbc);

		fieldsPanel.revalidate();
		fieldsPanel.repaint();
	}

	private static boolean isColorField(Field field) {
		JsonAdapter adapter = field.getAnnotation(JsonAdapter.class);
		return adapter != null && adapter.value() == ColorUtils.SrgbToLinearAdapter.class;
	}

	private void addFieldRow(GridBagConstraints gbc, WaterType type, Field field) {
		String label = field.getName();
		if ("name".equals(label))
			return;

		try {
			Class<?> fieldType = field.getType();
			if (fieldType == boolean.class) {
				addBoolRow(gbc, label, field.getBoolean(type), v -> updateField(field, type, v));
			} else if (fieldType == int.class) {
				SliderConfig cfg = SliderConfig.forInt(label);
				addIntSliderRow(
					gbc, label, field.getInt(type), (int) cfg.min, (int) cfg.max,
					v -> updateField(field, type, v)
				);
			} else if (fieldType == float.class) {
				SliderConfig cfg = SliderConfig.forFloat(label);
				addFloatSliderRow(
					gbc, label, field.getFloat(type), cfg.min, cfg.max, cfg.scale, cfg.format,
					v -> updateField(field, type, v)
				);
			} else if (fieldType == float[].class && isColorField(field)) {
				float[] linear = (float[]) field.get(type);
				if (linear == null)
					linear = new float[] { 1, 1, 1 };
				addColorRow(gbc, label, linear, c -> updateField(field, type, cloneFloat3(c)));
			} else if (Material.class.isAssignableFrom(fieldType)) {
				addNormalMapRow(
					gbc,
					getNormalMapTextureName((Material) field.get(type)),
					m -> updateField(field, type, m)
				);
			} else {
				log.warn("No editor control for WaterType.{} ({})", label, fieldType.getSimpleName());
			}
		} catch (IllegalAccessException ex) {
			log.error("Unable to bind WaterType.{}", label, ex);
		}
	}

	private void updateField(Field field, WaterType type, Object value) {
		try {
			field.set(type, value);
			applyChanges();
		} catch (IllegalAccessException ex) {
			throw new RuntimeException("Failed to update WaterType." + field.getName(), ex);
		}
	}

	private static GridBagConstraints newGridConstraints() {
		var gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(2, 4, 2, 4);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.weightx = 0;
		return gbc;
	}

	private void addReadOnlyRow(GridBagConstraints gbc, String label, String value) {
		gbc.gridx = 0;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(new JLabel(label + ":"), gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 3;
		gbc.weightx = 1;
		var field = new JTextField(value);
		field.setEditable(false);
		fieldsPanel.add(field, gbc);

		gbc.gridy++;
	}

	private void addIntSliderRow(
		GridBagConstraints gbc,
		String label,
		int value,
		int min,
		int max,
		IntConsumer setter
	) {
		gbc.gridx = 0;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(new JLabel(label + ":"), gbc);

		int clamped = Math.max(min, Math.min(max, value));
		var slider = new JSlider(min, max, clamped);
		var input = new JTextField(String.valueOf(clamped), 8);

		Runnable apply = () -> {
			if (suppressEvents || selected == null)
				return;
			int v = slider.getValue();
			suppressEvents = true;
			input.setText(String.valueOf(v));
			suppressEvents = false;
			setter.accept(v);
			applyChanges();
		};

		slider.addChangeListener(e -> apply.run());

		input.getDocument().addDocumentListener(simpleDocumentListener(() -> {
			if (suppressEvents || selected == null)
				return;
			try {
				int v = Integer.parseInt(input.getText().trim());
				v = Math.max(min, Math.min(max, v));
				suppressEvents = true;
				slider.setValue(v);
				suppressEvents = false;
				setter.accept(v);
				applyChanges();
			} catch (NumberFormatException ignored) {}
		}));

		gbc.gridx = 1;
		gbc.gridwidth = 1;
		gbc.weightx = 1;
		fieldsPanel.add(slider, gbc);

		gbc.gridx = 2;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(input, gbc);
		gbc.gridy++;
	}

	private void addFloatSliderRow(
		GridBagConstraints gbc,
		String label,
		float value,
		float min,
		float max,
		float scale,
		String format,
		Consumer<Float> setter
	) {
		gbc.gridx = 0;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(new JLabel(label + ":"), gbc);

		Slider slider = new Slider(value, min, max, false);
		slider.addUpdateListener(newValue -> {
			if (suppressEvents || selected == null)
				return;
			float v = slider.getValue() / scale;
			setter.accept(v);
			applyChanges();
		});

		gbc.gridx = 1;
		gbc.gridwidth = 1;
		gbc.weightx = 1;
		fieldsPanel.add(slider, gbc);

		gbc.gridx = 2;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(slider.getInputTextField(), gbc);
		gbc.gridy++;
	}

	private void addBoolRow(GridBagConstraints gbc, String label, boolean value, Consumer<Boolean> setter) {
		gbc.gridx = 0;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(new JLabel(label + ":"), gbc);

		var checkbox = new JCheckBox("", value);
		checkbox.addItemListener(e -> {
			if (suppressEvents || selected == null)
				return;
			setter.accept(checkbox.isSelected());
			applyChanges();
		});

		gbc.gridx = 1;
		gbc.gridwidth = 3;
		gbc.weightx = 1;
		fieldsPanel.add(checkbox, gbc);
		gbc.gridy++;
	}

	private static final class ColorField {
		final String label;
		final JTextField input;
		final JPanel swatch;
		final Supplier<float[]> getter;
		final Consumer<float[]> setter;

		ColorField(
			String label,
			JTextField input,
			JPanel swatch,
			Supplier<float[]> getter,
			Consumer<float[]> setter
		) {
			this.label = label;
			this.input = input;
			this.swatch = swatch;
			this.getter = getter;
			this.setter = setter;
		}
	}

	private ColorField addColorRow(
		GridBagConstraints gbc,
		String label,
		float[] linearColor,
		Consumer<float[]> setter
	) {
		gbc.gridx = 0;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(new JLabel(label + ":"), gbc);

		var swatch = new JPanel();
		swatch.setPreferredSize(new Dimension(28, 28));
		swatch.setMinimumSize(new Dimension(28, 28));
		swatch.setBorder(BorderFactory.createLineBorder(Color.GRAY));
		updateSwatch(swatch, linearColor);

		var input = new JTextField(ColorUtils.rgbToHex(linearColor), 10);
		Runnable updateFromHex = () -> {
			if (suppressEvents || selected == null)
				return;
			try {
				float[] color = ColorUtils.rgb(input.getText().trim());
				updateSwatch(swatch, color);
				setter.accept(color);
				applyChanges();
			} catch (Exception ignored) {}
		};

		input.getDocument().addDocumentListener(simpleDocumentListener(updateFromHex));

		gbc.gridx = 1;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(swatch, gbc);

		var colorField = new ColorField(label, input, swatch, () -> {
			try {
				return ColorUtils.rgb(input.getText().trim());
			} catch (Exception ex) {
				return linearColor;
			}
		}, setter);

		var pickButton = new JButton("Pick");
		pickButton.addActionListener(e -> openColorPicker(colorField));

		gbc.gridx = 2;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(input, gbc);

		gbc.gridx = 3;
		gbc.weightx = 0;
		fieldsPanel.add(pickButton, gbc);
		gbc.gridy++;

		return colorField;
	}

	private void closeWaterColorsPicker() {
		for (var picker : waterColorPickers.values())
			picker.dispose();
		waterColorPickers.clear();
	}

	private void openColorPicker(ColorField field) {
		if (selected == null)
			return;

		var existing = waterColorPickers.get(field.label);
		if (existing != null) {
			existing.toFront();
			return;
		}

		var picker = createRuneliteColorPicker(linearToAwt(field.getter.get()), field.label);
		picker.setLocationRelativeTo(frame);
		picker.setOnColorChange(c -> SwingUtilities.invokeLater(() -> applyPickerColor(field, c)));
		picker.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				waterColorPickers.remove(field.label, picker);
			}
		});
		waterColorPickers.put(field.label, picker);
		picker.setVisible(true);
	}

	private RuneliteColorPicker createRuneliteColorPicker(Color initial, String title) {
		try {
			Constructor<RuneliteColorPicker> ctor = RuneliteColorPicker.class.getDeclaredConstructor(
				Window.class,
				Color.class,
				String.class,
				boolean.class,
				ConfigManager.class,
				ColorPickerManager.class
			);
			ctor.setAccessible(true);
			var picker = ctor.newInstance(frame, initial, title, false, configManager, colorPickerManager);
			if (picker.isAlwaysOnTopSupported())
				picker.setAlwaysOnTop(frame.isAlwaysOnTop());
			return picker;
		} catch (ReflectiveOperationException ex) {
			log.error("Failed to create RuneliteColorPicker", ex);
			throw new RuntimeException(ex);
		}
	}

	private void applyPickerColor(ColorField field, Color awt) {
		if (suppressEvents || selected == null)
			return;

		float[] linear = ColorUtils.rgb(awt);
		suppressEvents = true;
		field.input.setText(ColorUtils.rgbToHex(linear));
		updateSwatch(field.swatch, linear);
		suppressEvents = false;
		field.setter.accept(linear);
		applyChanges();
	}

	private void addNormalMapRow(GridBagConstraints gbc, String currentTexture, Consumer<Material> setter) {
		gbc.gridx = 0;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		fieldsPanel.add(new JLabel("normalMap:"), gbc);

		var options = new ArrayList<String>();
		options.add("");
		options.addAll(getTextureNames());

		var combo = new JComboBox<>(options.toArray(new String[0]));
		if (currentTexture != null && !currentTexture.isEmpty())
			combo.setSelectedItem(currentTexture);
		else
			combo.setSelectedIndex(0);

		combo.addActionListener(e -> {
			if (suppressEvents || selected == null)
				return;
			Object item = combo.getSelectedItem();
			setter.accept(resolveNormalMapFromTextureName(item == null ? "" : item.toString()));
			applyChanges();
		});

		gbc.gridx = 1;
		gbc.gridwidth = 3;
		gbc.weightx = 1;
		fieldsPanel.add(combo, gbc);
		gbc.gridy++;
	}

	private static void updateSwatch(JPanel swatch, float[] linearColor) {
		swatch.setBackground(linearToAwt(linearColor));
		swatch.setOpaque(true);
	}

	private static Color linearToAwt(float[] linearColor) {
		float[] srgb = ColorUtils.linearToSrgb(linearColor);
		return new Color(
			clamp255(srgb[0]),
			clamp255(srgb[1]),
			clamp255(srgb[2])
		);
	}

	private List<String> getTextureNames() {
		if (textureNames != null)
			return textureNames;

		var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
		try {
			if (TEXTURE_PATH.isFileSystemResource()) {
				try (var stream = Files.list(TEXTURE_PATH.toPath())) {
					stream
						.filter(Files::isRegularFile)
						.map(p -> p.getFileName().toString())
						.filter(WaterTypeDevEditor::isImageFile)
						.map(WaterTypeDevEditor::stripExtension)
						.forEach(names::add);
				}
			}
		} catch (IOException ex) {
			log.warn("Unable to list textures in {}", TEXTURE_PATH, ex);
		}

		if (names.isEmpty()) {
			for (Material material : MaterialManager.MATERIAL_MAP.values()) {
				String textureName = material.getTextureName();
				if (textureName != null)
					names.add(textureName);
			}
		}

		textureNames = new ArrayList<>(names);
		return textureNames;
	}

	private static boolean isImageFile(String filename) {
		String lower = filename.toLowerCase();
		return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
	}

	private static String stripExtension(String filename) {
		int dot = filename.lastIndexOf('.');
		return dot > 0 ? filename.substring(0, dot) : filename;
	}

	private void saveJsonQuiet() {
		var managerTypes = WaterTypeManager.WATER_TYPES;
		if (managerTypes.length <= 1) {
			setStatus("Nothing to save");
			return;
		}

		Material defaultNormalMap = materialManager.getMaterial("WATER_NORMAL_MAP_1");
		var export = new WaterType[managerTypes.length - 1];
		Material[] savedNormalMaps = new Material[export.length];
		String json;
		try {
			for (int i = 1; i < managerTypes.length; i++) {
				WaterType draft = editDrafts.get(managerTypes[i].name);
				export[i - 1] = draft != null ? draft : managerTypes[i];

				Material normalMap = export[i - 1].getNormalMap();
				if (defaultNormalMap != null && normalMap == defaultNormalMap) {
					savedNormalMaps[i - 1] = normalMap;
					export[i - 1].setNormalMap(null);
				}
			}

			json = buildWaterTypesJson(export, defaultNormalMap);
		} finally {
			for (int i = 0; i < savedNormalMaps.length; i++)
				if (savedNormalMaps[i] != null)
					export[i].setNormalMap(savedNormalMaps[i]);
		}

		try {
			WATER_TYPES_PATH.writeString(json);
			setStatus("Saved " + WATER_TYPES_PATH.getFilename());
		} catch (IOException ex) {
			log.error("Failed to save water types:", ex);
			setStatus("Save failed: " + ex.getMessage());
			JOptionPane.showMessageDialog(
				frame,
				"Failed to save water types:\n" + ex.getMessage(),
				"Save",
				JOptionPane.ERROR_MESSAGE
			);
			return;
		}
	}

	private void scheduleSave() {
		setStatus("Saving...");
		if (saveDebounce != null)
			saveDebounce.stop();

		saveDebounce = new Timer(200, e -> {
			saveJsonQuiet();
			((Timer) e.getSource()).stop();
		});
		saveDebounce.setRepeats(false);
		saveDebounce.start();
	}

	private void setStatus(String message) {
		if (statusLabel != null)
			statusLabel.setText(message);
	}

	private void applyChanges() {
		if (selected != null)
			editDrafts.put(selected.name, selected);
		scheduleSave();
	}

	private String buildWaterTypesJson(WaterType[] export, Material defaultNormalMap) {
		var defaults = plugin.getGson().fromJson("{}", WaterType.class);
		var existingFile = loadExistingWaterTypesFile();

		var sb = new StringBuilder();
		sb.append("[\n");

		var written = new LinkedHashSet<String>();
		for (var existing : existingFile.entriesInOrder) {
			WaterType type = findWaterType(export, existing.name);
			if (type == null)
				continue;
			if (!written.isEmpty())
				sb.append(",\n");
			sb.append("  ");
			appendWaterTypeObject(
				sb,
				type,
				defaults,
				defaultNormalMap,
				existing.keysInOrder,
				existing.properties
			);
			written.add(type.name);
		}

		for (var type : export) {
			if (written.contains(type.name))
				continue;
			if (!written.isEmpty())
				sb.append(",\n");
			sb.append("  ");
			appendWaterTypeObject(
				sb,
				type,
				defaults,
				defaultNormalMap,
				List.of(ALL_PROPERTY_KEYS),
				null
			);
			written.add(type.name);
		}

		sb.append("\n]\n");
		return sb.toString();
	}

	private static WaterType findWaterType(WaterType[] export, String name) {
		for (var type : export)
			if (name.equals(type.name))
				return type;
		return null;
	}

	private static final class ExistingWaterTypeEntry {
		final String name;
		final List<String> keysInOrder;
		final Map<String, JsonElement> properties;

		ExistingWaterTypeEntry(String name, List<String> keysInOrder, Map<String, JsonElement> properties) {
			this.name = name;
			this.keysInOrder = keysInOrder;
			this.properties = properties;
		}
	}

	private static final class ExistingWaterTypesFile {
		final String raw;
		final List<ExistingWaterTypeEntry> entriesInOrder;

		ExistingWaterTypesFile(String raw, List<ExistingWaterTypeEntry> entriesInOrder) {
			this.raw = raw;
			this.entriesInOrder = entriesInOrder;
		}
	}

	private ExistingWaterTypesFile loadExistingWaterTypesFile() {
		String raw = "";
		var entries = new ArrayList<ExistingWaterTypeEntry>();
		try {
			raw = WATER_TYPES_PATH.loadString();
			var array = new JsonParser().parse(raw).getAsJsonArray();
			for (var element : array) {
				var obj = element.getAsJsonObject();
				if (!obj.has("name"))
					continue;

				var keys = new ArrayList<String>();
				var properties = new LinkedHashMap<String, JsonElement>();
				for (var entry : obj.entrySet()) {
					keys.add(entry.getKey());
					properties.put(entry.getKey(), entry.getValue());
				}
				entries.add(new ExistingWaterTypeEntry(obj.get("name").getAsString(), keys, properties));
			}
		} catch (Exception ex) {
			log.warn("Unable to read existing water types from {}", WATER_TYPES_PATH, ex);
		}
		return new ExistingWaterTypesFile(raw, entries);
	}

	private void appendWaterTypeObject(
		StringBuilder sb,
		WaterType type,
		WaterType defaults,
		Material defaultNormalMap,
		List<String> preferredKeyOrder,
		Map<String, JsonElement> existingProperties
	) {
		var keysToWrite = new LinkedHashSet<String>();
		for (String key : preferredKeyOrder)
			keysToWrite.add(key);
		for (String key : ALL_PROPERTY_KEYS)
			keysToWrite.add(key);

		sb.append("{\n");
		boolean first = true;

		for (String key : keysToWrite) {
			JsonElement existing = existingProperties != null ? existingProperties.get(key) : null;
			String value = formatPropertyValue(key, type, defaults, defaultNormalMap, existing);
			if (value == null)
				continue;
			first = appendJsonField(sb, first, key, value);
		}

		sb.append("\n  }");
	}

	private String formatPropertyValue(
		String key,
		WaterType type,
		WaterType defaults,
		Material defaultNormalMap,
		JsonElement existing
	) {
		if ("name".equals(key))
			return "\"" + escapeJson(type.name) + "\"";

		Field field = WATER_TYPE_FIELD_BY_NAME.get(key);
		if (field == null)
			return null;

		try {
			Object value = field.get(type);
			Object defaultValue = field.get(defaults);
			if (fieldValuesEqual(field, value, defaultValue, defaultNormalMap))
				return null;

			if (field.getType() == boolean.class)
				return String.valueOf((Boolean) value);

			if (field.getType() == int.class)
				return formatNumber((Integer) value, existing);

			if (field.getType() == float.class)
				return formatNumber((Float) value, existing);

			if (Material.class.isAssignableFrom(field.getType())) {
				Material normalMap = (Material) value;
				if (normalMap == null || normalMap == defaultNormalMap)
					return null;
				String materialName = normalMap.name;
				if (existing != null && existing.isJsonPrimitive() && materialName.equals(existing.getAsString()))
					return formatJsonPrimitive(existing);
				return "\"" + escapeJson(materialName) + "\"";
			}

			if (field.getType() == float[].class && isColorField(field))
				return formatColorProperty((float[]) value, existing);

			return null;
		} catch (IllegalAccessException ex) {
			log.warn("Unable to format property {}", key, ex);
			return null;
		}
	}

	private static boolean fieldValuesEqual(
		Field field,
		Object value,
		Object defaultValue,
		Material defaultNormalMap
	) {
		if (Material.class.isAssignableFrom(field.getType())) {
			Material material = (Material) value;
			return material == null || material == defaultNormalMap;
		}
		if (field.getType() == float[].class && isColorField(field))
			return colorEq((float[]) value, (float[]) defaultValue);
		if (field.getType() == float.class)
			return floatEq((Float) value, (Float) defaultValue);
		return Objects.equals(value, defaultValue);
	}

	private static String formatNumber(float value, JsonElement existing) {
		if (existing != null && existing.isJsonPrimitive() && floatEq(value, existing.getAsFloat()))
			return formatJsonPrimitive(existing);
		return formatFloat(value);
	}

	private static String formatNumber(int value, JsonElement existing) {
		if (existing != null && existing.isJsonPrimitive() && value == existing.getAsInt())
			return formatJsonPrimitive(existing);
		return String.valueOf(value);
	}

	private static String formatJsonPrimitive(JsonElement element) {
		if (!element.isJsonPrimitive())
			return element.toString();

		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isString())
			return "\"" + escapeJson(primitive.getAsString()) + "\"";
		return primitive.getAsString();
	}

	private static String formatColorProperty(float[] linear, JsonElement existing) {
		if (existing != null && existing.isJsonArray())
			return formatColorAsArray(linear, existing);

		if (existing != null && existing.isJsonPrimitive() && existing.getAsJsonPrimitive().isString()) {
			String raw = existing.getAsString();
			if (raw.startsWith("#"))
				return formatColorAsHex(linear, existing);
		}

		return formatColorDefault(linear);
	}

	private static String formatColorAsHex(float[] linear, JsonElement existing) {
		if (existing != null) {
			try {
				if (colorEq(linear, parseColorElement(existing)))
					return formatJsonPrimitive(existing);
			} catch (Exception ignored) {}
		}

		float[] srgb = ColorUtils.linearToSrgb(linear);
		return "\"" + String.format("#%06X", ColorUtils.packSrgb(srgb)) + "\"";
	}

	private static String formatColorAsArray(float[] linear, JsonElement existing) {
		if (existing != null) {
			try {
				if (colorEq(linear, parseColorElement(existing)))
					return formatColorArrayMultiline(existing.getAsJsonArray());
			} catch (Exception ignored) {}
		}

		float[] srgb = ColorUtils.linearToSrgb(linear);
		var sb = new StringBuilder("[\n");
		for (int i = 0; i < 3; i++) {
			sb.append("      ").append(formatColorChannel(srgb[i] * 255f));
			if (i < 2)
				sb.append(",");
			sb.append("\n");
		}
		sb.append("    ]");
		return sb.toString();
	}

	/** Hex if channels are whole numbers, otherwise a multiline array (matches {@link ColorUtils.SrgbAdapter}). */
	private static String formatColorDefault(float[] linear) {
		float[] srgb = ColorUtils.linearToSrgb(linear);
		boolean canFitHex = true;
		for (int i = 0; i < 3; i++) {
			float channel = srgb[i] * 255f;
			if (Math.abs(channel - Math.round(channel)) > FLOAT_EPS)
				canFitHex = false;
		}

		if (canFitHex)
			return formatColorAsHex(linear, null);

		return formatColorAsArray(linear, null);
	}

	private static float[] parseColorElement(JsonElement element) {
		if (element.isJsonPrimitive())
			return ColorUtils.rgb(element.getAsString());
		if (element.isJsonArray()) {
			var array = element.getAsJsonArray();
			float[] channels = new float[array.size()];
			for (int i = 0; i < array.size(); i++)
				channels[i] = array.get(i).getAsFloat();
			return ColorUtils.rgb(channels[0] / 255f, channels[1] / 255f, channels[2] / 255f);
		}
		throw new IllegalArgumentException("Unsupported color element: " + element);
	}

	private static String formatColorArrayMultiline(JsonArray array) {
		var sb = new StringBuilder("[\n");
		for (int i = 0; i < array.size(); i++) {
			sb.append("      ").append(formatJsonPrimitive(array.get(i)));
			if (i < array.size() - 1)
				sb.append(",");
			sb.append("\n");
		}
		sb.append("    ]");
		return sb.toString();
	}

	private static boolean appendJsonField(StringBuilder sb, boolean first, String key, String value) {
		if (!first)
			sb.append(",\n");
		sb.append("    \"").append(key).append("\": ").append(value);
		return false;
	}

	private static String escapeJson(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static boolean floatEq(float a, float b) {
		return Math.abs(a - b) < FLOAT_EPS;
	}

	private static boolean colorEq(float[] a, float[] b) {
		if (a == b)
			return true;
		if (a == null || b == null)
			return false;
		if (a.length != b.length)
			return false;
		for (int i = 0; i < a.length; i++)
			if (!floatEq(a[i], b[i]))
				return false;
		return true;
	}

	/** 0–255 color channel when writing a new/changed array value. */
	private static String formatColorChannel(float value) {
		float rounded = Math.round(value * 1000f) / 1000f;
		if (Math.abs(rounded) < FLOAT_EPS)
			return "0.0";
		if (Math.abs(rounded - Math.round(rounded)) < FLOAT_EPS)
			return String.valueOf(Math.round(rounded));
		return stripTrailingZeros(String.valueOf(rounded));
	}

	private static String formatFloat(float value) {
		float rounded = Math.round(value * 1000f) / 1000f;
		if (Math.abs(rounded - Math.round(rounded)) < FLOAT_EPS)
			return String.format("%.1f", rounded);
		return stripTrailingZeros(String.valueOf(rounded));
	}

	private static String stripTrailingZeros(String s) {
		if (!s.contains("."))
			return s;
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '0')
			end--;
		if (end > 0 && s.charAt(end - 1) == '.')
			end--;
		return s.substring(0, end);
	}

	private Material resolveNormalMapFromTextureName(String textureName) {
		if (textureName == null || textureName.isEmpty())
			return materialManager.getMaterial("WATER_NORMAL_MAP_1");

		Material material = materialManager.getMaterial(textureName.toUpperCase());
		if (material != Material.NONE)
			return material;

		for (Material candidate : MaterialManager.MATERIAL_MAP.values()) {
			if (textureName.equalsIgnoreCase(candidate.getTextureName()))
				return candidate;
		}

		log.warn("Unknown texture '{}'", textureName);
		return materialManager.getMaterial("WATER_NORMAL_MAP_1");
	}

	private String getNormalMapTextureName(Material normalMap) {
		Material defaultNormalMap = materialManager.getMaterial("WATER_NORMAL_MAP_1");
		if (normalMap == null || normalMap == defaultNormalMap)
			return "";

		String textureName = normalMap.getTextureName();
		return textureName != null ? textureName : normalMap.name.toLowerCase();
	}

	private static DocumentListener simpleDocumentListener(Runnable onChange) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				onChange.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				onChange.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				onChange.run();
			}
		};
	}

	private static int clamp255(float c) {
		return Math.max(0, Math.min(255, Math.round(c * 255)));
	}
}
