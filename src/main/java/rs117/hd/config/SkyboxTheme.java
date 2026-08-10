package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SkyboxTheme {
	NONE("None (Vanilla/117)"),
	CUSTOM("Custom (.runelite/117hd/custom-skyboxes)");

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
