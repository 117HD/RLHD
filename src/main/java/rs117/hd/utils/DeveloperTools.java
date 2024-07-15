package rs117.hd.utils;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimerOverlay;
import rs117.hd.overlays.LightGizmoOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;

@Slf4j
public class DeveloperTools extends MouseAdapter implements KeyListener {
	// This could be part of the config if we had developer mode config sections
	private static final Keybind KEY_TOGGLE_TILE_INFO = new Keybind(KeyEvent.VK_F3, InputEvent.CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_FRAME_TIMINGS = new Keybind(KeyEvent.VK_F4, InputEvent.CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_SHADOW_MAP_OVERLAY = new Keybind(KeyEvent.VK_F5, InputEvent.CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_LIGHT_GIZMO_OVERLAY = new Keybind(KeyEvent.VK_F6, InputEvent.CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_FREEZE_FRAME = new Keybind(KeyEvent.VK_ESCAPE, InputEvent.SHIFT_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_ORTHOGRAPHIC = new Keybind(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private TileInfoOverlay tileInfoOverlay;

	@Inject
	private FrameTimerOverlay frameTimerOverlay;

	@Inject
	private ShadowMapOverlay shadowMapOverlay;

	@Inject
	private LightGizmoOverlay lightGizmoOverlay;

	private boolean keyBindingsEnabled = false;
	private boolean tileInfoOverlayEnabled = false;
	private boolean frameTimingsOverlayEnabled = false;
	private boolean shadowMapOverlayEnabled = false;
	private boolean lightGizmoOverlayEnabled = false;

	public void activate() {
		// Listen for commands
		eventBus.register(this);

		// Don't do anything else unless we're in the development environment
		if (!Props.DEVELOPMENT)
			return;

		// Enable 117 HD's keybindings by default during development
		keyBindingsEnabled = true;
		keyManager.registerKeyListener(this);
		mouseManager.registerMouseListener(this);

		clientThread.invokeLater(() -> {
			tileInfoOverlay.setActive(tileInfoOverlayEnabled);
			frameTimerOverlay.setActive(frameTimingsOverlayEnabled);
			shadowMapOverlay.setActive(shadowMapOverlayEnabled);
			lightGizmoOverlay.setActive(lightGizmoOverlayEnabled);
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
		mouseManager.unregisterMouseListener(this);
		tileInfoOverlay.setActive(false);
		frameTimerOverlay.setActive(false);
		shadowMapOverlay.setActive(false);
		lightGizmoOverlay.setActive(false);
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
				frameTimerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
				break;
			case "shadowmap":
				shadowMapOverlay.setActive(shadowMapOverlayEnabled = !shadowMapOverlayEnabled);
				break;
			case "lights":
				lightGizmoOverlay.setActive(lightGizmoOverlayEnabled = !lightGizmoOverlayEnabled);
				break;
			case "keybindings":
				keyBindingsEnabled = !keyBindingsEnabled;
				if (keyBindingsEnabled) {
					keyManager.registerKeyListener(this);
					mouseManager.registerMouseListener(this);
				} else {
					keyManager.unregisterKeyListener(this);
					mouseManager.unregisterMouseListener(this);
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
		} else if (KEY_TOGGLE_SHADOW_MAP_OVERLAY.matches(e)) {
			shadowMapOverlay.setActive(shadowMapOverlayEnabled = !shadowMapOverlayEnabled);
		} else if (KEY_TOGGLE_LIGHT_GIZMO_OVERLAY.matches(e)) {
			lightGizmoOverlay.setActive(lightGizmoOverlayEnabled = !lightGizmoOverlayEnabled);
		} else if (KEY_TOGGLE_FREEZE_FRAME.matches(e)) {
			plugin.toggleFreezeFrame();
		} else if (KEY_TOGGLE_ORTHOGRAPHIC.matches(e)) {
			plugin.orthographicProjection = !plugin.orthographicProjection;
		} else {
			return;
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e) {}

	@Override
	public void keyTyped(KeyEvent e) {}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (SwingUtilities.isLeftMouseButton(e) && plugin.orthographicProjection)
			e.consume();
		return e;
	}
}
