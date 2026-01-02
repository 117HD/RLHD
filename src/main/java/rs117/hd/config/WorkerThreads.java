package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum WorkerThreads {
	LOW(0.25f),
	MED(0.5f),
	HIGH(0.75f),
	MAX(1.0f);

	public final float threadRatio;
}
