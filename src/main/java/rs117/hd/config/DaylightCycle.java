package rs117.hd.config;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle {
	OFF("Off", null, false),
	DEFAULT("Default", null, false),
	REAL_TIME("Real-time", null, true),
	CUSTOM("Custom", null, true),
	DAWN("Dawn", "DAWN", false),
	SUNRISE("Sunrise", "SUNRISE", false),
	DAY("Day", "DAY", false),
	SUNSET("Sunset", "SUNSET", false),
	DUSK("Dusk", "DUSK", false),
	NIGHT("Night", "NIGHT", false),
	;

	private final String name;
	@Nullable
	public final String fixedSkyPreset;
	public final boolean useConfigLatLon;

	@Override
	public String toString() {
		return name;
	}
}
