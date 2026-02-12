package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CpuUsage {
	MAX(1),
	HIGH(0.75f),
	MEDIUM(0.5f),
	LOW(0.25f),
	MINIMAL(0),
	;

	public final float threadRatio;
}
