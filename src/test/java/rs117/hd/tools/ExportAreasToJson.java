package rs117.hd.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.annotations.JsonAdapter;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;
import rs117.hd.data.environments.Area;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.RegionBox;

import static rs117.hd.utils.ResourcePath.path;

public class ExportAreasToJson {
	private static class SerializableArea {
		String name;
		@Nullable
		Area[] areas;
		@Nullable
		int[] regions;
		@Nullable
		@JsonAdapter(RegionBox.JsonAdapter.class)
		RegionBox[] regionBoxes;
		@Nullable
		@JsonAdapter(AABB.JsonAdapter.class)
		AABB[] aabbs;

		public SerializableArea(Area area) {
			name = area.name();
			if (area.merge.areas != null && area.merge.areas.length > 0)
				areas = area.merge.areas;
			if (area.merge.aabbs != null && area.merge.aabbs.length > 0)
				aabbs = area.merge.aabbs;
			if (area.merge.regionIds != null && area.merge.regionIds.length > 0)
				regions = area.merge.regionIds;
			if (area.merge.regionBoxes != null && area.merge.regionBoxes.length > 0)
				regionBoxes = area.merge.regionBoxes;
		}
	}

	public static void main(String... args) throws IOException {
		var gson = new GsonBuilder()
			.setLenient()
			.setPrettyPrinting()
			.create();

		path("src/main/resources/rs117/hd/scene/areas.json")
			.writeString(gson.toJson(
				Arrays.stream(Area.values())
					.map(SerializableArea::new)
					.toArray()));
	}
}
