package rs117.hd.tools;

import java.util.Arrays;
import java.util.HashSet;
import rs117.hd.data.environments.Area;
import rs117.hd.utils.AABB;

public class ExportAreasForExplv {
	public static void main(String... args) {
		StringBuilder sb = new StringBuilder();
		HashSet<AABB> aabbs = new HashSet<>();
		for (var area : Area.values()) {
			if (area == Area.ALL || area == Area.NONE)
				continue;
			aabbs.addAll(Arrays.asList(area.aabbs));
		}

		sb.append("Area[] area = { ");
		for (var aabb : aabbs)
			sb.append(String.format("new Area(%d, %d, %d, %d), ", aabb.minX, aabb.minY, aabb.maxX, aabb.maxY));
		sb.append("};");
		System.out.println(sb);
	}
}
