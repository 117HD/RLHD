package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MinimapType {
	NORMAL("Normal"),
	HD("HD Topdown");

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
