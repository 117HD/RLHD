package rs117.hd.data.materials;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Setter;
import lombok.experimental.Accessors;
import rs117.hd.HdPlugin;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;
import rs117.hd.scene.tile_overrides.ExpressionBasedReplacement;
import rs117.hd.scene.tile_overrides.TileOverride;

import static rs117.hd.utils.HDUtils.clamp;

@Setter
@Accessors(fluent = true)
@Deprecated
public class TileOverrideBuilder<T> {
    public Integer[] ids = null;
    public Area area = Area.ALL;
    public GroundMaterial groundMaterial = GroundMaterial.NONE;
    public WaterType waterType = WaterType.NONE;
    public boolean blended = true;
    public boolean blendedAsOpposite = false;
	private int hue = -1;
    public int shiftHue = 0;
	public int minHue = 0;
	public int maxHue = 63;
	private int saturation = -1;
	public int shiftSaturation = 0;
	public int minSaturation = 0;
	public int maxSaturation = 7;
	private int lightness = -1;
	public int shiftLightness = 0;
	public int minLightness = 0;
	public int maxLightness = 127;
	public int uvOrientation = 0;
	public float uvScale = 1;
	public List<TileOverride.IReplacement<T>> replacements = new ArrayList<>();

	public void normalize() {
		// Ensure values are within valid ranges
		if (hue != -1) {
			minHue = maxHue = clamp(hue, 0, 63);
		} else {
			minHue = clamp(minHue, 0, 63);
			maxHue = clamp(maxHue, 0, 63);
		}
		if (saturation != -1) {
			minSaturation = maxSaturation = clamp(saturation, 0, 7);
		} else {
			minSaturation = clamp(minSaturation, 0, 7);
			maxSaturation = clamp(maxSaturation, 0, 7);
		}
		if (lightness != -1) {
			minLightness = maxLightness = clamp(lightness, 0, 127);
		} else {
			minLightness = clamp(minLightness, 0, 127);
			maxLightness = clamp(maxLightness, 0, 127);
		}
	}

    TileOverrideBuilder<T> apply(Consumer<TileOverrideBuilder<T>> consumer) {
        consumer.accept(this);
        return this;
    }

    TileOverrideBuilder<T> ids(Integer... ids) {
        if (this.ids != null && this.ids.length > 0)
            throw new IllegalStateException(
                "Attempted to overwrite IDs " + Arrays.toString(this.ids) +
                " with IDs " + Arrays.toString(ids) +
                " in " + TileOverrideBuilder.class.getSimpleName() + "." +
                "This is likely a mistake.");
        this.ids = ids;
        // Overlay & underlay IDs were always meant to be treated as unsigned,
        // so our negative IDs broke when Jagex switched from bytes to shorts
        for (int i = 0; i < ids.length; i++)
            if (ids[i] < 0)
                ids[i] &= 0xff;
        return this;
    }

	TileOverrideBuilder<T> waterType(WaterType waterType) {
		this.waterType = waterType;
		this.groundMaterial = GroundMaterial.NONE;
		return this;
	}

	TileOverrideBuilder<T> uvScale(float uvScale) {
		this.uvScale = 1 / uvScale;
		return this;
	}

	TileOverrideBuilder<T> replaceWithIf(@Nullable T replacement, @Nonnull Function<HdPlugin, Boolean> condition) {
		replacements.add((plugin, scene, tile, original) -> condition.apply(plugin) ? replacement : original);
		return this;
	}

	TileOverrideBuilder<T> replacementResolver(TileOverride.IReplacement<T> resolver) {
		replacements.add(resolver);
		return this;
	}

	public static Map<String, Object> SEASON_CONSTANTS = new HashMap<>() {{
		for (var season : SeasonalTheme.values())
			put(season.name(), season.ordinal());
	}};

	TileOverrideBuilder<T> seasonalReplacement(SeasonalTheme season, T replacement) {
		replacements.add(new ExpressionBasedReplacement<T>(replacement, SEASON_CONSTANTS, "season == " + season.name()));
		return this;
	}

	TileOverrideBuilder<T> fallBackTo(T replacement) {
		replacements.add(new ExpressionBasedReplacement<>(replacement, null, "true"));
		return this;
	}

	TileOverrideBuilder<T> replaceWithIf(T replacement, String... cases) {
		replacements.add(new ExpressionBasedReplacement<>(replacement, null, cases));
		return this;
	}
}
