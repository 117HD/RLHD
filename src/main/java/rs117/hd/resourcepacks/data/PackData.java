package rs117.hd.resourcepacks.data;

import lombok.Data;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

@Data
public class PackData {
    String internalName;
    Map<String, BufferedImage> materials = new HashMap<>();
}
