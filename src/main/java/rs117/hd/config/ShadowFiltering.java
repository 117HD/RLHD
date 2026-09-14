package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ShadowFiltering {
	NEAREST(Mode.PCF, 1),
	SMOOTH_LOW(Mode.PCF, 2),
	SMOOTH_HIGH(Mode.PCF, 3),
	DITHERED_LOW(Mode.DITHER, 1),
	DITHERED_HIGH(Mode.DITHER, 2),
	PIXELATED(Mode.AVERAGE, 2);

	public final Mode filtering;
	public final int kernelSize;

	public enum Mode {
		PCF,
		DITHER,
		AVERAGE,
	}
}
