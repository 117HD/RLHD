package rs117.hd.config;

import lombok.RequiredArgsConstructor;

/**
 * Controls the moon's illuminated phase. DYNAMIC keeps the existing behavior
 * (phase advances naturally / per MoonBehavior); every other value locks the
 * moon at a fixed illumination fraction, where 1.0 = fully lit and 0.0 = dark.
 */
@RequiredArgsConstructor
public enum MoonPhase
{
	DYNAMIC("Dynamic", -1f),
	FULL_MOON("Full Moon", 1.0f),
	GIBBOUS("Gibbous", 0.75f),
	HALF_MOON("Half Moon", 0.5f),
	CRESCENT("Crescent", 0.25f),
	NEW_MOON("New Moon", 0.0f),
	;

	private final String name;
	// Locked illumination fraction (0..1), or -1 for DYNAMIC (no lock).
	public final float illumination;

	public boolean isLocked() {
		return this != DYNAMIC;
	}

	@Override
	public String toString() {
		return name;
	}
}
