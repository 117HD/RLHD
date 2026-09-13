package rs117.hd.config;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle {
	OFF("Off", null, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	DEFAULT("Default", null, true, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real-Time", null, false, false),
	DAWN("Dawn", "DAWN", true, false),
	SUNRISE("Sunrise", "SUNRISE", true, false),
	DAY("Day", "DAY", true, false),
	SUNSET("Sunset", "SUNSET", true, false),
	DUSK("Dusk", "DUSK", true, false),
	NIGHT("Night", "NIGHT", true, false),
	// Moving astronomical sun and moon driven by the configured Custom duration.
	CUSTOM_REALISTIC("Custom Realistic", null, false, true),
	// Synthetic sun orbit; the warped simulated timestamp also drives the moon and stars.
	CUSTOM_BASIC("Custom Basic", null, false, true),
	;

	private final String name;
	/** Named fixed sky preset, or null to use astronomical angles. */
	@Nullable
	public final String skyPreset;
	/**
	 * Use Default's UTC-synchronized simulated time.
	 */
	public final boolean usesDefaultCycleTime;
	/** Advance using the configured Custom duration. */
	public final boolean usesCustomCycleTime;

	@Override
	public String toString() {
		return name;
	}
}
