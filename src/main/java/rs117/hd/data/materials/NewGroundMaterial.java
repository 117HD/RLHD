package rs117.hd.data.materials;

import java.util.Random;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class NewGroundMaterial {
	public static final NewGroundMaterial NONE = new NewGroundMaterial("NONE", Material.NONE);

	public String name;
	public Material[] materials;

	public NewGroundMaterial(String name, Material... materials) {
		this.name = name;
		this.materials = materials;
	}

	/**
	 * Get a random material based on given coordinates.
	 *
	 * @param plane  The plane number.
	 * @param worldX The X coordinate in world space.
	 * @param worldY The Y coordinate in world space.
	 * @return A randomly selected Material.
	 */
	public Material getRandomMaterial(int plane, int worldX, int worldY) {
		// Generate a seed from the tile coordinates for consistent 'random' results between scene loads.
		long seed = (long) (plane + 1) * 10 * (worldX % 100) * 20 * (worldY % 100) * 30;
		Random randomTex = new Random(seed);
		int randomIndex = randomTex.nextInt(materials.length);
		return materials[randomIndex];
	}
}
