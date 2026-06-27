package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle
{
	DYNAMIC("Dynamic"),
	FIXED_DAWN("Fixed Dawn"),
	FIXED_MIDDAY("Fixed Midday"),
	FIXED_SUNSET("Fixed Sunset"),
	FIXED_NIGHT("Fixed Night"),
	ALWAYS_NIGHT("Always Night"),
	;

	private final String name;

	DaylightCycle() {
		name = this.name();
	}

	@Override
	public String toString() {
		return name;
	}
}
