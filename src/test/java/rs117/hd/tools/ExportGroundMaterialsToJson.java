package rs117.hd.tools;

import java.io.FileWriter;
import java.io.IOException;
import rs117.hd.data.materials.GroundMaterial;

import static rs117.hd.utils.ResourcePath.path;

public class ExportGroundMaterialsToJson {
	public static void main(String... args) {
		String json = GroundMaterial.toJson();
		System.out.println(json);
		var saveLoc = path("src/main/resources/rs117/hd/scene/ground_materials.json");
		try (FileWriter writer = new FileWriter(saveLoc.path)) {
			writer.write(json);
		} catch (IOException e) {
			System.err.println("Error writing JSON to file: " + e.getMessage());
		}
	}
}
