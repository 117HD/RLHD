package rs117.hd.config;

import lombok.RequiredArgsConstructor;

/**
 * REALISTIC follows {@link MoonBehavior}; other values lock the illuminated fraction.
 */
@RequiredArgsConstructor
public enum MoonPhase {
	REALISTIC(-1f, false, false),
	NEW_MOON(0.0f, true, false),
	WAXING_CRESCENT(0.25f, true, false),
	FIRST_QUARTER(0.5f, true, false),
	WAXING_GIBBOUS(0.75f, true, false),
	FULL_MOON(0.99f, true, false),
	WANING_GIBBOUS(0.75f, true, true),
	LAST_QUARTER(0.5f, true, true),
	WANING_CRESCENT(0.25f, true, true),
	;

	public final float illumination; // -1 for REALISTIC; otherwise 0..1.
	public final boolean isLocked;
	public final boolean reversesTerminator;
}
