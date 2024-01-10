package rs117.hd.data.materials;

import java.util.Arrays;
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

@Setter
@Accessors(fluent = true)
class TileOverrideBuilder<T> {
    public Integer[] ids = null;
    public Area area = Area.ALL;
    public GroundMaterial groundMaterial = GroundMaterial.NONE;
    public WaterType waterType = WaterType.NONE;
    public boolean blended = true;
    public boolean blendedAsOpposite = false;
    public int hue = -1;
    public int shiftHue = 0;
    public int saturation = -1;
    public int shiftSaturation = 0;
    public int lightness = -1;
    public int shiftLightness = 0;
	public TileOverrideResolver<T> replacementResolver;

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

	TileOverrideBuilder<T> replaceWithIf(@Nullable T replacement, @Nonnull Function<HdPlugin, Boolean> condition) {
		var previousResolver = replacementResolver;
		replacementResolver = (plugin, scene, tile, override) -> {
			// Earlier replacements take precedence
			if (previousResolver != null) {
				var resolved = previousResolver.resolve(plugin, scene, tile, override);
				if (resolved != override)
					return resolved;
			}

			if (condition.apply(plugin))
				return replacement;

			return override;
		};
		return this;
	}

	TileOverrideBuilder<T> seasonalReplacement(SeasonalTheme seasonalTheme, T replacement) {
		return replaceWithIf(replacement, plugin -> plugin.configSeasonalTheme == seasonalTheme);
	}

	TileOverrideBuilder<T> resolver(@Nonnull TileOverrideResolver<T> resolver) {
		replacementResolver = resolver;
		return this;
	}
}
