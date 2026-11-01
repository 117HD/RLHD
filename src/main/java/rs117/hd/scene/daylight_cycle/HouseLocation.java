package rs117.hd.scene.daylight_cycle;

import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;

/**
 * Player-owned house locations, keyed by the POH_HOUSE_LOCATION varbit.
 * Portal positions are in world coordinates, before any map shift is applied.
 */
@RequiredArgsConstructor
public enum HouseLocation {
	RIMMINGTON(1, 2953, 3224),
	TAVERLEY(2, 2894, 3465),
	POLLNIVNEACH(3, 3340, 3004),
	HOSIDIUS(4, 1743, 3517),
	RELLEKKA(5, 2670, 3631),
	ALDARIN(6, 1422, 2964),
	BRIMHAVEN(7, 2758, 3178),
	YANILLE(8, 2544, 3095),
	PRIFDDINAS(9, 3239, 6076);

	public final int varbitValue;
	public final int portalX, portalY;

	@Nullable
	public static HouseLocation fromVarbit(int value) {
		for (HouseLocation location : values())
			if (location.varbitValue == value)
				return location;
		return null;
	}
}
