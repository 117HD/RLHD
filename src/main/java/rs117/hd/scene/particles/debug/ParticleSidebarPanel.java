/*
 * Copyright (c) 2025, Mark7625.
 * Sidebar panel with Emitters, Particles, and Debug tabs. Debug has overlay toggles and test particles.
 */
package rs117.hd.scene.particles.debug;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import javax.swing.AbstractSpinnerModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Scrollable;
import javax.swing.BoxLayout;
import javax.swing.JTextField;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.border.MatteBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import rs117.hd.HdPlugin;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.definition.ParticleDefinition;
import rs117.hd.scene.particles.core.ParticleTextureLoader;
import rs117.hd.utils.ResourcePath;

@Slf4j
@Singleton
public class ParticleSidebarPanel extends PluginPanel  {

	private static final Color ACTIVE_BUTTON_GREEN = new Color(76, 175, 80);
	private static final Color ACTIVE_BUTTON_BORDER = new Color(56, 142, 60);
	/** Yaw units used by the game (0–2048, full circle). */
	private static final int YAW_UNITS = 2048;
	/** Game ticks per second (particle code uses delay/64 for lifetime in seconds). */
	private static final int TICKS_PER_SECOND = 64;

	/** Convert backend ticks to seconds (delay/64). */
	private static double emissionTicksToSeconds(int ticks) {
		return ticks < 0 ? 0 : ticks / (double) TICKS_PER_SECOND;
	}

	/** Convert seconds to backend ticks. */
	private static int emissionSecondsToTicks(double seconds) {
		return (int) Math.round(seconds * TICKS_PER_SECOND);
	}

	/** Convert ticks to hours, minutes, seconds (for display). */
	private static int[] emissionTicksToHMS(int ticks) {
		if (ticks < 0) return new int[]{ 0, 0, 0 };
		int totalSec = (int) Math.round(emissionTicksToSeconds(ticks));
		int h = totalSec / 3600;
		int m = (totalSec % 3600) / 60;
		int s = totalSec % 60;
		return new int[]{ h, m, s };
	}

	/** Convert hours, minutes, seconds to ticks. */
	private static int emissionHMSToTicks(int h, int m, int s) {
		long totalSec = (long) h * 3600 + (long) m * 60 + (long) s;
		return emissionSecondsToTicks(totalSec);
	}

	/** Format ticks as "HH:MM:SS" for display. */
	private static String emissionTicksToTimeString(int ticks) {
		int[] hms = emissionTicksToHMS(ticks);
		return String.format("%02d:%02d:%02d", hms[0], hms[1], hms[2]);
	}

