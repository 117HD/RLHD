package rs117.hd.tooling;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
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
import rs117.hd.tooling.overlays.FrameTimerOverlay;
import rs117.hd.tooling.overlays.LightGizmoOverlay;
import rs117.hd.tooling.overlays.ShadowMapOverlay;
import rs117.hd.tooling.overlays.TileInfoOverlay;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.Props;

@Slf4j
public class DeveloperTools implements KeyListener {

	public static Map<String, DeveloperSettings> settings = new HashMap<>();

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
	@Getter
	private ShadowMapOverlay shadowMapOverlay;

	@Inject
	@Getter
	private LightGizmoOverlay lightGizmoOverlay;

	@Getter
	private boolean keyBindingsEnabled = false;


	public void activate() {
		// Listen for commands
		eventBus.register(this);
		registerSettings();

		// Don't do anything else unless we're in the development environment
		if (!Props.DEVELOPMENT)
			return;

		// Enable 117 HD's keybindings by default during development
		keyBindingsEnabled = true;
		keyManager.registerKeyListener(this);

		clientThread.invokeLater(() -> settings.forEach( (s, developerSettings) -> {
			if (developerSettings.getDeveloperOverlay() != null) {
				tileInfoOverlay.setActive(developerSettings.enabled);
			}
		}));

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

	public void registerSettings() {
		settings.put("TILE_INFO",new DeveloperSettings("Tile Info", new Keybind(KeyEvent.VK_F3, InputEvent.CTRL_DOWN_MASK),"tileinfo",tileInfoOverlay));
		settings.put("FRAME_TIMERS",new DeveloperSettings("Frame Timers", new Keybind(KeyEvent.VK_F4, InputEvent.CTRL_DOWN_MASK),"timers",frameTimerOverlay));
		settings.put("SHADOW_MAP",new DeveloperSettings("Shadow Map", new Keybind(KeyEvent.VK_F5, InputEvent.CTRL_DOWN_MASK),"shadowmap",shadowMapOverlay));
		settings.put("LIGHT_GIZMO",new DeveloperSettings("Light Gizmo", new Keybind(KeyEvent.VK_F6, InputEvent.CTRL_DOWN_MASK),"lights",lightGizmoOverlay));
		settings.put("FREEZE_FRAME",new DeveloperSettings("Freeze Frame", new Keybind(KeyEvent.VK_ESCAPE, InputEvent.CTRL_DOWN_MASK),"freeze",() -> plugin.toggleFreezeFrame()));
	}

	public void deactivate() {
		eventBus.unregister(this);
		keyManager.unregisterKeyListener(this);
		settings.forEach( (s, developerSettings) -> {
			if (developerSettings.getDeveloperOverlay() != null) {
				tileInfoOverlay.setActive(false);
			}
		});
	}



	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted) {
		if (!commandExecuted.getCommand().equalsIgnoreCase("117hd"))
			return;

		String[] args = commandExecuted.getArguments();
		if (args.length < 1)
			return;

		String action = args[0].toLowerCase();

		if (DeveloperSettings.findByChatKey(settings,action).isPresent()) {
			DeveloperSettings.findByChatKey(settings,action).get().toggle();

		}

		if (action.equals("keybindings")) {
			keyBindingsEnabled = !keyBindingsEnabled;
			if (keyBindingsEnabled) {
				keyManager.registerKeyListener(this);
			} else {
				keyManager.unregisterKeyListener(this);
			}
		}
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if (DeveloperSettings.findByKeyEvent(settings,e).isPresent()) {
			DeveloperSettings.findByKeyEvent(settings,e).get().toggle();
		} else {
			return;
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent event) {}

	@Override
	public void keyTyped(KeyEvent event) {}
}
