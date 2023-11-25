package rs117.hd.utils;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.*;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import rs117.hd.data.environments.Area;
import rs117.hd.overlays.FrameTimingsOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;

@Slf4j
public class DeveloperTools implements KeyListener {
	// This could be part of the config if we had developer mode config sections
	private static final Keybind KEY_TOGGLE_TILE_INFO = new Keybind(KeyEvent.VK_F3, InputEvent.CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_FRAME_TIMINGS = new Keybind(KeyEvent.VK_F4, InputEvent.CTRL_DOWN_MASK);
	private static final Keybind KEY_TOGGLE_SHADOW_MAP_OVERLAY = new Keybind(KeyEvent.VK_F5, InputEvent.CTRL_DOWN_MASK);

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private TileInfoOverlay tileInfoOverlay;

	@Inject
	private FrameTimingsOverlay frameTimingsOverlay;

	@Inject
	private ShadowMapOverlay shadowMapOverlay;

	private boolean tileInfoOverlayEnabled = false;
	private boolean frameTimingsOverlayEnabled = false;
	private boolean shadowMapOverlayEnabled = false;

	public void activate() {
		eventBus.register(this);

		// Don't do anything else unless we're in the development environment
		if (!Props.DEVELOPMENT)
			return;

		keyManager.registerKeyListener(this);

		tileInfoOverlay.setActive(tileInfoOverlayEnabled);
		frameTimingsOverlay.setActive(frameTimingsOverlayEnabled);
		shadowMapOverlay.setActive(shadowMapOverlayEnabled);

		// Check for any out of bounds areas
		for (Area area : Area.values()) {
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
		frameTimingsOverlay.setActive(false);
		shadowMapOverlay.setActive(false);
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
				frameTimingsOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
				break;
			case "shadowmap":
				shadowMapOverlay.setActive(shadowMapOverlayEnabled = !shadowMapOverlayEnabled);
				break;
		}
	}

	@Override
	public void keyPressed(KeyEvent event) {
		if (KEY_TOGGLE_TILE_INFO.matches(event)) {
			event.consume();
			tileInfoOverlay.setActive(tileInfoOverlayEnabled = !tileInfoOverlayEnabled);
		}

		if (KEY_TOGGLE_FRAME_TIMINGS.matches(event)) {
			event.consume();
			frameTimingsOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
		}

		if (KEY_TOGGLE_SHADOW_MAP_OVERLAY.matches(event)) {
			event.consume();
			shadowMapOverlay.setActive(shadowMapOverlayEnabled = !shadowMapOverlayEnabled);
		}
	}

	@Override
	public void keyReleased(KeyEvent event) {}

	@Override
	public void keyTyped(KeyEvent event) {}
}
