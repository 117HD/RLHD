package rs117.hd.resourcepacks.data;

import lombok.Data;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Data
public class PackData {
    Map<String, InputStream> textures = new HashMap<>();
    Map<String, InputStream> normals = new HashMap<>();
}
