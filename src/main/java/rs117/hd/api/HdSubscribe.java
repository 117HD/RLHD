package rs117.hd.api;

import lombok.Value;

/**
 * Event posted when a plugin subscribes to an RLHD event.
 */
@Value
public class HdSubscribe {
	HdEvent event;
}
