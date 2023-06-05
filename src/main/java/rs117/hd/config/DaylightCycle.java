package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle
{
	HOUR_LONG_DAYS("1-Hour Days"),
	ALWAYS_DAY,
	ALWAYS_NIGHT,
	ALWAYS_SUNSET,
	ALWAYS_DUSK,
	;

	private final String name;

	DaylightCycle()
	{
		name = this.name();
	}

	@Override
	public String toString()
	{
		return name;
	}
}
