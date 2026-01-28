package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ThreadingMode {
	DISABLED(0.0f),
	LOW(0.25f),
	MED(0.5f),
	HIGH(0.75f);

	public final float threadRatio;
}
