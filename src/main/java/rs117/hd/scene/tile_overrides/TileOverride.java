package rs117.hd.scene.tile_overrides;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;
import rs117.hd.data.materials.GroundMaterial;
import rs117.hd.utils.Props;

import static rs117.hd.utils.HDUtils.clamp;

@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class TileOverride {
	public static final int OVERLAY_FLAG = 1 << 31;
	public static final TileOverride NONE = new TileOverride("NONE", GroundMaterial.DIRT);

	@Nullable
	public String name;
	public String description;
	public Area area = Area.NONE;
	public int[] overlayIds;
	public int[] underlayIds;
	public GroundMaterial groundMaterial = GroundMaterial.NONE;
	public WaterType waterType = WaterType.NONE;
	public boolean blended = true;
	public boolean blendedAsOpposite = false;
	public int shiftHue;
	public int minHue;
	public int maxHue = 63;
	public int shiftSaturation;
	public int minSaturation;
	public int maxSaturation = 7;
	public int shiftLightness;
	public int minLightness;
	public int maxLightness = 127;
	public int uvOrientation;
	public float uvScale = 1;
	@SerializedName("replacements")
	public LinkedHashMap<String, JsonElement> rawReplacements;

	public transient int index;
	public transient int[] ids;
	public transient List<ExpressionBasedReplacement> replacements;
	public transient boolean queriedAsOverlay;

	private TileOverride(@Nullable String name, GroundMaterial groundMaterial) {
		this.name = name;
		this.groundMaterial = groundMaterial;
		this.index = Integer.MAX_VALUE; // Prioritize any-match overrides over this
	}

	@Override
	public String toString() {
		if (name != null)
			return name;
		if (description != null)
			return description;
		if (area != null)
			return area.name();
		return "Unnamed";
	}

	public void normalize(TileOverride[] allOverrides, Map<String, Object> constants) {
		int numOverlays = overlayIds == null ? 0 : overlayIds.length;
		int numUnderlays = underlayIds == null ? 0 : underlayIds.length;
		int numIds = numOverlays + numUnderlays;
		if (numIds > 0) {
			ids = new int[numOverlays + numUnderlays];
			int i = 0;
			for (int j = 0; j < numOverlays; j++) {
				int id = overlayIds[j];
				ids[i++] = OVERLAY_FLAG | id;
			}
			for (int j = 0; j < numUnderlays; j++) {
				int id = underlayIds[j];
				ids[i++] = id;
			}
		}

		if (area == null) {
			log.warn("Undefined area in tile override: {}", this);
			area = Area.NONE;
		}
		if (groundMaterial == null) {
			log.warn("Undefined ground material in tile override: {}", this);
			groundMaterial = GroundMaterial.NONE;
		}
		if (waterType == null) {
			log.warn("Undefined water type in tile override: {}", this);
			waterType = WaterType.NONE;
		}

		// Convert UV scale to reciprocal, so we can multiply instead of dividing later
		uvScale = 1 / uvScale;

		if (rawReplacements != null) {
			replacements = new ArrayList<>();
			for (var entry : rawReplacements.entrySet()) {
				var expr = parseReplacementExpressions(entry, allOverrides, constants);

				if (expr.isConstant()) {
					if (expr.predicate.test(null)) {
						replacements.add(expr);
						// Parse unnecessary replacements only during development
						if (!Props.DEVELOPMENT)
							break;
					} else {
						continue;
					}
				}

				replacements.add(expr);
			}
		}
	}

	@NonNull
	private static ExpressionBasedReplacement parseReplacementExpressions(
		Map.Entry<String, JsonElement> expressions,
		TileOverride[] allOverrides,
		Map<String, Object> constants
	) {
		var name = expressions.getKey();
		TileOverride replacement = null;
		if (name == null) {
			log.warn("Null is reserved for future use");
			replacement = NONE;
		} else {
			if (name.equals(NONE.name)) {
				replacement = NONE;
			} else {
				for (var other : allOverrides) {
					if (name.equals(other.name)) {
						replacement = other;
						break;
					}
				}
				if (replacement == null) {
					replacement = NONE;
					if (Props.DEVELOPMENT)
						throw new IllegalStateException("Unknown tile override: '" + name + "'");
				}
			}
		}

		return new ExpressionBasedReplacement(replacement, constants, expressions.getValue());
	}

	public TileOverride resolveConstantReplacements() {
		if (replacements != null) {
			// Check if the override always resolves to the same replacement override
			for (var replacement : replacements) {
				if (!replacement.isConstant())
					break;

				if (replacement.predicate.test(null))
					return replacement.replacement;
			}
		}

		return this;
	}

	public int modifyColor(int jagexHsl) {
		int h = jagexHsl >> 10 & 0x3F;
		h += shiftHue;
		h = clamp(h, minHue, maxHue);

		int s = jagexHsl >> 7 & 7;
		s += shiftSaturation;
		s = clamp(s, minSaturation, maxSaturation);

		int l = jagexHsl & 0x7F;
		l += shiftLightness;
		l = clamp(l, minLightness, maxLightness);

		return h << 10 | s << 7 | l;
	}
}
