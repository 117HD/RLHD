package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MinimapType {
	NORMAL("Normal"),
	HD("HD Topdown"),
	SHADED("Shaded"),
	FLAT("Flat");

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
