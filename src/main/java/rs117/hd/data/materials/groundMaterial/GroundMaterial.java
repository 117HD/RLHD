package rs117.hd.data.materials.groundMaterial;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Random;
import rs117.hd.data.materials.Material;

@Getter
@Slf4j
@AllArgsConstructor
public class GroundMaterial {

	public static final GroundMaterial NONE = new GroundMaterial("NONE", new Material[] { Material.NONE });

	private String name;
	private Material[] materials;

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
		int randomIndex = randomTex.nextInt(this.materials.length);
		return this.materials[randomIndex];
	}
}