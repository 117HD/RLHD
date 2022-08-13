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

import lombok.NonNull;
import lombok.Setter;
import rs117.hd.HdPluginConfig;

import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public enum Material
{
	// - Each enum entry refers to a texture file by name, in lowercase. If a texture with the specified name is found,
	//   it will be loaded and resized to fit the dimensions of the texture array.
	// - Entries that specify a vanillaTextureIndex give names to vanilla textures, and will override the vanilla
	//   texture if a corresponding texture file exists.
	// - Materials can reuse textures by inheriting from a different material.
	// - Materials can be composed of multiple textures by setting texture map fields to materials loaded before it.

	// Default
	NONE,

	// Special textures
	TRANSPARENT,
	LAVA_FLOW_MAP,
	WATER_FLOW_MAP,
	UNDERWATER_FLOW_MAP,
	CAUSTICS_MAP,
	WATER_NORMAL_MAP_1,
	WATER_NORMAL_MAP_2,
	WATER_FOAM,

	STONE_WALL_NORMALS,
	STONE_WALL_DISPLACEMENT,
	STONE_WALL_ROUGHNESS,
	STONE_WALL(p -> p
		.setTextureScale(2, 2)
		.setNormalMap(STONE_WALL_NORMALS)
		.setDisplacementMap(STONE_WALL_DISPLACEMENT)
		.setRoughnessMap(STONE_WALL_ROUGHNESS)
		.setSpecular(1, 1)),

	// Reserve first 128 materials for vanilla OSRS texture ids
	TRAPDOOR(0),
	WATER_FLAT(1),
	BRICK(2),
	WOOD_PLANKS_1(3, p -> p
		.setSpecular(0.35f, 30f)
		.setParent(STONE_WALL)),
	DOOR(4),
	DARK_WOOD(5),
	ROOF_SHINGLES_1(6, p -> p
		.setSpecular(0.5f, 30f)),
	WOODEN_WINDOW(7),
	LEAVES_1(8, p -> p
		.setTextureScale(1.3f, 1.0f)),
	TREE_RINGS(9),
	MOSS_BRICK(10),
	CONCRETE(11),
	IRON_FENCE(12),
	PAINTING_LANDSCAPE(13),
	PAINTING_KING(14),
	MARBLE_DARK(15, p -> p
		.setSpecular(1.1f, 380f)),
	GRAIN_WOOD_ROOF(16),
	WATER_DROPLETS(17),
	STRAW(18),
	NET(19),
	BOOKCASE(20),
	ROOF_WOODEN_SLATE(21),
	WOOD_PLANKS_2(22, p -> p
		.setSpecular(0.35f, 30f)),
	BRICK_BROWN(23),
	WATER_FLAT_2(24),
	SWAMP_WATER_FLAT(25),
	SPIDER_WEB(26),
	ROOF_SLATE(27),
	MOSS(28),
	PALM_LEAF(29),
	WILLOW_LEAVES(30, p -> p
		.setTextureScale(1.025f, 1.0f)),
	LAVA(31, p -> p
		.setEmissiveStrength(1)
		.setFlowMap(LAVA_FLOW_MAP, 0.05f, 36f, 22f)
		.setScroll(0f, 3f)),
	BROWN_CARPET(32),
	MAPLE_LEAVES(33, p -> p
		.setTextureScale(1.3f, 1.0f)),
	MAGIC_STARS(34, p -> p
		.setEmissiveStrength(1.0f)),
	SAND_BRICK(35),
	DOOR_TEXTURE(36),
	CHAIN(37),
	SANDSTONE(38),
	PAINTING_ELF(39),
	FIRE_CAPE(40, p -> p
		.setEmissiveStrength(1)
		.setFlowMap(LAVA_FLOW_MAP, 0.05f, 12f, 4f)
		.setScroll(0f, -3f)),
	LEAVES_2(41, p -> p
		.setTextureScale(1.1f, 1.1f)),
	MARBLE(42, p -> p
		.setSpecular(1.0f, 400f)),
	TILE_DARK(43),
	ROOF_SHINGLES_2(44),
	ROOF_BRICK_TILE(45),
	STONE_PATTERN(46),
	TEXTURE_47(47),
	HIEROGLYPHICS(48),
	TEXTURE_49(49),
	ROOF_BRICK_TILE_GREEN(50),
	CLEAN_WOOD_FLOOR(51),
	SNOW_FLAKES(52),
	GLASS_TEXTURE(53),
	TEXTURE_54(54),
	ROOF_BRICK_TILE_DARK(55),
	RED_LAVA(56),
	WHITE_LAVA(57),
	UNUSED_LEAVES(58),
	INFERNAL_CAPE(59, p -> p
		.setEmissiveStrength(1)
		.setFlowMap(LAVA_FLOW_MAP, 0.02f, 12f, 4f)
		.setScroll(0f, 0f)),
	LEAVES_3(60),
	CLAN_SKULL(61),
	CLAN_PARTYHAT(62),
	CLAN_MAGIC_ICON(63),
	CLAN_MIME_HAPPY(64),
	CLAN_HELMET(65),
	CLAN_SWORDS(66),
	CLAN_MIME_SAD(67),
	CLAN_SKILLING(68),
	CLAN_FARMING(69),
	CLAN_ARROWS(70),
	CLAN_RUNE(71),
	CLAN_THIEVING(72),
	CLAN_BONES(73),
	CLAN_CABBAGE(74),
	CLAN_CAT(75),
	CLAN_COMPASS(76),
	CLAN_FISH(77),
	CLAN_HITPOINTS(78),
	CLAN_PRAYER(79),
	CLAN_HUNTER(80),
	CLAN_RING(81),
	CLAN_ROBINHOOD(82),
	CLAN_FLOWER(83),
	CLAN_DEFENCE(84),
	CLAN_ZAMORAK(85),
	CLAN_GROUP(86),
	CLAN_GROUP_HARDCORE(87),
	CLAN_EMPTY(88),
	SHAYZIEN_LEAVES_1(89),
	SHAYZIEN_LEAVES_2(90, p -> p
		.setTextureScale(1.1f, 1.1f)),
	WATER_ICE(91),
	SNOW_ROOF(92),
	SMALL_SNOWFLAKES(93),
	COLOR_MAP(94),
	CONCRETE_DARK(95),
	HIEROGLYPHICS_LARGE(96),
	HIEROGLYPHICS_SMALL(97),

	WHITE,
	GRAY_25,
	GRAY_50,
	GRAY_75,
	BLACK,

	BLANK_GLOSS(WHITE, p -> p
		.setSpecular(0.9f, 280f)),
	BLANK_SEMIGLOSS(WHITE, p -> p
		.setSpecular(0.35f, 80f)),

	SNOW_1,
	SNOW_2,
	SNOW_3,
	SNOW_4,

	GRASS_1,
	GRASS_2,
	GRASS_3,
	GRASS_SCROLLING(GRASS_1, p -> p
		.setScroll(0f, 0.7f)),

	DIRT_1,
	DIRT_2,
	GRAVEL,

	DIRT_SHINY_1(DIRT_1, p -> p
		.setSpecular(1.1f, 380f)),
	DIRT_SHINY_2(DIRT_2, p -> p
		.setSpecular(1.1f, 380f)),
	GRAVEL_SHINY(GRAVEL, p -> p
		.setSpecular(1.1f, 380f)),

	SAND_1,
	SAND_2,
	SAND_3,

	GRUNGE_1,
	GRUNGE_2,

	ROCK_1,
	ROCK_2,

	CARPET,

	FALADOR_PATH_BRICK(p -> p
		.setSpecular(0.3f, 30f)),
	JAGGED_STONE_TILE,

	TILE_SMALL_1(p -> p
		.setSpecular(0.8f, 70f)),
	TILES_1_2x2,
	TILES_2_2x2,
	TILES_2x2_1_GLOSS(TILES_1_2x2, p -> p
		.setSpecular(1.0f, 70f)),
	TILES_2x2_2_GLOSS(TILES_2_2x2, p -> p
		.setSpecular(1.0f, 70f)),
	TILES_2x2_1_SEMIGLOSS(TILES_1_2x2, p -> p
		.setSpecular(0.5f, 300f)),
	TILES_2x2_2_SEMIGLOSS(TILES_2_2x2, p -> p
		.setSpecular(0.5f, 300f)),

	MARBLE_1,
	MARBLE_2,
	MARBLE_3,
	MARBLE_1_GLOSS(MARBLE_1, p -> p
		.setSpecular(0.9f, 280f)),
	MARBLE_2_GLOSS(MARBLE_2, p -> p
		.setSpecular(0.8f, 300f)),
	MARBLE_3_GLOSS(MARBLE_3, p -> p
		.setSpecular(0.7f, 320f)),
	MARBLE_1_SEMIGLOSS(MARBLE_1, p -> p
		.setSpecular(0.35f, 80f)),
	MARBLE_2_SEMIGLOSS(MARBLE_2, p -> p
		.setSpecular(0.3f, 100f)),
	MARBLE_3_SEMIGLOSS(MARBLE_3, p -> p
		.setSpecular(0.4f, 120f)),

	HD_LAVA_1(p -> p
		.setEmissiveStrength(1.0f)
		.setFlowMap(LAVA_FLOW_MAP, 0.04f, 36f, 12f)),
	HD_LAVA_2(p -> p
		.setEmissiveStrength(1.0f)
		.setFlowMap(LAVA_FLOW_MAP, 0.04f, 36f, 12f)),
	HD_MAGMA_1(p -> p
		.setEmissiveStrength(1.0f)
		.setFlowMap(LAVA_FLOW_MAP, 0.04f, 36f, 12f)),
	HD_MAGMA_2(p -> p
		.setEmissiveStrength(1.0f)
		.setFlowMap(LAVA_FLOW_MAP, 0.04f, 36f, 12f)),

	BARK,
	WOOD_GRAIN,

	HD_INFERNAL_CAPE(p -> p
		.replaceIf(INFERNAL_CAPE, HdPluginConfig::hdInfernalTexture)
		.setEmissiveStrength(1)
		.setFlowMap(LAVA_FLOW_MAP, 0.02f, 12f, 4f)
		.setScroll(0f, 3f)),

	HD_BRICK(p -> p
		.replaceIf(BRICK, HdPluginConfig::objectTextures)),
	HD_ROOF_SHINGLES_1(p -> p
		.replaceIf(ROOF_SHINGLES_1, HdPluginConfig::objectTextures)
		.setSpecular(0.5f, 30f)),
	HD_MARBLE_DARK(p -> p
		.replaceIf(MARBLE_DARK, HdPluginConfig::objectTextures)
		.setSpecular(1.1f, 380f)),
	HD_BRICK_BROWN(p -> p
		.replaceIf(BRICK_BROWN, HdPluginConfig::objectTextures)),
	HD_LAVA_3(p -> p
		.replaceIf(LAVA, HdPluginConfig::objectTextures)
		.setEmissiveStrength(1)
		.setFlowMap(LAVA_FLOW_MAP, 0.05f, 36f, 22f)
		.setScroll(0f, 3f)),
	HD_ROOF_SHINGLES_2(p -> p
		.replaceIf(ROOF_SHINGLES_2, HdPluginConfig::objectTextures)),
	WORN_TILES,

	// Seasonal
	WINTER_WILLOW_LEAVES(p -> p
		.replaceIf(WILLOW_LEAVES, HdPluginConfig::winterTheme)
		.setTextureScale(1.025f, 1.0f)),
	WINTER_MAPLE_LEAVES(p -> p
		.replaceIf(MAPLE_LEAVES, HdPluginConfig::winterTheme)
		.setTextureScale(1.3f, 1.0f)),
	WINTER_LEAVES_1(p -> p
		.replaceIf(LEAVES_1, HdPluginConfig::winterTheme)
		.setTextureScale(1.3f, 1.0f)),
	WINTER_LEAVES_2(p -> p
		.replaceIf(LEAVES_2, HdPluginConfig::winterTheme)
		.setTextureScale(1.1f, 1.1f)),
	WINTER_LEAVES_3(p -> p
		.replaceIf(LEAVES_3, HdPluginConfig::winterTheme)),
	WINTER_PAINTING_LANDSCAPE(p -> p
		.replaceIf(PAINTING_LANDSCAPE, HdPluginConfig::winterTheme)),
	WINTER_PAINTING_KING(p -> p
		.replaceIf(PAINTING_KING, HdPluginConfig::winterTheme)),
	WINTER_PAINTING_ELF(p -> p
		.replaceIf(PAINTING_ELF, HdPluginConfig::winterTheme));

	public final int vanillaTextureIndex;
	public final Material parent;
	public final Material normalMap;
	public final Material displacementMap;
	public final Material roughnessMap;
	public final Material flowMap;
	public final float flowMapStrength;
	public final float flowMapDurationX;
	public final float flowMapDurationY;
	public final float specularStrength;
	public final float specularGloss;
	public final float emissiveStrength;
	public final float scrollDurationX;
	public final float scrollDurationY;
	public final float textureScaleX;
	public final float textureScaleY;
	public final Material materialToReplace;
	public final Function<HdPluginConfig, Boolean> replacementCondition;

	@Setter
	private static class Builder
	{
		private int vanillaTextureIndex = -1;
		private Material parent;
		private Material normalMap = NONE;
		private Material displacementMap = NONE;
		private Material roughnessMap = NONE;
		private Material flowMap = LAVA_FLOW_MAP;
		private float specularStrength;
		private float specularGloss;
		private float emissiveStrength;
		private float flowMapStrength;
		private float flowMapDurationX;
		private float flowMapDurationY;
		private float scrollDurationX;
		private float scrollDurationY;
		private float textureScaleX = 1;
		private float textureScaleY = 1;
		private Material materialToReplace;
		private Function<HdPluginConfig, Boolean> replacementCondition;

		Builder apply(Consumer<Builder> consumer)
		{
			consumer.accept(this);
			return this;
		}

		Builder setParent(Material parent)
		{
			this.parent = parent;
			this.vanillaTextureIndex = parent.vanillaTextureIndex;
			this.materialToReplace = parent.materialToReplace;
			this.replacementCondition = parent.replacementCondition;
			this.normalMap = parent.normalMap;
			this.displacementMap = parent.displacementMap;
			this.roughnessMap = parent.roughnessMap;
			this.flowMap = parent.flowMap;
			this.flowMapStrength = parent.flowMapStrength;
			this.flowMapDurationX = parent.flowMapDurationX;
			this.flowMapDurationY = parent.flowMapDurationY;
			this.specularStrength = parent.specularStrength;
			this.specularGloss = parent.specularGloss;
			this.emissiveStrength = parent.emissiveStrength;
			this.scrollDurationX = parent.scrollDurationX;
			this.scrollDurationY = parent.scrollDurationY;
			this.textureScaleX = parent.textureScaleX;
			this.textureScaleY = parent.textureScaleY;
			return this;
		}

		Builder setSpecular(float specularStrength, float specularGloss)
		{
			this.specularStrength = specularStrength;
			this.specularGloss = specularGloss;
			return this;
		}

		Builder setFlowMap(Material flowMap, float flowMapStrength, float flowMapDurationX, float flowMapDurationY)
		{
			this.flowMap = flowMap;
			this.flowMapStrength = flowMapStrength;
			this.flowMapDurationX = flowMapDurationX;
			this.flowMapDurationY = flowMapDurationY;
			return this;
		}

		Builder setScroll(float scrollDurationX, float scrollDurationY)
		{
			this.scrollDurationX = scrollDurationX;
			this.scrollDurationY = scrollDurationY;
			return this;
		}

		Builder setTextureScale(float textureScaleX, float textureScaleY)
		{
			this.textureScaleX = textureScaleX;
			this.textureScaleY = textureScaleY;
			return this;
		}

		Builder replaceIf(@NonNull Material materialToReplace, @NonNull Function<HdPluginConfig, Boolean> condition)
		{
			this.materialToReplace = materialToReplace;
			this.replacementCondition = condition;
			return this;
		}
	}

	Material()
	{
		this(b -> {});
	}

	Material(int vanillaTextureIndex)
	{
		this(p -> p.setVanillaTextureIndex(vanillaTextureIndex));
	}

	Material(Material parent, Consumer<Builder> consumer)
	{
		this(b -> b.setParent(parent).apply(consumer));
	}

	Material(int vanillaTextureIndex, Consumer<Builder> consumer)
	{
		this(b -> b.setVanillaTextureIndex(vanillaTextureIndex).apply(consumer));
	}

	Material(Consumer<Builder> consumer)
	{
		Builder builder = new Builder();
		consumer.accept(builder);
		this.vanillaTextureIndex = builder.vanillaTextureIndex;
		this.parent = builder.parent;
		this.normalMap = builder.normalMap;
		this.displacementMap = builder.displacementMap;
		this.roughnessMap = builder.roughnessMap;
		this.flowMap = builder.flowMap;
		this.flowMapStrength = builder.flowMapStrength;
		this.flowMapDurationX = builder.flowMapDurationX;
		this.flowMapDurationY = builder.flowMapDurationY;
		this.specularStrength = builder.specularStrength;
		this.specularGloss = builder.specularGloss;
		this.emissiveStrength = builder.emissiveStrength;
		this.scrollDurationX = builder.scrollDurationX;
		this.scrollDurationY = builder.scrollDurationY;
		this.textureScaleX = builder.textureScaleX;
		this.textureScaleY = builder.textureScaleY;
		this.materialToReplace = builder.materialToReplace;
		this.replacementCondition = builder.replacementCondition;
	}

	private static final HashMap<Integer, Material> DIFFUSE_ID_MATERIAL_MAP;

	static
	{
		DIFFUSE_ID_MATERIAL_MAP = new HashMap<>();
		for (Material material : values())
		{
			if (material.vanillaTextureIndex != -1)
			{
				DIFFUSE_ID_MATERIAL_MAP.putIfAbsent(material.vanillaTextureIndex, material);
			}
		}
	}

	public static Material getTexture(int diffuseMap)
	{
		return DIFFUSE_ID_MATERIAL_MAP.getOrDefault(diffuseMap, Material.NONE);
	}
}
