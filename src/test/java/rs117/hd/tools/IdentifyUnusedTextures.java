package rs117.hd.tools;

import java.util.Arrays;
import java.util.stream.Collectors;
import rs117.hd.data.materials.Material;

import static rs117.hd.utils.ResourcePath.path;

public class IdentifyUnusedTextures {
	public static void main(String... args) {
		var path = path("src/main/resources/rs117/hd/scene/textures");
		var unusedTextures = Arrays.stream(path.toFile().listFiles())
			.map(f -> f.getName())
			.filter(filename -> {
				var materialName = path(filename)
					.setExtension("")
					.getFilename()
					.toUpperCase();
				try {
					Material.valueOf(materialName);
					return false;
				} catch (Exception ex) {
					return true;
				}
			})
			.collect(Collectors.toList());
		System.out.println("Unused textures:");
		for (var filename : unusedTextures)
			System.out.println(filename);
	}
}
