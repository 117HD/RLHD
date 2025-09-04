package rs117.hd.utils;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimerOverlay;
import rs117.hd.overlays.LightGizmoOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;

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

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private HdPlugin plugin;

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

		// Check for any out of bounds areas
		for (Area area : AreaManager.AREAS) {
			if (area == Area.ALL || area == Area.NONE)
				continue;

			for (AABB aabb : area.aabbs) {
				if (aabb.minX < -128 || aabb.minY < 1000 || aabb.maxX > 5000 || aabb.maxY > 13000) {
					throw new IllegalArgumentException(
						"Your definition for the area " + area + " has an incorrect AABB: " + aabb);
				}
			}
		}
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
		}
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
