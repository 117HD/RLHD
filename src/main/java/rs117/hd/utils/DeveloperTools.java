package rs117.hd.utils;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.overlays.FrameTimerOverlay;
import rs117.hd.overlays.LightGizmoOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.profiling.Profiler;
import rs117.hd.scene.GamevalManager;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class DeveloperTools implements KeyListener {
	public static final float[] COLOR_PICKER = new float[4]; // non-linear sRGB & alpha
	public static final float[] COLOR_PICKER_LINEAR = new float[4]; // linear sRGB, non-linear alpha
	private static final Keybind KEY_COLOR_PICKER = new Keybind(KeyEvent.VK_P, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);
	public static Keybind KEY_TOGGLE_TILE_INFO = new Keybind(KeyEvent.VK_F3, CTRL_DOWN_MASK);
	public static Keybind KEY_TOGGLE_HIGHLIGHT = new Keybind(KeyEvent.VK_H, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);
	public static Keybind KEY_TOGGLE_FRAME_TIMINGS = new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK);
	public static Keybind KEY_RECORD_TIMINGS_SNAPSHOT = new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);
	public static Keybind KEY_TOGGLE_SHADOW_MAP_OVERLAY = new Keybind(KeyEvent.VK_F5, CTRL_DOWN_MASK);
	public static Keybind KEY_TOGGLE_LIGHT_GIZMO_OVERLAY = new Keybind(KeyEvent.VK_F6, CTRL_DOWN_MASK);
	public static Keybind KEY_TOGGLE_TILED_LIGHTING_OVERLAY = new Keybind(KeyEvent.VK_F7, CTRL_DOWN_MASK);
	public static Keybind KEY_TOGGLE_FREEZE_FRAME = new Keybind(KeyEvent.VK_ESCAPE, SHIFT_DOWN_MASK);
	public static Keybind KEY_TOGGLE_ORTHOGRAPHIC = new Keybind(KeyEvent.VK_TAB, SHIFT_DOWN_MASK);
	public static Keybind KEY_TOGGLE_HIDE_UI = new Keybind(KeyEvent.VK_H, CTRL_DOWN_MASK);
	public static Keybind KEY_RELOAD_SCENE = new Keybind(KeyEvent.VK_R, CTRL_DOWN_MASK);

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ColorPickerManager colorPickerManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private TileInfoOverlay tileInfoOverlay;

	@Inject
	private FrameTimerOverlay frameTimerOverlay;

	@Inject
	private ShadowMapOverlay shadowMapOverlay;

	@Inject
	private LightGizmoOverlay lightGizmoOverlay;

	@Inject
	private TiledLightingOverlay tiledLightingOverlay;

	@Inject
	private FrameTimingsRecorder frameTimingsRecorder;

	@Inject
	private Profiler profiler;

	@Getter
	private boolean developerPluginActive;
	private RuneliteColorPicker colorPicker;
	@Setter
	private Runnable togglesChangedListener = () -> {};
	@Setter
	private Consumer<Boolean> profilerOnChange = on -> {};

	@Getter
	private final List<Toggle> toggles = new ArrayList<>();
	@Getter
	private final List<Action> actions = new ArrayList<>();
	@Getter
	private final List<Choice> choices = new ArrayList<>();

	private final Toggle tileInfo = addToggle(
		"Tile info",
		() -> KEY_TOGGLE_TILE_INFO, on -> tileInfoOverlay.setActive(on),
		"tileinfo"
	);
	private final Toggle highlightModelOverrides = addToggle(
		"Highlight model overrides", () -> KEY_TOGGLE_HIGHLIGHT,
		on -> {
			postMessage((on ? "Enabled" : "Disabled") + " Model Override Highlighter");
			plugin.recompilePrograms();
		},
		"highlight"
	);

	private final Toggle frameTimings = addToggle(
		"Frame timings (managed by profiler)",
		() -> KEY_TOGGLE_FRAME_TIMINGS,
		on -> frameTimerOverlay.setActive(on && !developerPluginActive),
		"timers", "timings"
	).availableWhen(() -> !developerPluginActive);

	private final Toggle showProfiler = addToggle(
		"Show profiler",
		null,
		on -> profilerOnChange.accept(on)
	).availableWhen(() -> developerPluginActive);

	private final Toggle shadowMap = addToggle(
		"Shadow map overlay",
		() -> KEY_TOGGLE_SHADOW_MAP_OVERLAY,
		on -> shadowMapOverlay.setActive(on),
		"shadowmap"
	);

	private final Toggle lightGizmo = addToggle(
		"Light gizmo overlay",
		() -> KEY_TOGGLE_LIGHT_GIZMO_OVERLAY,
		on -> lightGizmoOverlay.setActive(on),
		"lights"
	);

	private final Toggle tiledLighting = addToggle(
		"Tiled lighting overlay",
		() -> KEY_TOGGLE_TILED_LIGHTING_OVERLAY,
		on -> tiledLightingOverlay.setActive(on),
		"tiledlights", "tiledlighting"
	);

	private final Toggle freezeCulling = addToggle(
		"Freeze culling",
		null,
		on -> plugin.freezeCulling = on,
		"culling"
	);

	private final Toggle orthographic = addToggle(
		"Orthographic projection",
		() -> KEY_TOGGLE_ORTHOGRAPHIC,
		on -> plugin.orthographicProjection = on
	);

	private final Toggle hideUi = addToggle("Hide UI", () -> KEY_TOGGLE_HIDE_UI, on -> {});

	private final Toggle keyBindings = addToggle(
		"Keybindings", null,
		on -> {
			if (on)
				keyManager.registerKeyListener(this);
			else
				keyManager.unregisterKeyListener(this);
		},
		"keybinds", "keybindings"
	);

	{
		addAction("Reload scene", () -> clientThread.invoke(() -> plugin.renderer.reloadScene()));
		addAction("Toggle color picker", this::toggleColorPicker);
	}

	public Toggle addToggle(
		String name,
		@Nullable Supplier<Keybind> keybind,
		Consumer<Boolean> onChange,
		String... commands
	) {
		Toggle toggle = new Toggle(name, keybind, onChange, commands);
		toggles.add(toggle);
		return toggle;
	}

	public Action addAction(String name, Runnable runnable) {
		Action action = new Action(name, runnable);
		actions.add(action);
		return action;
	}

	public Choice addChoice(String name, List<String> options, Supplier<String> value, Consumer<String> onChange) {
		Choice choice = new Choice(name, options, value, onChange);
		choices.add(choice);
		return choice;
	}

	public boolean isHighlightModelOverridesEnabled() {
		return highlightModelOverrides.isEnabled();
	}

	public boolean isHideUiEnabled() {
		return hideUi.isEnabled();
	}

	public void setKeyBindingsEnabled(boolean enabled) {
		keyBindings.setEnabled(enabled);
	}

	public boolean isProfilerEnabled() {
		return showProfiler.isEnabled();
	}

	public void setProfilerEnabled(boolean enabled) {
		showProfiler.setEnabled(enabled);
	}

	public void setDeveloperPluginActive(boolean active) {
		developerPluginActive = active;
		profiler.setEnableDetailedTimers(active);
		frameTimings.refresh();
		togglesChangedListener.run();
	}

	public void activate() {
		eventBus.register(this);

		// Enable 117 HD's keybindings by default during development
		if (Props.DEVELOPMENT)
			keyBindings.setEnabled(true);
	}

	public void deactivate() {
		eventBus.unregister(this);

		for (Toggle toggle : toggles)
			if (toggle != highlightModelOverrides && toggle != showProfiler)
				toggle.setEnabled(false);
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted) {
		if (!commandExecuted.getCommand().equalsIgnoreCase("117hd"))
			return;

		String[] args = commandExecuted.getArguments();
		if (args.length < 1)
			return;

		String action = args[0].toLowerCase();

		for (Toggle toggle : toggles) {
			if (toggle.getCommands().contains(action)) {
				toggle.toggle();
				return;
			}
		}

		switch (action) {
			case "snapshot":
				frameTimingsRecorder.recordSnapshot();
				break;
			case "reload":
				plugin.renderer.reloadScene();
				break;
			case "colorpicker":
				toggleColorPicker();
				break;
			case "latlon":
				handleLatLonCommand(args);
				break;
			case "varbit":
			case "varp":
				// Gated behind RuneLite's --developer-mode
				if (developerMode)
					handleVarCommand(action, args);
				break;
		}
	}

	private void handleLatLonCommand(String[] args) {
		if (args.length == 1) {
			String current = config.preciseLatLon();
			if (current.isEmpty())
				current = config.latitudeDegrees() + "," + config.longitudeDegrees() + " (from config panel)";
			postMessage("Current latitude & longitude: " + current);
		} else if (args.length == 2 && (args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("clear"))) {
			config.setPreciseLatLon("");
			postMessage("Reset latitude & longitude coordinates");
		} else if (args.length == 3) {
			float[] latLon = HDUtils.parseLatLon(args[1] + "," + args[2]);
			if (latLon == null) {
				postMessage("Latitude & longitude must be decimal numbers, within ±90 and ±180 degrees respectively");
				return;
			}

			config.setPreciseLatLon(latLon[0] + "," + latLon[1]);
			postMessage(
				"Changed latitude & longitude to: " + latLon[0] + "," + latLon[1] + ". Note, this will not show up in the config panel.");
		} else {
			postMessage("Usage: ::117hd latlon <lt>latitude<gt> <lt>longitude<gt> / reset");
		}
	}

	private void handleVarCommand(String type, String[] args) {
		if (args.length != 2 && args.length != 3) {
			postMessage("Usage: ::117hd " + type + " <name|id> [value]");
			return;
		}

		boolean varbit = type.equals("varbit");
		String nameOrId = args[1].toUpperCase();
		Integer id;
		try {
			id = Integer.parseInt(nameOrId);
		} catch (NumberFormatException ignored) {
			try (var gamevals = gamevalManager.obtainHandle()) {
				id = varbit ? gamevals.getVarbits().get(nameOrId) : gamevals.getVarps().get(nameOrId);
			}
		}
		if (id == null) {
			postMessage("Unknown " + type + ": " + nameOrId);
			return;
		}

		int[] varps = client.getVarps();
		if (args.length == 2) {
			int value = varbit ? client.getVarbitValue(varps, id) : varps[id];
			postMessage(type + " " + nameOrId + " (" + id + ") = " + value);
			return;
		}

		int value;
		try {
			value = Integer.parseInt(args[2]);
		} catch (NumberFormatException e) {
			postMessage("Invalid value: " + args[2]);
			return;
		}

		VarbitChanged changed = new VarbitChanged();
		changed.setValue(value);
		if (varbit) {
			client.setVarbitValue(varps, id, value);
			client.queueChangedVarp(client.getVarbit(id).getIndex());
			changed.setVarbitId(id);
		} else {
			varps[id] = value;
			client.queueChangedVarp(id);
			changed.setVarpId(id);
		}
		eventBus.post(changed);
		postMessage("Set " + type + " " + nameOrId + " (" + id + ") = " + value);
	}

	public void toggleColorPicker() {
		plugin.uboGlobal.colorPicker.set(1, 1, 1, 1);
		if (colorPicker != null) {
			colorPicker.setVisible(false);
			colorPicker = null;
			return;
		}

		colorPicker = colorPickerManager.create(client, Color.WHITE, "Shader Color Picker", false);
		colorPicker.setLocationRelativeTo(client.getCanvas());
		colorPicker.setOnColorChange(c -> clientThread.invoke(() -> {
			float[] srgb = ColorUtils.srgb(c);
			float alpha = c.getAlpha() / 255.f;
			copyTo(COLOR_PICKER, srgb);
			copyTo(COLOR_PICKER_LINEAR, ColorUtils.srgbToLinear(srgb));
			COLOR_PICKER_LINEAR[3] = COLOR_PICKER[3] = alpha;
			plugin.uboGlobal.colorPicker.set(COLOR_PICKER_LINEAR);
			togglesChangedListener.run(); // let the panel update its swatch
		}));
		colorPicker.setOnClose(e -> colorPicker = null);
		colorPicker.setVisible(true);
	}

	private void postMessage(String message) {
		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"117 HD",
			"<col=006600>[117 HD] " + message + "</col>",
			"117 HD"
		));
	}

	@Override
	public void keyTyped(KeyEvent e) {}

	@Override
	public void keyPressed(KeyEvent e) {
		for (Toggle toggle : toggles) {
			Keybind keybind = toggle.getKeybind() == null ? null : toggle.getKeybind().get();
			if (keybind != null && toggle.isAvailable() && keybind.matches(e)) {
				toggle.toggle();
				e.consume();
				return;
			}
		}

		if (KEY_RECORD_TIMINGS_SNAPSHOT.matches(e)) {
			frameTimingsRecorder.recordSnapshot();
		} else if (KEY_TOGGLE_FREEZE_FRAME.matches(e)) {
			plugin.toggleFreezeFrame();
		} else if (KEY_RELOAD_SCENE.matches(e)) {
			plugin.renderer.reloadScene();
		} else if (KEY_COLOR_PICKER.matches(e)) {
			toggleColorPicker();
		} else {
			return;
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e) {}

	@Getter
	public class Toggle {
		private final String name;
		@Nullable
		private final Supplier<Keybind> keybind;
		private final List<String> commands;
		private final Consumer<Boolean> onChange;
		private boolean enabled;
		@Nullable
		private BooleanSupplier available;

		private Toggle(String name, @Nullable Supplier<Keybind> keybind, Consumer<Boolean> onChange, String... commands) {
			this.name = name;
			this.keybind = keybind;
			this.onChange = onChange;
			this.commands = List.of(commands);
		}

		private Toggle availableWhen(BooleanSupplier available) {
			this.available = available;
			return this;
		}

		public boolean isAvailable() {
			return available == null || available.getAsBoolean();
		}

		public void setEnabled(boolean enabled) {
			if (this.enabled == enabled)
				return;
			this.enabled = enabled;
			refresh();
			togglesChangedListener.run();
		}

		private void refresh() { onChange.accept(enabled); }

		public void toggle() {
			if (isAvailable())
				setEnabled(!enabled);
		}
	}

	@Getter
	public class Action {
		private final String name;
		private final Runnable runnable;

		private Action(String name, Runnable runnable) {
			this.name = name;
			this.runnable = runnable;
		}

		public void run() {
			runnable.run();
		}
	}

	@Getter
	public class Choice {
		private final String name;
		private final List<String> options;
		private final Supplier<String> valueSupplier;
		private final Consumer<String> onChange;

		private Choice(String name, List<String> options, Supplier<String> valueSupplier, Consumer<String> onChange) {
			this.name = name;
			this.options = options;
			this.valueSupplier = valueSupplier;
			this.onChange = onChange;
		}

		public String getValue() {
			return valueSupplier.get();
		}

		public void setValue(String value) {
			onChange.accept(value);
		}
	}
}