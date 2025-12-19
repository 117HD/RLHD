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

@Singleton
@Slf4j
public class HdApi {
	private static final String NAMESPACE = "117hd";
	private static final String SUBSCRIBE_PREFIX = "subscribe:";
	private static final String UNSUBSCRIBE_PREFIX = "unsubscribe:";
	private static final String RESPONSE_PREFIX = "response:";

	@Inject
	private EventBus eventBus;

	private final Set<RLHDEvent> eventSubscriptions = ConcurrentHashMap.newKeySet();

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

		if (name.startsWith(SUBSCRIBE_PREFIX)) {
			String eventName = name.substring(SUBSCRIBE_PREFIX.length());
			handleSubscribe(eventName);
		} else if (name.startsWith(UNSUBSCRIBE_PREFIX)) {
			String eventName = name.substring(UNSUBSCRIBE_PREFIX.length());
			handleUnsubscribe(eventName);
		}
	}

	/**
	 * Handles a subscription request.
	 * @param eventName The event name to subscribe to
	 */
	private void handleSubscribe(String eventName) {
		RLHDEvent event = RLHDEvent.fromName(eventName);
		
		if (event == null) {
			sendResponse(eventName, false, "Event '" + eventName + "' is not available for subscription");
			log.warn("Rejected subscription request for unapproved event: {}", eventName);
			return;
		}

		eventSubscriptions.add(event);

		log.debug("Subscription added for event: {}", eventName);
		eventBus.post(new RLHDSubscribe(event));
	}

	/**
	 * Handles an unsubscription request.
	 * @param eventName The event name to unsubscribe from
	 */
	private void handleUnsubscribe(String eventName) {
		RLHDEvent event = RLHDEvent.fromName(eventName);
		
		if (event == null) {
			// Event not approved, but we'll still try to remove it (in case it was a typo)
			log.warn("Unsubscribe request for unapproved event: {}", eventName);
			sendResponse(eventName, false, "Event '" + eventName + "' is not available for unsubscription");
			return;
		}

		eventSubscriptions.remove(event);

		log.debug("Subscription removed for event: {}", eventName);
		eventBus.post(new RLHDUnsubscribe(event));
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
		
		String responseName = RESPONSE_PREFIX + eventName;
		eventBus.post(new PluginMessage(NAMESPACE, responseName, payload));
	}

	/**
	 * Checks if a specific event is currently subscribed.
	 * @param event The event to check
	 * @return true if subscribed, false otherwise
	 */
	public boolean isSubscribed(RLHDEvent event) {
		return eventSubscriptions.contains(event);
	}

	/**
	 * Notifies all active subscribers that 117 HD has started.
	 * Sends a startup message to each subscribed event.
	 */
	public void initialize() {
		eventBus.register(this);

		Map<String, Object> payload = new HashMap<>();
		payload.put("startup", true);
		payload.put("message", "117 HD has started");

		for (RLHDEvent event : RLHDEvent.values()) {
			String eventName = event.getEventName();
			eventBus.post(new PluginMessage(NAMESPACE, eventName, payload));
			log.debug("Sent startup notification to subscribers of event: {}", eventName);
		}
	}

	/**
	 * Notifies all active subscribers that 117 HD is shutting down.
	 * Sends a shutdown message to each subscribed event.
	 */
	public void destroy() {
		eventBus.unregister(this);

		Map<String, Object> payload = new HashMap<>();
		payload.put("shutdown", true);
		payload.put("message", "117 HD is shutting down");

		for (RLHDEvent event : RLHDEvent.values()) {
			String eventName = event.getEventName();
			eventBus.post(new PluginMessage(NAMESPACE, eventName, payload));
			log.debug("Sent shutdown notification to subscribers of event: {}", eventName);
		}

		eventSubscriptions.clear();
	}
}

