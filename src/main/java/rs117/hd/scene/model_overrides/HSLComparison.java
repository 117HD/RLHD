package rs117.hd.scene.model_overrides;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class HSLComparison implements ModelOverride.AhslPredicate {
	public final int targetHsl;

	@Override
	public boolean test(AHSLSupplier vars) { return vars.getInt("hsl") == targetHsl; }
}
