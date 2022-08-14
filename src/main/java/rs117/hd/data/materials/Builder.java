package rs117.hd.data.materials;

import lombok.NonNull;
import lombok.Setter;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;

import java.util.function.Consumer;
import java.util.function.Function;

@Setter
class Builder {
    public int id;
    public Integer[] ids = null;
    public Area area;
    public GroundMaterial groundMaterial;
    public WaterType waterType = WaterType.NONE;
    public boolean blended = true;
    public boolean blendedAsType = false;
    public int hue = -1;
    public int shiftHue = 0;
    public int saturation = -1;
    public int shiftSaturation = 0;
    public int lightness = -1;
    public int shiftLightness = 0;
    public Underlay underlayToReplace;
    public Function<HdPluginConfig, Boolean> replacementCondition;


    Builder apply(Consumer<Builder> consumer) {
        consumer.accept(this);
        return this;
    }

    Builder setId(int id) {
        this.id = id;
        return this;
    }

    Builder setIds(Integer... ids) {
        this.ids = ids;
        return this;
    }


    Builder setGroundMaterial(GroundMaterial groundMaterial) {
        this.groundMaterial = groundMaterial;
        return this;
    }

    Builder setWaterType(WaterType waterType) {
        this.waterType = waterType;
        this.groundMaterial = waterType.getGroundMaterial();
        return this;
    }

    Builder setArea(Area area) {
        this.area = area;
        return this;
    }

    Builder replaceIf(@NonNull Underlay underlayToReplace, @NonNull Function<HdPluginConfig, Boolean> condition) {
        this.underlayToReplace = underlayToReplace;
        this.replacementCondition = condition;
        return this;
    }

    Builder shiftLightness(int shiftLightness) {
        this.shiftLightness = shiftLightness;
        return this;
    }

    Builder lightness(int lightness) {
        this.lightness = lightness;
        return this;
    }

    Builder shiftSaturation(int shiftSaturation) {
        this.shiftSaturation = shiftSaturation;
        return this;
    }


    Builder saturation(int saturation) {
        this.saturation = saturation;
        return this;
    }

    Builder shiftHue(int shiftHue) {
        this.shiftHue = shiftHue;
        return this;
    }

    Builder hue(int hue) {
        this.hue = hue;
        return this;
    }

    Builder isBlendedAsType(boolean blended) {
        this.blendedAsType = blended;
        return this;
    }

    Builder isBlended(boolean blended) {
        this.blended = blended;
        return this;
    }

}