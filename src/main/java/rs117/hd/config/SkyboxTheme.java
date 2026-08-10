package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SkyboxTheme {
	NONE("None"),
	CUSTOM("Custom");

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
