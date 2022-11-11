package rs117.hd.scene.area;

import lombok.Data;
import rs117.hd.data.WaterType;
import rs117.hd.data.materials.Material;

@Data
public class HorizonTile {
    private final Material material;
    private final Material materialBelow;
    private final Boolean isOverlay;
    private final WaterType waterType;
    private final int height;
    private final int color;
}
