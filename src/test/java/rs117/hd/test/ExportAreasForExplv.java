package rs117.hd.test;

import rs117.hd.data.environments.Area;

public class ExportAreasForExplv {
	public static void main(String... args) {
		StringBuilder sb = new StringBuilder();
		sb.append("Area[] area = { ");
		for (var area : Area.values())
			for (var aabb : area.aabbs)
				sb.append(String.format("new Area(%d, %d, %d, %d), ", aabb.minX, aabb.minY, aabb.maxX, aabb.maxY));
		sb.append("};");
		System.out.println(sb);
	}
}
