package rs117.hd.utils.tooling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
	@Getter
	private final List<SubToggle> subToggles;

	public static class DeveloperToolBuilder {
		public DeveloperToolBuilder chatMessages(String... chatMessages) {
			this.chatMessages = chatMessages;
			return this;
		}

		public DeveloperToolBuilder subToggles(SubToggle... subToggles) {
			this.subToggles = new ArrayList<>();
			this.subToggles.addAll(Arrays.asList(subToggles));
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

	public static class SubToggle {
		@Getter
		private final String name;
		@Getter
		private final String description;
		@Getter
		private final ToggleType type;
		@Getter
		private final List<String> options;
		@Getter
		private final Runnable onToggle;
		@Getter
		private final Runnable onOptionChange;
		@Getter
		private final ValueSupplier valueSupplier;
		@Getter
		private final ValueSetter valueSetter;

		public SubToggle(String name, String description, ToggleType type, List<String> options,
			Runnable onToggle, Runnable onOptionChange, ValueSupplier valueSupplier, ValueSetter valueSetter) {
			this.name = name;
			this.description = description;
			this.type = type;
			this.options = options;
			this.onToggle = onToggle;
			this.onOptionChange = onOptionChange;
			this.valueSupplier = valueSupplier;
			this.valueSetter = valueSetter;
		}

		public static SubToggle booleanToggle(String name, String description, Runnable onToggle) {
			return new SubToggle(name, description, ToggleType.BOOLEAN, null, onToggle, null, null, null);
		}

		public static SubToggle dropdownToggle(String name, String description, List<String> options, ValueSetter valueSetter) {
			return new SubToggle(name, description, ToggleType.DROPDOWN, options, null, null, null, valueSetter);
		}

		public static SubToggle dropdownToggle(String name, String description, List<String> options,
			ValueSupplier valueSupplier, ValueSetter valueSetter) {
			return new SubToggle(name, description, ToggleType.DROPDOWN, options, null, null, valueSupplier, valueSetter);
		}

		@FunctionalInterface
		public interface ValueSupplier {
			Object getValue();
		}

		@FunctionalInterface
		public interface ValueSetter {
			void setValue(Object value);
		}
	}

	public enum ToggleType {
		BOOLEAN,
		DROPDOWN
	}

}
