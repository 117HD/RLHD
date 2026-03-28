package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MoonBehavior
{
	REALISTIC("Realistic"),
	NIGHT_SYNCED("Night Synced"),
	;

	private final String name;

	MoonBehavior() {
		name = this.name();
	}

	@Override
	public String toString() {
		return name;
	}
}
