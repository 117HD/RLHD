package rs117.hd.data.materials;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.GroundMaterialManager;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.RandomSeedGenerator;

@Getter
@Slf4j
public class GroundMaterial {
	public static final GroundMaterial NONE = new GroundMaterial("NONE", Material.NONE);
	public static final GroundMaterial DIRT = new GroundMaterial("DIRT", Material.DIRT_1, Material.DIRT_2);
	public static final GroundMaterial UNDERWATER_GENERIC = new GroundMaterial(
		"UNDERWATER_GENERIC",
		Material.DIRT_1,
		Material.DIRT_2
	);

	private final String name;
	private final Material[] materials;

	public GroundMaterial(String name, Material... materials) {
		this.name = name;
		this.materials = materials;
	}

	/**
	 * Get a random material based on the given coordinates.
	 */
	public Material getRandomMaterial(int... worldPos) {
		long seed = RandomSeedGenerator.generateSeed(worldPos);
		int r = RandomSeedGenerator.getRandomIndex(seed, materials.length);
		return materials[r];
	}

	@Override
	public String toString() {
		return name;
	}

	@Slf4j
	public static class JsonAdapter extends TypeAdapter<GroundMaterial> {
		@Override
		public GroundMaterial read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;

			if (in.peek() == JsonToken.STRING) {
				String name = in.nextString();
				for (var groundMaterial : GroundMaterialManager.GROUND_MATERIALS)
					if (name.equals(groundMaterial.name))
						return groundMaterial;

				log.warn("No ground material exists with the name '{}' at {}", name, GsonUtils.location(in), new Throwable());
			} else {
				log.warn("Unexpected type {} at {}", in.peek(), GsonUtils.location(in), new Throwable());
			}

			return null;
		}

		@Override
		public void write(JsonWriter out, GroundMaterial groundMaterial) throws IOException {
			if (groundMaterial == null) {
				out.nullValue();
			} else {
				out.value(groundMaterial.name);
			}
		}
	}
}
