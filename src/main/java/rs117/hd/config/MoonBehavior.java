package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MoonBehavior {
	DISABLED("Disabled"),
	REALISTIC("Realistic"),
	MIRRORED("Mirror the sun"),
	STATIC("Static"),
	;

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
