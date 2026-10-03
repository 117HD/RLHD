package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ShadowFiltering {
	NEAREST("Nearest", Mode.PCF, 1),
	SMOOTH_LOW("Smooth low", Mode.PCF, 2),
	SMOOTH_HIGH("Smooth high", Mode.PCF, 3),
	DITHERED_LOW("Dithered low", Mode.DITHER, 1),
	DITHERED_HIGH("Dithered high", Mode.DITHER, 2),
	PIXELATED("Pixelated", Mode.AVERAGE, 2),
	PCSS("Soft shadows", Mode.PCSS, 2);

	public enum Mode { PCF, DITHER, AVERAGE, PCSS }

	private final String name;
	public final Mode filtering;
	public final int kernelSize;

	@Override
	public String toString() {
		return name;
	}
}
