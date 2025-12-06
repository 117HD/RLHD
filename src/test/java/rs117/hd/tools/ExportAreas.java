package rs117.hd.tools;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.Props;

import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class ExportAreas {
	public static void main(String... args) throws IOException {
		Props.set("rlhd.resource-path", "src/main/resources");
		Area[] areas = path(AreaManager.class, "areas.json")
			.loadJson(new Gson(), Area[].class);

		HashSet<AABB> aabbs = new HashSet<>();
		HashSet<AABB> suspiciousAabbs = new HashSet<>();

		for (var area : areas) {
			if (area == Area.ALL || area == Area.NONE)
				continue;
			area.normalize();
			for (AABB aabb : area.aabbs) {
				int width = aabb.maxX + 1 - aabb.minX;
				int height = aabb.maxY + 1 - aabb.minY;
				float ratio = (float) width / height;
				if (max(ratio, 1 / ratio) > 64) {
					log.warn("Suspiciously shaped AABB (width={}, height={}) in {}: {}", width, height, area, aabb);
					suspiciousAabbs.add(aabb);
				}
			}
			aabbs.addAll(Arrays.asList(area.aabbs));
		}

		System.out.println("Suspicious AABBs:");
		printAabbs(suspiciousAabbs);
		System.out.println("\nAll AABBs:");
		printAabbs(aabbs);
	}

	public static void printAabbs(Collection<AABB> aabbs) {
		StringBuilder sb = new StringBuilder();
		sb.append("Area[] area = {");
		for (var aabb : aabbs)
			sb.append(String.format("new Area(%d, %d, %d, %d),", aabb.minX, aabb.minY, aabb.maxX, aabb.maxY));
		sb.deleteCharAt(sb.length() - 1);
		sb.append("};\n");
		sb.append("\"aabbs\": [");
		for (var aabb : aabbs)
			sb.append(String.format("[%d, %d, %d, %d],", aabb.minX, aabb.minY, aabb.maxX, aabb.maxY));
		sb.deleteCharAt(sb.length() - 1);
		sb.append("]");
		System.out.println(sb);
	}
}
