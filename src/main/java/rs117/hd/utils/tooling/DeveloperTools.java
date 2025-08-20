package rs117.hd.utils.tooling;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.ClientToolbar;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimerOverlay;
import rs117.hd.overlays.LightGizmoOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.FrameTimingsRecorder;
import rs117.hd.utils.Props;
import rs117.hd.utils.tooling.ui.HdSidebar;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;

@Slf4j
public class DeveloperTools implements KeyListener {

	@Getter
	private final List<DeveloperTool> developerTools = new ArrayList<>();

	@Inject
	private ClientThread clientThread;

	@Getter
	private HdSidebar sidebar;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Client client;

	@Inject
	private TileInfoOverlay tileInfoOverlay;

	@Inject
	@Getter
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
	private boolean frameTimingEnabled;

	@Getter
	private boolean hideUiEnabled;

	public void activate() {
		// Listen for commands
		eventBus.register(this);

		// Don't do anything else unless we're in the development environment
		initializeDeveloperTools();
		if (!Props.DEVELOPMENT) {
			return;
		}

		// Enable 117 HD's keybindings by default during development
		keyBindingsEnabled = true;

		keyManager.registerKeyListener(this);

		clientThread.invokeLater(() -> {
			for (DeveloperTool developerTool : developerTools) {
				if (developerTool.getOverlay() != null) {
					developerTool.setActive();
				}
			}
		});

		validateAreas();

		SwingUtilities.invokeLater(() -> {
			sidebar = new HdSidebar(clientToolbar,this);
		});
	}

	private void initializeDeveloperTools() {
		// Overlay-based tools
		registerTool(DeveloperTool.builder()
			.name("TILE_INFO")
			.keyBind(new Keybind(KeyEvent.VK_F3, CTRL_DOWN_MASK))
			.chatMessages("tileinfo")
			.overlay(tileInfoOverlay)
			.description("Shows tile information overlay")
			.subToggles(
				DeveloperTool.SubToggle.dropdownToggle("MODE", "Display mode",
					List.of("Tile Info", "Model Info", "Scene AABBs", "Object IDs"), 
					() -> tileInfoOverlay.getMode(),
					(value) -> {
						int mode = (Integer) value;
						tileInfoOverlay.setMode(mode);
					})
			));

		registerTool(DeveloperTool.builder()
			.name("FRAME_TIMINGS")
			.keyBind(new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK))
			.chatMessages("timers", "timings")
			.overlay(frameTimerOverlay)
			.customAction(() -> frameTimingEnabled = !frameTimingEnabled)
			.description("Shows frame timing overlay"));

		registerTool(DeveloperTool.builder()
			.name("SHADOW_MAP")
			.keyBind(new Keybind(KeyEvent.VK_F5, CTRL_DOWN_MASK))
			.chatMessages("shadowmap")
			.overlay(shadowMapOverlay)
			.description("Shows shadow map overlay"));

		registerTool(DeveloperTool.builder()
			.name("LIGHT_GIZMO")
			.keyBind(new Keybind(KeyEvent.VK_F6, CTRL_DOWN_MASK))
			.chatMessages("lights")
			.overlay(lightGizmoOverlay)
			.description("Shows light gizmo overlay"));

		registerTool(DeveloperTool.builder()
			.name("TILED_LIGHTING")
			.keyBind(new Keybind(KeyEvent.VK_F7, CTRL_DOWN_MASK))
			.chatMessages("tiledlights", "tiledlighting")
			.overlay(tiledLightingOverlay)
			.description("Shows tiled lighting overlay"));

