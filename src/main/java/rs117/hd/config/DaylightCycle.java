package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DaylightCycle
{
	DYNAMIC("Dynamic"),
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
