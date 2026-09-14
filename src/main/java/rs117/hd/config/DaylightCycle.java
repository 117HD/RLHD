package rs117.hd.config;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle {
	OFF("Off", null, false, false, false),
	// Moving sun and moon driven by UTC; every client sees the same sky.
	DEFAULT("Default", null, true, false, false),
	// Moving sun and moon driven by the player's local wall clock.
	REAL_TIME("Real-Time", null, false, false, true),
	DAWN("Dawn", "DAWN", true, false, false),
	SUNRISE("Sunrise", "SUNRISE", true, false, false),
	DAY("Day", "DAY", true, false, false),
	SUNSET("Sunset", "SUNSET", true, false, false),
	DUSK("Dusk", "DUSK", true, false, false),
	NIGHT("Night", "NIGHT", true, false, false),
	// Moving astronomical sun and moon driven by the configured Custom duration.
	CUSTOM_REALISTIC("Custom Realistic", null, false, true, true),
	// Synthetic sun orbit; the warped simulated timestamp also drives the moon and stars.
	CUSTOM_BASIC("Custom Basic", null, false, true, false),
	;

	private final String name;
	/**
	 * Named fixed sky preset, or null to use astronomical angles.
	 */
	@Nullable
	public final String skyPreset;
	/**
	 * Use Default's UTC-synchronized simulated time.
	 */
	public final boolean usesDefaultCycleTime;
	/**
	 * Advance using the configured Custom duration.
	 */
	public final boolean usesCustomCycleTime;
	/**
	 * Use the latitude and longitude selected in the plugin configuration.
	 */
	public final boolean usesConfiguredCoordinates;

	@Override
	public String toString() {
		return name;
	}
}
