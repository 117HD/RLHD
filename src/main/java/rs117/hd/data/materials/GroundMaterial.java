/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.data.materials;

import java.util.Random;
import lombok.Getter;

@Getter
public enum GroundMaterial {
	NONE(Material.NONE),
	SKULL_OBELISK(Material.SKULLS),
	TRANSPARENT(Material.TRANSPARENT),
	GRASS_1(Material.GRASS_1, Material.GRASS_2, Material.GRASS_3),
	OVERWORLD_GRASS_1(Material.GRASS_1, Material.GRASS_2, Material.GRASS_3),
	GRASS_SCROLLING(Material.GRASS_SCROLLING),
	STONE_SCROLLING(Material.STONE_SCROLLING),
	DIRT(Material.DIRT_1, Material.DIRT_2),
	SNOW_1(Material.SNOW_1, Material.SNOW_1, Material.SNOW_2, Material.SNOW_3, Material.SNOW_3, Material.SNOW_4),
	SNOW_2(Material.SNOW_2, Material.SNOW_4),
	GRAVEL(Material.GRAVEL),
	FALADOR_PATHS(Material.FALADOR_PATH_BRICK),
	VARROCK_PATHS(Material.JAGGED_STONE_TILE),
	VARIED_DIRT(Material.GRAVEL, Material.DIRT_1, Material.DIRT_2),
	VARIED_DIRT_SHINY(Material.GRAVEL_SHINY, Material.DIRT_1_SHINY, Material.DIRT_2_SHINY),
	TILE_SMALL(Material.TILE_SMALL_1),
	CARPET(Material.CARPET),
	BRICK(Material.BRICK),
	BRICK_BROWN(Material.BRICK_BROWN),
	GRUNGE(Material.GRUNGE_1, Material.GRUNGE_2, Material.GRUNGE_1),
	GRUNGE_2(Material.GRUNGE_2),
	SUBMERGED_GRUNGE_2(Material.SUBMERGED_GRUNGE_2),

	TILES_2x2_1(Material.TILES_2X2_1),
	TILES_2x2_2(Material.TILES_2X2_2),
	TILES_2X2_1_GLOSS(Material.TILES_2X2_1_GLOSS),
	TILES_2X2_2_GLOSS(Material.TILES_2X2_2_GLOSS),
	TILES_2X2_1_SEMIGLOSS(Material.TILES_2X2_1_SEMIGLOSS),
	TILES_2X2_2_SEMIGLOSS(Material.TILES_2X2_2_SEMIGLOSS),

	MARBLE_1(Material.MARBLE_1, Material.MARBLE_2, Material.MARBLE_3),
	MARBLE_2(Material.MARBLE_3, Material.MARBLE_1, Material.MARBLE_2),
	MARBLE_1_GLOSS(Material.MARBLE_1_GLOSS, Material.MARBLE_2_GLOSS, Material.MARBLE_3_GLOSS),
	MARBLE_2_GLOSS(Material.MARBLE_3_GLOSS, Material.MARBLE_1_GLOSS, Material.MARBLE_2_GLOSS),
	MARBLE_1_SEMIGLOSS(Material.MARBLE_1_SEMIGLOSS, Material.MARBLE_2_SEMIGLOSS, Material.MARBLE_3_SEMIGLOSS),
	MARBLE_2_SEMIGLOSS(Material.MARBLE_3_SEMIGLOSS, Material.MARBLE_1_SEMIGLOSS, Material.MARBLE_2_SEMIGLOSS),
	MARBLE_DARK(Material.MARBLE_DARK),

	LASSAR_UNDERCITY_TILES(Material.LASSAR_UNDERCITY_TILES),
	LASSAR_UNDERCITY_TILES_SUBMERGED(Material.LASSAR_UNDERCITY_TILES_SUBMERGED),

	BLANK_SEMIGLOSS(Material.BLANK_SEMIGLOSS),

	SAND(Material.SAND_1, Material.SAND_2, Material.SAND_3),

	UNDERWATER_GENERIC(Material.DIRT_1, Material.DIRT_2),

	WOOD_PLANKS_1(Material.WOOD_PLANKS_1),
	CLEAN_WOOD_FLOOR(Material.CLEAN_WOOD_FLOOR),

	HD_LAVA(
		Material.HD_LAVA_1,
		Material.HD_LAVA_2,
		Material.HD_LAVA_1,
		Material.HD_LAVA_1,
		Material.HD_LAVA_2,
		Material.HD_MAGMA_1,
		Material.HD_MAGMA_2
	),

	STONE_PATTERN(Material.STONE_PATTERN),
	CONCRETE(Material.CONCRETE),
	SAND_BRICK(Material.SAND_BRICK),
	CLEAN_TILE(Material.CLEAN_TILE),
	WORN_TILES(Material.WORN_TILES),
	WATER_FLAT(Material.WATER_FLAT),
	HD_WOOD_PLANKS_1(Material.HD_WOOD_PLANKS_1),
	ICE_1(Material.ICE_1_HIGHGLOSS),
	ICE_4(Material.ICE_4),
	WINTER_JAGGED_STONE_TILE(Material.WINTER_JAGGED_STONE_TILE),
	WINTER_JAGGED_STONE_TILE_LIGHT(Material.WINTER_JAGGED_STONE_TILE_LIGHT),
	WINTER_JAGGED_STONE_TILE_LIGHT_2(Material.WINTER_JAGGED_STONE_TILE_LIGHTER),
	ROCKY_CAVE_FLOOR(Material.GRUNGE_2, Material.ROCK_2, Material.ROCK_2, Material.ROCK_1, Material.GRAVEL),
	EARTHEN_CAVE_FLOOR(Material.GRUNGE_1, Material.DIRT_2, Material.DIRT_2, Material.ROCK_1, Material.DIRT_2),
	STONE_CAVE_FLOOR(Material.STONE, Material.ROCK_1, Material.ROCK_2),
	SANDY_STONE_FLOOR(Material.SAND_2, Material.STONE_NORMALED, Material.ROCK_2, Material.STONE_NORMALED),
	PACKED_EARTH(Material.DIRT_1, Material.GRAVEL, Material.DIRT_1, Material.DIRT_1, Material.DIRT_2),
	GRASSY_DIRT(Material.GRASS_1, Material.DIRT_1, Material.GRASS_2, Material.DIRT_2, Material.GRASS_3)
	;

	private final Material[] materials;

	GroundMaterial(Material... materials) {
		this.materials = materials;
	}

	public Material getRandomMaterial(int plane, int worldX, int worldY) {
		// Generate a seed from the tile coordinates for
		// consistent 'random' results between scene loads.
		// This seed creates a patchy, varied terrain
		long seed = (long) (plane + 1) * 10 * (worldX % 100) * 20 * (worldY % 100) * 30;
		Random randomTex = new Random(seed);
		int randomInt = randomTex.nextInt(this.materials.length);
		return this.materials[randomInt];
	}
}
