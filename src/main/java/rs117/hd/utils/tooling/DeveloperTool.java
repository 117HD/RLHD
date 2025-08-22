package rs117.hd.utils.tooling;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.Keybind;
import rs117.hd.overlays.DeveloperOverlay;

@Builder
@Slf4j
public class DeveloperTool {

	@Getter
	private boolean active;
	@Getter
	private final String name;
	@Getter
	private final Keybind keyBind;
	@Getter
	private final String[] chatMessages;
	@Getter
	private final DeveloperOverlay overlay;
	@Getter
	private final Runnable customAction;
	@Getter
	private final String description;

	public static class DeveloperToolBuilder {
		public DeveloperToolBuilder chatMessages(String... chatMessages) {
			this.chatMessages = chatMessages;
			return this;
		}
	}

	public String getDisplayDescription() {
		return description != null ? description : name;
	}

	public void toggle() {
		active = !active;
		if (overlay != null) {
			try {
				overlay.setActive(active);
			} catch (Exception e) {
				log.error("Error toggling overlay '{}' to state {}: {}", name, active, e.getMessage(), e);
			}
		}
		if (customAction != null) {
			try {
				customAction.run();
			} catch (Exception e) {
				log.error("Error executing custom action for developer setting '{}': {}", name, e.getMessage(), e);
			}
		}
	}

	public void setActive(boolean state) {
		active = state;
		if (overlay != null) {
			try {
				overlay.setActive(state);
			} catch (Exception e) {
				log.error("Error setting overlay '{}' to state {}: {}", name, state, e.getMessage(), e);
			}
		}
	}

	public void setActive() {
		if (overlay != null) {
			try {
				overlay.setActive(active);
			} catch (Exception e) {
				log.error("Error setting overlay '{}' to active state: {}", name, e.getMessage(), e);
			}
		}
	}

}
