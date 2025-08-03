package rs117.hd.tools;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;

import static rs117.hd.utils.ResourcePath.path;

public class ExportWaterTypes {
	public static void main(String... args) throws IOException, NoSuchFieldException, IllegalAccessException {
		Props.set("rlhd.resource-path", "src/main/resources");
		var path = path(WaterTypeManager.class, "water_types.json");
		var gson = GsonUtils.wrap(new Gson());

		var list = new ArrayList<WaterType>();
		var c = WaterType.class;
		for (var old : rs117.hd.data.WaterType.values()) {
			if (old == rs117.hd.data.WaterType.NONE)
				continue;
			var w = new WaterType();
			w.name = old.name();
			for (var f : rs117.hd.data.WaterType.class.getDeclaredFields())
				if (!f.isEnumConstant() && !f.isSynthetic())
					c.getField(f.getName()).set(w, f.get(old));

			list.add(w);
		}

		path.writeString(gson.toJson(list));
	}
}
