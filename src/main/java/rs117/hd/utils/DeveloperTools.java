package rs117.hd.utils;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
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
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimerOverlay;
import rs117.hd.overlays.LightGizmoOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.tooling.DeveloperSetting;

@Slf4j
public class DeveloperTools implements KeyListener {

	@Getter
	private final List<DeveloperSetting> developerSettings = new ArrayList<>();

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private Client client;

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

	@Getter
	private boolean hideUiEnabled;

	public void activate() {
		// Listen for commands
		eventBus.register(this);

		initializeDeveloperTools();
		// Don't do anything else unless we're in the development environment
		if (!Props.DEVELOPMENT)
			return;

		// Enable 117 HD's keybindings by default during development
		keyBindingsEnabled = true;
		keyManager.registerKeyListener(this);

		clientThread.invokeLater(() -> {
			for (DeveloperSetting developerSetting : developerSettings) {
				if (developerSetting.getOverlay() != null) {
					developerSetting.setActive();
				}
			}
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

	private void initializeDeveloperTools() {
		// Overlay-based tools
		registerTool(DeveloperSetting.builder()
			.name("TILE_INFO")
			.keyBind(new Keybind(KeyEvent.VK_F3, InputEvent.CTRL_DOWN_MASK))
			.chatMessages("tileinfo")
			.overlay(tileInfoOverlay)
			.description("Shows tile information overlay"));

		registerTool(DeveloperSetting.builder()
			.name("FRAME_TIMINGS")
			.keyBind(new Keybind(KeyEvent.VK_F4, InputEvent.CTRL_DOWN_MASK))
			.chatMessages("timers", "timings")
			.overlay(frameTimerOverlay)
			.description("Shows frame timing overlay"));

		registerTool(DeveloperSetting.builder()
			.name("SHADOW_MAP")
			.keyBind(new Keybind(KeyEvent.VK_F5, InputEvent.CTRL_DOWN_MASK))
			.chatMessages("shadowmap")
			.overlay(shadowMapOverlay)
			.description("Shows shadow map overlay"));

		registerTool(DeveloperSetting.builder()
			.name("LIGHT_GIZMO")
			.keyBind(new Keybind(KeyEvent.VK_F6, InputEvent.CTRL_DOWN_MASK))
			.chatMessages("lights")
			.overlay(lightGizmoOverlay)
			.description("Shows light gizmo overlay"));

		registerTool(DeveloperSetting.builder()
			.name("TILED_LIGHTING")
			.keyBind(new Keybind(KeyEvent.VK_F7, InputEvent.CTRL_DOWN_MASK))
			.chatMessages("tiledlights", "tiledlighting")
			.overlay(tiledLightingOverlay)
			.description("Shows tiled lighting overlay"));

		registerTool(DeveloperSetting.builder()
			.name("RECORD_TIMINGS")
			.keyBind(new Keybind(KeyEvent.VK_F4, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK))
			.chatMessages("snapshot")
			.customAction(() -> frameTimingsRecorder.recordSnapshot())
			.description("Records frame timing snapshot"));

		registerTool(DeveloperSetting.builder()
			.name("FREEZE_FRAME")
			.keyBind(new Keybind(KeyEvent.VK_ESCAPE, InputEvent.SHIFT_DOWN_MASK))
			.customAction(() -> plugin.toggleFreezeFrame())
			.description("Toggles frame freezing"));

		registerTool(DeveloperSetting.builder()
			.name("ORTHOGRAPHIC")
			.keyBind(new Keybind(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK))
			.customAction(() -> plugin.orthographicProjection = !plugin.orthographicProjection)
			.description("Toggles orthographic projection"));

		registerTool(DeveloperSetting.builder()
			.name("HIDE_UI")
			.keyBind(new Keybind(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK))
			.chatMessages("hideui")
			.customAction(() -> hideUiEnabled = !hideUiEnabled)
			.description("Toggles UI visibility"));
	}

	public void registerTool(DeveloperSetting.DeveloperSettingBuilder builder) {
		DeveloperSetting tool = builder.build();

		for (DeveloperSetting existingTool : developerSettings) {
			if (existingTool.getName().equals(tool.getName())) {
				throw new IllegalArgumentException("Developer tool with name '" + tool.getName() + "' already exists");
			}
		}

		developerSettings.add(tool);
	}

	public void deactivate() {
		eventBus.unregister(this);
		keyManager.unregisterKeyListener(this);
		for (DeveloperSetting developerSetting : developerSettings) {
			developerSetting.setActive(false);
		}
		developerSettings.clear();
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
		for (DeveloperSetting developerSetting : developerSettings) {
			for (String chatMessage : developerSetting.getChatMessages()) {
				if (chatMessage.equalsIgnoreCase(action)) {
					developerSetting.toggle();
					return;
				}
			}
		}

		switch (action) {
			case "list":
			case "help":
			case "commands":
				listCommands();
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

	private void listCommands() {
		StringBuilder message = new StringBuilder();
		message.append("<col=0000FF>[117 HD] Developer Commands:</col><br>");

		for (DeveloperSetting tool : developerSettings) {
			String commands = String.join(", ", tool.getChatMessages());
			String description = tool.getDisplayDescription();
			message.append("<col=0000FF>::117hd ").append(commands).append("</col> - ").append(description).append("<br>");
		}

		message.append("<br>");

		message.append("<col=0000FF>::117hd keybinds</col> - Toggle keybindings on/off<br>");
		message.append("<col=0000FF>::117hd list/help/commands</col> - Show this help");

		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.DIDYOUKNOW, "117 HD", message.toString(), "117 HD"));
	}


	@Override
	public void keyPressed(KeyEvent e) {
		for (DeveloperSetting developerSetting : developerSettings) {
			if (developerSetting.getKeyBind().matches(e)) {
				developerSetting.toggle();
				return;
			}
		}
	}

	@Override
	public void keyReleased(KeyEvent e) {}

	@Override
	public void keyTyped(KeyEvent e) {}
}
