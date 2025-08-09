package rs117.hd.tools;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;

import static rs117.hd.utils.ResourcePath.path;

public class ExportMaterials {
	public static void main(String... args) throws IOException, NoSuchFieldException, IllegalAccessException {
		Props.DEVELOPMENT = true;
		Props.set("rlhd.resource-path", "src/main/resources");
		var path = path(TextureManager.class, "materials.json");
		var gson = GsonUtils.wrap(new Gson());

		List<Material> list = new ArrayList<>(List.of(Material.REQUIRED_MATERIALS));

		for (var old : rs117.hd.data.materials.Material.values()) {
			if (old == rs117.hd.data.materials.Material.NONE ||
				old == rs117.hd.data.materials.Material.VANILLA)
				continue;

			var newmat = new Material.Definition();
			newmat.name = old.name();
			for (var oldfield : rs117.hd.data.materials.Material.class.getDeclaredFields()) {
				if (oldfield.isEnumConstant() ||
					oldfield.isSynthetic() ||
					Modifier.isStatic(oldfield.getModifiers()))
					continue;

				var f = Material.class.getDeclaredField(oldfield.getName());
				f.setAccessible(true);

				if (oldfield.getType() == rs117.hd.data.materials.Material.class) {
					var oldmat = (rs117.hd.data.materials.Material) oldfield.get(old);
					if (oldmat == null)
						continue;

					f.set(newmat, new Material.Reference(oldmat.name()));
					continue;
				}

				if (oldfield.getName().equals("materialsToReplace")) {
					var oldMaterialsToReplace = (List<rs117.hd.data.materials.Material>) oldfield.get(old);
					newmat.materialsToReplace = new ArrayList<>();
					for (var oldmat : oldMaterialsToReplace)
						if (oldmat != null)
							newmat.materialsToReplace.add(oldmat.name());
					continue;
				}

				f.set(newmat, oldfield.get(old));
			}

			list.add(newmat);
		}

		var map = new HashMap<String, Material>();
		for (var m : list)
			map.put(m.name, m);
		for (var m : list)
			m.normalize(map);

		list.subList(0, Material.REQUIRED_MATERIALS.length).clear();
		path.writeString(gson.toJson(list));
	}
}
