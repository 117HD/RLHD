package rs117.hd.api;

import lombok.Value;

/**
 * Event posted when a plugin unsubscribes from an RLHD event.
 */
@Value
public class RLHDUnsubscribe {
	RLHDEvent event;
}