	/** Parse "H:MM:SS" or "HH:MM:SS" to ticks; invalid input returns 0. */
	private static int emissionTimeStringToTicks(String s) {
		if (s == null || s.isEmpty()) return 0;
		String[] parts = s.trim().split(":");
		if (parts.length != 3) return 0;
		try {
			int h = Math.max(0, Math.min(99, Integer.parseInt(parts[0].trim())));
			int m = Math.max(0, Math.min(59, Integer.parseInt(parts[1].trim())));
			int sec = Math.max(0, Math.min(59, Integer.parseInt(parts[2].trim())));
			return emissionHMSToTicks(h, m, sec);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// --- Min/Max delay: minutes, seconds, milliseconds (no hours) ---

	/** Convert ticks to minutes, seconds, milliseconds. */
	private static int[] emissionTicksToMinSecMs(int ticks) {
		if (ticks < 0) return new int[]{ 0, 0, 0 };
		double totalSec = emissionTicksToSeconds(ticks);
		int min = (int) (totalSec / 60);
		int sec = (int) (totalSec % 60);
		int ms = (int) Math.round((totalSec - min * 60 - sec) * 1000);
		if (ms >= 1000) { ms = 0; sec++; if (sec >= 60) { sec = 0; min++; } }
		return new int[]{ min, sec, ms };
	}

	/** Convert minutes, seconds, milliseconds to ticks. */
	private static int emissionMinSecMsToTicks(int min, int sec, int ms) {
		double totalSec = min * 60 + sec + ms / 1000.0;
		return emissionSecondsToTicks(totalSec);
	}

	/** Format delay as "MM:SS.mmm" for display. */
	private static String emissionTicksToDelayString(int ticks) {
		int[] msm = emissionTicksToMinSecMs(ticks);
		return String.format("%02d:%02d.%03d", msm[0], msm[1], msm[2]);
	}

	/** Parse "M:SS.mmm" or "MM:SS.mmm" to ticks; invalid input returns 0. */
	private static int emissionDelayStringToTicks(String s) {
		if (s == null || s.isEmpty()) return 0;
		String[] colonParts = s.trim().split(":");
		if (colonParts.length != 2) return 0;
		String secMs = colonParts[1].trim();
		int dot = secMs.indexOf('.');
		int sec;
		int ms = 0;
		try {
			if (dot < 0) {
				sec = Math.max(0, Math.min(59, Integer.parseInt(secMs)));
			} else {
				sec = Math.max(0, Math.min(59, Integer.parseInt(secMs.substring(0, dot).trim())));
				String m = secMs.substring(dot + 1).replaceAll("\\D", "");
				if (m.length() > 3) m = m.substring(0, 3);
				if (!m.isEmpty()) {
					ms = Math.max(0, Math.min(999, Integer.parseInt(m)));
					if (m.length() == 1) ms *= 100;
					else if (m.length() == 2) ms *= 10;
				}
			}
			int min = Math.max(0, Math.min(99, Integer.parseInt(colonParts[0].trim())));
			return emissionMinSecMsToTicks(min, sec, ms);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Spinner model for delay: minutes, seconds, ms (MM:SS.mmm); step 1 second. */
	private static final class DelaySpinnerModel extends AbstractSpinnerModel {
		private static final int MAX_TICKS = 99 * 60 * TICKS_PER_SECOND;
		private int ticks;

		DelaySpinnerModel(int initialTicks) {
			this.ticks = Math.max(0, Math.min(MAX_TICKS, initialTicks));
		}

		int getTicks() {
			return ticks;
		}

		void setTicks(int t) {
			t = Math.max(0, Math.min(MAX_TICKS, t));
			if (t != ticks) {
				ticks = t;
				fireStateChanged();
			}
		}

		@Override
		public Object getValue() {
			return emissionTicksToDelayString(ticks);
		}

		@Override
		public void setValue(Object value) {
			if (value instanceof String) {
				setTicks(emissionDelayStringToTicks((String) value));
			} else if (value instanceof Number) {
				setTicks(((Number) value).intValue());
			}
		}

		@Override
		public Object getNextValue() {
			return emissionTicksToDelayString(Math.min(MAX_TICKS, ticks + TICKS_PER_SECOND));
		}

		@Override
		public Object getPreviousValue() {
			return emissionTicksToDelayString(Math.max(0, ticks - TICKS_PER_SECOND));
		}
	}

	/** Spinner model that holds time as ticks and displays/edits as HH:MM:SS; step is 1 second (64 ticks). When allowNegative, -1 is allowed and displays as -1. */
	private static final class TimeSpinnerModel extends AbstractSpinnerModel {
		private static final int MAX_TICKS = 99 * 3600 * TICKS_PER_SECOND;
		private int ticks;
		private final boolean allowNegative;

		TimeSpinnerModel(int initialTicks) {
			this(initialTicks, false);
		}

		TimeSpinnerModel(int initialTicks, boolean allowNegative) {
			this.allowNegative = allowNegative;
			this.ticks = allowNegative && initialTicks < 0
				? -1
				: Math.max(0, Math.min(MAX_TICKS, initialTicks));
		}

		int getTicks() {
			return ticks;
		}

		void setTicks(int t) {
			if (allowNegative && t < 0) {
				t = -1;
			} else {
				t = Math.max(0, Math.min(MAX_TICKS, t));
			}
			if (t != ticks) {
				ticks = t;
				fireStateChanged();
			}
		}

		@Override
		public Object getValue() {
			if (allowNegative && ticks < 0) return -1;
			return emissionTicksToTimeString(ticks);
		}

		@Override
		public void setValue(Object value) {
			if (value instanceof String) {
				String s = ((String) value).trim();
				if (allowNegative && ("-1".equals(s) || "−1".equals(s))) {
					setTicks(-1);
					return;
				}
				setTicks(emissionTimeStringToTicks((String) value));
			} else if (value instanceof Number) {
				setTicks(((Number) value).intValue());
			}
		}

		@Override
		public Object getNextValue() {
			if (allowNegative && ticks < 0) return emissionTicksToTimeString(0);
			return emissionTicksToTimeString(Math.min(MAX_TICKS, ticks + TICKS_PER_SECOND));
		}

		@Override
		public Object getPreviousValue() {
			if (allowNegative && ticks <= 0) return allowNegative ? -1 : emissionTicksToTimeString(0);
			return emissionTicksToTimeString(Math.max(0, ticks - TICKS_PER_SECOND));
		}
	}

	/** Make spinner text editable and commit when focus is lost so typing is applied. */
	private static void makeSpinnerEditable(JSpinner spinner) {
		if (spinner.getEditor() instanceof JSpinner.DefaultEditor) {
			JTextField tf = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
			tf.setEditable(true);
			tf.addFocusListener(new java.awt.event.FocusAdapter() {
				@Override
				public void focusLost(java.awt.event.FocusEvent e) {
					try {
						spinner.commitEdit();
					} catch (Exception ignored) {
						// ignore parse errors on commit
					}
				}
			});
		}
	}

	/** Create a delay spinner (min, sec, ms) showing 00:00.000; format in tooltip. */
	private static JPanel createDelaySpinnerWithLabel(Dimension size, JSpinner[] outSpinner) {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		p.setOpaque(false);
		DelaySpinnerModel model = new DelaySpinnerModel(0);
		JSpinner spinner = new JSpinner(model);
		spinner.setPreferredSize(size);
		spinner.setMinimumSize(size);
		spinner.setToolTipText("Minutes:Seconds.Milliseconds (e.g. 01:30.500)");
		makeSpinnerEditable(spinner);
		p.add(spinner);
		outSpinner[0] = spinner;
		return p;
	}

	/** Create a time spinner showing 00:00:00; format in tooltip. Use allowNegative true for cycle/threshold so -1 disables. */
	private static JPanel createTimeSpinnerWithLabel(Dimension size, JSpinner[] outSpinner) {
		return createTimeSpinnerWithLabel(size, outSpinner, false);
	}

	private static JPanel createTimeSpinnerWithLabel(Dimension size, JSpinner[] outSpinner, boolean allowNegative) {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		p.setOpaque(false);
		TimeSpinnerModel model = new TimeSpinnerModel(0, allowNegative);
		JSpinner spinner = new JSpinner(model);
		spinner.setPreferredSize(size);
		spinner.setMinimumSize(size);
		spinner.setToolTipText(allowNegative
			? "Hours:Minutes:Seconds or -1 to disable (e.g. 00:01:30 or -1)"
			: "Hours:Minutes:Seconds (e.g. 00:01:30)");
		makeSpinnerEditable(spinner);
		p.add(spinner);
		outSpinner[0] = spinner;
		return p;
	}

	/**
	 * Map stored game yaw to UI compass yaw.
	 * Game yaw uses:
	 *   0   = south,  512 = east, 1024 = north, 1536 = west
	 * Gizmo yaw uses:
	 *   0   = north,  512 = east, 1024 = south, 1536 = west
	 * This transform lines up all four cardinals: N/E/S/W.
	 */
	private static int gameToUiYaw(int yaw) {
		return (YAW_UNITS / 2 - yaw + YAW_UNITS) % YAW_UNITS;
	}

	/** Inverse of gameToUiYaw (same formula). */
	private static int uiToGameYaw(int yaw) {
		return (YAW_UNITS / 2 - yaw + YAW_UNITS) % YAW_UNITS;
	}

	private final HdPlugin plugin;
	private final ParticleManager particleManager;
	private final ParticleGizmoOverlay particleGizmoOverlay;
	private final Client client;
	private final ColorPickerManager colorPickerManager;
	private final ClientThread clientThread;

	private boolean gizmoOverlayActive;

	/** Dropdown in Particles tab; used to refresh and load default on activate. */
	private JComboBox<String> particleDropdownRef;
	private JButton placeBtnRef;
	private MaterialTabGroup tabGroupRef;
	private MaterialTab particlesTabRef;

	public ParticleSidebarPanel(
		HdPlugin plugin,
		ParticleManager particleManager,
		ClientThread clientThread,
		Client client,
		ColorPickerManager colorPickerManager,
		ParticleGizmoOverlay particleGizmoOverlay
	) {
		super(false);
		this.plugin = plugin;
		this.particleManager = particleManager;
		this.clientThread = clientThread;
		this.client = client;
		this.colorPickerManager = colorPickerManager;
		this.particleGizmoOverlay = particleGizmoOverlay;

		setLayout(new BorderLayout());

		JPanel display = new JPanel();
		display.setLayout(new BorderLayout());
		display.setLayout(new CardLayout());
		display.setBorder(new EmptyBorder(0, 0, 0, 0));

		MaterialTabGroup tabGroup = new MaterialTabGroup(display);
		tabGroup.setLayout(new GridLayout(1, 0, 8, 4));
		tabGroup.setBorder(new EmptyBorder(0, 10, 10, 10));

		MaterialTab emittersTab = new MaterialTab("Emitters", tabGroup, buildEmittersPanel());
		MaterialTab particlesTab = new MaterialTab("Particles", tabGroup, buildParticlesPanel());
		MaterialTab debugTab = new MaterialTab("Debug", tabGroup, buildDebugPanel());

		tabGroupRef = tabGroup;
		particlesTabRef = particlesTab;

		tabGroup.setBorder(new EmptyBorder(5, 0, 0, 0));
		tabGroup.addTab(emittersTab);
		tabGroup.addTab(particlesTab);
		tabGroup.addTab(debugTab);
		tabGroup.select(debugTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
	}

	@Override
	public void onActivate() {
		refreshParticleDropdownAndLoadDefault();
	}

	/** Switch to Particles tab and select the given particle definition. Used by right-click "Edit config". */
	public void openToParticleConfig(String particleId) {
		if (tabGroupRef != null && particlesTabRef != null) {
			tabGroupRef.select(particlesTabRef);
		}
		if (particleDropdownRef != null && particleId != null) {
			particleDropdownRef.setSelectedItem(particleId);
		}
	}

	/** Refresh dropdown from definitions and select default particle so General section loads its values. */
	private void refreshParticleDropdownAndLoadDefault() {
		if (particleDropdownRef == null) return;
		java.util.List<String> orderedIds = particleManager.getDefinitionIdsOrdered();
		// Same ordering as initial build: text IDs first, then purely numeric IDs
		java.util.List<String> textIds = new java.util.ArrayList<>();
		java.util.List<String> numericIds = new java.util.ArrayList<>();
		for (String id : orderedIds) {
			if (id != null && id.matches("\\d+")) {
				numericIds.add(id);
			} else {
				textIds.add(id);
			}
		}
		textIds.addAll(numericIds);
		particleDropdownRef.setModel(new DefaultComboBoxModel<>(textIds.toArray(new String[0])));
		selectDefaultParticle(particleDropdownRef);
	}

	private static final String DEFAULT_PARTICLE_ID = "7";

	private static void selectDefaultParticle(JComboBox<String> dropdown) {
		for (int i = 0; i < dropdown.getItemCount(); i++) {
			if (DEFAULT_PARTICLE_ID.equals(dropdown.getItemAt(i))) {
				dropdown.setSelectedIndex(i);
				return;
			}
		}
		if (dropdown.getItemCount() > 0)
			dropdown.setSelectedIndex(0);
	}

	private JPanel buildEmittersPanel() {
		JPanel p = new JPanel();
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.add(new JLabel("Emitters (coming soon)"));
		return p;
	}

	private JPanel buildParticlesPanel() {
		JPanel p = new JPanel();
		p.setLayout(new BorderLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(new EmptyBorder(5, 5, 5, 5));

		// Top: dropdown + Load + New in one row (default to test emitter "7" from emitters.json)
		java.util.List<String> orderedIds = particleManager.getDefinitionIdsOrdered();
		// Show human-labeled IDs first (not just numeric), while preserving original relative order
		java.util.List<String> textIds = new java.util.ArrayList<>();
		java.util.List<String> numericIds = new java.util.ArrayList<>();
		for (String id : orderedIds) {
			if (id != null && id.matches("\\d+")) {
				numericIds.add(id);
			} else {
				textIds.add(id);
			}
		}
		textIds.addAll(numericIds);
		String[] particleIds = textIds.toArray(new String[0]);
		JComboBox<String> particleDropdown = new JComboBox<>(particleIds);
		particleDropdownRef = particleDropdown;
		selectDefaultParticle(particleDropdown);

		JButton loadBtn = new JButton("Load");
		styleButton(loadBtn);

		JButton newBtn = new JButton("New");
		styleButton(newBtn);

		JPanel topBar = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		c.weighty = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 0, 4);
		c.gridx = 0;
		c.weightx = 0.95;
		topBar.add(particleDropdown, c);
		c.gridx = 1;
		c.weightx = 0.025;
		topBar.add(loadBtn, c);
		c.gridx = 2;
		c.weightx = 0.025;
		c.insets = new Insets(0, 0, 0, 0);
		topBar.add(newBtn, c);
		p.add(topBar, BorderLayout.NORTH);

		// Center: scrollable content — one section per particle def type
		// ScrollablePanel tracks viewport width so content does not expand scroll horizontally
		JPanel scrollContent = new ScrollablePanel();
		scrollContent.setLayout(new BoxLayout(scrollContent, BoxLayout.Y_AXIS));
		scrollContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Texture section first so texture / flipbook settings sit above General
		scrollContent.add(buildTextureSection(particleDropdown));
		scrollContent.add(buildGeneralSection(particleDropdown));
		scrollContent.add(buildSpreadSection(particleDropdown));
		scrollContent.add(buildSpeedSection(particleDropdown));
		scrollContent.add(buildScaleSection(particleDropdown));
		scrollContent.add(buildColoursSection(particleDropdown));
		scrollContent.add(buildEmissionSection(particleDropdown));
		scrollContent.add(buildPhysicsSection(particleDropdown));
		JScrollPane scroll = new JScrollPane(scrollContent);
		scroll.setBorder(new EmptyBorder(5, 0, 5, 5));
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		// Prefer height 0 so BorderLayout gives the scroll pane all remaining vertical space
		scroll.setPreferredSize(new Dimension(0, 0));
		p.add(scroll, BorderLayout.CENTER);

		// Footer: Place, Export — full width, equal size
		JPanel footer = new JPanel(new GridLayout(1, 2, 4, 0));

		JButton placeBtn = new JButton("Place");
		placeBtnRef = placeBtn;
		styleButton(placeBtn);
		particleGizmoOverlay.setOnPlaceModeChanged(() ->
			SwingUtilities.invokeLater(() -> setButtonActive(placeBtnRef, particleGizmoOverlay.isPlaceModeActive())));
		placeBtn.addActionListener(e -> {
			String pid = particleDropdownRef != null ? (String) particleDropdownRef.getSelectedItem() : null;
			if (pid == null || pid.isEmpty()) return;
			boolean entering = !particleGizmoOverlay.isPlaceModeActive();
			particleGizmoOverlay.setPlaceMode(entering, pid);
			setButtonActive(placeBtn, entering);
		});
		footer.add(placeBtn);

		JButton exportBtn = new JButton("Export");
		styleButton(exportBtn);
		footer.add(exportBtn);

		p.add(footer, BorderLayout.SOUTH);
		return p;
	}

	private static final Insets ROW_INSETS_LABEL = new Insets(2, 0, 2, 8);
	private static final Insets ROW_INSETS_CONTROL = new Insets(2, 0, 2, 0);

	/** Label left, control right; control column has weightx=1 so it stays right-aligned within section width. */
	private static GridBagConstraints labelConstraints(int row) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = row;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		c.insets = ROW_INSETS_LABEL;
		return c;
	}

	private static GridBagConstraints controlConstraints(int row) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 1;
		c.gridy = row;
		c.anchor = GridBagConstraints.EAST;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 1;
		c.insets = ROW_INSETS_CONTROL;
		return c;
	}

	/** Control column left-aligned (so wide controls like time spinners stay visible). */
	private static GridBagConstraints controlConstraintsWest(int row) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 1;
		c.gridy = row;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 1;
		c.insets = ROW_INSETS_CONTROL;
		return c;
	}

	/** Control column left-aligned, no horizontal grow (keeps section compact, doesn't push other content). */
	private static GridBagConstraints controlConstraintsWestNoGrow(int row) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 1;
		c.gridy = row;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		c.insets = ROW_INSETS_CONTROL;
		return c;
	}

	/** Min/Max delay only: minimal gap so spinner sits right next to label and doesn't overflow. */
	private static final Insets DELAY_ROW_INSETS_LABEL = new Insets(2, 0, 2, 0);
	private static GridBagConstraints delayLabelConstraints(int row) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = row;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		c.insets = DELAY_ROW_INSETS_LABEL;
		return c;
	}
	private static GridBagConstraints delayControlConstraints(int row) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 1;
		c.gridy = row;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		c.insets = new Insets(2, 0, 2, 0);
		return c;
	}

	private boolean generalLoadingFromDefinition;
	private boolean textureLoadingFromDefinition;
	private boolean spreadLoadingFromDefinition;
	private boolean speedLoadingFromDefinition;
	private boolean scaleLoadingFromDefinition;
	private boolean coloursLoadingFromDefinition;
	private boolean emissionLoadingFromDefinition;
	private boolean physicsLoadingFromDefinition;
	private DirectionGizmoPanel directionGizmo;

	private JPanel buildGeneralSection(JComboBox<String> particleDropdown) {
		generalLoadingFromDefinition = false;
		return buildGeneralSectionInner(particleDropdown);
	}

	private JPanel buildSpreadSection(JComboBox<String> particleDropdown) {
		spreadLoadingFromDefinition = false;

		JPanel section = buildTitledSection("Spread");
		JPanel content = new JPanel(new GridBagLayout());
		// Match margins used by Emission/Physics so labels and controls line up
		content.setBorder(new EmptyBorder(6, 6, 6, 10));

		int row = 0;

		JLabel yawMinLabel = new JLabel("Yaw Min");
		yawMinLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(yawMinLabel, labelConstraints(row));
		JSpinner yawMinSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 2048, 10));
		content.add(yawMinSpinner, controlConstraints(row));
		row++;

		JLabel yawMaxLabel = new JLabel("Yaw Max");
		yawMaxLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(yawMaxLabel, labelConstraints(row));
		JSpinner yawMaxSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 2048, 10));
		content.add(yawMaxSpinner, controlConstraints(row));
		row++;

		JLabel pitchMinLabel = new JLabel("Pitch Min");
		pitchMinLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(pitchMinLabel, labelConstraints(row));
		JSpinner pitchMinSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 1024, 10));
		content.add(pitchMinSpinner, controlConstraints(row));
		row++;

		JLabel pitchMaxLabel = new JLabel("Pitch Max");
		pitchMaxLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(pitchMaxLabel, labelConstraints(row));
		JSpinner pitchMaxSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 1024, 10));
		content.add(pitchMaxSpinner, controlConstraints(row));

		ItemListener loadFromDef = e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			spreadLoadingFromDefinition = true;
			try {
				ParticleDefinition.Spread s = def.spread;
				// Convert stored game yaw to UI yaw so 0 = North, 512 = East, etc.
				int yawMinGame = (int) s.yawMin;
				int yawMaxGame = (int) s.yawMax;
				int yawMinUi = Math.max(0, Math.min(2048, gameToUiYaw(yawMinGame)));
				int yawMaxUi = Math.max(0, Math.min(2048, gameToUiYaw(yawMaxGame)));
				int pitchMin = Math.max(0, Math.min(1024, (int) s.pitchMin));
				int pitchMax = Math.max(0, Math.min(1024, (int) s.pitchMax));
				yawMinSpinner.setValue(yawMinUi);
				yawMaxSpinner.setValue(yawMaxUi);
				pitchMinSpinner.setValue(pitchMin);
				pitchMaxSpinner.setValue(pitchMax);
			} finally {
				spreadLoadingFromDefinition = false;
			}
			// Refresh gizmo spread immediately when switching particles
			if (directionGizmo != null) {
				directionGizmo.repaint();
			}
		};
		particleDropdown.addItemListener(loadFromDef);

		Runnable pushToDef = () -> {
			if (spreadLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			// Convert UI yaw back to game yaw before storing on the definition
			int yawMinUi = ((Number) yawMinSpinner.getValue()).intValue();
			int yawMaxUi = ((Number) yawMaxSpinner.getValue()).intValue();
			def.spread.yawMin = uiToGameYaw(yawMinUi);
			def.spread.yawMax = uiToGameYaw(yawMaxUi);
			def.spread.pitchMin = ((Number) pitchMinSpinner.getValue()).floatValue();
			def.spread.pitchMax = ((Number) pitchMaxSpinner.getValue()).floatValue();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
			if (directionGizmo != null) {
				directionGizmo.repaint();
			}
		};
		ChangeListener spreadChange = e -> pushToDef.run();
		yawMinSpinner.addChangeListener(spreadChange);
		yawMaxSpinner.addChangeListener(spreadChange);
		pitchMinSpinner.addChangeListener(spreadChange);
		pitchMaxSpinner.addChangeListener(spreadChange);

		// Initial load for default selection
		loadFromDef.itemStateChanged(new ItemEvent(particleDropdown, ItemEvent.ITEM_STATE_CHANGED, particleDropdown.getSelectedItem(), ItemEvent.SELECTED));

		// Hook spread values into gizmo so it can visualize them
		if (directionGizmo != null) {
			directionGizmo.setSpreadSupplier(() -> {
				int yawMin = ((Number) yawMinSpinner.getValue()).intValue();
				int yawMax = ((Number) yawMaxSpinner.getValue()).intValue();
				int pitchMin = ((Number) pitchMinSpinner.getValue()).intValue();
				int pitchMax = ((Number) pitchMaxSpinner.getValue()).intValue();
				return new DirectionGizmoPanel.SpreadValues(yawMin, yawMax, pitchMin, pitchMax);
			});
		}

		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildSpeedSection(JComboBox<String> particleDropdown) {
		speedLoadingFromDefinition = false;

		JPanel section = buildTitledSection("Speed");
		JPanel content = new JPanel(new GridBagLayout());
		content.setBorder(new EmptyBorder(6, 6, 6, 10));

		int row = 0;
		Dimension spinnerSize = new Dimension(72, 24);
		Dimension speedDelaySize = new Dimension(96, 24); // Same as min/max delay

		// Speed values displayed at 1/100 scale, integer only (e.g. 786 = 78643 internal)
		final int SPEED_UI_SCALE = 100;

		JLabel minSpeedLabel = new JLabel("Min speed");
		minSpeedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		minSpeedLabel.setToolTipText("Lower bound of random initial speed when the particle spawns. Displayed at 1/100 scale (integer).");
		content.add(minSpeedLabel, labelConstraints(row));
		JSpinner minSpeedSpinner = new JSpinner(new SpinnerNumberModel(2, 0, 999999, 1));
		minSpeedSpinner.setPreferredSize(speedDelaySize);
		minSpeedSpinner.setMinimumSize(speedDelaySize);
		content.add(minSpeedSpinner, controlConstraints(row));
		row++;

		JLabel maxSpeedLabel = new JLabel("Max speed");
		maxSpeedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		maxSpeedLabel.setToolTipText("Upper bound of random initial speed when the particle spawns. Displayed at 1/100 scale (integer).");
		content.add(maxSpeedLabel, labelConstraints(row));
		JSpinner maxSpeedSpinner = new JSpinner(new SpinnerNumberModel(6, 0, 999999, 1));
		maxSpeedSpinner.setPreferredSize(speedDelaySize);
		maxSpeedSpinner.setMinimumSize(speedDelaySize);
		content.add(maxSpeedSpinner, controlConstraints(row));
		row++;

		// Target speed: no target (-1) shows Enable button; otherwise spinner + Disable (like cycle duration)
		JLabel targetSpeedLabel = new JLabel("Target speed");
		targetSpeedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		targetSpeedLabel.setToolTipText("Speed to transition toward over the particle's lifetime. Displayed at 1/100 scale (integer). Enable to set a target; Disable for no target.");
		content.add(targetSpeedLabel, labelConstraints(row));
		JPanel targetSpeedCardPanel = new JPanel(new CardLayout());
		targetSpeedCardPanel.setOpaque(false);
		JButton targetSpeedEnableBtn = new JButton("Enable");
		targetSpeedEnableBtn.setToolTipText("Enable target speed");
		targetSpeedCardPanel.add(targetSpeedEnableBtn, "enable");
		JPanel targetSpeedSpinnerRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
		targetSpeedSpinnerRow.setOpaque(false);
		JSpinner targetSpeedSpinner = new JSpinner(new SpinnerNumberModel(6, -1, 999999, 1));
		targetSpeedSpinner.setPreferredSize(spinnerSize);
		targetSpeedSpinner.setMinimumSize(spinnerSize);
		targetSpeedSpinnerRow.add(targetSpeedSpinner);
		JButton targetSpeedDisableBtn = new JButton("Disable");
		targetSpeedDisableBtn.setToolTipText("No target speed (particle speed stays constant)");
		targetSpeedSpinnerRow.add(targetSpeedDisableBtn);
		targetSpeedCardPanel.add(targetSpeedSpinnerRow, "spinner");
		content.add(targetSpeedCardPanel, controlConstraints(row));
		final boolean[] targetSpeedEnabled = { false };
		row++;

		JLabel speedTransitionLabel = new JLabel("Speed transition %");
		speedTransitionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		speedTransitionLabel.setToolTipText("Percentage of the particle's lifetime over which speed changes from initial to target. 100 = transition over full lifetime.");
		content.add(speedTransitionLabel, labelConstraints(row));
		JSpinner speedTransitionSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 1));
		speedTransitionSpinner.setPreferredSize(spinnerSize);
		speedTransitionSpinner.setMinimumSize(spinnerSize);
		content.add(speedTransitionSpinner, controlConstraints(row));

		targetSpeedEnableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.speed.targetSpeed = 60f;
			targetSpeedSpinner.setValue(6);
			targetSpeedEnabled[0] = true;
			((CardLayout) targetSpeedCardPanel.getLayout()).show(targetSpeedCardPanel, "spinner");
			def.postDecode();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});
		targetSpeedDisableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.speed.targetSpeed = -1f;
			targetSpeedEnabled[0] = false;
			((CardLayout) targetSpeedCardPanel.getLayout()).show(targetSpeedCardPanel, "enable");
			def.postDecode();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});

		ItemListener loadFromDef = e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			speedLoadingFromDefinition = true;
			try {
				ParticleDefinition.Speed s = def.speed;
				minSpeedSpinner.setValue((int) Math.round(s.getMinSpeed() / SPEED_UI_SCALE));
				maxSpeedSpinner.setValue((int) Math.round(s.getMaxSpeed() / SPEED_UI_SCALE));
				boolean hasTarget = s.getTargetSpeed() >= 0;
				targetSpeedEnabled[0] = hasTarget;
				targetSpeedSpinner.setValue(hasTarget ? (int) Math.round(s.getTargetSpeed() / SPEED_UI_SCALE) : 6);
				((CardLayout) targetSpeedCardPanel.getLayout()).show(targetSpeedCardPanel, hasTarget ? "spinner" : "enable");
				speedTransitionSpinner.setValue(s.getSpeedTransitionPercent());
			} finally {
				speedLoadingFromDefinition = false;
			}
			SwingUtilities.invokeLater(() -> {
				targetSpeedCardPanel.revalidate();
				targetSpeedCardPanel.repaint();
			});
		};
		particleDropdown.addItemListener(loadFromDef);

		Runnable pushToDef = () -> {
			if (speedLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			ParticleDefinition.Speed s = def.speed;
			s.minSpeed = ((Number) minSpeedSpinner.getValue()).intValue() * SPEED_UI_SCALE;
			s.maxSpeed = ((Number) maxSpeedSpinner.getValue()).intValue() * SPEED_UI_SCALE;
			s.targetSpeed = targetSpeedEnabled[0] ? ((Number) targetSpeedSpinner.getValue()).intValue() * SPEED_UI_SCALE : -1f;
			s.speedTransitionPercent = ((Number) speedTransitionSpinner.getValue()).intValue();
			def.postDecode();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		};
		ChangeListener speedChanged = e -> pushToDef.run();
		minSpeedSpinner.addChangeListener(speedChanged);
		maxSpeedSpinner.addChangeListener(speedChanged);
		targetSpeedSpinner.addChangeListener(e -> {
			if (((Number) targetSpeedSpinner.getValue()).intValue() < 0) { // -1 = disabled
				targetSpeedEnabled[0] = false;
				((CardLayout) targetSpeedCardPanel.getLayout()).show(targetSpeedCardPanel, "enable");
			}
			pushToDef.run();
		});
		speedTransitionSpinner.addChangeListener(speedChanged);

		loadFromDef.itemStateChanged(new ItemEvent(particleDropdown, ItemEvent.ITEM_STATE_CHANGED, particleDropdown.getSelectedItem(), ItemEvent.SELECTED));

		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildScaleSection(JComboBox<String> particleDropdown) {
		scaleLoadingFromDefinition = false;

		JPanel section = buildTitledSection("Scale");
		JPanel content = new JPanel(new GridBagLayout());
		content.setBorder(new EmptyBorder(6, 6, 6, 10));

		int row = 0;
		Dimension spinnerSize = new Dimension(72, 24);

		JLabel minScaleLabel = new JLabel("Min scale");
		minScaleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		minScaleLabel.setToolTipText("Lower bound of initial particle size. Higher values make particles spawn larger.");
		content.add(minScaleLabel, labelConstraints(row));
		JSpinner minScaleSpinner = new JSpinner(new SpinnerNumberModel(2f, 0f, 10000f, 0.5f));
		minScaleSpinner.setPreferredSize(spinnerSize);
		minScaleSpinner.setMinimumSize(spinnerSize);
		content.add(minScaleSpinner, controlConstraints(row));
		row++;

		JLabel maxScaleLabel = new JLabel("Max scale");
		maxScaleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		maxScaleLabel.setToolTipText("Upper bound of initial particle size. Each particle gets a random size between min and max.");
		content.add(maxScaleLabel, labelConstraints(row));
		JSpinner maxScaleSpinner = new JSpinner(new SpinnerNumberModel(4f, 0f, 10000f, 0.5f));
		maxScaleSpinner.setPreferredSize(spinnerSize);
		maxScaleSpinner.setMinimumSize(spinnerSize);
		content.add(maxScaleSpinner, controlConstraints(row));
		row++;

		// Target scale: no target (-1) shows Enable button; otherwise spinner + Disable (like target speed)
		JLabel targetScaleLabel = new JLabel("Target scale");
		targetScaleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		targetScaleLabel.setToolTipText("Size to transition toward over the particle's lifetime. Enable to set a target; Disable or -1 for no target.");
		content.add(targetScaleLabel, labelConstraints(row));
		JPanel targetScaleCardPanel = new JPanel(new CardLayout());
		targetScaleCardPanel.setOpaque(false);
		JButton targetScaleEnableBtn = new JButton("Enable");
		targetScaleEnableBtn.setToolTipText("Enable target scale");
		targetScaleCardPanel.add(targetScaleEnableBtn, "enable");
		JPanel targetScaleSpinnerRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
		targetScaleSpinnerRow.setOpaque(false);
		JSpinner targetScaleSpinner = new JSpinner(new SpinnerNumberModel(4f, -1f, 10000f, 0.5f));
		targetScaleSpinner.setPreferredSize(spinnerSize);
		targetScaleSpinner.setMinimumSize(spinnerSize);
		targetScaleSpinnerRow.add(targetScaleSpinner);
		JButton targetScaleDisableBtn = new JButton("Disable");
		targetScaleDisableBtn.setToolTipText("No target scale (particle size stays between min and max)");
		targetScaleSpinnerRow.add(targetScaleDisableBtn);
		targetScaleCardPanel.add(targetScaleSpinnerRow, "spinner");
		content.add(targetScaleCardPanel, controlConstraints(row));
		final boolean[] targetScaleEnabled = { false };
		row++;

		JLabel scaleTransitionLabel = new JLabel("Scale transition %");
		scaleTransitionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		scaleTransitionLabel.setToolTipText("Percentage of the particle's lifetime over which scale changes from initial to target. 100 = transition over full lifetime.");
		content.add(scaleTransitionLabel, labelConstraints(row));
		JSpinner scaleTransitionSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 1));
		scaleTransitionSpinner.setPreferredSize(spinnerSize);
		scaleTransitionSpinner.setMinimumSize(spinnerSize);
		content.add(scaleTransitionSpinner, controlConstraints(row));

		targetScaleEnableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.scale.targetScale = 4f;
			targetScaleSpinner.setValue(4f);
			targetScaleEnabled[0] = true;
			((CardLayout) targetScaleCardPanel.getLayout()).show(targetScaleCardPanel, "spinner");
			def.postDecode();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});
		targetScaleDisableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.scale.targetScale = ParticleDefinition.NO_TARGET;
			targetScaleEnabled[0] = false;
			((CardLayout) targetScaleCardPanel.getLayout()).show(targetScaleCardPanel, "enable");
			def.postDecode();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});

		ItemListener loadFromDef = e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			scaleLoadingFromDefinition = true;
			try {
				ParticleDefinition.Scale s = def.scale;
				minScaleSpinner.setValue((float) s.getMinScale());
				maxScaleSpinner.setValue((float) s.getMaxScale());
				boolean hasTarget = s.getTargetScale() >= 0;
				targetScaleEnabled[0] = hasTarget;
				targetScaleSpinner.setValue(hasTarget ? s.getTargetScale() : 4f);
				((CardLayout) targetScaleCardPanel.getLayout()).show(targetScaleCardPanel, hasTarget ? "spinner" : "enable");
				scaleTransitionSpinner.setValue(s.getScaleTransitionPercent());
			} finally {
				scaleLoadingFromDefinition = false;
			}
			SwingUtilities.invokeLater(() -> {
				targetScaleCardPanel.revalidate();
				targetScaleCardPanel.repaint();
			});
		};
		particleDropdown.addItemListener(loadFromDef);

		Runnable pushToDef = () -> {
			if (scaleLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			ParticleDefinition.Scale s = def.scale;
			s.minScale = ((Number) minScaleSpinner.getValue()).floatValue();
			s.maxScale = ((Number) maxScaleSpinner.getValue()).floatValue();
			s.targetScale = targetScaleEnabled[0] ? ((Number) targetScaleSpinner.getValue()).floatValue() : ParticleDefinition.NO_TARGET;
			s.scaleTransitionPercent = ((Number) scaleTransitionSpinner.getValue()).intValue();
			def.postDecode();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		};
		ChangeListener scaleChanged = e -> pushToDef.run();
		minScaleSpinner.addChangeListener(scaleChanged);
		maxScaleSpinner.addChangeListener(scaleChanged);
		targetScaleSpinner.addChangeListener(e -> {
			if (((Number) targetScaleSpinner.getValue()).floatValue() < 0) {
				targetScaleEnabled[0] = false;
				((CardLayout) targetScaleCardPanel.getLayout()).show(targetScaleCardPanel, "enable");
			}
			pushToDef.run();
		});
		scaleTransitionSpinner.addChangeListener(scaleChanged);

		loadFromDef.itemStateChanged(new ItemEvent(particleDropdown, ItemEvent.ITEM_STATE_CHANGED, particleDropdown.getSelectedItem(), ItemEvent.SELECTED));

		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildColoursSection(JComboBox<String> particleDropdown) {
		coloursLoadingFromDefinition = false;

		JPanel section = new JPanel();
		section.setLayout(new BorderLayout(0, 0));
		TitledBorder coloursTitleBorder = new TitledBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
			"Colours",
			TitledBorder.LEFT,
			TitledBorder.TOP);
		coloursTitleBorder.setTitleColor(ColorScheme.LIGHT_GRAY_COLOR);
		coloursTitleBorder.setTitleFont(coloursTitleBorder.getTitleFont().deriveFont(Font.BOLD, 14f));
		section.setBorder(coloursTitleBorder);

		JPanel content = new JPanel(new GridBagLayout());
		content.setBorder(new EmptyBorder(6, 6, 0, 6));

		int row = 0;

		// Min colour
		JLabel minLabel = new JLabel("Min Colour");
		minLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(minLabel, labelConstraints(row));
		JButton minButton = new JButton();
		minButton.setOpaque(true);
		minButton.setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		minButton.setPreferredSize(new Dimension(140, 24));
		minButton.setMinimumSize(new Dimension(100, 24));
		updateColorButton(minButton, null);
		content.add(minButton, controlConstraints(row));
		row++;

		// Max colour
		JLabel maxLabel = new JLabel("Max Colour");
		maxLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(maxLabel, labelConstraints(row));
		JButton maxButton = new JButton();
		maxButton.setOpaque(true);
		maxButton.setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		maxButton.setPreferredSize(new Dimension(140, 24));
		maxButton.setMinimumSize(new Dimension(100, 24));
		updateColorButton(maxButton, null);
		content.add(maxButton, controlConstraints(row));
		row++;

		// Target colour: no target (0/-1 argb) shows Enable button; otherwise colour button + Disable (like cycle duration)
		JLabel targetLabel = new JLabel("Target Colour");
		targetLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		targetLabel.setToolTipText("Optional end colour to transition to. Enable to set a target; Disable for no target.");
		content.add(targetLabel, labelConstraints(row));
		JPanel targetColourCardPanel = new JPanel(new CardLayout());
		targetColourCardPanel.setOpaque(false);
		JButton targetColourEnableBtn = new JButton("Enable");
		targetColourEnableBtn.setToolTipText("Enable target colour");
		targetColourCardPanel.add(targetColourEnableBtn, "enable");
		JPanel targetColourControlRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
		targetColourControlRow.setOpaque(false);
		JButton targetButton = new JButton();
		targetButton.setOpaque(true);
		targetButton.setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		targetButton.setToolTipText("Target end colour");
		// Compact swatch so the colour box + Disable button fit comfortably
		targetButton.setPreferredSize(new Dimension(84, 24));
		targetButton.setMinimumSize(new Dimension(64, 24));
		updateColorButton(targetButton, null);
		targetColourControlRow.add(targetButton);
		JButton targetColourDisableBtn = new JButton("Disable");
		targetColourDisableBtn.setToolTipText("No target colour (particle colour stays between min and max only)");
		targetColourControlRow.add(targetColourDisableBtn);
		targetColourCardPanel.add(targetColourControlRow, "control");
		content.add(targetColourCardPanel, controlConstraints(row));
		final boolean[] targetColourEnabled = { false };
		row++;

		// Colour transition %
		JLabel colourTransLabel = new JLabel("Color Transition %");
		colourTransLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(colourTransLabel, labelConstraints(row));
		JSpinner colourTransSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));
		content.add(colourTransSpinner, controlConstraints(row));
		row++;

		// Alpha transition %
		JLabel alphaTransLabel = new JLabel("Alpha Transition %");
		alphaTransLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(alphaTransLabel, labelConstraints(row));
		JSpinner alphaTransSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));
		content.add(alphaTransSpinner, controlConstraints(row));
		row++;

		// Uniform colour variation
		JLabel uniformLabel = new JLabel("Uniform variation");
		uniformLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(uniformLabel, labelConstraints(row));
		JCheckBox uniformCheck = new JCheckBox("", false);
		uniformCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(uniformCheck, controlConstraints(row));
		row++;

		JLabel useSceneAmbientLabel = new JLabel("Use scene ambient light");
		useSceneAmbientLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		useSceneAmbientLabel.setToolTipText("Apply scene ambient lighting to this particle. Disable for self-lit effects like fire.");
		content.add(useSceneAmbientLabel, labelConstraints(row));
		JCheckBox useSceneAmbientCheck = new JCheckBox("", true);
		useSceneAmbientCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(useSceneAmbientCheck, controlConstraints(row));

		section.add(content, BorderLayout.NORTH);

		JLabel paletteLabel = new JLabel("Color palette");
		paletteLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		paletteLabel.setBorder(new EmptyBorder(4, 0, 2, 0));

		// Single colour bar: content swaps based on Uniform variation toggle
		JPanel preview = new JPanel() {
			@Override
			protected void paintComponent(java.awt.Graphics g) {
				super.paintComponent(g);
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
				int w = getWidth();
				int h = getHeight();
				if (w <= 0 || h <= 0) {
					return;
				}

				Color minPicked = (Color) minButton.getClientProperty("pickedColor");
				Color maxPicked = (Color) maxButton.getClientProperty("pickedColor");
				Color tgtPicked = (Color) targetButton.getClientProperty("pickedColor");
				Color min = minPicked != null ? minPicked : Color.WHITE;
				Color max = maxPicked != null ? maxPicked : Color.WHITE;
				boolean hasTarget = tgtPicked != null;
				Color tgt = tgtPicked;

				int topGap = 8;
				int bottomGap = 8;
				int barHeight = Math.max(20, h - topGap - bottomGap);
				int barY = topGap;
				int steps = 24;
				int sw = Math.max(2, w / steps);

				java.util.function.Function<Double, Color> gradient = t -> {
					double clamped = Math.max(0.0, Math.min(1.0, t));
					if (!hasTarget) {
						return lerpColor(min, max, clamped);
					}
					if (clamped < 0.5) {
						return lerpColor(min, tgt, clamped * 2.0);
					} else {
						return lerpColor(tgt, max, (clamped - 0.5) * 2.0);
					}
				};

				boolean uniform = uniformCheck.isSelected();
				if (uniform) {
					// Uniform: smooth left-to-right gradient
					for (int i = 0; i < steps; i++) {
						double t = steps == 1 ? 0.0 : (double) i / (steps - 1);
						Color c = gradient.apply(t);
						int x = i * sw;
						g2.setColor(c);
						g2.fillRect(x, barY, sw + 1, barHeight);
					}
				} else {
					// Normal: random samples across gradient
					java.util.Random rng = new java.util.Random(0);
					for (int i = 0; i < steps; i++) {
						double t = rng.nextDouble();
						Color c = gradient.apply(t);
						int x = i * sw;
						g2.setColor(c);
						g2.fillRect(x, barY, sw + 1, barHeight);
					}
				}

				g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g2.drawRect(0, barY, w - 1, barHeight);
			}
		};
		preview.setPreferredSize(new Dimension(0, 44));
		preview.setBorder(new EmptyBorder(0, 0, 0, 0));

		JPanel palettePanel = new JPanel(new BorderLayout(0, 0));
		palettePanel.setBorder(new EmptyBorder(0, 6, 8, 6));
		palettePanel.add(paletteLabel, BorderLayout.NORTH);
		palettePanel.add(preview, BorderLayout.CENTER);
		section.add(palettePanel, BorderLayout.CENTER);

		// Load from selected definition
		ItemListener loadFromDef = e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			coloursLoadingFromDefinition = true;
			try {
				ParticleDefinition.Colours c = def.colours;
				updateColorButton(minButton, isUnsetColourArgb(c.minColourArgb) ? null : argbToColor(c.minColourArgb));
				updateColorButton(maxButton, isUnsetColourArgb(c.maxColourArgb) ? null : argbToColor(c.maxColourArgb));
				boolean hasTargetColour = !isUnsetColourArgb(c.targetColourArgb);
				targetColourEnabled[0] = hasTargetColour;
				updateColorButton(targetButton, hasTargetColour ? argbToColor(c.targetColourArgb) : null);
				((CardLayout) targetColourCardPanel.getLayout()).show(targetColourCardPanel, hasTargetColour ? "control" : "enable");
				int colPct = Math.max(0, Math.min(100, c.colourTransitionPercent));
				int alphaPct = Math.max(0, Math.min(100, c.alphaTransitionPercent));
				colourTransSpinner.setValue(colPct);
				alphaTransSpinner.setValue(alphaPct);
				uniformCheck.setSelected(c.uniformColourVariation);
				useSceneAmbientCheck.setSelected(c.useSceneAmbientLight);
				preview.repaint();
			} finally {
				coloursLoadingFromDefinition = false;
			}
			SwingUtilities.invokeLater(() -> {
				targetColourCardPanel.revalidate();
				targetColourCardPanel.repaint();
			});
		};
		particleDropdown.addItemListener(loadFromDef);

		targetColourEnableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			Color gray = Color.GRAY;
			def.colours.targetColourArgb = colorToArgb(gray);
			updateColorButton(targetButton, gray);
			targetColourEnabled[0] = true;
			((CardLayout) targetColourCardPanel.getLayout()).show(targetColourCardPanel, "control");
			preview.repaint();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});
		targetColourDisableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.colours.targetColourArgb = 0;
			updateColorButton(targetButton, null);
			targetColourEnabled[0] = false;
			((CardLayout) targetColourCardPanel.getLayout()).show(targetColourCardPanel, "enable");
			preview.repaint();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});

		// Push non-colour settings (percentages + uniform checkbox) back into definition
		ChangeListener percentagesChanged = e -> {
			if (coloursLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.colours.colourTransitionPercent = ((Number) colourTransSpinner.getValue()).intValue();
			def.colours.alphaTransitionPercent = ((Number) alphaTransSpinner.getValue()).intValue();
			def.colours.uniformColourVariation = uniformCheck.isSelected();
			def.colours.useSceneAmbientLight = useSceneAmbientCheck.isSelected();
			preview.repaint();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		};
		colourTransSpinner.addChangeListener(percentagesChanged);
		alphaTransSpinner.addChangeListener(percentagesChanged);
		uniformCheck.addItemListener(e -> percentagesChanged.stateChanged(null));
		useSceneAmbientCheck.addItemListener(e -> percentagesChanged.stateChanged(null));

		// Colour pickers using RuneLite's RuneliteColorPicker
		minButton.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			final String defId = id;
			Color initial = (Color) minButton.getClientProperty("pickedColor");
			openColorPicker("Min Colour", initial != null ? initial : Color.WHITE, picked -> {
				ParticleDefinition def = particleManager.getDefinition(defId);
				if (def == null) return;
				def.colours.minColourArgb = colorToArgb(picked);
				updateColorButton(minButton, picked);
				preview.repaint();
				clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(defId));
			});
		});

		maxButton.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			final String defId = id;
			Color initial = (Color) maxButton.getClientProperty("pickedColor");
			openColorPicker("Max Colour", initial != null ? initial : Color.WHITE, picked -> {
				ParticleDefinition def = particleManager.getDefinition(defId);
				if (def == null) return;
				def.colours.maxColourArgb = colorToArgb(picked);
				updateColorButton(maxButton, picked);
				preview.repaint();
				clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(defId));
			});
		});

		targetButton.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			final String defId = id;
			Color initial = (Color) targetButton.getClientProperty("pickedColor");
			openColorPicker("Target Colour", initial != null ? initial : Color.GRAY, picked -> {
				ParticleDefinition def = particleManager.getDefinition(defId);
				if (def == null) return;
				def.colours.targetColourArgb = colorToArgb(picked);
				updateColorButton(targetButton, picked);
				preview.repaint();
				clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(defId));
			});
		});

		// Initial load for current selection
		loadFromDef.itemStateChanged(new ItemEvent(particleDropdown, ItemEvent.ITEM_STATE_CHANGED, particleDropdown.getSelectedItem(), ItemEvent.SELECTED));

		return section;
	}

	private JPanel buildPhysicsSection(JComboBox<String> particleDropdown) {
		physicsLoadingFromDefinition = false;

		JPanel section = buildTitledSection("Physics");
		JPanel content = new JPanel(new GridBagLayout());
		content.setBorder(new EmptyBorder(6, 6, 6, 6));

		int row = 0;

		JLabel clipLabel = new JLabel("Clip to terrain");
		clipLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(clipLabel, labelConstraints(row));
		JCheckBox clipCheck = new JCheckBox("", true);
		clipCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(clipCheck, controlConstraints(row));
		row++;

		JLabel collidesLabel = new JLabel("Collides with objects");
		collidesLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(collidesLabel, labelConstraints(row));
		JCheckBox collidesCheck = new JCheckBox("", false);
		collidesCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(collidesCheck, controlConstraints(row));
		row++;

		Dimension spinnerSize = new Dimension(72, 24);

		JLabel upperLabel = new JLabel("Upper bound level");
		upperLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		upperLabel.setToolTipText("-2 = no bound, -1 = current plane, 0-3 = level");
		content.add(upperLabel, labelConstraints(row));
		JSpinner upperSpinner = new JSpinner(new SpinnerNumberModel(-2, -2, 3, 1));
		upperSpinner.setPreferredSize(spinnerSize);
		upperSpinner.setMinimumSize(spinnerSize);
		content.add(upperSpinner, controlConstraints(row));
		row++;

		JLabel lowerLabel = new JLabel("Lower bound level");
		lowerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lowerLabel.setToolTipText("-2 = no bound, -1 = current plane, 0-3 = level");
		content.add(lowerLabel, labelConstraints(row));
		JSpinner lowerSpinner = new JSpinner(new SpinnerNumberModel(-2, -2, 3, 1));
		lowerSpinner.setPreferredSize(spinnerSize);
		lowerSpinner.setMinimumSize(spinnerSize);
		content.add(lowerSpinner, controlConstraints(row));
		row++;

		JLabel falloffTypeLabel = new JLabel("Distance falloff type");
		falloffTypeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		falloffTypeLabel.setToolTipText("0 = none, 1 = linear, 2 = squared");
		content.add(falloffTypeLabel, labelConstraints(row));
		JSpinner falloffTypeSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 2, 1));
		falloffTypeSpinner.setPreferredSize(spinnerSize);
		falloffTypeSpinner.setMinimumSize(spinnerSize);
		content.add(falloffTypeSpinner, controlConstraints(row));
		row++;

		JLabel falloffStrengthLabel = new JLabel("Distance falloff strength");
		falloffStrengthLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(falloffStrengthLabel, labelConstraints(row));
		JSpinner falloffStrengthSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1));
		falloffStrengthSpinner.setPreferredSize(new Dimension(80, 24));
		falloffStrengthSpinner.setMinimumSize(new Dimension(80, 24));
		content.add(falloffStrengthSpinner, controlConstraints(row));

		ItemListener loadFromDef = e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			physicsLoadingFromDefinition = true;
			try {
				ParticleDefinition.Physics ph = def.physics;
				clipCheck.setSelected(ph.clipToTerrain);
				collidesCheck.setSelected(ph.collidesWithObjects);
				upperSpinner.setValue(ph.upperBoundLevel);
				lowerSpinner.setValue(ph.lowerBoundLevel);
				falloffTypeSpinner.setValue(ph.distanceFalloffType);
				falloffStrengthSpinner.setValue(ph.distanceFalloffStrength);
			} finally {
				physicsLoadingFromDefinition = false;
			}
		};
		particleDropdown.addItemListener(loadFromDef);

		Runnable pushPhysics = () -> {
			if (physicsLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			ParticleDefinition.Physics ph = def.physics;
			ph.clipToTerrain = clipCheck.isSelected();
			ph.collidesWithObjects = collidesCheck.isSelected();
			ph.upperBoundLevel = ((Number) upperSpinner.getValue()).intValue();
			ph.lowerBoundLevel = ((Number) lowerSpinner.getValue()).intValue();
			ph.distanceFalloffType = ((Number) falloffTypeSpinner.getValue()).intValue();
			ph.distanceFalloffStrength = ((Number) falloffStrengthSpinner.getValue()).intValue();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		};
		ChangeListener physicsChanged = e -> pushPhysics.run();
		clipCheck.addItemListener(e -> pushPhysics.run());
		collidesCheck.addItemListener(e -> pushPhysics.run());
		upperSpinner.addChangeListener(physicsChanged);
		lowerSpinner.addChangeListener(physicsChanged);
		falloffTypeSpinner.addChangeListener(physicsChanged);
		falloffStrengthSpinner.addChangeListener(physicsChanged);

		loadFromDef.itemStateChanged(new ItemEvent(particleDropdown, ItemEvent.ITEM_STATE_CHANGED, particleDropdown.getSelectedItem(), ItemEvent.SELECTED));

		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildEmissionSection(JComboBox<String> particleDropdown) {
		emissionLoadingFromDefinition = false;

		JPanel section = buildTitledSection("Emission");
		JPanel content = new JPanel(new GridBagLayout());
		content.setBorder(new EmptyBorder(6, 6, 6, 10));

		Dimension smallSpinnerSize = new Dimension(72, 24);
		Dimension timeSpinnerSize = new Dimension(88, 24);
		Dimension delaySpinnerSize = new Dimension(96, 24); // slightly wider than time for M:S.mmm, still fits on panel

		int row = 0;
		final int emissionLabelMaxWidth = 85;

		// Min delay
		JLabel minDelayLabel = new JLabel("Min delay");
		minDelayLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		minDelayLabel.setToolTipText("Particle lifetime min (minutes:seconds.milliseconds)");
		minDelayLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 36));
		content.add(minDelayLabel, labelConstraints(row));
		JSpinner[] minDelaySpinnerRef = new JSpinner[1];
		content.add(createDelaySpinnerWithLabel(delaySpinnerSize, minDelaySpinnerRef), controlConstraints(row));
		JSpinner minDelayTimeSpinner = minDelaySpinnerRef[0];
		row++;

		// Max delay
		JLabel maxDelayLabel = new JLabel("Max delay");
		maxDelayLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		maxDelayLabel.setToolTipText("Particle lifetime max (minutes:seconds.milliseconds)");
		maxDelayLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 36));
		content.add(maxDelayLabel, labelConstraints(row));
		JSpinner[] maxDelaySpinnerRef = new JSpinner[1];
		content.add(createDelaySpinnerWithLabel(delaySpinnerSize, maxDelaySpinnerRef), controlConstraints(row));
		JSpinner maxDelayTimeSpinner = maxDelaySpinnerRef[0];
		row++;

		JLabel minSpawnLabel = new JLabel("Min spawn");
		minSpawnLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		minSpawnLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 24));
		content.add(minSpawnLabel, labelConstraints(row));
		JSpinner minSpawnSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 4096, 1));
		minSpawnSpinner.setPreferredSize(smallSpinnerSize);
		minSpawnSpinner.setMinimumSize(smallSpinnerSize);
		content.add(minSpawnSpinner, controlConstraints(row));
		row++;

		JLabel maxSpawnLabel = new JLabel("Max spawn");
		maxSpawnLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		maxSpawnLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 24));
		content.add(maxSpawnLabel, labelConstraints(row));
		JSpinner maxSpawnSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 4096, 1));
		maxSpawnSpinner.setPreferredSize(smallSpinnerSize);
		maxSpawnSpinner.setMinimumSize(smallSpinnerSize);
		content.add(maxSpawnSpinner, controlConstraints(row));
		row++;

		JLabel initialSpawnLabel = new JLabel("Initial spawn");
		initialSpawnLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		initialSpawnLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 24));
		content.add(initialSpawnLabel, labelConstraints(row));
		JSpinner initialSpawnSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 4096, 1));
		initialSpawnSpinner.setPreferredSize(smallSpinnerSize);
		initialSpawnSpinner.setMinimumSize(smallSpinnerSize);
		content.add(initialSpawnSpinner, controlConstraints(row));
		row++;

		// Cycle duration: when -1 show "Enable" button; when >= 0 show spinner + "Disable" button
		JLabel cycleDurationLabel = new JLabel("Cycle duration");
		cycleDurationLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cycleDurationLabel.setToolTipText("When enabled, emission repeats every this duration (-1 = always). Format: Hours:Minutes:Seconds");
		cycleDurationLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 36));
		content.add(cycleDurationLabel, labelConstraints(row));
		JPanel cycleCardPanel = new JPanel(new CardLayout());
		cycleCardPanel.setOpaque(false);
		JButton cycleEnableBtn = new JButton("Enable");
		cycleEnableBtn.setToolTipText("Enable cycle duration");
		cycleCardPanel.add(cycleEnableBtn, "enable");
		JSpinner[] cycleSpinnerRef = new JSpinner[1];
		JPanel cycleSpinnerRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		cycleSpinnerRow.setOpaque(false);
		cycleSpinnerRow.add(createTimeSpinnerWithLabel(timeSpinnerSize, cycleSpinnerRef, true));
		JButton cycleDisableBtn = new JButton("Disable");
		cycleDisableBtn.setToolTipText("Set cycle duration to -1 (disabled)");
		cycleSpinnerRow.add(cycleDisableBtn);
		cycleCardPanel.add(cycleSpinnerRow, "spinner");
		content.add(cycleCardPanel, controlConstraints(row));
		JSpinner cycleTimeSpinner = cycleSpinnerRef[0];
		final boolean[] cycleDurationEnabled = { false };
		row++;

		// Time threshold: when -1 show "Enable" button; when >= 0 show spinner + "Disable" button
		JLabel thresholdLabel = new JLabel("Time threshold");
		thresholdLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		thresholdLabel.setToolTipText("When enabled, emit only before/after this time in the cycle (-1 = disabled). Format: Hours:Minutes:Seconds");
		thresholdLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 36));
		content.add(thresholdLabel, labelConstraints(row));
		JPanel thresholdCardPanel = new JPanel(new CardLayout());
		thresholdCardPanel.setOpaque(false);
		JButton thresholdEnableBtn = new JButton("Enable");
		thresholdEnableBtn.setToolTipText("Enable time threshold");
		thresholdCardPanel.add(thresholdEnableBtn, "enable");
		JSpinner[] thresholdSpinnerRef = new JSpinner[1];
		JPanel thresholdSpinnerRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		thresholdSpinnerRow.setOpaque(false);
		thresholdSpinnerRow.add(createTimeSpinnerWithLabel(timeSpinnerSize, thresholdSpinnerRef, true));
		JButton thresholdDisableBtn = new JButton("Disable");
		thresholdDisableBtn.setToolTipText("Set time threshold to -1 (disabled)");
		thresholdSpinnerRow.add(thresholdDisableBtn);
		thresholdCardPanel.add(thresholdSpinnerRow, "spinner");
		content.add(thresholdCardPanel, controlConstraints(row));
		JSpinner thresholdTimeSpinner = thresholdSpinnerRef[0];
		final boolean[] thresholdEnabled = { false };
		row++;

		JLabel emitOnlyBeforeLabel = new JLabel("Emit only before");
		emitOnlyBeforeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emitOnlyBeforeLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 36));
		content.add(emitOnlyBeforeLabel, labelConstraints(row));
		JCheckBox emitOnlyBeforeCheck = new JCheckBox("", true);
		emitOnlyBeforeCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(emitOnlyBeforeCheck, controlConstraints(row));
		row++;

		JLabel loopLabel = new JLabel("Loop emission");
		loopLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		loopLabel.setMaximumSize(new Dimension(emissionLabelMaxWidth, 36));
		content.add(loopLabel, labelConstraints(row));
		JCheckBox loopCheck = new JCheckBox("", true);
		loopCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(loopCheck, controlConstraints(row));

		// Cycle duration: Enable -> set 1 sec and show spinner; Disable / 00:00:00 -> show Enable
		cycleEnableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			int oneSec = TICKS_PER_SECOND;
			def.emission.emissionCycleDuration = oneSec;
			((TimeSpinnerModel) cycleTimeSpinner.getModel()).setTicks(oneSec);
			cycleDurationEnabled[0] = true;
			((CardLayout) cycleCardPanel.getLayout()).show(cycleCardPanel, "spinner");
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});
		cycleDisableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.emission.emissionCycleDuration = -1;
			cycleDurationEnabled[0] = false;
			((CardLayout) cycleCardPanel.getLayout()).show(cycleCardPanel, "enable");
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});

		// Time threshold: Enable -> set 1 sec and show spinner; Disable / 00:00:00 -> show Enable
		thresholdEnableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			int oneSec = TICKS_PER_SECOND;
			def.emission.emissionTimeThreshold = oneSec;
			((TimeSpinnerModel) thresholdTimeSpinner.getModel()).setTicks(oneSec);
			thresholdEnabled[0] = true;
			((CardLayout) thresholdCardPanel.getLayout()).show(thresholdCardPanel, "spinner");
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});
		thresholdDisableBtn.addActionListener(e -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.emission.emissionTimeThreshold = -1;
			thresholdEnabled[0] = false;
			((CardLayout) thresholdCardPanel.getLayout()).show(thresholdCardPanel, "enable");
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		});

		ItemListener loadFromDef = e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			ParticleDefinition.Emission em = def.emission;
			emissionLoadingFromDefinition = true;
			try {
				((DelaySpinnerModel) minDelayTimeSpinner.getModel()).setTicks(em.minDelay);
				((DelaySpinnerModel) maxDelayTimeSpinner.getModel()).setTicks(em.maxDelay);
				minSpawnSpinner.setValue(em.minSpawn);
				maxSpawnSpinner.setValue(em.maxSpawn);
				initialSpawnSpinner.setValue(em.initialSpawn);
				boolean cycleEn = em.emissionCycleDuration > 0;
				boolean threshEn = em.emissionTimeThreshold > 0;
				cycleDurationEnabled[0] = cycleEn;
				thresholdEnabled[0] = threshEn;
				((TimeSpinnerModel) cycleTimeSpinner.getModel()).setTicks(cycleEn ? em.emissionCycleDuration : 0);
				((TimeSpinnerModel) thresholdTimeSpinner.getModel()).setTicks(threshEn ? em.emissionTimeThreshold : 0);
				((CardLayout) cycleCardPanel.getLayout()).show(cycleCardPanel, cycleEn ? "spinner" : "enable");
				((CardLayout) thresholdCardPanel.getLayout()).show(thresholdCardPanel, threshEn ? "spinner" : "enable");
				emitOnlyBeforeCheck.setSelected(em.emitOnlyBeforeTime);
				loopCheck.setSelected(em.loopEmission);
			} finally {
				emissionLoadingFromDefinition = false;
			}
			// Defer UI refresh so cycle/threshold cards and spinners update after other listeners
			SwingUtilities.invokeLater(() -> {
				cycleCardPanel.revalidate();
				cycleCardPanel.repaint();
				thresholdCardPanel.revalidate();
				thresholdCardPanel.repaint();
			});
		};
		particleDropdown.addItemListener(loadFromDef);

		Runnable pushEmission = () -> {
			if (emissionLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			ParticleDefinition.Emission em = def.emission;
			em.minDelay = ((DelaySpinnerModel) minDelayTimeSpinner.getModel()).getTicks();
			em.maxDelay = ((DelaySpinnerModel) maxDelayTimeSpinner.getModel()).getTicks();
			em.minSpawn = ((Number) minSpawnSpinner.getValue()).intValue();
			em.maxSpawn = ((Number) maxSpawnSpinner.getValue()).intValue();
			em.initialSpawn = ((Number) initialSpawnSpinner.getValue()).intValue();
			em.emissionCycleDuration = cycleDurationEnabled[0] ? ((TimeSpinnerModel) cycleTimeSpinner.getModel()).getTicks() : -1;
			em.emissionTimeThreshold = thresholdEnabled[0] ? ((TimeSpinnerModel) thresholdTimeSpinner.getModel()).getTicks() : -1;
			em.emitOnlyBeforeTime = emitOnlyBeforeCheck.isSelected();
			em.loopEmission = loopCheck.isSelected();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		};

		ChangeListener emissionChanged = e -> pushEmission.run();
		minDelayTimeSpinner.addChangeListener(emissionChanged);
		maxDelayTimeSpinner.addChangeListener(emissionChanged);
		// When spinner is set to -1 or 00:00:00 (0), switch back to Enable button
		cycleTimeSpinner.addChangeListener(e -> {
			if (((TimeSpinnerModel) cycleTimeSpinner.getModel()).getTicks() <= 0) {
				cycleDurationEnabled[0] = false;
				((CardLayout) cycleCardPanel.getLayout()).show(cycleCardPanel, "enable");
			}
			pushEmission.run();
		});
		thresholdTimeSpinner.addChangeListener(e -> {
			if (((TimeSpinnerModel) thresholdTimeSpinner.getModel()).getTicks() <= 0) {
				thresholdEnabled[0] = false;
				((CardLayout) thresholdCardPanel.getLayout()).show(thresholdCardPanel, "enable");
			}
			pushEmission.run();
		});
		minSpawnSpinner.addChangeListener(emissionChanged);
		maxSpawnSpinner.addChangeListener(emissionChanged);
		initialSpawnSpinner.addChangeListener(emissionChanged);
		emitOnlyBeforeCheck.addItemListener(e -> pushEmission.run());
		loopCheck.addItemListener(e -> pushEmission.run());

		loadFromDef.itemStateChanged(new ItemEvent(particleDropdown, ItemEvent.ITEM_STATE_CHANGED, particleDropdown.getSelectedItem(), ItemEvent.SELECTED));

		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildTextureSection(JComboBox<String> particleDropdown) {
		textureLoadingFromDefinition = false;

		JPanel section = buildTitledSection("Texture");
		JTabbedPane tabs = new JTabbedPane() {
			@Override
			public Dimension getPreferredSize() {
				Dimension superPref = super.getPreferredSize();
				int count = getTabCount();
				if (count == 0)
					return superPref;

				int maxChildHeight = 0;
				int selectedChildHeight = 0;
				java.awt.Component selected = getSelectedComponent();
				for (int i = 0; i < count; i++) {
					java.awt.Component c = getComponentAt(i);
					if (c == null)
						continue;
					Dimension cd = c.getPreferredSize();
					int h = cd != null ? cd.height : 0;
					if (h > maxChildHeight)
						maxChildHeight = h;
					if (c == selected)
						selectedChildHeight = h;
				}

				if (maxChildHeight <= 0)
					return superPref;

				int headerHeight = superPref.height - maxChildHeight;
				if (headerHeight < 0)
					headerHeight = 0;

				int newHeight = headerHeight + selectedChildHeight;
				if (newHeight > 0 && newHeight < superPref.height) {
					return new Dimension(superPref.width, newHeight);
				}
				return superPref;
			}
		};

		// --- Texture tab: file + preview ---
		JPanel textureTab = new JPanel(new GridBagLayout());
		textureTab.setBorder(new EmptyBorder(1, 1, 1, 1));
		int row = 0;

		// Row 0: Texture dropdown
		JLabel texLabel = new JLabel("Texture");
		texLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		textureTab.add(texLabel, labelConstraints(row));

		java.util.List<String> textureNames = particleManager.getAvailableTextureNames();
		// Build model: use "None" as display for empty; store "" in model for no texture
		String[] names = textureNames.toArray(new String[0]);
		JComboBox<String> textureCombo = new JComboBox<>(names);
		textureCombo.setEditable(false);
		textureCombo.setRenderer(new DefaultListCellRenderer() {
			private static final int DROP_PREVIEW_SIZE = 24;
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				String file = value == null ? "" : value.toString();
				if (file.isEmpty()) {
					setText("None");
					setIcon(null);
				} else {
					setText(file);
					ImageIcon icon = loadTexturePreview(file, DROP_PREVIEW_SIZE);
					setIcon(icon);
				}
				return this;
			}
		});
		GridBagConstraints texC = controlConstraints(row);
		textureTab.add(textureCombo, texC);
		row++;

		// Row 1: Preview area (label + panel) used by Preview tab
		JLabel previewLabel = new JLabel(null, null, JLabel.CENTER);
		previewLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel previewPanel = new JPanel(new BorderLayout());
		previewPanel.setPreferredSize(new Dimension(72, 72));
		previewPanel.setMinimumSize(new Dimension(72, 72));
		previewPanel.setBorder(new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR));
		previewPanel.add(previewLabel, BorderLayout.CENTER);

		// --- Flipbook tab: Enable + options ---
		JPanel flipbookTab = new JPanel(new BorderLayout());
		flipbookTab.setBorder(new EmptyBorder(4, 4, 4, 4));

		CardLayout flipCards = new CardLayout();
		JPanel flipCardPanel = new JPanel(flipCards);
		flipCardPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

		// Disabled card: centered Enable button
		JPanel disabledCard = new JPanel(new BorderLayout());
		JButton enableFlipbookBtn = new JButton("Enable");
		styleButton(enableFlipbookBtn);
		disabledCard.add(enableFlipbookBtn, BorderLayout.CENTER);

		// Enabled card: Cols, Rows, Mode + Disable button
		JPanel enabledCard = new JPanel(new GridBagLayout());
		GridBagConstraints fc = new GridBagConstraints();
		fc.insets = new Insets(0, 0, 0, 4);
		fc.anchor = GridBagConstraints.WEST;

		JLabel colsLbl = new JLabel("Cols");
		colsLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		fc.gridx = 0; fc.gridy = 0;
		enabledCard.add(colsLbl, fc);

		JSpinner colsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 64, 1));
		fc.gridx = 1;
		enabledCard.add(colsSpinner, fc);

		JLabel rowsLbl = new JLabel("Rows");
		rowsLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		fc.gridx = 2;
		enabledCard.add(rowsLbl, fc);

		JSpinner rowsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 64, 1));
		fc.gridx = 3;
		enabledCard.add(rowsSpinner, fc);

		JLabel modeLbl = new JLabel("Mode");
		modeLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		fc.gridx = 0; fc.gridy = 1; fc.gridwidth = 4;
		fc.insets = new Insets(6, 0, 0, 0);
		fc.fill = GridBagConstraints.NONE;
		fc.weightx = 0;
		enabledCard.add(modeLbl, fc);

		JComboBox<String> modeCombo = new JComboBox<>(new String[] { "Order", "Random" });
		modeCombo.setEditable(false);
		fc.gridx = 0; fc.gridy = 2; fc.gridwidth = 4;
		fc.insets = new Insets(2, 0, 0, 0);
		fc.fill = GridBagConstraints.HORIZONTAL;
		fc.weightx = 1;
		enabledCard.add(modeCombo, fc);

		JButton disableFlipbookBtn = new JButton("Disable");
		styleButton(disableFlipbookBtn);
		fc.gridx = 0; fc.gridy = 3; fc.gridwidth = 4;
		fc.insets = new Insets(8, 0, 0, 0);
		fc.fill = GridBagConstraints.HORIZONTAL;
		fc.weightx = 1;
		enabledCard.add(disableFlipbookBtn, fc);

		flipCardPanel.add(disabledCard, "disabled");
		flipCardPanel.add(enabledCard, "enabled");

		// Keep flipbook content hugging the left
		flipbookTab.add(flipCardPanel, BorderLayout.WEST);

		// Helpers to load current def into UI
		Runnable reloadFromDefinition = () -> {
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;

			textureLoadingFromDefinition = true;
			try {
				// Texture file
				String file = def.texture.file;
				String display = (file == null || file.isEmpty()) ? "(no texture)" : file;
				textureCombo.setSelectedItem(file != null ? file : "");
				ImageIcon icon = loadTexturePreview(file);
				if (icon != null) {
					previewLabel.setIcon(icon);
					previewLabel.setText(null);
				} else {
					previewLabel.setIcon(null);
					previewLabel.setText(display);
				}

				// Flipbook
				var fb = def.texture.flipbook;
				boolean fbEnabled = fb != null &&
					(fb.flipbookColumns > 0 || fb.flipbookRows > 0 ||
						(fb.flipbookMode != null && !fb.flipbookMode.isEmpty()));
				if (fbEnabled) {
					colsSpinner.setValue(Math.max(1, fb.flipbookColumns));
					rowsSpinner.setValue(Math.max(1, fb.flipbookRows));
					String m = fb.flipbookMode;
					if (m != null && "random".equalsIgnoreCase(m)) {
						modeCombo.setSelectedItem("Random");
					} else {
						modeCombo.setSelectedItem("Order");
					}
					flipCards.show(flipCardPanel, "enabled");
				} else {
					flipCards.show(flipCardPanel, "disabled");
				}
			} finally {
				textureLoadingFromDefinition = false;
			}
		};

		// When the selected particle changes, reload texture/flipbook UI
		particleDropdown.addItemListener(e -> {
			if (e.getStateChange() == ItemEvent.SELECTED) {
				reloadFromDefinition.run();
			}
		});

		// Push texture changes back into definition + emitters
		Runnable pushTextureToDefinition = () -> {
			if (textureLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;

			// File
			Object sel = textureCombo.getSelectedItem();
			String file = sel != null ? sel.toString().trim() : "";
			def.texture.file = file.isEmpty() ? null : file;
			ImageIcon icon = loadTexturePreview(def.texture.file);
			if (icon != null) {
				previewLabel.setIcon(icon);
				previewLabel.setText(null);
			} else {
				previewLabel.setIcon(null);
				previewLabel.setText(def.texture.file != null ? def.texture.file : "(no texture)");
			}

			// Flipbook
			var fb = def.texture.flipbook;
			if (((CardLayout) flipCardPanel.getLayout()) == flipCards) {
				// if disabled, clear flipbook; if enabled, store values
				boolean enabled = ((Number) colsSpinner.getValue()).intValue() > 0 ||
					((Number) rowsSpinner.getValue()).intValue() > 0;
				if (!enabled) {
					fb.flipbookColumns = 0;
					fb.flipbookRows = 0;
					fb.flipbookMode = null;
				} else {
					fb.flipbookColumns = ((Number) colsSpinner.getValue()).intValue();
					fb.flipbookRows = ((Number) rowsSpinner.getValue()).intValue();
					Object selMode = modeCombo.getSelectedItem();
					if (selMode == null) {
						fb.flipbookMode = null;
					} else if ("Random".equals(selMode.toString())) {
						fb.flipbookMode = "random";
					} else {
						fb.flipbookMode = "order";
					}
				}
			}

			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		};

		textureCombo.addItemListener(e -> {
			if (e.getStateChange() == ItemEvent.SELECTED) {
				pushTextureToDefinition.run();
			}
		});

		enableFlipbookBtn.addActionListener(e -> {
			if (textureLoadingFromDefinition) return;
			flipCards.show(flipCardPanel, "enabled");
			// Sensible defaults
			if (((Number) colsSpinner.getValue()).intValue() <= 0) colsSpinner.setValue(4);
			if (((Number) rowsSpinner.getValue()).intValue() <= 0) rowsSpinner.setValue(4);
			modeCombo.setSelectedItem("Order");
			pushTextureToDefinition.run();
		});

		disableFlipbookBtn.addActionListener(e -> {
			if (textureLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id != null) {
				ParticleDefinition def = particleManager.getDefinition(id);
				if (def != null) {
					var fb = def.texture.flipbook;
					fb.flipbookColumns = 0;
					fb.flipbookRows = 0;
					fb.flipbookMode = null;
					clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
				}
			}
			flipCards.show(flipCardPanel, "disabled");
			colsSpinner.setValue(1);
			rowsSpinner.setValue(1);
			modeCombo.setSelectedItem("Order");
		});

		colsSpinner.addChangeListener(e -> pushTextureToDefinition.run());
		rowsSpinner.addChangeListener(e -> pushTextureToDefinition.run());
		modeCombo.addItemListener(e -> {
			if (e.getStateChange() == ItemEvent.SELECTED) {
				pushTextureToDefinition.run();
			}
		});

		// --- Big image preview tab ---
		JPanel previewTab = new JPanel(new BorderLayout());
		previewTab.setBorder(new EmptyBorder(4, 4, 4, 4));
		previewTab.add(previewPanel, BorderLayout.CENTER);

		// Initial load for current selection
		reloadFromDefinition.run();

		// Tabs: Texture / Flipbook
		tabs.addTab("Texture", textureTab);
		tabs.addTab("Flipbook", flipbookTab);
		tabs.addTab("Preview", previewTab);

		section.add(tabs, BorderLayout.CENTER);
		return section;
	}

	private static final int MAIN_PREVIEW_SIZE = 64;

	@Nullable
	private ImageIcon loadTexturePreview(@Nullable String file) {
		return loadTexturePreview(file, MAIN_PREVIEW_SIZE);
	}

	@Nullable
	private ImageIcon loadTexturePreview(@Nullable String file, int maxSize) {
		if (file == null || file.isEmpty())
			return null;
		try {
			ResourcePath base = ParticleTextureLoader.getParticleTexturesPath();
			ResourcePath res = base.resolve(file);
			try (java.io.InputStream is = res.toInputStream()) {
				BufferedImage img = javax.imageio.ImageIO.read(is);
				if (img == null)
					return null;
				int w = img.getWidth();
				int h = img.getHeight();
				if (w > maxSize || h > maxSize) {
					float scale = Math.min((float) maxSize / w, (float) maxSize / h);
					int nw = Math.max(1, Math.round(w * scale));
					int nh = Math.max(1, Math.round(h * scale));
					BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = scaled.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, nw, nh, null);
					g2.dispose();
					img = scaled;
				}
				return new ImageIcon(img);
			}
		} catch (Exception ex) {
			log.warn("[Particles] Failed to load texture preview for {}", file, ex);
			return null;
		}
	}

	private static JPanel buildTitledSection(String title) {
		JPanel section = new JPanel();
		section.setLayout(new BorderLayout(0, 0));
		TitledBorder titledBorder = new TitledBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
			title,
			TitledBorder.LEFT,
			TitledBorder.TOP);
		titledBorder.setTitleColor(ColorScheme.LIGHT_GRAY_COLOR);
		titledBorder.setTitleFont(titledBorder.getTitleFont().deriveFont(Font.BOLD));
		section.setBorder(titledBorder);
		return section;
	}

	private static Color lerpColor(Color a, Color b, double t) {
		double clamped = Math.max(0.0, Math.min(1.0, t));
		int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * clamped);
		int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * clamped);
		int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * clamped);
		return new Color(r, g, bl);
	}

	private static Color safeColor(Object c, Color fallback) {
		return c instanceof Color ? (Color) c : fallback;
	}

	private static String colorToHex(Color c) {
		if (c == null) return "#000000";
		return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
	}

	/** Treat 0 or 0xFFFFFFFF as "colour not set". */
	private static boolean isUnsetColourArgb(int argb) {
		return argb == 0 || argb == -1;
	}

	/** Set button to swatch + hex, or normal "Set color" when no colour is set (null). */
	private static void updateColorButton(JButton btn, Color c) {
		btn.putClientProperty("pickedColor", c);
		if (c == null) {
			btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			btn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			btn.setText("Set color");
		} else {
			btn.setBackground(c);
			btn.setText(colorToHex(c));
			double luminance = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
			btn.setForeground(luminance < 0.5 ? Color.WHITE : Color.BLACK);
		}
	}

	private static int colorToArgb(Color c) {
		if (c == null) {
			return 0;
		}
		return (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
	}

	private static Color argbToColor(int argb) {
		if (argb == 0) {
			return Color.WHITE;
		}
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		return new Color(r, g, b, a);
	}

	private void openColorPicker(String title, Color initial, java.util.function.Consumer<Color> onPicked) {
		if (client == null || colorPickerManager == null) {
			log.warn("Color picker not available: client or colorPickerManager not injected");
			return;
		}
		SwingUtilities.invokeLater(() -> {
			RuneliteColorPicker picker = colorPickerManager.create(
				client,
				initial,
				title,
				true
			);
			picker.setOnClose(col -> {
				if (col != null) {
					onPicked.accept(col);
				}
			});
			picker.setVisible(true);
		});
	}

	private JPanel buildGeneralSectionInner(JComboBox<String> particleDropdown) {
		JPanel section = buildTitledSection("General");
		JPanel content = new JPanel(new GridBagLayout());
		// Match margins used by other sections so everything lines up visually
		content.setBorder(new EmptyBorder(6, 6, 6, 10));

		ParticleDefinition.General defaults = new ParticleDefinition.General();
		int row = 0;

		JSpinner yawSpinner = new JSpinner(new SpinnerNumberModel(defaults.getDirectionYaw(), 0, 2048, 10));
		JSpinner pitchSpinner = new JSpinner(new SpinnerNumberModel(defaults.getDirectionPitch(), 0, 1024, 10));

		GridBagConstraints gizmoC = new GridBagConstraints();
		gizmoC.gridx = 0;
		gizmoC.gridy = row;
		gizmoC.gridwidth = 2;
		gizmoC.fill = GridBagConstraints.HORIZONTAL;
		gizmoC.weightx = 1;
		gizmoC.anchor = GridBagConstraints.CENTER;
		gizmoC.insets = new Insets(4, 0, 4, 0);
		directionGizmo = new DirectionGizmoPanel(yawSpinner, pitchSpinner);
		content.add(directionGizmo, gizmoC);
		row++;

		// Presets: Yaw and Pitch combo boxes on one row
		int[] yawPresets = { 0, 256, 512, 768, 1024, 1280, 1536, 1792 };
		String[] yawLabels = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
		int[] pitchPresets = { 0, 512, 1024 };
		String[] pitchLabels = { "Up", "Side", "Down" };

		JPanel presetsRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
		JLabel yawPresetLbl = new JLabel("Yaw:");
		yawPresetLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		presetsRow.add(yawPresetLbl);
		JComboBox<String> yawPresetCombo = new JComboBox<>(yawLabels);
		yawPresetCombo.setMaximumRowCount(yawLabels.length);
		// Keep width minimal so Pitch dropdown fits on same row
		int comboH = yawPresetCombo.getPreferredSize().height;
		yawPresetCombo.setPreferredSize(new Dimension(48, comboH));
		yawPresetCombo.setMaximumSize(new Dimension(48, comboH));
		yawPresetCombo.addItemListener(e -> {
			if (e.getStateChange() == ItemEvent.SELECTED) {
				int idx = yawPresetCombo.getSelectedIndex();
				if (idx >= 0 && idx < yawPresets.length)
					yawSpinner.setValue(yawPresets[idx]);
			}
		});
		presetsRow.add(yawPresetCombo);
		JLabel pitchPresetLbl = new JLabel("Pitch:");
		pitchPresetLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		presetsRow.add(pitchPresetLbl);
		JComboBox<String> pitchPresetCombo = new JComboBox<>(pitchLabels);
		pitchPresetCombo.setMaximumRowCount(pitchLabels.length);
		pitchPresetCombo.addItemListener(e -> {
			if (e.getStateChange() == ItemEvent.SELECTED) {
				int idx = pitchPresetCombo.getSelectedIndex();
				if (idx >= 0 && idx < pitchPresets.length)
					pitchSpinner.setValue(pitchPresets[idx]);
			}
		});
		presetsRow.add(pitchPresetCombo);

		GridBagConstraints presetsC = new GridBagConstraints();
		presetsC.gridx = 0;
		presetsC.gridy = row;
		presetsC.gridwidth = 2;
		presetsC.anchor = GridBagConstraints.CENTER;
		presetsC.insets = new Insets(2, 0, 4, 0);
		content.add(presetsRow, presetsC);
		row++;

		GridBagConstraints sepC = new GridBagConstraints();
		sepC.gridx = 0;
		sepC.gridy = row;
		sepC.gridwidth = 2;
		sepC.fill = GridBagConstraints.HORIZONTAL;
		sepC.weightx = 1;
		sepC.insets = new Insets(4, 0, 6, 0);
		JPanel sepPanel = new JPanel();
		sepPanel.setPreferredSize(new Dimension(0, 1));
		sepPanel.setMinimumSize(new Dimension(0, 1));
		sepPanel.setBorder(new MatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		content.add(sepPanel, sepC);
		row++;

		JLabel yawLbl = new JLabel("Base Yaw");
		yawLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(yawLbl, labelConstraints(row));
		content.add(yawSpinner, controlConstraints(row));
		row++;

		JLabel pitchLbl = new JLabel("Base Pitch");
		pitchLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(pitchLbl, labelConstraints(row));
		content.add(pitchSpinner, controlConstraints(row));
		row++;

		JLabel heightLbl = new JLabel("Height Offset");
		heightLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(heightLbl, labelConstraints(row));
		JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(defaults.getHeightOffset(), Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
		content.add(heightSpinner, controlConstraints(row));
		row++;

		JLabel culledLbl = new JLabel("Display When Culled");
		culledLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(culledLbl, labelConstraints(row));
		JCheckBox culledCheck = new JCheckBox("", defaults.isDisplayWhenCulled());
		culledCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(culledCheck, controlConstraints(row));

		// Load from selected definition when dropdown changes
		ItemListener loadFromDef = e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			generalLoadingFromDefinition = true;
			try {
				ParticleDefinition.General g = def.general;
				heightSpinner.setValue(g.getHeightOffset());
				int uiYaw = gameToUiYaw(g.getDirectionYaw());
				int pitch = Math.min(1024, Math.max(0, g.getDirectionPitch()));
				yawSpinner.setValue(uiYaw);
				pitchSpinner.setValue(pitch);
				yawPresetCombo.setSelectedIndex(Math.min(7, (uiYaw + 128) / 256 % 8));
				pitchPresetCombo.setSelectedIndex(pitch <= 256 ? 0 : (pitch <= 768 ? 1 : 2));
				culledCheck.setSelected(g.isDisplayWhenCulled());
			} finally {
				generalLoadingFromDefinition = false;
			}
		};
		particleDropdown.addItemListener(loadFromDef);

		// Push General to definition and apply to game (test emitter) when controls change
		Runnable pushToGame = () -> {
			if (generalLoadingFromDefinition) return;
			String id = (String) particleDropdown.getSelectedItem();
			if (id == null) return;
			ParticleDefinition def = particleManager.getDefinition(id);
			if (def == null) return;
			def.general.heightOffset = (Integer) heightSpinner.getValue();
			def.general.directionYaw = uiToGameYaw((Integer) yawSpinner.getValue());
			def.general.directionPitch = (Integer) pitchSpinner.getValue();
			def.general.displayWhenCulled = culledCheck.isSelected();
			clientThread.invoke(() -> particleManager.applyDefinitionToEmittersWithId(id));
		};
		ChangeListener changePush = e -> pushToGame.run();
		heightSpinner.addChangeListener(changePush);
		yawSpinner.addChangeListener(changePush);
		pitchSpinner.addChangeListener(changePush);
		culledCheck.addItemListener(e -> pushToGame.run());

		// Initial load from default selection ("7")
		loadFromDef.itemStateChanged(new ItemEvent(particleDropdown, ItemEvent.ITEM_STATE_CHANGED, particleDropdown.getSelectedItem(), ItemEvent.SELECTED));

		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private static JPanel buildDefinitionSection(String title, String[] subHeadings, String[][] contentLines) {
		JPanel section = new JPanel();
		section.setLayout(new BorderLayout(0, 0));
		TitledBorder titledBorder = new TitledBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
			title,
			TitledBorder.LEFT,
			TitledBorder.TOP);
		titledBorder.setTitleColor(ColorScheme.LIGHT_GRAY_COLOR);
		titledBorder.setTitleFont(titledBorder.getTitleFont().deriveFont(Font.BOLD));
		section.setBorder(titledBorder);

		// Content: sub-headings with indented content lines
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(new EmptyBorder(1, 1, 1, 1));
		int lineIndent = 20;
		for (int i = 0; i < subHeadings.length; i++) {
			JLabel subLabel = new JLabel(subHeadings[i]);
			subLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			subLabel.setBorder(new EmptyBorder(6, 0, 2, 0));
			content.add(subLabel);
			String[] lines = i < contentLines.length ? contentLines[i] : new String[0];
			for (String line : lines) {
				JLabel lineLabel = new JLabel(line);
				lineLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				lineLabel.setBorder(new EmptyBorder(1, lineIndent, 1, 0));
				content.add(lineLabel);
			}
		}
		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private static void styleButton(JButton btn) {
		btn.setFocusPainted(false);
		btn.setBorderPainted(true);
		btn.setContentAreaFilled(true);
		btn.setOpaque(true);
	}

	private static void setButtonActive(JButton btn, boolean active) {
		if (active) {
			btn.setBackground(ACTIVE_BUTTON_GREEN);
			btn.setForeground(Color.WHITE);
		} else {
			btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			btn.setForeground(Color.WHITE);
		}
	}

	private JPanel buildDebugPanel() {
		JPanel p = new JPanel();
		p.setLayout(new BorderLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(new EmptyBorder(15, 15, 15, 15));

		JPanel buttons = new JPanel();
		buttons.setLayout(new GridLayout(0, 1, 0, 4));

		JButton gizmoOverlayBtn = new JButton("Particle gizmo overlay");
		gizmoOverlayBtn.setToolTipText("Show emitter gizmos in-game. Right-click a tile with emitters for menu options.");
		styleButton(gizmoOverlayBtn);
		gizmoOverlayBtn.addActionListener(e -> {
			gizmoOverlayActive = !gizmoOverlayActive;
			particleGizmoOverlay.setActive(gizmoOverlayActive);
			setButtonActive(gizmoOverlayBtn, gizmoOverlayActive);
		});
		buttons.add(gizmoOverlayBtn);

		JButton testParticlesBtn = new JButton("Test particles");
		styleButton(testParticlesBtn);
		testParticlesBtn.addActionListener(e -> {
			clientThread.invoke(() -> {
				if (particleManager.hasPerformanceTestEmitters()) {
					particleManager.despawnPerformanceTestEmitters();
					setButtonActive(testParticlesBtn, false);
					testParticlesBtn.setText("Test particles");
				} else {
					particleManager.spawnPerformanceTestEmitters();
					setButtonActive(testParticlesBtn, true);
					testParticlesBtn.setText("Despawn Test Particles");
				}
			});
		});
		buttons.add(testParticlesBtn);

		JButton spawn4096Btn = new JButton("Spawn 4096 particles");
		spawn4096Btn.setToolTipText("Toggle continuous spawning of particles around the player (maintains ~4096 until turned off)");
		styleButton(spawn4096Btn);
		boolean initialSpawn = particleManager.isContinuousRandomSpawn();
		setButtonActive(spawn4096Btn, initialSpawn);
		spawn4096Btn.setText(initialSpawn ? "Stop spawning" : "Spawn 4096 particles");
		spawn4096Btn.addActionListener(e -> {
			clientThread.invoke(() -> {
				boolean on = !particleManager.isContinuousRandomSpawn();
				particleManager.setContinuousRandomSpawn(on);
				setButtonActive(spawn4096Btn, on);
				spawn4096Btn.setText(on ? "Stop spawning" : "Spawn 4096 particles");
			});
		});
		buttons.add(spawn4096Btn);

		p.add(buttons, BorderLayout.NORTH);
		return p;
	}

	/** JPanel that implements Scrollable so the view tracks viewport width and does not expand horizontally. */
	private static class ScrollablePanel extends JPanel implements Scrollable {
		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}
		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 16;
		}
		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return orientation == javax.swing.SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
		}
		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}
	}
}
