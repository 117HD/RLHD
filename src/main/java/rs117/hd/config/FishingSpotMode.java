package rs117.hd.config;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum FishingSpotMode {
	HD("117 HD"),
	COMBINED("117 & Vanilla"),
	OSRS("Vanilla");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}

}
