package rs117.hd.config;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MoonBehavior {
	DISABLED(false, false, true),
	REALISTIC(false, false, false),
	MIRRORED(true, false, false),
	STATIC(false, true, false),
	;

	public final boolean mirrorsSun;
	public final boolean isStatic;
	public final boolean isDisabled;
}
