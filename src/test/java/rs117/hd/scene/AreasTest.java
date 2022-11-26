package rs117.hd.scene;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.WorldUtil;
import org.apache.commons.text.WordUtils;
import org.junit.Test;
import rs117.hd.data.environments.Area;
import rs117.hd.scene.area.AreaData;
import rs117.hd.scene.area.AreaManager;
import rs117.hd.utils.AABB;
import rs117.hd.utils.Env;
import rs117.hd.utils.ResourcePath;

import java.io.IOException;
import java.util.*;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class AreasTest {

    private static final String ENV_AREA_PATH = "RLHD_AREA_PATH";
    private static final ResourcePath areaDataPath =  Env.getPathOrDefault(ENV_AREA_PATH,
            () -> path(AreaManager.class, "areas.json"));

    public static ArrayList<AreaData> areas = new ArrayList<AreaData>();

    public static Gson gson = new Gson();

    public static void main(String[] args) throws IOException
    {
        OptionParser parser = new OptionParser();

        areaDataPath.watch(path -> {
            try {
                AreaData[] temp = path.loadJson(gson, AreaData[].class);
                Collections.addAll(areas, temp);
                log.debug("Loaded {} areas", areas.size());
            } catch (IOException ex) {
                log.error("Failed to load areas: ", ex);
            }
        });

        Area[] knownAreas = Arrays.stream(Area.values()).filter(it -> !it.name().contains("ALL")).filter(it -> !it.name().contains("UNKNOWN")).toArray(Area[]::new);

        Map<String, String> descriptionsFound = new HashMap<>();

        areas.forEach( area -> {
            for (AABB aabb : area.aabbs) {
                for (Area areaEnum : knownAreas) {
                    for (AABB aabbEnum : areaEnum.getAabbs()) {
                        if (aabbEnum.intersects(aabb)) {
                            if (area.description.equals("UNKNOWN")) {
                                descriptionsFound.put(String.valueOf(area.aabbs[0]), formatName(areaEnum.name()));
                            }
                        }
                    }
                }
            }

            if (area.region != -1) {
                for (Area areaEnum : knownAreas) {
                    for (AABB aabbEnum : areaEnum.getAabbs()) {
                        int regionID = new WorldPoint(aabbEnum.maxX,aabbEnum.maxY,0).getRegionID();
                        if (area.region == regionID) {
                            if (area.description.equals("UNKNOWN")) {
                                descriptionsFound.put(String.valueOf(area.region),formatName(areaEnum.name()));
                            }
                        }
                    }
                }
            }
        });

        descriptionsFound.forEach( (region, found) -> System.out.println(region + " - " + found));

        OptionSpec<?> explv = parser.accepts("explv", "gets all areas in a format explv can read");
        OptionSet options = parser.parse(args);

            StringBuilder data = new StringBuilder("Area[] area = {" + System.lineSeparator());
            for(AreaData area : areas) {
                for (AABB aabb : area.aabbs) {
                    data.append("new Area(").append(aabb.minX).append(", ").append(aabb.minY).append(", ").append(aabb.maxX).append(" , ").append(aabb.maxY).append("), ").append(System.lineSeparator());
                }
                if (area.region != -1) {
                    final int REGIONS_PER_COLUMN = 256;
                    int baseX = (int)Math.floor((float)area.region / REGIONS_PER_COLUMN) * Constants.REGION_SIZE;
                    int baseY = (area.region % REGIONS_PER_COLUMN) * Constants.REGION_SIZE;
                    int oppositeX = baseX + Constants.REGION_SIZE;
                    int oppositeY = baseY + Constants.REGION_SIZE;
                    AABB[] aabbs1 = new AABB[]{new AABB(baseX, baseY, oppositeX, oppositeY)};
                    for (AABB aabb : aabbs1) {
                        data.append("new Area(").append(aabb.minX).append(", ").append(aabb.minY).append(", ").append(aabb.maxX).append(" , ").append(aabb.maxY).append("), ").append(System.lineSeparator());
                    }
                }
            }
            data.append("};");

            System.out.println(data);

    }

    public static String formatName(String desc) {
        return WordUtils.capitalizeFully(desc.toLowerCase().replace("_"," "));
    }


}
