package rs117.hd.utils;

import java.awt.Color;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Named;
import lombok.Getter;
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
import rs117.hd.overlays.FrameTimerOverlay;
import rs117.hd.overlays.LightGizmoOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.scene.GamevalManager;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;

@Slf4j
public class DeveloperTools implements KeyListener {
	// This could be part of the config if we had developer mode config sections
	private static final Keybind KEY_TOGGLE_TILE_INFO = new Keybind(KeyEvent.VK_F3, CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_FRAME_TIMINGS = new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK);
	private static final Keybind KEY_RECORD_TIMINGS_SNAPSHOT = new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_SHADOW_MAP_OVERLAY = new Keybind(KeyEvent.VK_F5, CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_LIGHT_GIZMO_OVERLAY = new Keybind(KeyEvent.VK_F6, CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_TILED_LIGHTING_OVERLAY = new Keybind(KeyEvent.VK_F7, CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_FREEZE_FRAME = new Keybind(KeyEvent.VK_ESCAPE, SHIFT_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_ORTHOGRAPHIC = new Keybind(KeyEvent.VK_TAB, SHIFT_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_HIDE_UI = new Keybind(KeyEvent.VK_H, CTRL_DOWN_MASK);
	private static final Keybind KEY_RELOAD_SCENE = new Keybind(KeyEvent.VK_R, CTRL_DOWN_MASK);

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
	private GamevalManager gamevalManager;

	@Inject
	private TileInfoOverlay tileInfoOverlay;

	@Inject
	private FrameTimerOverlay frameTimerOverlay;

	@Inject
	private FrameTimingsRecorder frameTimingsRecorder;

	@Inject
	private ShadowMapOverlay shadowMapOverlay;

	@Inject
	private LightGizmoOverlay lightGizmoOverlay;

	@Inject
	private TiledLightingOverlay tiledLightingOverlay;

	private boolean keyBindingsEnabled;
	private boolean tileInfoOverlayEnabled;
	@Getter
	private boolean frameTimingsOverlayEnabled;
	private boolean shadowMapOverlayEnabled;
	private boolean lightGizmoOverlayEnabled;
	@Getter
	private boolean hideUiEnabled;
	private boolean tiledLightingOverlayEnabled;

	private RuneliteColorPicker colorPicker;

	public void activate() {
		// Listen for commands
		eventBus.register(this);

		// Don't do anything else unless we're in the development environment
		if (!Props.DEVELOPMENT)
			return;

		// Enable 117 HD's keybindings by default during development
		keyBindingsEnabled = true;
		keyManager.registerKeyListener(this);

		clientThread.invokeLater(() -> {
			tileInfoOverlay.setActive(tileInfoOverlayEnabled);
			frameTimerOverlay.setActive(frameTimingsOverlayEnabled);
			shadowMapOverlay.setActive(shadowMapOverlayEnabled);
			lightGizmoOverlay.setActive(lightGizmoOverlayEnabled);
			tiledLightingOverlay.setActive(tiledLightingOverlayEnabled);
		});
	}

	public void deactivate() {
		eventBus.unregister(this);
		keyManager.unregisterKeyListener(this);
		tileInfoOverlay.setActive(false);
		frameTimerOverlay.setActive(false);
		shadowMapOverlay.setActive(false);
		lightGizmoOverlay.setActive(false);
		tiledLightingOverlay.setActive(false);
		hideUiEnabled = false;
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted) {
		if (!commandExecuted.getCommand().equalsIgnoreCase("117hd"))
			return;

		String[] args = commandExecuted.getArguments();
		if (args.length < 1)
			return;

		String action = args[0].toLowerCase();
		switch (action) {
			case "tileinfo":
				tileInfoOverlay.setActive(tileInfoOverlayEnabled = !tileInfoOverlayEnabled);
				break;
			case "timers":
			case "timings":
				frameTimerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
				break;
			case "snapshot":
				frameTimingsRecorder.recordSnapshot();
				break;
			case "shadowmap":
				shadowMapOverlay.setActive(shadowMapOverlayEnabled = !shadowMapOverlayEnabled);
				break;
			case "lights":
				lightGizmoOverlay.setActive(lightGizmoOverlayEnabled = !lightGizmoOverlayEnabled);
				break;
			case "tiledlights":
			case "tiledlighting":
				tiledLightingOverlay.setActive(tiledLightingOverlayEnabled = !tiledLightingOverlayEnabled);
				break;
			case "keybinds":
			case "keybindings":
				keyBindingsEnabled = !keyBindingsEnabled;
				if (keyBindingsEnabled) {
					keyManager.registerKeyListener(this);
				} else {
					keyManager.unregisterKeyListener(this);
				}
				break;
			case "reload":
				plugin.renderer.reloadScene();
				break;
			case "culling":
				plugin.freezeCulling = !plugin.freezeCulling;
				break;
			case "colorpicker":
				toggleColorPicker();
				break;
		}

		if (!developerMode)
			return;

		switch (action) {
			case "varbit":
			case "varp":
				handleVarCommand(action, args);
				break;
		}
	}

	private void handleVarCommand(String type, String[] args) {
		assert client.isClientThread();
		String usage = "Usage: ::117hd " + type + " <name|id> [value]";
		if (args.length != 2 && args.length != 3) {
			postMessage(usage);
			return;
		}

		String nameOrId = args[1].toUpperCase();
		Integer id;
		try {
			id = Integer.parseInt(nameOrId);
		} catch (NumberFormatException ignored) {
			try (var gamevals = gamevalManager.obtainHandle()) {
				switch (type) {
					case "varbit":
						id = gamevals.getVarbits().get(nameOrId);
						break;
					case "varp":
						id = gamevals.getVarps().get(nameOrId);
						break;
					default:
						throw new IllegalStateException("Unhandled variable kind: " + type);
				}
			}
		}
		if (id == null) {
			postMessage("Unknown " + type + ": " + nameOrId);
			return;
		}

		int[] varps = client.getVarps();
		if (args.length == 2) {
			int value;
			switch (type) {
				case "varbit":
					value = client.getVarbitValue(varps, id);
					break;
				case "varp":
					value = varps[id];
					break;
				default:
					throw new IllegalStateException("Unhandled variable kind: " + type);
			}
			postMessage(type + " " + nameOrId + " (" + id + ") = " + value);
			return;
		}

		final int value;
		try {
			value = Integer.parseInt(args[2]);
		} catch (NumberFormatException e) {
			postMessage("Invalid value: " + args[2]);
			return;
		}

		VarbitChanged changed = new VarbitChanged();
		changed.setValue(value);
		switch (type) {
			case "varbit":
				client.setVarbitValue(varps, id, value);
				client.queueChangedVarp(client.getVarbit(id).getIndex());
				changed.setVarbitId(id);
				break;
			case "varp":
				varps[id] = value;
				client.queueChangedVarp(id);
				changed.setVarpId(id);
				break;
			default:
				throw new IllegalStateException("Unhandled variable kind: " + type);
		}
		eventBus.post(changed);
		postMessage("Set " + type + " " + nameOrId + " (" + id + ") = " + value);
	}

	private void toggleColorPicker() {
		plugin.uboGlobal.colorPicker.set(1, 1, 1, 1);
		if (colorPicker == null) {
			colorPicker = colorPickerManager.create(
				client,
				Color.WHITE,
				"Shader Color Picker",
				false
			);
			colorPicker.setLocationRelativeTo(client.getCanvas());
			colorPicker.setOnColorChange(c -> clientThread.invoke(() -> {
				var rgb = ColorUtils.rgb(c); // linear
				plugin.uboGlobal.colorPicker.set(rgb[0], rgb[1], rgb[2], c.getAlpha() / 255.f);
			}));
			colorPicker.setVisible(true);
		} else {
			colorPicker.setVisible(false);
			colorPicker = null;
		}
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
	public void keyPressed(KeyEvent e) {
		if (KEY_TOGGLE_TILE_INFO.matches(e)) {
			tileInfoOverlay.setActive(tileInfoOverlayEnabled = !tileInfoOverlayEnabled);
		} else if (KEY_TOGGLE_FRAME_TIMINGS.matches(e)) {
			frameTimerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
		} else if (KEY_RECORD_TIMINGS_SNAPSHOT.matches(e)) {
			frameTimingsRecorder.recordSnapshot();
		} else if (KEY_TOGGLE_SHADOW_MAP_OVERLAY.matches(e)) {
			shadowMapOverlay.setActive(shadowMapOverlayEnabled = !shadowMapOverlayEnabled);
		} else if (KEY_TOGGLE_LIGHT_GIZMO_OVERLAY.matches(e)) {
			lightGizmoOverlay.setActive(lightGizmoOverlayEnabled = !lightGizmoOverlayEnabled);
		} else if (KEY_TOGGLE_TILED_LIGHTING_OVERLAY.matches(e)) {
			tiledLightingOverlay.setActive(tiledLightingOverlayEnabled = !tiledLightingOverlayEnabled);
		} else if (KEY_TOGGLE_FREEZE_FRAME.matches(e)) {
			plugin.toggleFreezeFrame();
		} else if (KEY_TOGGLE_ORTHOGRAPHIC.matches(e)) {
			plugin.orthographicProjection = !plugin.orthographicProjection;
		} else if (KEY_TOGGLE_HIDE_UI.matches(e)) {
			hideUiEnabled = !hideUiEnabled;
		} else if (KEY_RELOAD_SCENE.matches(e)) {
			plugin.renderer.reloadScene();
		} else {
			return;
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e) {}

	@Override
	public void keyTyped(KeyEvent e) {}
}
