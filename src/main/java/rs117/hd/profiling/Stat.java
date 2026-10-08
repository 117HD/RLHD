package rs117.hd.profiling;

import java.awt.Color;

import static rs117.hd.utils.HDUtils.enumToName;

public enum Stat {
	GARBAGE_COLLECTION_COUNT,
	VISIBLE_LIGHTS((value) -> String.format("%d/%d", value & 0xFFFF_FFFFL, value >>> 32)),
	VISIBLE_DYNAMIC_RENDERABLES,
	RENDER_STATE_CHANGES,
	DRAW_CALL_COUNT;

	public static final Stat[] STATS = values();

	public final String name = enumToName(name().toLowerCase());
	public final Color color = Color.getHSBColor((ordinal() * 0.618033988749895f) % 1f, 0.65f, 0.95f);
	public final Formatter formatter;

	@FunctionalInterface
	public interface Formatter {
		String format(long value);
	}

	Stat() { formatter = String::valueOf; }

	Stat(Formatter formatter) { this.formatter = formatter; }
}
