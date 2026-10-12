package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum GroundBlending {
	ON("On", true, true),
	TEXTURES_ONLY("Textures only", false, true),
	OFF("Off", false, false);

	private final String name;
	public final boolean colors;
	public final boolean textures;

	@Override
	public String toString() {
		return name;
	}
}
