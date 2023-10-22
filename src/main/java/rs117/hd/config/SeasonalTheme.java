package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SeasonalTheme {
	DEFAULT_THEME("Default", 0),
	WINTER_THEME("Winter", 1),
	AUTUMN_THEME("Autumn", 2);

	private final String name;
	private final int mode;

	@Override
	public String toString() {return name;}
}
