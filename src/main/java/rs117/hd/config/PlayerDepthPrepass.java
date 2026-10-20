package rs117.hd.config;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum PlayerDepthPrepass {
	OFF("Off"),
	LOCAL_PLAYER("Local player"),
	ALL_PLAYERS("All players");

	private final String name;

	@Override
	public String toString() {
		return name;
	}
}
