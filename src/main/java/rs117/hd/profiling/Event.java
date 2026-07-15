package rs117.hd.profiling;

import java.awt.Color;

public enum Event {
	GC,
	ROOT_MAP_LOAD,
	ROOT_SWAP_SCENE,
	SUB_MAP_LOAD,
	SUB_SWAP_SCENE,
	ZONE_INVALIDATE;

	public static final Event[] EVENTS = values();

	public final String name = name().toLowerCase();
	public final Color color = Color.getHSBColor((ordinal() * 0.618033988749895f) % 1f, 0.65f, 0.95f);
}
