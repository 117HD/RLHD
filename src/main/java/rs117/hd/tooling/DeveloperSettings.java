package rs117.hd.tooling;

import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import net.runelite.client.config.Keybind;
import rs117.hd.tooling.impl.DeveloperToolsButton;
import rs117.hd.utils.Props;

@Data

public class DeveloperSettings {

	private DeveloperOverlay developerOverlay;

	private DeveloperToolsButton button;

	private DeveloperOverlay overlay;

	public DeveloperSettings(String name,Keybind keybind, String chatMessage, DeveloperOverlay overlay, Runnable action) {
		this.keybind = keybind;
		this.chatKey = chatMessage;
		this.overlay = overlay;
		this.action = action;
		this.button = new DeveloperToolsButton(name,this);
	}

	public DeveloperSettings(String name,Keybind keybind, String chatMessage, DeveloperOverlay overlay) {
		this.keybind = keybind;
		this.chatKey = chatMessage;
		this.developerOverlay = overlay;
		this.button = new DeveloperToolsButton(name,this);
	}

	public DeveloperSettings(String name,Keybind keybind, String chatMessage, Runnable action) {
		this.keybind = keybind;
		this.chatKey = chatMessage;
		this.action = action;
		this.button = new DeveloperToolsButton(name,this);
	}

	private Keybind keybind;
	private Runnable action;
	private String chatKey;

	public boolean enabled;

	public void setActive(boolean state) {
		this.enabled = state;
		if (Props.DEVELOPMENT) {
			button.setActive(state);
		}
		if (developerOverlay != null) {
			developerOverlay.setActive(state);
		}
		if (action != null) {
			action.run();
		}
	}

	public void toggle() {
		setActive(!enabled);
	}

	public static Optional<DeveloperSettings> findByChatKey(Map<String, DeveloperSettings> settings, String key) {
		return settings.values().stream()
			.filter(developerSettings -> developerSettings.getChatKey().equalsIgnoreCase(key))
			.findFirst();
	}

	public static Optional<DeveloperSettings> findByKeyEvent(Map<String, DeveloperSettings> settings, KeyEvent e) {
		return settings.values().stream()
			.filter(developerSettings -> developerSettings.getKeybind().matches(e))
			.findFirst();
	}

}