		registerTool(DeveloperTool.builder()
			.name("RECORD_TIMINGS")
			.keyBind(new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK | SHIFT_DOWN_MASK))
			.chatMessages("snapshot")
			.customAction(() -> frameTimingsRecorder.recordSnapshot())
			.description("Records frame timing snapshot"));

		registerTool(DeveloperTool.builder()
			.name("FREEZE_FRAME")
			.keyBind(new Keybind(KeyEvent.VK_ESCAPE, SHIFT_DOWN_MASK))
			.customAction(() -> plugin.toggleFreezeFrame())
			.description("Toggles frame freezing"));

		registerTool(DeveloperTool.builder()
			.name("ORTHOGRAPHIC")
			.keyBind(new Keybind(KeyEvent.VK_TAB, SHIFT_DOWN_MASK))
			.customAction(() -> plugin.orthographicProjection = !plugin.orthographicProjection)
			.description("Toggles orthographic projection"));

		registerTool(DeveloperTool.builder()
			.name("HIDE_UI")
			.keyBind(new Keybind(KeyEvent.VK_H, CTRL_DOWN_MASK))
			.chatMessages("hideui")
			.customAction(() -> hideUiEnabled = !hideUiEnabled)
			.description("Toggles UI visibility"));
	}


	public void registerTool(DeveloperTool.DeveloperToolBuilder builder) {
		DeveloperTool tool = builder.build();

		for (DeveloperTool existingTool : developerTools) {
			if (existingTool.getName().equals(tool.getName())) {
				throw new IllegalArgumentException("Developer tool with name '" + tool.getName() + "' already exists");
			}
		}
		
		developerTools.add(tool);
	}

	private void validateAreas() {
		// Check for any out of bounds areas
		for (Area area : AreaManager.AREAS) {
			if (area == Area.ALL || area == Area.NONE) {
				continue;
			}

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

		for (DeveloperTool developerTool : developerTools) {
			developerTool.setActive(false);
		}
		developerTools.clear();

		hideUiEnabled = false;
		if (sidebar != null)
			sidebar.destroy();
		sidebar = null;

	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted) {
		if (!commandExecuted.getCommand().equalsIgnoreCase("117hd")) {
			return;
		}

		String[] args = commandExecuted.getArguments();
		if (args.length < 1) {
			return;
		}

		String action = args[0].toLowerCase();

		// Check if any developer tool's chat messages match the command
		for (DeveloperTool developerTool : developerTools) {
			for (String chatMessage : developerTool.getChatMessages()) {
				if (chatMessage.equalsIgnoreCase(action)) {
					developerTool.toggle();
					return;
				}
			}
		}

		switch (action) {
			case "keybinds":
			case "keybindings":
				toggleKeyBindings();
				break;
			case "list":
			case "help":
			case "commands":
				listCommands();
				break;
		}
	}

	private void toggleKeyBindings() {
		keyBindingsEnabled = !keyBindingsEnabled;
		if (keyBindingsEnabled) {
			keyManager.registerKeyListener(this);
		} else {
			keyManager.unregisterKeyListener(this);
		}
	}

	private void listCommands() {
		StringBuilder message = new StringBuilder();
		message.append("<col=0000FF>[117 HD] Developer Commands:</col><br>");

		for (DeveloperTool tool : developerTools) {
			String commands = String.join(", ", tool.getChatMessages());
			String description = tool.getDisplayDescription();
			message.append("<col=0000FF>::117hd ").append(commands).append("</col> - ").append(description).append("<br>");
		}

		message.append("<br>");

		message.append("<col=0000FF>::117hd keybinds</col> - Toggle keybindings on/off<br>");
		message.append("<col=0000FF>::117hd list/help/commands</col> - Show this help");

		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.GAMEMESSAGE, "117 HD", message.toString(), "117 HD"));
	}

	@Override
	public void keyPressed(KeyEvent e) {
		// Check all developer tools (including custom keybindings)
		for (DeveloperTool developerTool : developerTools) {
			if (developerTool.getKeyBind().matches(e)) {
				developerTool.toggle();
				e.consume();
				return;
			}
		}
	}

	@Override public void keyReleased(KeyEvent e) {}

	@Override
	public void keyTyped(KeyEvent e) {}
}
