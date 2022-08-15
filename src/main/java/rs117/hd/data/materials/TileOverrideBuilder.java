package rs117.hd.data.materials;

import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;

import java.util.function.Consumer;
import java.util.function.Function;

@Setter
@Accessors(fluent = true)
class TileOverrideBuilder<T> {
    public Integer[] ids = {};
    public Area area = Area.ALL;
    public GroundMaterial groundMaterial = GroundMaterial.NONE;
    public WaterType waterType = WaterType.NONE;
    public boolean blended = true;
    public boolean blendedAsType = false;
    public int hue = -1;
    public int shiftHue = 0;
    public int saturation = -1;
    public int shiftSaturation = 0;
    public int lightness = -1;
    public int shiftLightness = 0;
    public T replacement;
    public Function<HdPluginConfig, Boolean> replacementCondition;

    TileOverrideBuilder<T> apply(Consumer<TileOverrideBuilder<T>> consumer) {
        consumer.accept(this);
        return this;
    }

    TileOverrideBuilder<T> ids(Integer... ids) {
        this.ids = ids;
        return this;
    }

    TileOverrideBuilder<T> waterType(WaterType waterType) {
        this.waterType = waterType;
        this.groundMaterial = waterType.getGroundMaterial();
        return this;
    }

    TileOverrideBuilder<T> replaceWithIf(@NonNull T replacement, @NonNull Function<HdPluginConfig, Boolean> condition) {
        this.replacement = replacement;
        this.replacementCondition = condition;
        return this;
    }
}
