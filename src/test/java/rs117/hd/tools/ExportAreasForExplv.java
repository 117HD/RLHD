package rs117.hd.tools;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.Props;

import static rs117.hd.utils.ResourcePath.path;

public class ExportAreasForExplv {
	public static void main(String... args) throws IOException {
		Props.set("rlhd.resource-path", "src/main/resources");
		Area[] areas = path(AreaManager.class, "areas.json")
			.loadJson(new Gson(), Area[].class);

		StringBuilder sb = new StringBuilder();
		HashSet<AABB> aabbs = new HashSet<>();
		for (var area : areas) {
			if (area == Area.ALL || area == Area.NONE)
				continue;
			area.normalize();
			aabbs.addAll(Arrays.asList(area.aabbs));
		}

		sb.append("Area[] area = { ");
		for (var aabb : aabbs)
			sb.append(String.format("new Area(%d, %d, %d, %d), ", aabb.minX, aabb.minY, aabb.maxX, aabb.maxY));
		sb.append("};");
		System.out.println(sb);
	}
}
