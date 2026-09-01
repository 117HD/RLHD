package rs117.hd.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Enumeration of approved events that can be subscribed to via the 117 HD API.
 */
@AllArgsConstructor
public enum HdEvent {
	EVENT_MINIMAP("event.minimap");

	@Getter
	private final String eventName;

	/**
	 * Finds an event by its name.
	 * @param eventName The event name to look up
	 * @return The matching event, or null if not found
	 */
	public static HdEvent fromName(String eventName) {
		for (HdEvent event : values()) {
			if (event.eventName.equals(eventName)) {
				return event;
			}
		}
		return null;
	}
}
