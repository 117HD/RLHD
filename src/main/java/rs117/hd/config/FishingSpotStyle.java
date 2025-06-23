package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum FishingSpotStyle {
	HD("Bubbles"),
	VANILLA("Droplets"),
	BOTH("Both");

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
