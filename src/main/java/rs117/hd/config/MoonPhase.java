package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MoonPhase {
	DYNAMIC(-1f, false),
	FULL_MOON(0.99f, false),
	WANING_GIBBOUS(0.75f, true),
	THIRD_QUARTER(0.5f, true),
	WANING_CRESCENT(0.25f, true),
	NEW_MOON(0.0f, false),
	WAXING_CRESCENT(0.25f, false),
	FIRST_QUARTER(0.5f, false),
	WAXING_GIBBOUS(0.75f, false),
	;

	public final float illuminatedFraction;
	public final boolean reverseDirection;
}
