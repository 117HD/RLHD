package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MinimapType {
	VANILLA("Vanilla"),
	SHADED("Shaded"),
	HD("Topdown");

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
