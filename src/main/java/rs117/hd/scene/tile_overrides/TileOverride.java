package rs117.hd.scene.tile_overrides;

import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionPredicate;
import rs117.hd.utils.Props;
import rs117.hd.utils.VariableSupplier;

import static rs117.hd.utils.ExpressionParser.asExpression;
import static rs117.hd.utils.ExpressionParser.parseExpression;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class TileOverride {
	public static final int OVERLAY_FLAG = 1 << 31;
	public static final TileOverride NONE = new TileOverride("NONE", GroundMaterial.DIRT);

	@Nullable
	public String name;
	public String description;
	@JsonAdapter(AreaManager.Adapter.class)
	public Area area = Area.NONE;
	public int[] overlayIds;
	public int[] underlayIds;
	@JsonAdapter(GroundMaterial.Adapter.class)
	public GroundMaterial groundMaterial = GroundMaterial.NONE;
	@JsonAdapter(WaterType.Adapter.class)
	public WaterType waterType = WaterType.NONE;
	public boolean blended = true;
	public boolean blendedAsOpposite;
	public boolean forced;
	public boolean depthTested;
	private int setHue = -1;
	private int shiftHue;
	private int minHue;
	private int maxHue = 63;
	private int setSaturation = -1;
	private int shiftSaturation;
	private int minSaturation;
	private int maxSaturation = 7;
	private int setLightness = -1;
	private int shiftLightness;
	private int minLightness;
	private int maxLightness = 127;
	public int uvOrientation;
	public float uvScale = 1;
	public int heightOffset;
	@SerializedName("replacements")
	public LinkedHashMap<String, JsonElement> rawReplacements;

	public transient int index;
	public transient int[] ids;
	public transient boolean queriedAsOverlay;
	@Nonnull
	private transient List<Map.Entry<ExpressionPredicate, TileOverride>> replacements = Collections.emptyList();

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
			return area.name;
		return "Unnamed";
	}

	public boolean isConstant() {
		return replacements.isEmpty();
	}

	public void normalize(TileOverride[] allOverrides, VariableSupplier constants) {
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

		if (forced) {
			// Replace hidden tiles with white by default
			minHue = maxHue = minSaturation = maxSaturation = 0;
			minLightness = maxLightness = 127;
		}

		if (setHue != -1)
			minHue = maxHue = setHue;
		if (setSaturation != -1)
			minSaturation = maxSaturation = setSaturation;
		if (setLightness != -1)
			minLightness = maxLightness = setLightness;

		// Convert UV scale to reciprocal, so we can multiply instead of dividing later
		uvScale = 1 / uvScale;

		if (rawReplacements != null) {
			replacements = new ArrayList<>();
			for (var entry : rawReplacements.entrySet()) {
				var name = entry.getKey();
				TileOverride replacement = null;
				if (name == null) {
					assert false : "Null is reserved for future use";
					replacement = NONE;
				} else if (name.equals(NONE.name)) {
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
						// Technically, we could allow nulls, but it's indistinguishable from a parsing error atm
						if (Props.DEVELOPMENT)
							throw new IllegalStateException("Unknown tile override: '" + name + "'");
					}
				}

				var result = parseExpression(ExpressionParser.mergeJsonExpressions("||", entry.getValue()), constants);
				if (Props.DEVELOPMENT && result instanceof ExpressionParser.Expression) {
					var expr = (ExpressionParser.Expression) result;
					// Ensure all variables are defined
					var acceptedVariables = Set.of("h", "s", "l");
					for (var variable : expr.variables)
						if (!acceptedVariables.contains(variable))
							throw new IllegalStateException(String.format(
								"Expression '%s' contains unknown variable '%s'. Accepted variables: %s",
								expr, variable, String.join(", ", acceptedVariables)
							));
				}

				ExpressionPredicate predicate;
				boolean isConstant = result instanceof Boolean;
				if (isConstant) {
					if (!(boolean) result)
						continue; // Skip replacement conditions that are always false
					predicate = ExpressionPredicate.TRUE;
				} else {
					predicate = asExpression(result).toPredicate();
				}

				replacements.add(Map.entry(predicate, replacement));

				// Skip parsing the remaining unnecessary replacements, unless in development mode, where we want to parse all anyway
				if (isConstant && !Props.DEVELOPMENT)
					break;
			}
		}
	}

	public TileOverride resolveConstantReplacements() {
		// Check if the override always resolves to the same replacement override
		for (var entry : replacements) {
			var predicate = entry.getKey();
			// Stop on the first non-constant predicate
			if (predicate != ExpressionPredicate.TRUE)
				break;

			if (predicate.test()) {
				return entry.getValue();
			} else {
				assert false : "Constant false expressions should be filtered out by this point";
			}
		}

		return this;
	}

	public TileOverride resolveReplacements(VariableSupplier vars) {
		var replacement = resolveNextReplacement(vars);
		if (replacement == this)
			return replacement;
		return replacement.resolveReplacements(vars);
	}

	public TileOverride resolveNextReplacement(VariableSupplier vars) {
		for (var entry : replacements) {
			if (!entry.getKey().test(vars))
				continue;

			var replacement = entry.getValue();
			if (replacement == null)
				replacement = NONE;

			replacement.queriedAsOverlay = queriedAsOverlay;
			return replacement;
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
