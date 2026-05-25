package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SkyboxTheme {
	NONE("None (Vanilla/117)", null),
	CLEAR_DAY("Clear Day (Autumn Field)", "/rs117/hd/skybox3d/autumn_field_puresky_4k.png"),
	CLOUDY("Cloudy Overcast (Soil)", "/rs117/hd/skybox3d/overcast_soil_puresky_4k.png"),
	STARRY_NIGHT("Starry Night (Qwantani)", "/rs117/hd/skybox3d/qwantani_night_puresky_4k.png"),
	PARTLY_CLOUDY("Partly Cloudy (Sunflowers)", "/rs117/hd/skybox3d/sunflowers_puresky_4k.png");

	private final String name;
	private final String resourcePath;

	@Override
	public String toString() {
		return name;
	}
}