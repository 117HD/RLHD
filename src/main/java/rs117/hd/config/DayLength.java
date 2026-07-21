package rs117.hd.config;

import lombok.RequiredArgsConstructor;

/**
 * Controls how the fixed total cycle time is split between day and night.
 * The full cycle still takes {@code cycleDurationMinutes}; this only changes
 * how much of that time the sun spends above vs below the horizon by warping
 * the cycle clock (slower during the favored period, faster during the other).
 *
 * {@code dayFraction} is the share of the cycle spent in daytime. The natural
 * (unwarped) split is 0.70 day / 0.30 night, which is what STANDARD uses.
 */
@RequiredArgsConstructor
public enum DayLength
{
	STANDARD("Standard", 0.70),
	LONGER_DAYS("Longer Days", 0.85),
	LONGER_NIGHTS("Longer Nights", 0.45),
	;

	private final String name;
	public final double dayFraction;

	@Override
	public String toString() {
		return name;
	}
}
