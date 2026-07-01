package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ShadowFiltering {
	NEAREST(0, 1),
	SMOOTH_LOW(0, 2),
	SMOOTH_HIGH(0, 3),
	DITHERED_LOW(1, 1),
	DITHERED_HIGH(1, 2),
	PIXELATED(2, 2);

	// 0 = Smoothed, 1 = Dithered, 2 = Pixelated
	public final int filtering;
	public final int kernalSize;
}
