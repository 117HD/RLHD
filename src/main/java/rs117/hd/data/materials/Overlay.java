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

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.NonNull;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;
import rs117.hd.scene.SceneUploader;

import static rs117.hd.utils.HDUtils.MAX_SNOW_LIGHTNESS;
import static rs117.hd.utils.HDUtils.clamp;
import static rs117.hd.utils.HDUtils.localToWorld;

public enum Overlay {
	// Winter Theme fixes
	WINTER_GRASS(p -> p
		.ids()
		.groundMaterial(GroundMaterial.SNOW_1)
		.hue(0)
		.saturation(0)
		.shiftLightness(40)
		.maxLightness(MAX_SNOW_LIGHTNESS)
		.blended(true)),
	WINTER_DIRT(p -> p
		.ids()
		.groundMaterial(GroundMaterial.SNOW_2)
		.hue(0)
		.saturation(0)
		.shiftLightness(40)
		.maxLightness(MAX_SNOW_LIGHTNESS)
		.blended(true)),
	WINTER_JAGGED_STONE_TILE(p -> p
		.ids()
		.groundMaterial(GroundMaterial.WINTER_JAGGED_STONE_TILE)
	),
	WINTER_JAGGED_STONE_TILE_LIGHT(p -> p
		.ids()
		.groundMaterial(GroundMaterial.WINTER_JAGGED_STONE_TILE_LIGHT)
	),
	WINTER_JAGGED_STONE_TILE_LIGHT_2(p -> p
		.ids()
		.groundMaterial(GroundMaterial.WINTER_JAGGED_STONE_TILE_LIGHT_2)
	),
	SNOW_2(p -> p
		.ids()
		.groundMaterial(GroundMaterial.SNOW_2)
	),
	WINTER_EAST_ARDOUGNE_CASTLE_PATH_FIX(p -> p
		.ids()
		.shiftLightness(3)
		.blended(false)
	),
	WINTER_CANIFIS_BAR_FLOOR_BLENDED(85, Area.CANIFIS_BAR_FLOOR_BLENDED, GroundMaterial.HD_WOOD_PLANKS_1, p -> p
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
	),
	DEFAULT_GRASS(p -> p.ids().groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)),
	VERTICAL_DIRT_FIX_NEAR_FALADOR(p -> p.ids(22).area(Area.VERTICAL_DIRT_FIX_NEAR_FALADOR).groundMaterial(GroundMaterial.VERTICAL_DIRT)),

	// Tutorial Island
	TUTORIAL_ISLAND_KITCHEN_TILE_1(9, Area.TUTORIAL_ISLAND_KITCHEN, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
	TUTORIAL_ISLAND_KITCHEN_TILE_2(11, Area.TUTORIAL_ISLAND_KITCHEN, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),
	TUTORIAL_ISLAND_QUEST_BUILDING_TILE_1(
		13,
		Area.TUTORIAL_ISLAND_QUEST_BUILDING,
		GroundMaterial.MARBLE_1_SEMIGLOSS,
		p -> p.blended(false)
	),
	TUTORIAL_ISLAND_QUEST_BUILDING_TILE_2(
		26,
		Area.TUTORIAL_ISLAND_QUEST_BUILDING,
		GroundMaterial.MARBLE_2_SEMIGLOSS,
		p -> p.blended(false)
	),
	TUTORIAL_ISLAND_BANK_TILE_1(2, Area.TUTORIAL_ISLAND_BANK, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
	TUTORIAL_ISLAND_BANK_TILE_2(3, Area.TUTORIAL_ISLAND_BANK, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),

	//Lumbridge Interior
	LUMBRIDGE_TOWER_INTERIOR_FLOOR(10, Area.LUMBRIDGE_TOWER_FLOOR_INTERIOR, GroundMaterial.VARROCK_PATHS, p -> p
		.shiftLightness(10)
		.blended(false)
	),
	LUMBRIDGE_CASTLE_ENTRANCE_FIX(10, Area.LUMBRIDGE_CASTLE_ENTRANCE, GroundMaterial.WORN_TILES, p -> p.shiftLightness(12)),
	LUMBRIDGE_CASTLE_TILE(3, Area.LUMBRIDGE_CASTLE_BASEMENT, GroundMaterial.MARBLE_1_SEMIGLOSS),
	LUMBRIDGE_CASTLE_FLOORS(10, Area.LUMBRIDGE_CASTLE_COBBLE, GroundMaterial.VARROCK_PATHS, p -> p.shiftLightness(10)),

	// Lumbridge
	LUM_BRIDGE_10(Area.LUM_BRIDGE, GroundMaterial.GRAVEL, p -> p
		.ids(10)
		.shiftLightness(12)
		.hue(7)
		.saturation(1)
	),
	LUM_BRIDGE_22(Area.LUM_BRIDGE, GroundMaterial.GRAVEL, p -> p
		.ids(22)
		.shiftLightness(-5)
		.hue(7)
		.saturation(1)
	),
	LUMBRIDGE_TOWERS_EXTERIOR(10, Area.LUMBRIDGE_CASTLE, GroundMaterial.VARROCK_PATHS, p -> p
		.shiftLightness(10)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE_LIGHT)
	),
	BLEND_IMPROVEMENT_1(10, Area.LUMBRIDGE_DRAYNOR_PATH_BLEND_1, GroundMaterial.GRAVEL, p -> p.shiftLightness(6).hue(7).saturation(1)),
	BLEND_IMPROVEMENT_2(10, Area.LUMBRIDGE_DRAYNOR_PATH_BLEND_2, GroundMaterial.GRAVEL, p -> p.shiftLightness(9).hue(7).saturation(1)),
	SWAMP_PATH_FIX_1(81, Area.LUMBRIDGE_SWAMP_PATH_FIX, GroundMaterial.DIRT, p -> p.saturation(4).shiftLightness(-3)),
	SWAMP_PATH_FIX_2(83, Area.LUMBRIDGE_SWAMP_PATH_FIX, GroundMaterial.DIRT, p -> p.saturation(4).shiftLightness(-5)),
	SWAMP_PATH_FIX_3(88, Area.LUMBRIDGE_SWAMP_PATH_FIX, GroundMaterial.GRAVEL, p -> p.shiftLightness(12).hue(7).saturation(1)),
	LUMBRIDGE_VARROCK_BAND_BLEND_FIX(10, Area.LUMBRIDGE_VARROCK_PATH_BLEND_FIX, GroundMaterial.OVERWORLD_GRASS_1, p -> p
		.hue(9)
		.saturation(5)
		.lightness(18)
	),
	LUMBRIDGE_VARROCK_PATH_FIX_1(3, Area.LUMBRIDGE_VARROCK_PATH_FIX, GroundMaterial.GRAVEL, p -> p.shiftLightness(12).hue(7).saturation(1)),
	LUMBRIDGE_VARROCK_PATH_FIX_2(8, Area.LUMBRIDGE_VARROCK_PATH_FIX, GroundMaterial.GRAVEL, p -> p.shiftLightness(12).hue(7).saturation(1)),
	LUMBRIDGE_PATHS(10, Area.LUMBRIDGE, GroundMaterial.GRAVEL, p -> p.shiftLightness(12).hue(7).saturation(1)),
	LUMBRIDGE_CASTLE_ENTRYWAY_1(2, Area.LUMBRIDGE_CASTLE_ENTRYWAY, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	LUMBRIDGE_CASTLE_ENTRYWAY_2(3, Area.LUMBRIDGE_CASTLE_ENTRYWAY, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	LUMBRIDGE_CAVES_FLOOR(49, Area.LUMBRIDGE_SWAMP_CAVES, GroundMaterial.ROCKY_CAVE_FLOOR),
	GOBLIN_MAZE_FLOOR(48, Area.GOBLIN_MAZE, GroundMaterial.ROCKY_CAVE_FLOOR),
	LUMBRIDGE_CHRISTMAS_EVENT(263, Area.LUMBRIDGE, GroundMaterial.ICE_1),


	//Varrock Indoors
	VARROCK_GUARD_POSTS(171, Area.VARROCK_GUARD_POSTS, GroundMaterial.VARROCK_PATHS),
	VARROCK_HORVIKS_ANVIL(171, Area.VARROCK_HORVIKS_ANVIL, GroundMaterial.VARROCK_PATHS),
	VARROCK_ZAMORACK_TEMPLE(171, Area.VARROCK_ZAMORAK_TEMPLE, GroundMaterial.VARROCK_PATHS),
	VARROCK_HOUSE_1(171, Area.VARROCK_HOUSE_1, GroundMaterial.VARROCK_PATHS),
	VARROCK_HOUSE_2(171, Area.VARROCK_HOUSE_2, GroundMaterial.VARROCK_PATHS),
	VARROCK_BLUE_MOON_INN(171, Area.VARROCK_BLUE_MOON_INN, GroundMaterial.VARROCK_PATHS),
	VARROCK_MUSEUM_FLOOR(56, Area.VARROCK_MUSEUM, GroundMaterial.TILES_2X2_2_GLOSS, p -> p.blended(false)),
	VARROCK_MUSEUM_BASEMENT_FLOOR(56, Area.VARROCK_MUSEUM_BASEMENT, GroundMaterial.TILES_2X2_2_GLOSS, p -> p.blended(false)),
	VARROCK_CHAMPIONS_GUILD(171, Area.VARROCK_CHAMPIONS_GUILD, GroundMaterial.VARROCK_PATHS),

	// Varrock
	VARROCK_HALLOWEEN_DIRT(81, Area.VARROCK_HALLOWEEN_EVENT_DIRT, GroundMaterial.DIRT),
	VARROCK_GRAND_EXCHANGE_ROOF(p -> p
		.ids(8)
		.groundMaterial(GroundMaterial.GRAVEL)
		.area(Area.VARROCK_GRAND_EXCHANGE_ROOF)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)),
	VARROCK_MUSEUM_SOUTH_PATH_FIX_1(-85, Area.VARROCK_MUSEUM_SOUTH_PATH_FIX, GroundMaterial.DIRT, p -> p.shiftSaturation(2)),
	VARROCK_MUSEUM_SOUTH_PATH_FIX_2(-84, Area.VARROCK_MUSEUM_SOUTH_PATH_FIX, GroundMaterial.DIRT, p -> p.shiftSaturation(1)),
	VARROCK_MUSEUM_SOUTH_PATH_FIX_3(56, Area.VARROCK_MUSEUM_SOUTH_PATH_FIX, GroundMaterial.VARROCK_PATHS, p -> p.shiftLightness(4)),
	VARROCK_WEST_BANK_SOUTH_PATH_FIX_1(
		-84,
		Area.VARROCK_WEST_BANK_SOUTH_PATH_FIX,
		GroundMaterial.VARROCK_PATHS,
		p -> p.shiftLightness(4).shiftSaturation(-1)
	),
	VARROCK_WILDERNESS_DITCH_PATH_FIX_1(-84, Area.VARROCK_WILDERNESS_DITCH_PATH_FIX, GroundMaterial.DIRT, p -> p.shiftSaturation(1)),
	VARROCK_JULIETS_FLOWER_BED(81, Area.VARROCK_JULIETS_HOUSE_FLOWER_BED, GroundMaterial.DIRT, p -> p.blended(true)),
	VARROCK_JULIETS_HOUSE_HARD_FLOORS(-85, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
	VARROCK_JULIETS_HOUSE_CARPET_RED(-93, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.CARPET, p -> p.blended(false)),
	VARROCK_JULIETS_HOUSE_CARPET_PINK(-96, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.CARPET, p -> p.blended(false)),
	VARROCK_JOLLY_BOAR_INN_KITCHEN_1(-84, Area.VARROCK_JOLLY_BOAR_INN, GroundMaterial.TILES_2X2_1_SEMIGLOSS, p -> p.blended(false)),
	VARROCK_JOLLY_BOAR_INN_KITCHEN_2(-85, Area.VARROCK_JOLLY_BOAR_INN, GroundMaterial.TILES_2X2_1_SEMIGLOSS, p -> p.blended(false)),
	VARROCK_CHURCH_CARPET(-83, Area.VARROCK_SARADOMIN_CHURCH, GroundMaterial.CARPET, p -> p.blended(false)),
	VARROCK_CHURCH_FLOOR(-85, Area.VARROCK_SARADOMIN_CHURCH, GroundMaterial.FALADOR_PATHS, p -> p.blended(false)),
	VARROCK_ANVILS(81, Area.VARROCK_ANVILS, GroundMaterial.DIRT),
	VARROCK_BUILDING_RUINS(81, Area.VARROCK_BUILDING_RUINS, GroundMaterial.DIRT),
	VARROCK_BUILDING_FLOOR_1(81, Area.VARROCK, GroundMaterial.TILE_SMALL, p -> p.blended(false)),
	VARROCK_BUILDING_FLOOR_2(4, Area.VARROCK, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),
	VARROCK_PLANT_PATCHES(89, Area.VARROCK, GroundMaterial.DIRT, p -> p.blended(false)),
	VARROCK_EAST_BANK_CENTER(-83, Area.VARROCK_EAST_BANK_CENTER, GroundMaterial.TILES_2X2_1_SEMIGLOSS, p -> p.blended(false)),
	VARROCK_EAST_BANK_OUTSIDE_1(-85, Area.VARROCK_EAST_BANK_OUTSIDE_1, GroundMaterial.TILES_2X2_1_SEMIGLOSS, p -> p.blended(false)),
	VARROCK_EAST_BANK(-85, Area.VARROCK_EAST_BANK, GroundMaterial.TILES_2X2_1_SEMIGLOSS, p -> p.blended(false)),
	VARROCK_CASTLE_PATH_FIXES_1(173, Area.VARROCK_CASTLE_DIRT_PATH_FIXES_1, GroundMaterial.DIRT, p -> p
		.blended(false)
		.shiftLightness(6)
	),
	VARROCK_CASTLE_PATH_FIXES_2(173, Area.VARROCK_CASTLE_DIRT_PATH_FIXES_2, GroundMaterial.DIRT, p -> p
		.blended(false)
		.shiftLightness(7)
	),
	VARROCK_CASTLE_FLOOR(171, Area.VARROCK_CASTLE, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
	VARROCK_ROOF_GRAVEL(2, Area.VARROCK_CASTLE, GroundMaterial.GRAVEL, p -> p.blended(false)),
	VARROCK_ROOF_ARCHERY_FLOOR_1(-83, Area.VARROCK_CASTLE, GroundMaterial.DIRT, p -> p.blended(false)),
	VARROCK_ROOF_ARCHERY_FLOOR_2(-84, Area.VARROCK_CASTLE, GroundMaterial.DIRT, p -> p.blended(false)),
	VARROCK_DIRT_BLENDING_IMPROVEMENT(-84, Area.VARROCK, GroundMaterial.DIRT, p -> p.shiftSaturation(1)),
	// this tile is used by jagex to blend between dirt paths and regular paths; blending desaturates the dirt and looks bad, extra saturation cancels out the effect

	VARROCK_SEWERS_BRICK_FLOOR(p -> p
		.ids(27, 306)
		.area(Area.VARROCK_SEWERS)
		.groundMaterial(GroundMaterial.HD_BRICK_BROWN)
		.lightness(86)
		.uvOrientation(512)
		.uvScale(1.5f)
	),

	// Barbarian Village
	BARBARIAN_VILLAGE_EAST_PATH_FIX_1(83, Area.BARBARIAN_VILLAGE_EAST_PATH_FIX, GroundMaterial.DIRT, p -> p.shiftSaturation(2)),
	BARBARIAN_VILLAGE_EAST_PATH_FIX_2(
		2,
		Area.BARBARIAN_VILLAGE_EAST_PATH_FIX,
		GroundMaterial.GRAVEL,
		p -> p.shiftSaturation(-1).shiftLightness(4)
	),

	// Digsite
	EXAMCENTRE_FLOOR_FIX_1(88, Area.DIGSITE_EXAM_CENTRE, GroundMaterial.WORN_TILES, p -> p.blended(false).shiftLightness(2)),
	EXAMCENTRE_FLOOR_FIX_2(120, Area.DIGSITE_EXAM_CENTRE, GroundMaterial.WORN_TILES, p -> p.blended(false).shiftLightness(2)),
	DIGSITE_DOCK(93, Area.DIGSITE_DOCK, GroundMaterial.TILES_2X2_1_GLOSS, p -> p.blended(false)),

	// Al Kharid
	SORCERESS_GARDEN_UNDERWALL(p -> p.ids(26).area(Area.SORCERESS_GARDEN).blended(false)),
	MAGE_TRAINING_ARENA_FLOOR(-122, Area.MAGE_TRAINING_ARENA, GroundMaterial.TILES_2X2_2_GLOSS, p -> p.blended(false)),
	AL_KHARID_WELL_FIX(21, Area.AL_KHARID_WELL, GroundMaterial.DIRT, p -> p.blended(false)),
	AL_KHARID_FLOOR_1(26, Area.AL_KHARID_BUILDINGS, GroundMaterial.TILES_2X2_2_SEMIGLOSS, p -> p
		.blended(false)
		.shiftSaturation(-1)
		.shiftLightness(7)),
	AL_KHARID_FLOOR_2(1, Area.AL_KHARID_BUILDINGS, GroundMaterial.TILES_2X2_2_SEMIGLOSS, p -> p.blended(false)),
	AL_KHARID_FLOOR_MARBLE_1(3, Area.AL_KHARID_BUILDINGS, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),
	AL_KHARID_FLOOR_MARBLE_2(4, Area.AL_KHARID_BUILDINGS, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
	// Sophanem
	SOPHANEM_PYRAMID_UNDERSIDE(p -> p.ids(26).area(Area.SOPHANEM_PYRAMIDS).groundMaterial(GroundMaterial.SAND).hue(6).saturation(3).shiftLightness(-10)),
	OVERRIDE_SOPHANEM_CHURCH_FLOOR_21(21, Area.SOPHANEM_TEMPLE_FLOORS, GroundMaterial.TILES_2X2_2_SEMIGLOSS, p -> p.blended(false)),
	OVERRIDE_SOPHANEM_CHURCH_FLOOR_26(26, Area.SOPHANEM_TEMPLE_FLOORS, GroundMaterial.TILES_2X2_2_SEMIGLOSS, p -> p.blended(false)),
	SOPHANEM_INTERIOR_FLOORS(p -> p.ids(26).area(Area.SOPHANEM_BUILDING_FLOORS).groundMaterial(GroundMaterial.FALADOR_PATHS).shiftLightness(5).blended(false)),
	SOPHANEM_SUNTRAP(p -> p
		.ids(1)
		.groundMaterial(GroundMaterial.MARBLE_2)
		.area(Area.KHARID_DESERT_REGION)
		.blended(false)
		.shiftLightness(10)),
	SOPHANEM_UPPER_FLOORS_CORRECTION(p -> p // The tiles in this area are supposed to be shaded, so the tiles are darker than intended as a result, this corrects it.
		.ids(21)
		.area(Area.SOPHANEM)
		.groundMaterial(GroundMaterial.FALADOR_PATHS)
		.shiftHue(1)
		.shiftLightness(23)
		.shiftSaturation(-5)
	),
	SOPHANEM_TEMPLE_LIGHTNESS_FIX_25(p -> p
		.ids(25)
		.area(Area.SOPHANEM_TEMPLE_UPPER_FLOORS)
		.groundMaterial(GroundMaterial.FALADOR_PATHS)
		.shiftHue(1)
		.shiftSaturation(-1)
		.lightness(40)),
	SOPHANEM_TEMPLE_LIGHTNESS_FIX_26(p -> p
		.ids(26)
		.area(Area.SOPHANEM_TEMPLE_UPPER_FLOORS)
		.groundMaterial(GroundMaterial.FALADOR_PATHS)
		.lightness(40)),
	SOPHANEM_UPPER_FLOORS(p -> p.ids(25).area(Area.SOPHANEM).groundMaterial(GroundMaterial.FALADOR_PATHS).shiftSaturation(-1)),
	SOPHANEM_PATH(p -> p.ids(26).area(Area.SOPHANEM).groundMaterial(GroundMaterial.FALADOR_PATHS)),
	SOPHANEM_UNDERGROUND_BANK_MARBLE(p -> p.ids(26).area(Area.SOPHANEM_TEMPLE_BANK).groundMaterial(GroundMaterial.MARBLE_2_SEMIGLOSS).blended(false).shiftLightness(10)),
	// Kharidian Region
	KHARID_PATHS_1(26, Area.KHARID_DESERT_REGION, GroundMaterial.DIRT, p -> p.saturation(2).hue(6).shiftLightness(5)),
	KHARID_PATHS_2(76, Area.KHARID_DESERT_REGION, GroundMaterial.DIRT, p -> p.saturation(3).hue(6).shiftLightness(-10)),
	KHARID_PATHS_3(25, Area.KHARID_DESERT_REGION, GroundMaterial.DIRT, p -> p.saturation(3).hue(6)),
	GIANTS_PLATEAU_CABBAGE_PATCH_FIX(14, Area.GIANTS_PLATEAU_CABBAGE_PATCH, GroundMaterial.DIRT, p -> p.blended(false)),
	PVP_ARENA_PATH(227, Area.PVP_ARENA, GroundMaterial.DIRT),
	DESERT_TREASURE_INTERIOR_FLOOR(GroundMaterial.FALADOR_PATHS, p -> p
		.area(Area.DESERT_TREASURE_PYRAMID)
		.ids(21)
	),
	GIANTS_FOUNDRY_224(224, Area.GIANTS_FOUNDRY, GroundMaterial.ROCKY_CAVE_FLOOR),

	// Falador
	FALADOR_EAST_BANK_PATH_FIX_2(-119, Area.FALADOR_EAST_BANK_PATH_FIX_2, GroundMaterial.FALADOR_PATHS, p -> p
		.hue(7)
		.saturation(1)
		.shiftLightness(13)
		.blended(false)),
	FALADOR_EAST_BANK_PATH_FIX_1(-119, Area.FALADOR_EAST_BANK_PATH_FIX_1, GroundMaterial.FALADOR_PATHS, p -> p
		.hue(7)
		.saturation(1)
		.shiftLightness(9)
		.blended(false)),
	FALADOR_TRIANGLE_DIRT_PATH_FIX_1(2, Area.FALADOR_TRIANGLE_PATH_FIX_1, GroundMaterial.GRAVEL, p -> p.shiftLightness(3)),
	FALADOR_TRIANGLE_DIRT_PATH_FIX_2(
		83,
		Area.FALADOR_TRIANGLE_PATH_FIX_1,
		GroundMaterial.GRAVEL,
		p -> p.shiftLightness(5).shiftSaturation(-1).shiftHue(-4)
	),
	FALADOR_TRIANGLE_PATH_PATH_FIX_1(
		119,
		Area.FALADOR_TRIANGLE_PATH_FIX_2,
		GroundMaterial.GRAVEL,
		p -> p.shiftLightness(2).shiftSaturation(-1).shiftHue(-4)
	),
	FALADOR_SOUTH_PATH_FIX_1(119, Area.FALADOR_SOUTH_PATH_FIX, GroundMaterial.FALADOR_PATHS, p -> p.hue(7).saturation(1).shiftLightness(7)),
	FALADOR_SOUTH_PATH_FIX_2(
		88,
		Area.FALADOR_SOUTH_PATH_FIX,
		GroundMaterial.GRAVEL,
		p -> p.shiftLightness(2).shiftSaturation(-1).shiftHue(-4)
	),
	FALADOR_PATHS(-119, Area.FALADOR, GroundMaterial.FALADOR_PATHS, p -> p.hue(7).saturation(1).shiftLightness(7)),
	FALADOR_HAIRDRESSER_TILE_1(77, Area.FALADOR_HAIRDRESSER, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	FALADOR_HAIRDRESSER_TILE_2(123, Area.FALADOR_HAIRDRESSER, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	FALADOR_PARTY_ROOM_TILE_1(33, Area.FALADOR_PARTY_ROOM, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	FALADOR_PARTY_ROOM_TILE_2(123, Area.FALADOR_PARTY_ROOM, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	FALADOR_PARTROOM_STAIRS_FIX(37, Area.FALADOOR_PARTY_ROOM_STAIRS_FIX, GroundMaterial.NONE, p -> p
		.blended(false)
		.lightness(0)),
	FALADOR_BUILDING_FLOOR_1(123, Area.FALADOR, GroundMaterial.TILES_2X2_1_GLOSS, p -> p.blended(false)),
	FALADOR_BUILDING_FLOOR_2(33, Area.FALADOR, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	FALADOR_BUILDING_FLOOR_3(77, Area.FALADOR, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	FALADOR_BUILDING_FLOOR_4(13, Area.FALADOR, GroundMaterial.NONE, p -> p.blended(false)),
	GIANT_MOLE_LAIR_DIRT(p -> p.ids(42, 161).area(Area.GIANT_MOLE_LAIR).groundMaterial(GroundMaterial.VARIED_DIRT)),

	// Port Sarim
	PORT_SARIM_BETTYS_HOUSE_1(11, Area.PORT_SARIM_BETTYS_HOUSE, GroundMaterial.MARBLE_DARK, p -> p
		.blended(false)
		.lightness(20)),
	PORT_SARIM_BETTYS_HOUSE_2(2, Area.PORT_SARIM_BETTYS_HOUSE, GroundMaterial.MARBLE_DARK, p -> p
		.blended(false)
		.lightness(30)),
	PORT_SARIM_BETTYS_HOUSE_3(3, Area.PORT_SARIM_BETTYS_HOUSE, GroundMaterial.MARBLE_DARK, p -> p
		.blended(false)
		.lightness(40)),

	// Brimhaven
	BRIMHAVEN_DOCKS_TEXTURE_REMOVAL(5, Area.BRIMHAVEN_DOCKS_TEXTURED, GroundMaterial.TRANSPARENT),

	// Rimmington
	CRAFTING_GUILD_TILE_1(2, Area.CRAFTING_GUILD, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	CRAFTING_GUILD_TILE_2(3, Area.CRAFTING_GUILD, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	CRAFTING_GUILD_TILE_3(4, Area.CRAFTING_GUILD, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),

	// Edgeville
	EDGEVILLE_BANK_TILE_1(3, Area.EDGEVILLE_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p
		.blended(false)
		.blendedAsOpposite(true)
		.lightness(22)),
	EDGEVILLE_BANK_TILE_2(4, Area.EDGEVILLE_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p
		.blended(false)
		.blendedAsOpposite(true)
		.lightness(30)),
	EDGEVILLE_BANK_TILING_FIX_1(10, Area.EDGEVILLE_BANK_TILING, GroundMaterial.MARBLE_2_GLOSS, p -> p
		.blended(false)
		.blendedAsOpposite(true)
		.lightness(22)),
	EDGEVILLE_BANK_PERIMETER_FIX(10, Area.EDGEVILLE_BANK_PERIMETER_FIX, GroundMaterial.MARBLE_2_GLOSS, p -> p.lightness(30)),
	EDGEVILLE_BANK_PERIMETER(10, Area.EDGEVILLE_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p
		.blended(false)
		.blendedAsOpposite(true)
		.lightness(30)),
	EDGEVILLE_BANK_SURROUNDING_PATH(10, Area.EDGEVILLE_BANK_SURROUNDING_PATH, GroundMaterial.VARROCK_PATHS, p -> p
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE_LIGHT)
	),
	EDGEVILLE_DORIS_HOUSE_FLOOR(119, Area.EDGEVILLE_DORIS_HOUSE, GroundMaterial.TILE_SMALL),
	EDGEVILLE_FURNACE_FLOOR(10, Area.EDGEVILLE_FURNACE_FLOOR, GroundMaterial.TILES_2x2_1, p -> p
		.lightness(26)
		.blended(false)),
	EDGEVILLE_MANS_HOUSE_FLOOR(10, Area.EDGEVILLE_MANS_HOUSE_FLOOR, GroundMaterial.TILE_SMALL, p -> p
		.hue(5)
		.saturation(4)
		.shiftLightness(-4)
		.blended(false)),
	EDGEVILLE_GENERAL_STORE_BLEND_FIX(10, Area.EDGEVILLE_GENERAL_STORE_FLOOR_FIX, GroundMaterial.TILE_SMALL, p -> p
		.hue(5)
		.saturation(4)
		.shiftLightness(-4)
		.blended(false)),
	EDGEVILLE_GENERAL_STORE_FLOOR(10, Area.EDGEVILLE_GENERAL_STORE_FLOOR, GroundMaterial.TILE_SMALL, p -> p
		.hue(5)
		.saturation(4)
		.shiftLightness(-4)),
	EDGEVILLE_GUARD_TOWER_FLOOR(10, Area.EDGEVILLE_GUARD_TOWER_FLOOR, GroundMaterial.CONCRETE),
	EDGEVILLE_MONASTERY_FLOOR(10, Area.EDGEVILLE_MONASTERY, GroundMaterial.GRAVEL, p -> p.blended(false)),
	EDGEVILLE_BRIDGE_TEXTURE(12, Area.EDGEVILLE_BRIDGE, GroundMaterial.VARROCK_PATHS, p -> p
		.lightness(32)
		.blended(true)
	),


	// Goblin Village
	GOBLIN_VILLAGE_TILES_BLEND_FIX_OVERLAY(
		Area.GOBLIN_VILLAGE_COOKS_CHAMBER_BLEND_FIX,
		GroundMaterial.WORN_TILES,
		p -> p.ids(11).blended(true)
	),
	GOBLIN_VILLAGE_TILES(Area.GOBLIN_VILLAGE_COOKS_CHAMBER, GroundMaterial.WORN_TILES, p -> p.ids(11, 26).blended(false)),
	GOBLIN_VILLAGE_SAND(Area.GOBLIN_VILLAGE_COOKS_CHAMBER, GroundMaterial.VARIED_DIRT, p -> p.ids(83).blended(false)),
	GOBLIN_VILLAGE_PIT(Area.GOBLIN_VILLAGE_COOKS_CHAMBER, GroundMaterial.VARIED_DIRT, p -> p.ids(37)),

	// Burthorpe
	HEROES_GUILD_TILE_1(3, Area.HEROES_GUILD, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	HEROES_GUILD_TILE_2(4, Area.HEROES_GUILD, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	WARRIORS_GUILD_TILE_1(10, Area.WARRIORS_GUILD_FLOOR_2, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	WARRIORS_GUILD_TILE_2(11, Area.WARRIORS_GUILD_FLOOR_2, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	WARRIORS_GUILD_TILE_BLUE(87, Area.WARRIORS_GUILD, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	WARRIORS_GUILD_FLOOR_1(11, Area.WARRIORS_GUILD, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
	WARRIORS_GUILD_CARPET(86, Area.WARRIORS_GUILD, GroundMaterial.CARPET, p -> p.blended(false)),

	// Trollweiss Region
	TROLLHEIM_WALLS(83, Area.TROLLHEIM, GroundMaterial.VERTICAL_DIRT),
	WEISS_FIREPIT_FIX(171, Area.WEISS_FIRE_PIT, GroundMaterial.DIRT),
	WEISS_TENT_INTERIOR(171, Area.INTERIOR_WEISS_THRONE_TENT, GroundMaterial.VARROCK_PATHS),
	WEISS_SNOWY_PATH(171, Area.WEISS_REGION, GroundMaterial.WINTER_JAGGED_STONE_TILE),
	// Taverley Underground
	TAVERLEY_DUNGEON_BLACK_KNIGHT_KITCHEN(GroundMaterial.TILES_2X2_2_SEMIGLOSS, p -> p
		.area(Area.TAVERLEY_DUNGEON_BLACK_KNIGHT_KITCHEN)
		.blended(false)
		.ids(0, 2, 10)
	),
	TAVERLEY_DUNGEON_MARBLE_GLOSS(GroundMaterial.MARBLE_2_GLOSS, p -> p
		.area(Area.TAVERLEY_DUNGEON)
		.ids(3, 11)
	),
	TAVERLEY_DUNGEON_GRAVEL_OVERLAY(GroundMaterial.GRAVEL, p -> p
		.area(Area.TAVERLEY_DUNGEON)
		.ids(2, 10, 34)
	),
	ASGARNIA_MOUNTAINS_VERTICAL_DIRT_FIX(p -> p.ids(22).area(Area.ASGARNIA_MOUNTAINS).groundMaterial(GroundMaterial.VERTICAL_DIRT)),
	ICE_QUEENS_SYMBOL(33, Area.ICE_QUEENS_DUNGEON, GroundMaterial.ICE_1_HIGHGLOSS),
	ICE_QUEENS_DUNGEON_OVERLAY(Area.ICE_QUEENS_DUNGEON, GroundMaterial.SNOW_1, p -> p.ids(42).lightness(100).hue(0).saturation(0)),

	// Seers Indoors
	SEERS_ELEMENTAL_WORKSHOP_HOUSE_171_BLENDED(171, Area.ELEMENTAL_WORKSHOP_HOUSE_ENTRANCE, GroundMaterial.VARROCK_PATHS),
	SEERS_ELEMENTAL_WORKSHOP_HOUSE_171(
		171,
		Area.ELEMENTAL_WORKSHOP_HOUSE,
		GroundMaterial.VARROCK_PATHS,
		p -> p.blended(false).shiftLightness(3)
	),
	SEERS_BAR_171_BLENDED(171, Area.SEERS_BAR_ENTRANCE, GroundMaterial.VARROCK_PATHS),
	SEERS_BAR_171(171, Area.SEERS_BAR, GroundMaterial.VARROCK_PATHS, p -> p.blended(false).shiftLightness(3)),
	SEERS_CAMELOT_CASTLE_BLENDED(171, Area.SEERS_CAMELOT_CASTLE_BLENDED, GroundMaterial.VARROCK_PATHS),
	SEERS_CAMELOT_CASTLE(171, Area.SEERS_CAMELOT_CASTLE, GroundMaterial.VARROCK_PATHS, p -> p.blended(false).shiftLightness(3)),
	SEERS_CAMELOT_CASTLE_KNIGHTSWAVE_OVERWORLD(171, Area.SEERS_CAMELOT_CASTLE_KNIGHTSWAVE_OVERWORLD, GroundMaterial.VARROCK_PATHS),
	SEERS_BANK_TILE_1(3, Area.SEERS_BANK, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	SEERS_BANK_TILE_2(4, Area.SEERS_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	SEERS_BANK_TILE_3(8, Area.SEERS_BANK, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	SEERS_HOUSE_FLOORS(22, Area.SEERS_HOUSES, GroundMaterial.WOOD_PLANKS_1, p -> p
		.replaceWithIf(null, pl -> !pl.configGroundTextures)
		.blended(false)
		.lightness(45)
		.saturation(2)
		.hue(12)),
	SEERS_CHURCH_1(-85, Area.SEERS_CHURCH, GroundMaterial.TILES_2x2_2, p -> p.blended(false)),
	SEERS_CHURCH_2(8, Area.SEERS_CHURCH, GroundMaterial.MARBLE_2, p -> p.blended(false)),
	SEERS_COURTHOUSE_FLOOR(p -> p.area(Area.SEERS_COURTHOUSE).ids(110, 123).groundMaterial(GroundMaterial.FALADOR_PATHS)),
	SINCLAIR_MANSION_FLOOR(173, Area.SINCLAIR_MANSION, GroundMaterial.WOOD_PLANKS_1, p -> p
		.replaceWithIf(null, pl -> !pl.configGroundTextures)
		.lightness(40)
		.hue(10)
		.saturation(5)
		.blended(false)
	),
	SINCLAIR_MANSION_KITCHEN_FLOOR(3, Area.SINCLAIR_MANSION, GroundMaterial.TILE_SMALL, p -> p.blended(false)),
	KEEP_LE_FAYE_FLOOR_8(8, Area.KEEP_LE_FAYE, GroundMaterial.WORN_TILES),
	KEEP_LE_FAYE_INSTANCED_FLOOR_COBBLE(GroundMaterial.WORN_TILES, p -> p.area(Area.KEEP_LE_FAYE_INSTANCE).ids(88, 119)),
	KEEP_LE_FAYE_INSTANCED_FLOOR_BRICK(
		Area.KEEP_LE_FAYE_INSTANCE,
		GroundMaterial.MARBLE_1_SEMIGLOSS,
		p -> p.area(Area.KEEP_LE_FAYE_INSTANCE).blended(false).ids(120)
	),

	// Catherby
	CATHERBY_BEACH_OBELISK_WATER_FIX(6, Area.CATHERBY_BEACH_OBELISK_WATER_FIX, WaterType.WATER_FLAT),
	CATHERBY_BEACH_LADDER_FIX(11, Area.CATHERBY_BEACH_LADDER_FIX, GroundMaterial.NONE, p -> p.blended(false)),
	CATHERBY_BANK_TILE_1(3, Area.CATHERBY_BANK, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	CATHERBY_BANK_TILE_2(4, Area.CATHERBY_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),

	// Ardougne Indoors
	EAST_ARDOUNGE_INDOORS_FURNACE(10, Area.EAST_ARDOUGNE_FURNACE, GroundMaterial.VARROCK_PATHS, p -> p.shiftLightness(6)),
	EAST_ARDOUGNE_BANK_FLOOR(10, Area.EAST_ARDOUGNE_BANK, GroundMaterial.VARROCK_PATHS, p -> p.shiftLightness(6)),
	EAST_ARDOUGNE_COBBLE_HOUSE_BLENDING(10, Area.EAST_ARDOUGNE_COBBLE_OFFICE, GroundMaterial.VARROCK_PATHS),
	EAST_ARDOUGNE_COBBLE_HOUSE(10, Area.EAST_ARDOUGNE_COBBLE_OFFICE, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
	EAST_ARDOUNGE_COBBLE_CHURCH(10, Area.EAST_ARDOUGNE_COBBLE_CHRUCH, GroundMaterial.VARROCK_PATHS),
	EAST_ARDOUGNE_COBBLE_DOCK_HOUSE(10, Area.EAST_ARDOUGNE_COBBLE_DOCK_HOUSE, GroundMaterial.VARROCK_PATHS),

	// Ardougne
	EAST_ARDOUGNE_PATH_BLENDING_FIX(p -> p
		.ids()
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.hue(11)
		.saturation(4)
		.lightness(21)
	),
	EAST_ARDOUGNE_PATH_BLENDING_FIX_TOGGLE_WINTER(p -> p
		.ids()
		.area(Area.EAST_ARDOUGNE_PATHING_FIXES)
		.groundMaterial(GroundMaterial.WINTER_JAGGED_STONE_TILE_LIGHT)
		.replaceWithIf(WINTER_GRASS, plugin -> plugin.configGroundBlending)
	),
	EAST_ARDOUGNE_PATH_BLENDING_FIX_TOGGLE(p -> p
		.ids(10)
		.area(Area.EAST_ARDOUGNE_PATHING_FIXES)
		.groundMaterial(GroundMaterial.VARROCK_PATHS)
		.shiftLightness(6)
		.seasonalReplacement(SeasonalTheme.WINTER, EAST_ARDOUGNE_PATH_BLENDING_FIX_TOGGLE_WINTER)
		.replaceWithIf(EAST_ARDOUGNE_PATH_BLENDING_FIX, plugin -> plugin.configGroundBlending)
	),
	EAST_ARDOUGNE_DOCKS_FIX_5(5, Area.EAST_ARDOUGNE_DOCKS_FIX, GroundMaterial.WOOD_PLANKS_1, p -> p.lightness(65)),
	EAST_ARDOUGNE_DOCKS_FIX_35(35, Area.EAST_ARDOUGNE_DOCKS_FIX, GroundMaterial.WOOD_PLANKS_1, p -> p.lightness(65)),
	EAST_ARDOUGNE_NORTH_BANK_11(11, Area.EAST_ARDOUGNE_BANK_NORTH, GroundMaterial.CONCRETE, p -> p.blended(false).lightness(40)),
	EAST_ARDOUGNE_CASTLE_DIRT_FIX(14, Area.EAST_ARDOUGNE_CASTLE_DIRT_FIX, GroundMaterial.DIRT, p -> p
		.shiftLightness(7)
		.blended(false)),
	EAST_ARDOUGNE_CASTLE_FLOOR_TEXTURE(
		11,
		Area.EAST_ARDOUGNE_CASTLE_DIRT_FIX,
		GroundMaterial.CONCRETE,
		p -> p.blended(false).lightness(40)
	),
	EAST_ARDOUGNE_CASTLE_PATH_FIX_BLENDING(p -> p
		.ids()
		.groundMaterial(GroundMaterial.VARROCK_PATHS)
		.shiftLightness(16) // Disabling blending requires shifting the lightness up further, to match the color of paths with blending
		.blended(false)
	),
	EAST_ARDOUGNE_CASTLE_PATH_FIX(p -> p
		.ids(10)
		.area(Area.EAST_ARDOUGNE_CASTLE_PATH_FIX)
		.groundMaterial(GroundMaterial.VARROCK_PATHS)
		.shiftLightness(6)
		.blended(false)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE_LIGHT)
		.replaceWithIf(EAST_ARDOUGNE_CASTLE_PATH_FIX_BLENDING, plugin -> plugin.configGroundBlending)
	),
	EAST_ARDOUGNE_PATHS_1(10, Area.EAST_ARDOUGNE, GroundMaterial.VARROCK_PATHS, p -> p
		.shiftLightness(6)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE_LIGHT)
	),
	WIZARD_HOUSE_TILE_LIGHT(38, Area.EAST_ARDOUGNE, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
	WIZARD_HOUSE_TILE_DARK(40, Area.EAST_ARDOUGNE, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),
	EAST_ARDOUGNE_HAZEL_MANOR_PATH_TEXTURE(12, Area.EAST_ARDOUGNE_HAZEL_HOUSE, GroundMaterial.VARROCK_PATHS, p -> p
		.lightness(33)
	),
	EAST_ARDOUGNE_BRIDGE_TEXTURE(12, Area.EAST_ARDOUGNE_BRIDGE, GroundMaterial.VARROCK_PATHS, p -> p
		.lightness(33)
	),
	EAST_ARDOUGNE_HANDELMORT_TEXTURE(12, Area.EAST_ARDOUGNE_HANDELMORT_STONE_FLOOR, GroundMaterial.FALADOR_PATHS, p -> p
		.lightness(33)
	),
	EAST_ARDOUGNE_HANDELMORT_BALCONY_TEXTURE(10, Area.EAST_ARDOUGNE_HANDELMORT_BALCONY, GroundMaterial.FALADOR_PATHS, p -> p
		.lightness(33)
	),
	WEST_ARDOUNGE_CARPET_FIX_1(14, Area.WEST_ARDOUGNE_CARPET_FIX, GroundMaterial.CARPET, p -> p
		.blended(false)
		.shiftHue(1)
		.shiftSaturation(3)
		.shiftLightness(-2)
	),
	WEST_ARDOUGNE_COBBLE_HOUSES(11, Area.WEST_ARDOUGNE_COBBLE_HOUSES, GroundMaterial.VARROCK_PATHS),
	CLOCK_TOWER_DUNGEON_COLORED_TILES_FIX(Area.CLOCK_TOWER_DUNGEON_COLORED_TILES, GroundMaterial.MARBLE_1, p -> p
		.blended(false)
		.ids(9, 11, 13, 18)
	),

	WITCHAVEN_PATHS(p -> p.ids(106).area(Area.WITCHAVEN).groundMaterial(GroundMaterial.VARIED_DIRT)),

	KHAZARD_BATTLEFIELD_COBBLE_INDOORS(11, Area.KHAZARD_BATTLEFIELD_COBBLE, GroundMaterial.VARROCK_PATHS),
	KHAZARD_BATTLEFIELD_COBBLE_OUTSIDE(11, Area.KHAZARD_BATTLEFIELD_COBBLE_OUTSIDE, GroundMaterial.VARROCK_PATHS, p -> p
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE_LIGHT_2)
	),
	KANDARIN_MONISTARY_WINGS_TEXTURE_13(13, Area.KANDARIN_MONISTARY_WINGS, GroundMaterial.TILES_2X2_2_SEMIGLOSS),
	KANDARIN_MONISTARY_FLOOR_TEXTURE(12, Area.KANDARIN_MONISTARY, GroundMaterial.VARROCK_PATHS, p -> p
		.lightness(33)
	),

	// Yanille
	YANILLE_BANK_TILE_1(3, Area.YANILLE_BANK, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	YANILLE_BANK_TILE_2(4, Area.YANILLE_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
	YANILLE_BANK_REAR_GROUND(2, Area.YANILLE_BANK, GroundMaterial.TILES_2X2_2_GLOSS, p -> p
		.blended(false)
		.lightness(25)),
	YANILLE_HUNTER_SHOP_FLOOR(
		16,
		Area.YANILLE,
		GroundMaterial.WOOD_PLANKS_1,
		p -> p.blended(false).lightness(35).shiftHue(3).shiftSaturation(-3)
	),
	YANILLE_MAGIC_GUILD_FLOOR_FIX(10, Area.YANILLE_MAGIC_GUILD_FLOORS, GroundMaterial.TILES_2X2_1_SEMIGLOSS, p -> p.lightness(30)),
	GUTANOTH_CAVE(29, Area.GUTANOTH_CAVE, WaterType.SWAMP_WATER_FLAT),
	FELDIP_HILLS_HUNTER_OVERLAY(p -> p.ids(29).area(Area.FELDIP_HILLS_SOUTHERN_REGION).groundMaterial(GroundMaterial.GRASS_1)),

	// Watchtower
	YANILLE_WATCHTOWER_ARCHWAY_FIX_1(3, Area.YANILLE_WATCHTOWER_BOTTOM_DOORWAY, GroundMaterial.WORN_TILES),
	YANILLE_WATCHTOWER_ARCHWAY_FIX_2(4, Area.YANILLE_WATCHTOWER_BOTTOM_DOORWAY, GroundMaterial.WORN_TILES),
	YANILLE_WATCHTOWER_BOTTOM_FLOOR_FIX_3(3, Area.YANILLE_WATCHTOWER_BOTTOM, GroundMaterial.WORN_TILES, p -> p.blended(false)),
	YANILLE_WATCHTOWER_BOTTOM_FLOOR_FIX_4(4, Area.YANILLE_WATCHTOWER_BOTTOM, GroundMaterial.WORN_TILES, p -> p.blended(false)),
	YANILLE_WATCHTOWER_MIDDLE_FLOOR_FIX_3(3, Area.YANILLE_WATCHTOWER_MIDDLE, GroundMaterial.WORN_TILES, p -> p.blended(false)),
	YANILLE_WATCHTOWER_MIDDLE_FLOOR_FIX_4(4, Area.YANILLE_WATCHTOWER_MIDDLE, GroundMaterial.WORN_TILES, p -> p.blended(false)),
	YANILLE_WATCHTOWER_TOP_FLOOR_FIX_1(1, Area.YANILLE_WATCHTOWER_TOP, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	YANILLE_WATCHTOWER_TOP_FLOOR_FIX_2(2, Area.YANILLE_WATCHTOWER_TOP, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
	YANILLE_WATCHTOWER_TOP_FLOOR_FIX_3(3, Area.YANILLE_WATCHTOWER_TOP, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),

	// Draynor
	DRAYNOR_AGGIES_HOUSE(-93, Area.DRAYNOR_AGGIES_HOUSE, GroundMaterial.CARPET, p -> p.blended(false)),
	WISE_OLD_MANS_HOUSE_CARPET(86, Area.DRAYNOR, GroundMaterial.CARPET, p -> p.blended(false)),
	DRAYNOR_WOM_FRONT_FIX_0(p -> p
		.ids(0)
		.area(Area.DRAYNOR_WOM_HOUSE_FRONT)
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)
	),
	DRAYNOR_WOM_FRONT_FIX_10(10, Area.DRAYNOR_WOM_HOUSE_FRONT, GroundMaterial.OVERWORLD_GRASS_1, p -> p
		.hue(8)
		.saturation(4)
		.lightness(15)
	),
	DRAYNOR_PATH_BLENDING_FIX(10, Area.DRAYNOR_PATH_BLENDING_FIXES, GroundMaterial.OVERWORLD_GRASS_1, p -> p
		.hue(9)
		.saturation(5)
		.lightness(15)),
	DRAYNOR_NEDS_PATH_FIX(p -> p
		.ids()
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.hue(9)
		.saturation(5)
		.lightness(18)
	),
	DRAYNOR_NEDS_PATH_FIX_ENABLE(p -> p
		.ids(10)
		.groundMaterial(GroundMaterial.GRAVEL)
		.area(Area.DRAYNOR_NEDS_PATH_FIXES)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				if (!plugin.configGroundBlending)
					return override;
				if (plugin.configSeasonalTheme == SeasonalTheme.WINTER)
					return WINTER_GRASS;
				return DRAYNOR_NEDS_PATH_FIX;
			}
		)
	),
	DRAYNOR_BANK_FLOOR(10, Area.DRAYNOR_BANK, GroundMaterial.WORN_TILES, p -> p.blended(false)),
	DRAYNOR_BANK_ENTRANCE_PATH(p -> p
		.ids()
		.groundMaterial(GroundMaterial.GRAVEL)
		.hue(0)
		.saturation(0)
		.lightness(22)
	),
	DRAYNOR_BANK_FRONT_FIX(p -> p
		.ids(0)
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.area(Area.DRAYNOR_BANK_FRONT_PATH)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				if (plugin.configGroundBlending)
					return DRAYNOR_BANK_ENTRANCE_PATH;
				if (plugin.configSeasonalTheme == SeasonalTheme.WINTER)
					return WINTER_GRASS;
				return override;
			}
		)
	),
	DRAYNOR_BANK_PATH_FIX_10_DARK(p -> p
		.ids()
		.area(Area.DRAYNOR_BANK_PATH_FIX_DARK)
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.hue(9)
		.saturation(4)
		.lightness(8)
	),
	DRAYNOR_BANK_PATH_FIX_DARK_ENABLE(p -> p
		.ids(10)
		.groundMaterial(GroundMaterial.GRAVEL)
		.area(Area.DRAYNOR_BANK_PATH_FIX_DARK)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				if (!plugin.configGroundBlending)
					return override;
				if (plugin.configSeasonalTheme == SeasonalTheme.WINTER)
					return WINTER_GRASS;
				return DRAYNOR_BANK_PATH_FIX_10_DARK;
			}
		)
	),
	DRAYNOR_BANK_PATH_FIX_10_LIGHT(p -> p
		.ids()
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.hue(9)
		.saturation(5)
		.lightness(18)

	),
	DRAYNOR_BANK_PATH_FIX_LIGHT_ENABLE(p -> p
		.ids(10)
		.groundMaterial(GroundMaterial.GRAVEL)
		.area(Area.DRAYNOR_BANK_PATH_FIX_LIGHT)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				if (!plugin.configGroundBlending)
					return override;
				if (plugin.configSeasonalTheme == SeasonalTheme.WINTER)
					return WINTER_GRASS;
				return DRAYNOR_BANK_PATH_FIX_10_LIGHT;
			}
		)
	),
	DRAYNOR_BANK_PATH_FIX_0(p -> p
		.ids(0)
		.area(Area.DRAYNOR_PATH_BLENDING_FIXES)
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)
	),
	DRAYNOR_MANS_HOUSE_FLOOR(14, Area.DRAYNOR_NORTHERN_HOUSE_FLOOR, GroundMaterial.WOOD_PLANKS_1, p -> p
		.blended(false)
		.lightness(74)
		.shiftHue(-3)
		.shiftSaturation(-7)),
	// Draynor
	DRAYNOR_SEWERS(p -> p.area(Area.DRAYNOR_SEWERS).ids(89).waterType(WaterType.MUDDY_WATER)),

	// Draynor manor
	DRAYNOR_MANOR_TILE_DARK(2, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.MARBLE_1, p -> p.blended(false)),
	DRAYNOR_MANOR_TILE_LIGHT(10, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.MARBLE_2, p -> p.blended(false)),
	DRAYNOR_MANOR_TILE_SMALL(11, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.TILE_SMALL, p -> p.blended(false)),
	DRAYNOR_MANOR_WOOD(119, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),
	DRAYNOR_MANOR_CARPET(127, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.CARPET, p -> p.blended(false)),
	DRAYNOR_MANOR_ENTRANCE_PATH(p -> p
		.area(Area.DRAYNOR_MANOR)
		.ids(2, 90, 117, 120, 127, 132)
		.groundMaterial(GroundMaterial.VARROCK_PATHS)
	),


	// Wizards Tower
	WIZARDS_TOWER_PATH(p -> p.ids(164).area(Area.WIZARDS_TOWER_PATH).groundMaterial(GroundMaterial.FALADOR_PATHS).shiftSaturation(-1)),
	WIZARDS_TOWER_PATH_BLEND_FIX(p -> p
		.ids()
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.hue(10)
		.saturation(5)
		.lightness(19)
	),
	WIZARDS_TOWER_PATH_BLEND_FIX_WINTER(p -> p
		.ids()
		.area(Area.WIZARD_TOWER_PATH_PARTIAL_TILES)
		.groundMaterial(GroundMaterial.FALADOR_PATHS)
		.shiftSaturation(-1)
		.replaceWithIf(WINTER_GRASS, plugin -> plugin.configGroundBlending)
	),
	WIZARDS_TOWER_PATH_BLEND_FIX_TOGGLE(p -> p
		.ids(164)
		.area(Area.WIZARD_TOWER_PATH_PARTIAL_TILES)
		.groundMaterial(GroundMaterial.FALADOR_PATHS)
		.shiftSaturation(-1)
		.seasonalReplacement(SeasonalTheme.WINTER, WIZARDS_TOWER_PATH_BLEND_FIX_WINTER)
		.replaceWithIf(WIZARDS_TOWER_PATH_BLEND_FIX, plugin -> plugin.configGroundBlending)
	),

	// Misthalin Mystery
	MISTHALIN_MYSTERY_MANOR_TILE_DARK_1(11, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.MARBLE_2, p -> p.blended(false)),
	MISTHALIN_MYSTERY_MANOR_TILE_DARK_2(10, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.MARBLE_2, p -> p.blended(false)),
	MISTHALIN_MYSTERY_MANOR_TILE_LIGHT_1(127, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.MARBLE_1, p -> p.blended(false)),
	MISTHALIN_MYSTERY_MANOR_TILE_LIGHT_2(2, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.MARBLE_1, p -> p.blended(false)),
	MISTHALIN_MYSTERY_MANOR_WOOD(119, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),

	// Castle Wars
	CASTLE_WARS_LOBBY_FLOOR(14, Area.CASTLE_WARS_LOBBY, GroundMaterial.TILES_2X2_2_GLOSS, p -> p
		.saturation(0)
		.shiftLightness(4)
		.blended(false)),
	CASTLE_WARS_SARADOMIN_FLOOR_CENTER(15, Area.CASTLE_WARS_ARENA_SARADOMIN_SIDE, GroundMaterial.FALADOR_PATHS, p -> p
		.saturation(1)
		.shiftLightness(18)
		.hue(9)
		.blended(false)),
	CASTLE_WARS_SARADOMIN_FLOOR(26, Area.CASTLE_WARS, GroundMaterial.FALADOR_PATHS, p -> p
		.saturation(1)
		.shiftLightness(5)
		.blended(false)),
	CASTLE_WARS_ZAMORAK_FLOOR(15, Area.CASTLE_WARS, GroundMaterial.TILES_2X2_2_GLOSS, p -> p
		.saturation(1)
		.shiftLightness(5)
		.blended(false)),

	// Zanaris
	COSMIC_ENTITYS_PLANE_ABYSS(37, Area.COSMIC_ENTITYS_PLANE, GroundMaterial.NONE, p -> p.lightness(0).blended(false)),
	ZANARIS_POND_WATER(p -> p.ids(151).area(Area.ZANARIS).waterType(WaterType.WATER)),

	// Morytania
	CANIFIS_FLOORS(85, Area.CANIFIS, GroundMaterial.HD_WOOD_PLANKS_1),

	MORYTANIA_SLAYER_TOWER(102, Area.MORYTANIA_SLAYER_TOWER, GroundMaterial.VARROCK_PATHS),
	MORYTANIA_FENKENSTRAINS_CASTLE(102, Area.FENKENSTRAINS_CASTLE, GroundMaterial.VARROCK_PATHS),
	SLEPE_PATHS(p -> p.ids(11).area(Area.SLEPE).groundMaterial(GroundMaterial.GRAVEL)),
	ABANDONED_MINE_ROCK(11, Area.MORYTANIA, GroundMaterial.DIRT),
	TRUE_BLOOD_ALTAR_BLOOD(72, Area.TRUE_BLOOD_ALTAR, WaterType.BLOOD),
	BARROWS_PATH(GroundMaterial.DIRT, p -> p
		.ids(110)
		.area(Area.BARROWS)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)),
	TEMPLE_TREKKING_SWAMP_WATER(p -> p.ids(85).area(Area.TEMPLE_TREKKING_INSTANCES).waterType(WaterType.SWAMP_WATER)),

	// Wilderness
	WILDERNESS_NORTH_OF_RESOURCE_AREA_HILLS(11, Area.WILDERNESS_NORTH_OF_RESOURCE_AREA, GroundMaterial.VARIED_DIRT),
	WILDERNESS_FOUNTAIN_OF_RUNE_BLOOD(p -> p.ids(86).area(Area.WILDERNESS_FOUNTAIN_OF_RUNE).waterType(WaterType.BLOOD)),

	// Tirannwn
	ARANDAR_PATHS(p -> p
		.area(Area.ARANDAR)
		.ids(106)
		.groundMaterial(GroundMaterial.SAND_BRICK)
		.shiftLightness(12)
		.shiftSaturation(-2)
	),
	LLETYA_WOOD_FLOORS(p -> p.ids(22).area(Area.LLETYA_UPPER_LEVELS).groundMaterial(GroundMaterial.HD_WOOD_PLANKS_1)),
	TIRANNWN_PATHS(p -> p.area(Area.TIRANNWN).ids(106).groundMaterial(GroundMaterial.GRASSY_DIRT)),
	PRIFDDINAS_POND_WATER(p -> p.area(Area.PRIFDDINAS).ids(151).waterType(WaterType.WATER)),
	POISON_WASTE(85, Area.POISON_WASTE, WaterType.POISON_WASTE),
	POISON_WASTE_DUNGEON_TAR(p -> p.ids(304).area(Area.POISON_WASTE_DUNGEON).waterType(WaterType.BLACK_TAR_FLAT)),

	// Fossil Island
	FOSSIL_ISLAND_WYVERN_DIRT(p -> p.ids(63).area(Area.FOSSIL_ISLAND_WYVERN_TASK_CAVE).groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR)),
	ANCIENT_MUSHROOM_POOL(95, Area.FOSSIL_ISLAND, WaterType.SWAMP_WATER_FLAT),
	FOSSIL_ISLAND_CENTRAL_BANK_FIX(11, Area.FOSSIL_ISLAND_CENTRAL_BANK_FIX, GroundMaterial.GRAVEL, p -> p
		.shiftLightness(-2)
		.blended(false)),
	FOSSIL_ISLAND_HILL_HOUSE_INTERIOR(11, Area.FOSSIL_ISLAND_HILL_HOUSE_INTERIOR, GroundMaterial.VARROCK_PATHS),
	FOSSIL_ISLAND_HILL_HOUSE(11, Area.FOSSIL_ISLAND_HILL_HOUSE_FIX, GroundMaterial.VARROCK_PATHS, p -> p
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE_LIGHT_2)
	),
	FOSSIL_ISLAND_HILL_TEXTURE_FIX(11, Area.FOSSIL_ISLAND_HILL_TEXTURE_FIX, GroundMaterial.VARIED_DIRT),
	FOSSIL_ISLAND_VOLCANO_PATH(p -> p.ids(94).area(Area.FOSSIL_ISLAND).groundMaterial(GroundMaterial.GRAVEL)),
	FOSSIL_ISLAND_SWAMP_WATER(p -> p.ids(59).area(Area.FOSSIL_ISLAND).waterType(WaterType.SWAMP_WATER)),

	// Ape Atoll
	APE_ATOLL_WATERFALLS(p -> p.area(Area.APE_ATOLL_WATERFALLS).waterType(WaterType.WATER).blended(false)),

	// Zeah
	LOVAKENGJ_GROUND(p -> p.ids(11).area(Area.LOVAKENGJ).groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)),
	LOVAKENGJ_PATH(p -> p.ids(132).area(Area.LOVAKENGJ).groundMaterial(GroundMaterial.PACKED_EARTH)),
	// Great Kourend
	KOUREND_CASTLE_BLEND_FIX(11, Area.KOUREND_CASTLE_ENTRANCE_FIX, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
	KOUREND_CASTLE_FLOOR(11, Area.KOUREND_CASTLE, GroundMaterial.VARROCK_PATHS),
	KOUREND_CASTLE_GUARDPOSTS_FLOORS(11, Area.KOUREND_GUARDPOSTS, GroundMaterial.VARROCK_PATHS),
	KOUREND_HOUSES_FLOOR_11(11, Area.KOUREND_HOUSES, GroundMaterial.VARROCK_PATHS),
	SHAYZIEN_INTERIOR_FLOOR_11(11, Area.SHAYZIEN_INTERIORS_COBBLE, GroundMaterial.VARROCK_PATHS),
	SHAYZIEN_BANK_FLOOR_11(11, Area.SHAYZIEN_BANK, GroundMaterial.VARROCK_PATHS),
	KOUREND_GREAT_STATUE_BLEND_FIX_1(108, Area.GREAT_KOUREND_STATUE, GroundMaterial.GRASS_1, p -> p.blended(true)),
	KOUREND_GREAT_STATUE_BLEND_FIX_2(11, Area.GREAT_KOUREND_STATUE, GroundMaterial.GRASS_1, p -> p.blended(false)),
	HOSIDIUS_WELL_BLEND_FIX(-119, Area.HOSIDIUS_WELL, GroundMaterial.FALADOR_PATHS, p -> p.blended(false)),
	HOSIDIUS_STAIRS_BLEND_FIX(-119, Area.HOSIDIUS_STAIRS, GroundMaterial.FALADOR_PATHS, p -> p.blended(false)),
	SHAYZIEN_EAST_PATH_FIX(11, Area.SHAYZIEN_EAST_ENTRANCE_BLEND_FIX, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
	XERICS_LOOKOUT_TILE(Area.XERICS_LOOKOUT, GroundMaterial.TILES_2x2_2, p -> p.blended(false).ids(50, 2)),

	HOSIDIUS_STONE_FLOOR(123, Area.HOSIDIUS, GroundMaterial.FALADOR_PATHS),
	BLOOD_ALTAR_BLOOD(72, Area.BLOOD_ALTAR, WaterType.BLOOD),
	SHAYZIEN_PAVED_AREA(Area.SHAYZIEN, GroundMaterial.GRAVEL, p -> p.blended(false).ids(2, -117)),
	SHAYZIEN_RED_FLOOR(86, Area.SHAYZIEN, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),

	SHAYZIEN_COMBAT_RING_FLOOR(Area.SHAYZIEN_COMBAT_RING, GroundMaterial.CARPET, p -> p
		.blended(false)
		.ids(30, 37, 72, 73)),

	MESS_HALL_KITCHEN_TILE_1(30, Area.MESS_HALL_KITCHEN, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
	MESS_HALL_KITCHEN_TILE_2(99, Area.MESS_HALL_KITCHEN, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),
	// Kebos Swamp
	XERIC_DIRT_SYMBOL_FIX(115, Area.KEBOS_SWAMP_XERIC_DIRT, GroundMaterial.DIRT, p -> p.blended(false)),
	// Chambers of Xeric
	MOUNT_QUIDAMORTEM_SYMBOL(-93, Area.MOUNT_QUIDAMORTEM, GroundMaterial.DIRT, p -> p.blended(false)),
	// Kebos Lowlands
	LIZARDMAN_TEMPLE_WATER(-100, Area.LIZARDMAN_TEMPLE, WaterType.SWAMP_WATER_FLAT),
	NORTHERN_WASTES_COBBLE(GroundMaterial.VARROCK_PATHS, p -> p
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE)
		.ids(44)
	),
	XAMPHUR_BOSS_TILES(p -> p.ids(115, 133).area(Area.XAMPHUR_BOSS).groundMaterial(GroundMaterial.MARBLE_2_SEMIGLOSS).blended(false)),

	OVERLAY_KOUREND_PATH(Area.ZEAH, GroundMaterial.MARBLE_1, p -> p.ids(133).blended(false)),
	OVERLAY_ZEAH_PATHS(Area.ZEAH, GroundMaterial.VARROCK_PATHS, p -> p
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE_LIGHT_2)
		.ids(11)
	),

	// The Stranglewood
	STRANGLEWOOD_HILLSIDE(p -> p.area(Area.THE_STRANGLEWOOD_EXTENDED).ids(222).groundMaterial(GroundMaterial.DIRT).blended(false)),
	STRANGLEWOOD_SNOW(p -> p.area(Area.THE_STRANGLEWOOD_EXTENDED).ids(271).groundMaterial(GroundMaterial.SNOW_1)),

	// Temple of the Eye
	TEMPLE_OF_THE_EYE_INCORRECT_WATER(p -> p
		.ids(-100, 156)
		.area(Area.TEMPLE_OF_THE_EYE)
		.groundMaterial(GroundMaterial.TEMPLE_OF_THE_EYE_FLOOR)
		.blendedAsOpposite(true)
		.shiftHue(8)
		.shiftSaturation(3)
		.shiftLightness(-20)
	),
	TEMPLE_OF_THE_EYE_PATH_BLENDING_FIX(p -> p
		.ids()
		.area(Area.TEMPLE_OF_THE_EYE_BLEND_FIX)
		.groundMaterial(GroundMaterial.TEMPLE_OF_THE_EYE_FLOOR)
		.blendedAsOpposite(true)
		.shiftHue(27)
		.saturation(5)
		.lightness(38)
	),
	TEMPLE_OF_THE_EYE_PATH_BLENDING_FIX_TOGGLE(p -> p
		.ids(203)
		.area(Area.TEMPLE_OF_THE_EYE_BLEND_FIX)
		.groundMaterial(GroundMaterial.SAND)
		.replaceWithIf(TEMPLE_OF_THE_EYE_PATH_BLENDING_FIX, plugin -> plugin.configGroundBlending)
	),
	TEMPLE_OF_THE_EYE_CENTER_PLATFORM_181(p -> p
		.ids(181)
		.area(Area.TEMPLE_OF_THE_EYE_CENTER_PLATFORM)
		.groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)
		.blended(false)
		.lightness(18)
	),
	TEMPLE_OF_THE_EYE_CENTER_PLATFORM_204(p -> p
		.ids(204)
		.area(Area.TEMPLE_OF_THE_EYE_CENTER_PLATFORM)
		.groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)
		.blended(false)
		.lightness(58)
	),
	TEMPLE_OF_THE_EYE_SAND_202(202, Area.TEMPLE_OF_THE_EYE, GroundMaterial.SAND, p -> p.shiftLightness(-4).blendedAsOpposite(true)),
	TEMPLE_OF_THE_EYE_SAND_203(203, Area.TEMPLE_OF_THE_EYE, GroundMaterial.SAND, p -> p.lightness(64)),
	TEMPLE_OF_THE_EYE_ROCK(p -> p
		.ids(181, 201)
		.area(Area.TEMPLE_OF_THE_EYE)
		.groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)
	),

	// God Wars Dungeon (GWD)
	GWD_WATER(104, Area.GOD_WARS_DUNGEON, WaterType.ICE_FLAT),

	// Purple symbol near Wintertodt
	PURPLE_SYMBOL(68, Area.ZEAH_SNOWY_NORTHERN_REGION, GroundMaterial.DIRT, p -> p.blended(false)),
	// Zeah Defaults
	ZEAH_DIRT(p -> p
		.area(Area.ZEAH)
		.groundMaterial(GroundMaterial.VARIED_DIRT)
		.ids(114, 148)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
	),

	// Burthorpe games room
	GAMES_ROOM_FLOOR(22, Area.GAMES_ROOM, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),

	CRANDOR_GROUND_1(11, Area.CRANDOR, GroundMaterial.GRAVEL),

	FISHING_TRAWLER_BOAT_PORT_KHAZARD_FIX(42, Area.FISHING_TRAWLER_BOAT_PORT_KHAZARD, WaterType.WATER),
	FISHING_TRAWLER_BOAT_FLOODED(6, Area.FISHING_TRAWLER_BOAT_FLOODED, WaterType.WATER_FLAT),

	// Mind Altar
	MIND_ALTAR_TILE_1(3, Area.MIND_ALTAR, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
	MIND_ALTAR_TILE_4(Area.MIND_ALTAR, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false).ids(4, 10)),

	// Law Altar
	LAW_ALTAR_SYMBOL(87, Area.LAW_ALTAR, GroundMaterial.DIRT, p -> p.blended(false)),

	// Dragon Slayer II
	DS2_SHIPS_WATER(6, Area.DS2_SHIPS, WaterType.WATER_FLAT),
	DREAM_WORLD_GROUND(160, Area.LUNAR_DREAM_WORLD, GroundMaterial.NONE),

	// Camdozaal (Below Ice Mountain)
	CAMDOZAAL_WATER(-75, Area.CAMDOZAAL, WaterType.WATER),

	// Pest Control
	PEST_CONTROL_LANDER_WATER_FIX_1(-95, Area.PEST_CONTROL_LANDER_WATER_FIX, WaterType.WATER),
	PEST_CONTROL_LANDER_WATER_FIX_2(42, Area.PEST_CONTROL_LANDER_WATER_FIX, WaterType.WATER),

	// Barbarian Assault
	BA_WAITING_ROOM_NUMBERS(89, Area.BARBARIAN_ASSAULT_WAITING_ROOMS, GroundMaterial.DIRT, p -> p.blended(false)),

	// Tombs of Amascut
	TOA_DISABLE_BLENDING_4(4, Area.TOA_PATH_HUB, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_11(11, Area.TOA_PATH_HUB, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_86(86, Area.TOA_PATH_HUB, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_N23(-23, Area.TOA_PATH_HUB, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_50(50, Area.TOA_PATH_OF_SCABARAS_PUZZLE, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_66(66, Area.TOA_PATH_OF_SCABARAS_BOSS, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_N18(-18, Area.TOA_PATH_OF_APMEKEN_PUZZLE, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_30(30, Area.TOA_PATH_OF_APMEKEN_BOSS, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_N16(-16, Area.TOA_PATH_OF_HET_PUZZLE, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_N62(-62, Area.TOA_PATH_OF_CRONDIS_BOSS, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_CRONDIS_PUZZLE_WATER(-5, Area.TOA_PATH_OF_CRONDIS_PUZZLE, GroundMaterial.NONE, p -> p.blended(false)),
	TOA_DISABLE_BLENDING_LOOT_ROOM(Area.TOA_LOOT_ROOM, GroundMaterial.NONE, p -> p.blended(false)),

	// Tombs of Amascut
	TOA_CRONDIS_ROCK(Area.TOA_PATH_OF_CRONDIS_BOSS, GroundMaterial.GRUNGE_2, p -> p.ids(134, 182).blended(false)),
	TOA_CRONDIS_ROCK_SUBMERGED(Area.TOA_CRONDIS_ISLAND_SUBMERGED, GroundMaterial.SUBMERGED_GRUNGE_2, p -> p.ids(133).blended(false)),
	TOA_CRONDIS_SWAMP_SLUDGE(p -> p.ids(133, 245).area(Area.TOA_PATH_OF_CRONDIS_BOSS).waterType(WaterType.SWAMP_WATER).blended(false)),
	TOA_CRONDIS_WATER(p -> p.ids(246).area(Area.TOA_CRONDIS_WATER).waterType(WaterType.SWAMP_WATER).blended(false)),

	// Desert Treasure 2 areas
	THE_SCAR_WATER(p -> p.ids(302).area(Area.THE_SCAR).waterType(WaterType.SCAR_SLUDGE).blended(false)),
	LASSAR_UNDERCITY_HIDDEN(p -> p.ids(264, 286).area(Area.LASSAR_UNDERCITY).groundMaterial(GroundMaterial.NONE).blended(false)),
	LASSAR_UNDERCITY_CARPET(285, Area.LASSAR_UNDERCITY, GroundMaterial.CARPET, p -> p.blended(false)),
	LASSAR_UNDERCITY_TILES(p -> p
		.ids(290, 291, 298)
		.area(Area.LASSAR_UNDERCITY)
		.groundMaterial(GroundMaterial.LASSAR_UNDERCITY_TILES)
		.blended(false)),
	LASSAR_UNDERCITY_WATER(p -> p
		.ids(292)
		.area(Area.LASSAR_UNDERCITY_WATER)
		.waterType(WaterType.PLAIN_WATER)
		.blended(false)),
	ANCIENT_VAULT_CARPET(283, Area.ANCIENT_VAULT, GroundMaterial.NONE, p -> p.blended(false)),

	// POHs
	POH_DESERT_INDOORS(Area.PLAYER_OWNED_HOUSE, GroundMaterial.TILES_2x2_2, p -> p.blended(false).ids(26, 99)),

	// Cutscenes
	LAND_OF_GOBLINS_WATER_FIX(13, Area.LAND_OF_GOBLINS_CUTSCENE_WATER, WaterType.WATER),
	CANOE_CUTSCENE_STONE(Area.CANOE_CUTSCENE, GroundMaterial.STONE_SCROLLING, p -> p.ids(0, 11)),

	// Random events
	PRISON_PETE_TILE_1(2, Area.RANDOM_EVENT_PRISON_PETE, GroundMaterial.MARBLE_1, p -> p.blended(false)),
	PRISON_PETE_TILE_2(-125, Area.RANDOM_EVENT_PRISON_PETE, GroundMaterial.MARBLE_2, p -> p.blended(false)),
	CLASSROOM(p -> p.ids(102).area(Area.RANDOM_EVENT_CLASSROOM).groundMaterial(GroundMaterial.HD_WOOD_PLANKS_1)),

	// Elid Cave fix
	ELID_CAVE_WATER_FIX(-126, Area.ELID_CAVE, WaterType.WATER),

	// Entrana glass/furnace building fix
	ENTRANA_GLASS_BUILDING_FIX(10, Area.ENTRANA_GLASS_BUILDING_FIX, GroundMaterial.GRAVEL, p -> p
		.shiftLightness(8)
		.blended(false)),

	// Ancient Cavern upper level water change
	ANCIENT_CAVERN_WATER(41, Area.ANCIENT_CAVERN, WaterType.WATER_FLAT),

	// Lunar Isles
	LUNAR_ISLAND_HOUSES_DIRT_FLOOR(81, Area.LUNAR_VILLAGE_HOUSE_INTERIORS_GROUND, GroundMaterial.VARIED_DIRT, p -> p
		.blended(true)),
	LUNAR_ISLAND_HOUSES_CARPET_FLOOR(88, Area.LUNAR_VILLAGE_HOUSE_INTERIORS_GROUND, GroundMaterial.CARPET, p -> p
		.blended(false)),
	LUNAR_ISLAND_HOUSES_WOOD_FLOOR(81, Area.LUNAR_VILLAGE_HOUSE_INTERIORS_FIRST, GroundMaterial.HD_WOOD_PLANKS_1, p -> p
		.blended(true)),
	LUNAR_ESSENCE_MINE_WATER(p -> p.ids(151).area(Area.LUNAR_ESSENCE_MINE).waterType(WaterType.WATER)),
	KELDAGRIM_PATHS(117, GroundMaterial.FALADOR_PATHS),

	CERBERUS_WATER(128, Area.CERBERUS, WaterType.SWAMP_WATER_FLAT),
	SHIP_SAILING_WATER(p -> p.area(Area.SHIP_SAILING).ids(6).waterType(WaterType.WATER_FLAT)),

	WIZARD_TOWER_ROOF(GroundMaterial.MARBLE_1_GLOSS, p -> p
		.ids(33)
		.blended(false)
		.area(Area.WIZARD_TOWER_ROOF)
		.seasonalReplacement(SeasonalTheme.WINTER, SNOW_2)
	),
	VARROCK_PATHS(Area.OVERWORLD, GroundMaterial.VARROCK_PATHS, p -> p
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_JAGGED_STONE_TILE)
		.ids(-85, -77, 11)
	),
	SOUL_WARS_DIRT_PATH(p -> p.ids(106).area(Area.ISLE_OF_SOULS).groundMaterial(GroundMaterial.DIRT)),

	// Default overworld overlays
	OVERWORLD_GRASS(p -> p
		.area(Area.OVERWORLD)
		.ids(29)
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)
	),

	// Fallback overlays
	OVERLAY_WATER(p -> p.ids(-105, -98, 6, 41, 104, 196).waterType(WaterType.WATER)),
	OVERLAY_DIRT(
		GroundMaterial.DIRT,
		p -> p.ids(-124, -84, -83, 14, 15, 16, 21, 22, 23, 60, 77, 81, 82, 88, 89, 101, 102, 107, 108, 110, 115, 123, 227)
	),
	OVERLAY_GRAVEL(GroundMaterial.GRAVEL, p -> p.ids(-76, 2, 3, 4, 8, 9, 10, 119, 127)),
	OVERLAY_SWAMP_WATER(WaterType.SWAMP_WATER, p -> p.ids(-100, 7, 128)),
	OVERLAY_WOOD_PLANKS(GroundMaterial.WOOD_PLANKS_1, p -> p.ids(5, 35)),
	OVERLAY_CLEAN_WOOD_PLANKS(GroundMaterial.CLEAN_WOOD_FLOOR, p -> p.ids(52).shiftLightness(-4)),
	OVERLAY_SAND(GroundMaterial.SAND, p -> p.ids(25, 26, 76)),
	OVERLAY_BRICK_BROWN(GroundMaterial.BRICK_BROWN, p -> p.ids(27, 46).blended(false)),
	OVERLAY_SNOW(GroundMaterial.SNOW_1, p -> p.ids(30, 33, 254)),
	OVERLAY_VARIED_DIRT(GroundMaterial.VARIED_DIRT, p -> p.ids(49, 83, 91)),
	OVERLAY_SAND_BRICK(GroundMaterial.SAND_BRICK, p -> p.ids(-49, 84)),
	OVERLAY_N122(-122, GroundMaterial.TILES_2X2_2_GLOSS),
	OVERLAY_N119(-119, GroundMaterial.FALADOR_PATHS),
	OVERLAY_N93(-93, GroundMaterial.CARPET),
	OVERLAY_N82(-82, GroundMaterial.CLEAN_TILE),
	OVERLAY_12(12, GroundMaterial.STONE_PATTERN),
	OVERLAY_13(13, GroundMaterial.CARPET, p -> p.blended(false)),
	LAVA(19, GroundMaterial.HD_LAVA, p -> p.hue(0).saturation(0).shiftLightness(127).blended(false)),
	OVERLAY_20(20, GroundMaterial.MARBLE_DARK),
	OVERLAY_28(28, GroundMaterial.BRICK, p -> p.blended(false)),
	OVERLAY_29(29, GroundMaterial.GRASS_1),
	OVERLAY_32(32, GroundMaterial.CONCRETE),
	OVERLAY_90(90, GroundMaterial.DIRT), // Known locations: 90 = Dark Wizards Tower; Random shading in the overworld
	OVERLAY_DIRT_PATH(GroundMaterial.VARIED_DIRT, p -> p
		.ids(36, 80, 85, 131)), // 36 = Corsair Cove, 80 = Mythics Guild, 85 = Slayer Tower, 131 = Burgh de Rott
	OVERWORLD_GRUNGE(GroundMaterial.GRUNGE, p -> p.ids(48, 185)),
	OVERLAY_120(GroundMaterial.GRUNGE, p -> p.ids(120)),
	OVERLAY_SKULLS(261, GroundMaterial.SKULL_OBELISK),

	NONE(GroundMaterial.DIRT, p -> {});

	@Nullable
	public final Integer[] filterIds;
	public final Area area;
	public final GroundMaterial groundMaterial;
	public final WaterType waterType;
	public final boolean blended;
	public final boolean blendedAsUnderlay;
	public final int shiftHue;
	public final int minHue;
	public final int maxHue;
	public final int shiftSaturation;
	public final int minSaturation;
	public final int maxSaturation;
	public final int shiftLightness;
	public final int minLightness;
	public final int maxLightness;
	public final int uvOrientation;
	public final float uvScale;
	public final TileOverrideResolver<Overlay> replacementResolver;

	Overlay(int id, GroundMaterial material) {
		this(p -> p.ids(id).groundMaterial(material));
	}

	Overlay(int id, Area area, GroundMaterial material) {
		this(p -> p.ids(id).groundMaterial(material).area(area));
	}

	Overlay(int id, Area area, WaterType waterType) {
		this(p -> p.ids(id).waterType(waterType).area(area).blended(false));
	}

	Overlay(int id, GroundMaterial material, Consumer<TileOverrideBuilder<Overlay>> consumer) {
		this(p -> p.ids(id).groundMaterial(material).apply(consumer));
	}

	Overlay(int id, Area area, GroundMaterial material, Consumer<TileOverrideBuilder<Overlay>> consumer) {
		this(p -> p.ids(id).groundMaterial(material).area(area).apply(consumer));
	}

	Overlay(GroundMaterial material, Consumer<TileOverrideBuilder<Overlay>> consumer) {
		this(p -> p.groundMaterial(material).apply(consumer));
	}

	Overlay(WaterType waterType, Consumer<TileOverrideBuilder<Overlay>> consumer) {
		this(p -> p.waterType(waterType).blended(false).apply(consumer));
	}

	Overlay(Area area, GroundMaterial material, Consumer<TileOverrideBuilder<Overlay>> consumer) {
		this(p -> p.groundMaterial(material).area(area).apply(consumer));
	}

	Overlay(Consumer<TileOverrideBuilder<Overlay>> consumer) {
		TileOverrideBuilder<Overlay> builder = new TileOverrideBuilder<>();
		consumer.accept(builder);
		builder.normalize();
		this.filterIds = builder.ids;
		this.replacementResolver = builder.replacementResolver;
		this.area = builder.area;
		this.groundMaterial = builder.groundMaterial;
		this.waterType = builder.waterType;
		this.blended = builder.blended;
		this.blendedAsUnderlay = builder.blendedAsOpposite;
		this.shiftHue = builder.shiftHue;
		this.minHue = builder.minHue;
		this.maxHue = builder.maxHue;
		this.shiftSaturation = builder.shiftSaturation;
		this.minSaturation = builder.minSaturation;
		this.maxSaturation = builder.maxSaturation;
		this.shiftLightness = builder.shiftLightness;
		this.minLightness = builder.minLightness;
		this.maxLightness = builder.maxLightness;
		this.uvOrientation = builder.uvOrientation;
		this.uvScale = builder.uvScale;
	}

	private static final Overlay[] ANY_MATCH;
	private static final HashMap<Integer, Overlay[]> FILTERED_MAP = new HashMap<>();

	static {
		ArrayList<Overlay> anyMatch = new ArrayList<>();
		ListMultimap<Integer, Overlay> multiMap = ArrayListMultimap.create();
		for (Overlay overlay : values()) {
			if (overlay.filterIds == null) {
				anyMatch.add(overlay);
			} else {
				for (Integer id : overlay.filterIds) {
					multiMap.put(id, overlay);
				}
			}
		}

		ANY_MATCH = anyMatch.toArray(new Overlay[0]);
		for (Map.Entry<Integer, Collection<Overlay>> entry : multiMap.asMap().entrySet())
			FILTERED_MAP.put(entry.getKey(), entry.getValue().toArray(new Overlay[0]));
	}

	@NonNull
	public static Overlay getOverlay(Scene scene, Tile tile, HdPlugin plugin) {
		return getOverlayBeforeReplacements(scene, tile).resolveReplacements(scene, tile, plugin);
	}

	@NonNull
	public static Overlay getOverlayBeforeReplacements(Scene scene, Tile tile) {
		LocalPoint localLocation = tile.getLocalLocation();
		int[] worldPoint = localToWorld(scene, localLocation.getX(), localLocation.getY(), tile.getRenderLevel());

		Overlay match = Overlay.NONE;
		for (Overlay overlay : ANY_MATCH) {
			if (overlay.area.containsPoint(worldPoint)) {
				match = overlay;
				break;
			}
		}

		int tileExX = localLocation.getSceneX() + SceneUploader.SCENE_OFFSET;
		int tileExY = localLocation.getSceneY() + SceneUploader.SCENE_OFFSET;
		short overlayId = scene.getOverlayIds()[tile.getRenderLevel()][tileExX][tileExY];
		Overlay[] overlays = FILTERED_MAP.get((int) overlayId);
		if (overlays != null) {
			for (Overlay overlay : overlays) {
				if (overlay.ordinal() >= match.ordinal())
					break;
				if (overlay.area.containsPoint(worldPoint)) {
					match = overlay;
					break;
				}
			}
		}

		return match;
	}

	public Overlay resolveReplacements(Scene scene, Tile tile, HdPlugin plugin) {
		if (replacementResolver != null) {
			var replacement = replacementResolver.resolve(plugin, scene, tile, this);
			if (replacement == null)
				replacement = NONE;
			return replacement;
		}

		return this;
	}

	public int modifyColor(int jagexHsl) {
		int h = jagexHsl >> 10 & 0x3F;
		h += shiftHue;
		h = clamp(h, minHue, maxHue);

		int s = jagexHsl >> 7 & 7;
		s += shiftSaturation;
		s = clamp(s, minSaturation, maxSaturation);

		int l = jagexHsl & 0x7F;
		l += shiftLightness;
		l = clamp(l, minLightness, maxLightness);

		return h << 10 | s << 7 | l;
	}
}
