package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ReflectionMode {
	DISABLED(0),
	LOW(0.25f),
	MEDIUM(0.35f),
	HIGH(0.5f),
	ULTRA(0.65f),
	EXTREME(1);

	public final float resolutionFrac;
}
