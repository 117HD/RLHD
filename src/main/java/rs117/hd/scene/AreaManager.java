package rs117.hd.scene;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class AreaManager {
	private static final ResourcePath AREA_PATH = Props.getPathOrDefault(
		"rlhd.area-path",
		() -> path(AreaManager.class, "areas.json")
	);

	@Inject
	private HdPlugin plugin;

	private FileWatcher.UnregisterCallback fileWatcher;

	public static Area[] AREAS = new Area[0];

	public void startUp() {
		fileWatcher = AREA_PATH.watch((path, first) -> {
			if (!first) {
				// This is kind of slow, but the easiest to implement
				plugin.restartPlugin();
				return;
			}

			try {
				Area[] areas = path.loadJson(plugin.getGson(), Area[].class);
				if (areas == null)
					throw new IOException("Empty or invalid: " + path);

				AREAS = Arrays.copyOf(areas, areas.length + 2);
				AREAS[AREAS.length - 2] = Area.ALL;
				AREAS[AREAS.length - 1] = Area.NONE;

				for (Area area : areas)
					area.normalize();

				Area.OVERWORLD = getArea("OVERWORLD");

				log.debug("Loaded {} areas", areas.length);
			} catch (IOException ex) {
				log.error("Failed to load areas:", ex);
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		AREAS = new Area[0];
	}

	public Area getArea(String name) {
		for (Area area : AREAS)
			if (name.equals(area.name))
				return area;
		return Area.NONE;
	}

	@Slf4j
	public static class JsonAdapter extends TypeAdapter<Area> {
		@Override
		public Area read(JsonReader in) throws IOException {
			var token = in.peek();
			if (token == JsonToken.NULL)
				return null;

			if (token == JsonToken.BEGIN_OBJECT)
				throw new IllegalStateException(
					"This is only meant for mapping area names to existing areas, not parse new ones. Unexpected token " + token + " at "
					+ GsonUtils.location(in));

			if (token != JsonToken.STRING) {
				log.warn("Expected an area name instead of {} at {}", token, GsonUtils.location(in), new Throwable());
				return Area.NONE;
			}

			var str = in.nextString();
			for (Area area : AREAS)
				if (str.equals(area.name))
					return area;

			log.warn("No area exists with the name '{}' at {}", str, GsonUtils.location(in), new Throwable());
			return Area.NONE;
		}

		@Override
		public void write(JsonWriter out, Area area) throws IOException {
			if (area == null) {
				out.nullValue();
			} else {
				out.value(area.name);
			}
		}
	}
}
