package rs117.hd.utils.tooling;

import lombok.Builder;
import lombok.Getter;
import net.runelite.client.config.Keybind;
import rs117.hd.overlays.HdOverlay;

@Builder
public class DeveloperSetting {

	@Getter
	private boolean active;
	@Getter
	private final String name;
	@Getter
	private final Keybind keyBind;
	@Getter
	private final String[] chatMessages;
	@Getter
	private final HdOverlay overlay;
	@Getter
	private final Runnable customAction;
	@Getter
	private final String description;

	public static class DeveloperSettingBuilder {
		public DeveloperSettingBuilder chatMessages(String... chatMessages) {
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
			overlay.setActive(active);
		}
		if (customAction != null) {
			customAction.run();
		}
	}

	public void setActive(boolean state) {
		active = state;
		if (overlay != null) {
			overlay.setActive(state);
		}
	}

	public void setActive() {
		if (overlay != null) {
			overlay.setActive(active);
		}
	}

}
