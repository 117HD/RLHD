package rs117.hd.scene;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

/**
 * GadScape's own 117 HD data, kept apart from 117 HD's files so updates from upstream merge cleanly: for each of
 * areas, environments, lights, tile overrides and model overrides, a file of the same name in
 * rs117/hd/scene/gadscape/ (written by the GadScape map editor's 117 HD window). Its entries come first, so they win
 * where 117 HD takes the first match; an entry with the same key as one of 117 HD's (an area's or a tile override's
 * name, an environment's area) replaces it.
 */
@Slf4j
public final class GadScapeData {
	private GadScapeData() {}

	public static <T> T[] merge(Gson gson, String file, @Nullable T[] base, Class<T[]> type, @Nullable Function<T, String> key) {
		ResourcePath p = path(GadScapeData.class, "gadscape", file);
		if (!p.exists())
			return base;
		T[] extra;
		try {
			extra = p.loadJson(gson, type);
		} catch (Exception e) {
			log.error("Couldn't read GadScape's {}; 117 HD's own is used", p, e);
			return base;
		}
		if (extra == null || extra.length == 0)
			return base;
		if (base == null)
			return extra;
		Set<String> keys = new HashSet<>();
		if (key != null)
			for (T t : extra) {
				String k = key.apply(t);
				if (k != null)
					keys.add(k);
			}
		List<T> out = new ArrayList<>(Arrays.asList(extra));
		for (T t : base) {
			String k = key == null ? null : key.apply(t);
			if (k == null || !keys.contains(k))
				out.add(t);
		}
		log.debug("GadScape's {}: {} entries", file, extra.length);
		return out.toArray(Arrays.copyOf(base, 0));
	}
}
