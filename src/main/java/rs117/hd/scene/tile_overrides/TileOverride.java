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
import net.runelite.api.*;
import rs117.hd.HdPlugin;
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

	public String name = "Unnamed";
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
	private LinkedHashMap<String, JsonElement> replacementExpressions;

	public transient int[] ids;
	public transient List<Replacement<TileOverride>> replacements;
	public transient boolean queriedAsOverlay;

	// Used in conversion from old format
	public transient int index;
	public boolean REQUIRES_MANUAL_TRANSLATION = false;

	private TileOverride(String name, GroundMaterial groundMaterial) {
		this.name = name;
		this.groundMaterial = groundMaterial;
	}

	public void normalize(TileOverride[] allOverrides, Map<String, Object> constants) {
		int numOverlays = overlayIds == null ? 0 : overlayIds.length;
		int numUnderlays = underlayIds == null ? 0 : underlayIds.length;
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

		if (replacementExpressions != null) {
			replacements = new ArrayList<>();
			for (var entry : replacementExpressions.entrySet()) {
				var expr = parseReplacementExpressions(entry, allOverrides, constants);

				if (expr.isConstant()) {
					if (expr.predicate.test(null)) {
						replacements.add(expr);
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
	private static ExpressionBasedReplacement<TileOverride> parseReplacementExpressions(
		Map.Entry<String, JsonElement> expressions,
		TileOverride[] allOverrides,
		Map<String, Object> constants
	) {
		var name = expressions.getKey();
		TileOverride replacement = null;
		if (name == null || name.equals(NONE.name)) {
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

		return new ExpressionBasedReplacement<>(replacement, constants, expressions.getValue());
	}

	public TileOverride resolveConstantReplacements() {
		if (replacements != null) {
			// Check if the override always resolves to the same replacement override
			for (var replacement : replacements) {
				if (!(replacement instanceof ExpressionBasedReplacement))
					break;

				var expr = (ExpressionBasedReplacement<TileOverride>) replacement;
				if (!expr.isConstant())
					break;

				if (expr.predicate.test(null)) {
					log.debug("Statically replacing override {} with {}", name, expr.replacement.name);
					return expr.replacement;
				}
			}
		}

		return this;
	}

	public TileOverride resolveReplacements(Scene scene, Tile tile, HdPlugin plugin) {
		if (replacements != null) {
			for (var resolver : replacements) {
				TileOverride replacement = resolver.resolve(plugin, scene, tile, this);
				if (replacement == null)
					replacement = NONE;
				if (replacement != this) {
					replacement.queriedAsOverlay = queriedAsOverlay;
					return replacement.resolveReplacements(scene, tile, plugin);
				}
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

	@Deprecated
	@FunctionalInterface
	public interface IReplacement<T> {
		@Nullable
		T resolve(HdPlugin plugin, Scene scene, Tile tile, T original);
	}

	public abstract static class Replacement<T> implements IReplacement<T> {
		public final T replacement;

		public Replacement(T replacement) {
			this.replacement = replacement;
		}
	}
}
