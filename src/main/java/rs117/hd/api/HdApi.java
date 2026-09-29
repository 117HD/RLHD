package rs117.hd.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;

@Slf4j
@Singleton
public class HdApi {
	private static final String NAMESPACE = "117hd";
	private static final String PREFIX_SUBSCRIBE = "subscribe:";
	private static final String PREFIX_UNSUBSCRIBE = "unsubscribe:";
	private static final String PREFIX_RESPONSE = "response:";

	@Inject
	private EventBus eventBus;

	private final Set<HdEvent> eventSubscriptions = ConcurrentHashMap.newKeySet();

	public void initialize() {
		eventBus.register(this);

		Map<String, Object> payload = new HashMap<>();
		payload.put("startup", true);
		payload.put("message", "117 HD has started");

		for (HdEvent event : HdEvent.values()) {
			String eventName = event.getEventName();
			eventBus.post(new PluginMessage(NAMESPACE, eventName, payload));
			log.debug("Sent startup notification to subscribers of event: {}", eventName);
		}
	}

	public void destroy() {
		eventBus.unregister(this);

		Map<String, Object> payload = new HashMap<>();
		payload.put("shutdown", true);
		payload.put("message", "117 HD is shutting down");

		for (HdEvent event : HdEvent.values()) {
			String eventName = event.getEventName();
			eventBus.post(new PluginMessage(NAMESPACE, eventName, payload));
			log.debug("Sent shutdown notification to subscribers of event: {}", eventName);
		}

		eventSubscriptions.clear();
	}

	/**
	 * Handles incoming plugin messages for subscription management.
	 * @param message The plugin message to process
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage message) {
		// Only handle messages for our namespace
		if (!NAMESPACE.equals(message.getNamespace())) {
			return;
		}

		String name = message.getName();
		if (name == null) {
			return;
		}

		if (name.startsWith(PREFIX_SUBSCRIBE)) {
			String eventName = name.substring(PREFIX_SUBSCRIBE.length());
			handleSubscribe(eventName);
		} else if (name.startsWith(PREFIX_UNSUBSCRIBE)) {
			String eventName = name.substring(PREFIX_UNSUBSCRIBE.length());
			handleUnsubscribe(eventName);
		}
	}

	/**
	 * Handles a subscription request.
	 * @param eventName The event name to subscribe to
	 */
	private void handleSubscribe(String eventName) {
		HdEvent event = HdEvent.fromName(eventName);
		
		if (event == null) {
			sendResponse(eventName, false, "Event '" + eventName + "' is not available for subscription");
			log.warn("Rejected subscription request for unapproved event: {}", eventName);
			return;
		}

		eventSubscriptions.add(event);

		log.debug("Subscription added for event: {}", eventName);
		eventBus.post(new HdSubscribe(event));
	}

	/**
	 * Handles an unsubscription request.
	 * @param eventName The event name to unsubscribe from
	 */
	private void handleUnsubscribe(String eventName) {
		HdEvent event = HdEvent.fromName(eventName);
		
		if (event == null) {
			// Event not approved, but we'll still try to remove it (in case it was a typo)
			log.warn("Unsubscribe request for unapproved event: {}", eventName);
			sendResponse(eventName, false, "Event '" + eventName + "' is not available for unsubscription");
			return;
		}

		eventSubscriptions.remove(event);

		log.debug("Subscription removed for event: {}", eventName);
		eventBus.post(new HdUnsubscribe(event));
	}

	/**
	 * Sends a response message back to the subscriber.
	 * @param eventName The event name that was requested
	 * @param success Whether the subscription was successful
	 * @param message A descriptive message
	 */
	private void sendResponse(String eventName, boolean success, String message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("event", eventName);
		payload.put("success", success);
		payload.put("message", message);

		String responseName = PREFIX_RESPONSE + eventName;
		eventBus.post(new PluginMessage(NAMESPACE, responseName, payload));
	}

	public boolean isSubscribed(HdEvent event) {
		return eventSubscriptions.contains(event);
	}
}
