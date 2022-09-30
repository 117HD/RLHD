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
import net.runelite.api.Client;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public enum Overlay {
    // Tutorial Island
    TUTORIAL_ISLAND_KITCHEN_TILE_1(9, Area.TUTORIAL_ISLAND_KITCHEN, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
    TUTORIAL_ISLAND_KITCHEN_TILE_2(11, Area.TUTORIAL_ISLAND_KITCHEN, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),
    TUTORIAL_ISLAND_QUEST_BUILDING_TILE_1(13, Area.TUTORIAL_ISLAND_QUEST_BUILDING, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
    TUTORIAL_ISLAND_QUEST_BUILDING_TILE_2(26, Area.TUTORIAL_ISLAND_QUEST_BUILDING, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),
    TUTORIAL_ISLAND_BANK_TILE_1(2, Area.TUTORIAL_ISLAND_BANK, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
    TUTORIAL_ISLAND_BANK_TILE_2(3, Area.TUTORIAL_ISLAND_BANK, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),

    // Lumbridge
    LUM_BRIDGE(10, Area.LUM_BRIDGE, GroundMaterial.GRAVEL),
    LUMBRIDGE_CASTLE_TILE(3, Area.LUMBRIDGE_CASTLE_BASEMENT, GroundMaterial.MARBLE_1_SEMIGLOSS),
    LUMBRIDGE_CASTLE_FLOORS(10, Area.LUMBRIDGE_CASTLE, GroundMaterial.VARROCK_PATHS_LIGHT, p -> p.shiftLightness(10)),
    LUMBRIDGE_TOWER_FLOOR_TEXTURE(10, Area.LUMBRIDGE_TOWER_FLOOR, GroundMaterial.VARROCK_PATHS_LIGHT, p -> p.shiftLightness(10)),
    BLEND_IMPROVEMENT_1(10, Area.LUMBRIDGE_DRAYNOR_PATH_BLEND_1, GroundMaterial.GRAVEL, p -> p.shiftLightness(6).hue(7).saturation(1)),
    BLEND_IMPROVEMENT_2(10, Area.LUMBRIDGE_DRAYNOR_PATH_BLEND_2, GroundMaterial.GRAVEL, p -> p.shiftLightness(9).hue(7).saturation(1)),
    SWAMP_PATH_FIX_1(81, Area.LUMBRIDGE_SWAMP_PATH_FIX, GroundMaterial.DIRT, p -> p.saturation(4).shiftLightness(-3)),
    SWAMP_PATH_FIX_2(83, Area.LUMBRIDGE_SWAMP_PATH_FIX, GroundMaterial.DIRT, p -> p.saturation(4).shiftLightness(-5)),
    SWAMP_PATH_FIX_3(88, Area.LUMBRIDGE_SWAMP_PATH_FIX, GroundMaterial.GRAVEL, p -> p.shiftLightness(12).hue(7).saturation(1)),
    LUMBRIDGE_VARROCK_PATH_FIX_1(3, Area.LUMBRIDGE_VARROCK_PATH_FIX, GroundMaterial.GRAVEL, p -> p.shiftLightness(12).hue(7).saturation(1)),
    LUMBRIDGE_VARROCK_PATH_FIX_2(8, Area.LUMBRIDGE_VARROCK_PATH_FIX, GroundMaterial.GRAVEL, p -> p.shiftLightness(12).hue(7).saturation(1)),
    LUMBRIDGE_PATHS(10, Area.LUMBRIDGE, GroundMaterial.GRAVEL, p -> p.shiftLightness(12).hue(7).saturation(1)),
    LUMBRIDGE_CASTLE_ENTRYWAY_1(2, Area.LUMBRIDGE_CASTLE_ENTRYWAY, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    LUMBRIDGE_CASTLE_ENTRYWAY_2(3, Area.LUMBRIDGE_CASTLE_ENTRYWAY, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),

    // Varrock
    VARROCK_MUSEUM_SOUTH_PATH_FIX_1(-85, Area.VARROCK_MUSEUM_SOUTH_PATH_FIX, GroundMaterial.DIRT, p -> p.shiftSaturation(2)),
    VARROCK_MUSEUM_SOUTH_PATH_FIX_2(-84, Area.VARROCK_MUSEUM_SOUTH_PATH_FIX, GroundMaterial.DIRT, p -> p.shiftSaturation(1)),
    VARROCK_MUSEUM_SOUTH_PATH_FIX_3(56, Area.VARROCK_MUSEUM_SOUTH_PATH_FIX, GroundMaterial.VARROCK_PATHS, p -> p.shiftLightness(4)),
    VARROCK_WEST_BANK_SOUTH_PATH_FIX_1(-84, Area.VARROCK_WEST_BANK_SOUTH_PATH_FIX, GroundMaterial.VARROCK_PATHS, p -> p.shiftLightness(4).shiftSaturation(-1)),
    VARROCK_WILDERNESS_DITCH_PATH_FIX_1(-84, Area.VARROCK_WILDERNESS_DITCH_PATH_FIX, GroundMaterial.DIRT, p -> p.shiftSaturation(1)),
    VARROCK_MUSEUM_FLOOR(56, Area.VARROCK_MUSEUM, GroundMaterial.TILES_2x2_2_GLOSS, p -> p.blended(false)),
    VARROCK_MUSEUM_BASEMENT_FLOOR(56, Area.VARROCK_MUSEUM_BASEMENT, GroundMaterial.TILES_2x2_2_GLOSS, p -> p.blended(false)),
    VARROCK_JULIETS_FLOWER_BED(81, Area.VARROCK_JULIETS_HOUSE_FLOWER_BED, GroundMaterial.DIRT, p -> p.blended(true)),
    VARROCK_JULIETS_HOUSE_HARD_FLOORS(-85, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
    VARROCK_JULIETS_HOUSE_CARPET_RED(-93, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.CARPET, p -> p.blended(false)),
    VARROCK_JULIETS_HOUSE_CARPET_PINK(-96, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.CARPET, p -> p.blended(false)),
    VARROCK_JOLLY_BOAR_INN_KITCHEN_1(-84, Area.VARROCK_JOLLY_BOAR_INN, GroundMaterial.TILES_2x2_1_SEMIGLOSS, p -> p.blended(false)),
    VARROCK_JOLLY_BOAR_INN_KITCHEN_2(-85, Area.VARROCK_JOLLY_BOAR_INN, GroundMaterial.TILES_2x2_1_SEMIGLOSS, p -> p.blended(false)),
    VARROCK_CHURCH_CARPET(-83, Area.VARROCK_SARADOMIN_CHURCH, GroundMaterial.NONE, p -> p.blended(false)),
    VARROCK_CHURCH_FLOOR(-85, Area.VARROCK_SARADOMIN_CHURCH, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
    VARROCK_ANVILS(81, Area.VARROCK_ANVILS, GroundMaterial.DIRT),
    VARROCK_BUILDING_RUINS(81, Area.VARROCK_BUILDING_RUINS, GroundMaterial.DIRT),
    VARROCK_BUILDING_FLOOR_1(81, Area.VARROCK, GroundMaterial.TILE_SMALL, p -> p.blended(false)),
    VARROCK_BUILDING_FLOOR_2(4, Area.VARROCK, GroundMaterial.NONE, p -> p.blended(false)),
    VARROCK_PLANT_PATCHES(89, Area.VARROCK, GroundMaterial.DIRT, p -> p.blended(false)),
    VARROCK_EAST_BANK_CENTER(-83, Area.VARROCK_EAST_BANK_CENTER, GroundMaterial.TILES_2x2_1_SEMIGLOSS, p -> p.blended(false)),
    VARROCK_EAST_BANK_OUTSIDE_1(-85, Area.VARROCK_EAST_BANK_OUTSIDE_1, GroundMaterial.TILES_2x2_1_SEMIGLOSS, p -> p.blended(false)),
    VARROCK_EAST_BANK(-85, Area.VARROCK_EAST_BANK, GroundMaterial.TILES_2x2_1_SEMIGLOSS, p -> p.blended(false)),
    VARROCK_ROOF_GRAVEL(2, Area.VARROCK_CASTLE, GroundMaterial.GRAVEL, p -> p.blended(false)),
    VARROCK_ROOF_ARCHERY_FLOOR_1(-83, Area.VARROCK_CASTLE, GroundMaterial.DIRT, p -> p.blended(false)),
    VARROCK_ROOF_ARCHERY_FLOOR_2(-84, Area.VARROCK_CASTLE, GroundMaterial.DIRT, p -> p.blended(false)),
    VARROCK_DIRT_BLENDING_IMPROVEMENT(-84, Area.VARROCK, GroundMaterial.DIRT, p -> p.shiftSaturation(1)),
    // this tile is used by jagex to blend between dirt paths and regular paths; blending desaturates the dirt and looks bad, extra saturation cancels out the effect

    // Barbarian Village
    BARBARIAN_VILLAGE_EAST_PATH_FIX_1(83, Area.BARBARIAN_VILLAGE_EAST_PATH_FIX, GroundMaterial.DIRT, p -> p.shiftSaturation(2)),
    BARBARIAN_VILLAGE_EAST_PATH_FIX_2(2, Area.BARBARIAN_VILLAGE_EAST_PATH_FIX, GroundMaterial.GRAVEL, p -> p.shiftSaturation(-1).shiftLightness(4)),

    // Digsite
    DIGSITE_DOCK(93, Area.DIGSITE_DOCK, GroundMaterial.TILES_2x2_1_GLOSS, p -> p.blended(false)),

    // Al Kharid
    OVERRIDE_SOPHANEM_CHURCH_FLOOR_FIX_1(21, Area.SOPHANEM_FLOORS, GroundMaterial.TILES_2x2_2_SEMIGLOSS, p -> p.blended(false)),
    OVERRIDE_SOPHANEM_CHURCH_FLOOR_FIX_2(26, Area.SOPHANEM_FLOORS, GroundMaterial.TILES_2x2_2_SEMIGLOSS, p -> p.blended(false)),
    MAGE_TRAINING_ARENA_FLOOR(-122, Area.MAGE_TRAINING_ARENA, GroundMaterial.TILES_2x2_2_GLOSS, p -> p.blended(false)),
    AL_KHARID_FLOOR_1(26, Area.AL_KHARID_BUILDINGS, GroundMaterial.TILES_2x2_2_SEMIGLOSS, p -> p
        .blended(false)
        .shiftSaturation(-1)
        .shiftLightness(7)),
    AL_KHARID_FLOOR_2(1, Area.AL_KHARID_BUILDINGS, GroundMaterial.TILES_2x2_2_SEMIGLOSS, p -> p.blended(false)),
    AL_KHARID_FLOOR_MARBLE_1(3, Area.AL_KHARID_BUILDINGS, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),
    AL_KHARID_FLOOR_MARBLE_2(4, Area.AL_KHARID_BUILDINGS, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
    KHARID_PATHS_1(26, Area.KHARID_DESERT_REGION, GroundMaterial.DIRT, p -> p.saturation(2).hue(6).shiftLightness(5)),
    KHARID_PATHS_2(76, Area.KHARID_DESERT_REGION, GroundMaterial.DIRT, p -> p.saturation(3).hue(6).shiftLightness(-10)),
    KHARID_PATHS_3(25, Area.KHARID_DESERT_REGION, GroundMaterial.DIRT, p -> p.saturation(3).hue(6)),

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
    FALADOR_TRIANGLE_DIRT_PATH_FIX_2(83, Area.FALADOR_TRIANGLE_PATH_FIX_1, GroundMaterial.GRAVEL, p -> p.shiftLightness(5).shiftSaturation(-1).shiftHue(-4)),
    FALADOR_TRIANGLE_PATH_PATH_FIX_1(119, Area.FALADOR_TRIANGLE_PATH_FIX_2, GroundMaterial.GRAVEL, p -> p.shiftLightness(2).shiftSaturation(-1).shiftHue(-4)),
    FALADOR_SOUTH_PATH_FIX_1(119, Area.FALADOR_SOUTH_PATH_FIX, GroundMaterial.FALADOR_PATHS, p -> p.hue(7).saturation(1).shiftLightness(7)),
    FALADOR_SOUTH_PATH_FIX_2(88, Area.FALADOR_SOUTH_PATH_FIX, GroundMaterial.GRAVEL, p -> p.shiftLightness(2).shiftSaturation(-1).shiftHue(-4)),
    FALADOR_PATHS(-119, Area.FALADOR, GroundMaterial.FALADOR_PATHS, p -> p.hue(7).saturation(1).shiftLightness(7)),
    FALADOR_HAIRDRESSER_TILE_1(77, Area.FALADOR_HAIRDRESSER, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    FALADOR_HAIRDRESSER_TILE_2(123, Area.FALADOR_HAIRDRESSER, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
    FALADOR_PARTY_ROOM_TILE_1(33, Area.FALADOR_PARTY_ROOM, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    FALADOR_PARTY_ROOM_TILE_2(123, Area.FALADOR_PARTY_ROOM, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
    FALADOR_PARTROOM_STAIRS_FIX(37, Area.FALADOOR_PARTY_ROOM_STAIRS_FIX, GroundMaterial.NONE, p -> p
        .blended(false)
        .lightness(0)),
    FALADOR_BUILDING_FLOOR_1(123, Area.FALADOR, GroundMaterial.TILES_2x2_1_GLOSS, p -> p.blended(false)),
    FALADOR_BUILDING_FLOOR_2(33, Area.FALADOR, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    FALADOR_BUILDING_FLOOR_3(77, Area.FALADOR, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
    FALADOR_BUILDING_FLOOR_4(13, Area.FALADOR, GroundMaterial.NONE, p -> p.blended(false)),

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
    EDGEVILLE_BANK_SURROUNDING_PATH(10, Area.EDGEVILLE_BANK_SURROUNDING_PATH, GroundMaterial.VARROCK_PATHS),
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


    // Burthorpe
    HEROES_GUILD_TILE_1(3, Area.HEROES_GUILD, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    HEROES_GUILD_TILE_2(4, Area.HEROES_GUILD, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
    WARRIORS_GUILD_TILE_1(10, Area.WARRIORS_GUILD_FLOOR_2, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    WARRIORS_GUILD_TILE_2(11, Area.WARRIORS_GUILD_FLOOR_2, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
    WARRIORS_GUILD_TILE_BLUE(87, Area.WARRIORS_GUILD, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    WARRIORS_GUILD_FLOOR_1(11, Area.WARRIORS_GUILD, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
    WARRIORS_GUILD_CARPET(86, Area.WARRIORS_GUILD, GroundMaterial.CARPET, p -> p.blended(false)),

    // Seers
    SEERS_BANK_TILE_1(3, Area.SEERS_BANK, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    SEERS_BANK_TILE_2(4, Area.SEERS_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
    SEERS_BANK_TILE_3(8, Area.SEERS_BANK, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    SEERS_HOUSE_FLOORS(22, Area.SEERS_HOUSES, GroundMaterial.WOOD_PLANKS_1, p -> p
        .blended(false)
        .lightness(45)
        .saturation(2)
        .hue(15)),
    SEERS_CHURCH_1(-85, Area.SEERS_CHURCH, GroundMaterial.TILES_2x2_2, p -> p.blended(false)),
    SEERS_CHURCH_2(8, Area.SEERS_CHURCH, GroundMaterial.MARBLE_2, p -> p.blended(false)),

    // Catherby
    CATHERBY_BEACH_OBELISK_WATER_FIX(6, Area.CATHERBY_BEACH_OBELISK_WATER_FIX, WaterType.WATER_FLAT),
    CATHERBY_BEACH_LADDER_FIX(11, Area.CATHERBY_BEACH_LADDER_FIX, GroundMaterial.NONE, p -> p.blended(false)),
    CATHERBY_BANK_TILE_1(3, Area.CATHERBY_BANK, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    CATHERBY_BANK_TILE_2(4, Area.CATHERBY_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),

    // Ardougne
    EAST_ARDOUGNE_CASTLE_DIRT_FIX(14, Area.EAST_ARDOUGNE_CASTLE_DIRT_FIX, GroundMaterial.DIRT, p -> p
        .shiftLightness(7)
        .blended(false)),
    EAST_ARDOUGNE_CASTLE_PATH_FIX(10, Area.EAST_ARDOUGNE_CASTLE_PATH_FIX, GroundMaterial.VARROCK_PATHS_LIGHT, p -> p
        .shiftLightness(16)
        .blended(false)),
    EAST_ARDOUGNE_PATHS_1(10, Area.EAST_ARDOUGNE, GroundMaterial.VARROCK_PATHS_LIGHT, p -> p.shiftLightness(6)),
    WIZARD_HOUSE_TILE_LIGHT(38, Area.EAST_ARDOUGNE, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
    WIZARD_HOUSE_TILE_DARK(40, Area.EAST_ARDOUGNE, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),

    // Yanille
    YANILLE_BANK_TILE_1(3, Area.YANILLE_BANK, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    YANILLE_BANK_TILE_2(4, Area.YANILLE_BANK, GroundMaterial.MARBLE_2_GLOSS, p -> p.blended(false)),
    YANILLE_BANK_REAR_GROUND(2, Area.YANILLE_BANK, GroundMaterial.TILES_2x2_2_GLOSS, p -> p
        .blended(false)
        .lightness(25)),
    YANILLE_HUNTER_SHOP_FLOOR(16, Area.YANILLE, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false).lightness(32)),
    YANILLE_MAGIC_GUILD_FLOOR_FIX(10, Area.YANILLE_MAGIC_GUILD_FLOORS, GroundMaterial.TILES_2x2_1_SEMIGLOSS, p -> p.lightness(30)),
    GUTANOTH_CAVE(29, Area.GUTANOTH_CAVE, WaterType.SWAMP_WATER_FLAT),

    // Watchtower
    YANILLE_WATCHTOWER_TOP_FLOOR_FIX_2(2, Area.YANILLE_WATCHTOWER_TOP, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),
    YANILLE_WATCHTOWER_TOP_FLOOR_FIX_3(3, Area.YANILLE_WATCHTOWER_TOP, GroundMaterial.MARBLE_1_GLOSS, p -> p.blended(false)),

    // Draynor
    DRAYNOR_AGGIES_HOUSE(-93, Area.DRAYNOR_AGGIES_HOUSE, GroundMaterial.CARPET, p -> p.blended(false)),
    WISE_OLD_MANS_HOUSE_CARPET(86, Area.DRAYNOR, GroundMaterial.CARPET, p -> p.blended(false)),
    DRAYNOR_BANK_FLOOR(10, Area.DRAYNOR_BANK, GroundMaterial.WORN_TILES, p -> p.blended(false)),
    DRAYNOR_MANS_HOUSE_FLOOR(14, Area.DRAYNOR_NORTHERN_HOUSE_FLOOR, GroundMaterial.WOOD_PLANKS_1, p -> p
        .blended(false)
        .lightness(74)
        .shiftHue(-3)
        .shiftSaturation(-7)),

    // Draynor manor
    DRAYNOR_MANOR_TILE_DARK(2, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.MARBLE_1, p -> p.blended(false)),
    DRAYNOR_MANOR_TILE_LIGHT(10, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.MARBLE_2, p -> p.blended(false)),
    DRAYNOR_MANOR_TILE_SMALL(11, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.TILE_SMALL, p -> p.blended(false)),
    DRAYNOR_MANOR_WOOD(119, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),
    DRAYNOR_MANOR_CARPET(127, Area.DRAYNOR_MANOR_INTERIOR, GroundMaterial.CARPET, p -> p.blended(false)),
    DRAYNOR_MANOR_ENTRANCE_DIRT_1(2, Area.DRAYNOR_MANOR, GroundMaterial.DIRT),
    DRAYNOR_MANOR_ENTRANCE_DIRT_2(127, Area.DRAYNOR_MANOR, GroundMaterial.DIRT),

    // Misthalin Mystery
    MISTHALIN_MYSTERY_MANOR_TILE_DARK_1(11, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.MARBLE_2, p -> p.blended(false)),
    MISTHALIN_MYSTERY_MANOR_TILE_DARK_2(10, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.MARBLE_2, p -> p.blended(false)),
    MISTHALIN_MYSTERY_MANOR_TILE_LIGHT_1(127, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.MARBLE_1, p -> p.blended(false)),
    MISTHALIN_MYSTERY_MANOR_TILE_LIGHT_2(2, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.MARBLE_1, p -> p.blended(false)),
    MISTHALIN_MYSTERY_MANOR_WOOD(119, Area.MISTHALIN_MYSTERY_MANOR, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),

    // Castle Wars
    CASTLE_WARS_LOBBY_FLOOR(14, Area.CASTLE_WARS_LOBBY, GroundMaterial.TILES_2x2_2_GLOSS, p -> p
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
    CASTLE_WARS_ZAMORAK_FLOOR(15, Area.CASTLE_WARS, GroundMaterial.TILES_2x2_2_GLOSS, p -> p
        .saturation(1)
        .shiftLightness(5)
        .blended(false)),

    // Zanaris
    COSMIC_ENTITYS_PLANE_ABYSS(37, Area.COSMIC_ENTITYS_PLANE, GroundMaterial.NONE, p -> p.lightness(0).blended(false)),

    // Morytania
    MORYTANIA_SLAYER_TOWER(102, Area.MORYTANIA_SLAYER_TOWER, GroundMaterial.VARROCK_PATHS_LIGHT),
    ABANDONED_MINE_ROCK(11, Area.MORYTANIA, GroundMaterial.DIRT),
    TRUE_BLOOD_ALTAR_BLOOD(72, Area.TRUE_BLOOD_ALTAR, WaterType.BLOOD),

    // Tirannwn
    POISON_WASTE(85, Area.POISON_WASTE, WaterType.POISON_WASTE),

    // Fossil Island
    ANCIENT_MUSHROOM_POOL(95, Area.FOSSIL_ISLAND, WaterType.SWAMP_WATER_FLAT),
    FOSSIL_ISLAND_CENTRAL_BANK_FIX(11, Area.FOSSIL_ISLAND_CENTRAL_BANK_FIX, GroundMaterial.GRAVEL, p -> p
        .shiftLightness(-2)
        .blended(false)),
    FOSSIL_ISLAND_HILL_HOUSE_FIX(11, Area.FOSSIL_ISLAND_HILL_HOUSE_FIX, GroundMaterial.VARROCK_PATHS),
    FOSSIL_ISLAND_HILL_TEXTURE_FIX(11, Area.FOSSIL_ISLAND_HILL_TEXTURE_FIX, GroundMaterial.VARIED_DIRT),

    // Zeah
    // Great Kourend
    KOUREND_CASTLE_BLEND_FIX(11, Area.KOUREND_CASTLE_ENTRANCE_FIX, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
    KOUREND_GREAT_STATUE_BLEND_FIX_1(108, Area.GREAT_KOUREND_STATUE, GroundMaterial.GRASS_1, p -> p.blended(true)),
    KOUREND_GREAT_STATUE_BLEND_FIX_2(11, Area.GREAT_KOUREND_STATUE, GroundMaterial.GRASS_1, p -> p.blended(false)),
    HOSIDIUS_WELL_BLEND_FIX(-119, Area.HOSIDIUS_WELL, GroundMaterial.FALADOR_PATHS, p -> p.blended(false)),
    HOSIDIUS_STAIRS_BLEND_FIX(-119, Area.HOSIDIUS_STAIRS, GroundMaterial.FALADOR_PATHS, p -> p.blended(false)),
    SHAYZIEN_EAST_PATH_FIX(11, Area.SHAYZIEN_EAST_ENTRANCE_BLEND_FIX, GroundMaterial.VARROCK_PATHS, p -> p.blended(false)),
    XERICS_LOOKOUT_TILE(Area.XERICS_LOOKOUT, GroundMaterial.TILES_2x2_2, p -> p.blended(false).ids(50, 2)),

    HOSIDIUS_STONE_FLOOR(123, Area.HOSIDIUS, GroundMaterial.FALADOR_PATHS),
    BLOOD_ALTAR_BLOOD(72, Area.BLOOD_ALTAR, WaterType.BLOOD),
    SHAYZIEN_PAVED_AREA(Area.SHAYZIEN, GroundMaterial.GRAVEL, p -> p.blended(false).ids(2, -117)),

    SHAYZIEN_COMBAT_RING_FLOOR(Area.SHAYZIEN_COMBAT_RING, GroundMaterial.CARPET, p -> p
        .blended(false)
        .ids(30, 37, 72, 73)),

    MESS_HALL_KITCHEN_TILE_1(30, Area.MESS_HALL_KITCHEN, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
    MESS_HALL_KITCHEN_TILE_2(99, Area.MESS_HALL_KITCHEN, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),
    // Chambers of Xeric
    MOUNT_QUIDAMORTEM_SYMBOL(-93, Area.MOUNT_QUIDAMORTEM, GroundMaterial.DIRT, p -> p.blended(false)),
    // Kebos Lowlands
    LIZARDMAN_TEMPLE_WATER(-100, Area.LIZARDMAN_TEMPLE, WaterType.SWAMP_WATER_FLAT),

    // Temple of the Eye
    TEMPLE_OF_THE_EYE_INCORRECT_WATER(-100, Area.TEMPLE_OF_THE_EYE, GroundMaterial.DIRT),

    // God Wars Dungeon (GWD)
    GWD_WATER(104, Area.GOD_WARS_DUNGEON, WaterType.ICE_FLAT),

    // Purple symbol near Wintertodt
    PURPLE_SYMBOL(68, Area.ZEAH_SNOWY_NORTHERN_REGION, GroundMaterial.DIRT, p -> p.blended(false)),

    // Burthorpe games room
    GAMES_ROOM_FLOOR(22, Area.GAMES_ROOM, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),

    CRANDOR_GROUND_1(11, Area.CRANDOR, GroundMaterial.GRAVEL),

    FISHING_TRAWLER_BOAT_PORT_KHAZARD_FIX(42, Area.FISHING_TRAWLER_BOAT_PORT_KHAZARD, WaterType.WATER),
    FISHING_TRAWLER_BOAT_FLOODED(6, Area.FISHING_TRAWLER_BOAT_FLOODED, WaterType.WATER_FLAT),

    // Mind Altar
    MIND_ALTAR_TILE_1(3, Area.MIND_ALTAR, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),
    MIND_ALTAR_TILE_4(Area.MIND_ALTAR, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false).ids(4, 10)),

    // Dragon Slayer II
    DS2_SHIPS_WATER(6, Area.DS2_SHIPS, WaterType.WATER_FLAT),
    DS2_FLEET_ATTACKED(6, Area.DS2_FLEET_ATTACKED, WaterType.WATER_FLAT),

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
    // TODO: fix tile blending color issues with bridge tiles
//    TOA_CRONDIS_ROCK(Area.TOA_PATH_OF_CRONDIS_BOSS, GroundMaterial.NONE, p -> p.ids(-123, -122, -74).blended(false)),
//    TOA_CRONDIS_ISLAND(Area.TOA_CRONDIS_ISLAND, p -> p.groundMaterial(GroundMaterial.SAND)),
    TOA_CRONDIS_WATER(Area.TOA_CRONDIS_WATER, p -> p.waterType(WaterType.SWAMP_WATER).blended(false)),

	// POHs
	POH_DESERT_INDOORS(Area.PLAYER_OWNED_HOUSE, GroundMaterial.TILES_2x2_2, p -> p.blended(false).ids(26, 99)),

    // Random events
    PRISON_PETE_TILE_1(2, Area.RANDOM_EVENT_PRISON_PETE, GroundMaterial.MARBLE_1, p -> p.blended(false)),
    PRISON_PETE_TILE_2(-125, Area.RANDOM_EVENT_PRISON_PETE, GroundMaterial.MARBLE_2, p -> p.blended(false)),

    // GOTR Entrance fix
    TEMPLE_OF_THE_EYE_ENTRANCE(Area.TEMPLE_OF_THE_EYE_ENTRANCE_FIX, GroundMaterial.DIRT, p -> p
        .shiftLightness(-10)
        .blended(false)
        .ids(-53)),

    // Elid Cave fix
    ELID_CAVE_WATER_FIX(-126, Area.ELID_CAVE, WaterType.WATER),

    // Entrana glass/furnace building fix
    ENTRANA_GLASS_BUILDING_FIX(10, Area.ENTRANA_GLASS_BUILDING_FIX, GroundMaterial.GRAVEL, p -> p
        .shiftLightness(8)
        .blended(false)),

    // Ancient Cavern upper level water change
    ANCIENT_CAVERN_UPPER_WATER(41, Area.ANCIENT_CAVERN_UPPER, WaterType.WATER_FLAT),

    // default overlays
    OVERLAY_WATER(WaterType.WATER, p -> p.ids(-128, -105, -98, 6, 41, 104)),
    OVERLAY_DIRT(GroundMaterial.DIRT, p -> p.ids(-124, -84, -83, 14, 15, 21, 22, 23, 60, 77, 81, 82, 88, 89, 101, 102, 107, 108, 110, 115, 123)),
    OVERLAY_GRAVEL(GroundMaterial.GRAVEL, p -> p.ids(-76, 2, 3, 4, 6, 8, 10, 119)),
    OVERLAY_VARROCK_PATHS(GroundMaterial.VARROCK_PATHS, p -> p.ids(-85, -77, 11)),
    OVERLAY_SWAMP_WATER(WaterType.SWAMP_WATER, p -> p.ids(-100, 7)),
    OVERLAY_WOOD_PLANKS(GroundMaterial.WOOD_PLANKS_1, p -> p.ids(5, 35)),
    OVERLAY_CLEAN_WOOD_PLANKS(GroundMaterial.CLEAN_WOOD_FLOOR, p -> p.ids(52).shiftLightness(-4)),
    OVERLAY_SAND(GroundMaterial.SAND, p -> p.ids(25, 26)),
    OVERLAY_BRICK_BROWN(GroundMaterial.BRICK_BROWN, p -> p.ids(27, 46).blended(false)),
    OVERLAY_SNOW(GroundMaterial.SNOW_2, p -> p.ids(30, 33)),
    OVERLAY_VARIED_DIRT(GroundMaterial.VARIED_DIRT, p -> p.ids(49, 83)),
    OVERLAY_SAND_BRICK(GroundMaterial.SAND_BRICK, p -> p.ids(-49, 84)),
    OVERLAY_N122(-122, GroundMaterial.TILES_2x2_2_GLOSS),
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

    NONE(GroundMaterial.DIRT, p -> {});

    public final Integer[] ids;
    public final Area area;
    public final GroundMaterial groundMaterial;
    public final WaterType waterType;
    public final boolean blended;
    public final boolean blendedAsUnderlay;
    public final int hue;
    public final int shiftHue;
    public final int saturation;
    public final int shiftSaturation;
    public final int lightness;
    public final int shiftLightness;
    public final Overlay replacementOverlay;
    public final Function<HdPluginConfig, Boolean> replacementCondition;

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

    Overlay(Area area, Consumer<TileOverrideBuilder<Overlay>> consumer) {
        this(p -> p.area(area).apply(consumer));
    }

    Overlay(Area area, GroundMaterial material, Consumer<TileOverrideBuilder<Overlay>> consumer) {
        this(p -> p.groundMaterial(material).area(area).apply(consumer));
    }

    Overlay(Consumer<TileOverrideBuilder<Overlay>> consumer) {
        TileOverrideBuilder<Overlay> builder = new TileOverrideBuilder<>();
        consumer.accept(builder);
        this.ids = builder.ids;
        this.replacementOverlay = builder.replacement;
        this.replacementCondition = builder.replacementCondition;
        this.waterType = builder.waterType;
        this.groundMaterial = builder.groundMaterial;
        this.area = builder.area;
        this.blended = builder.blended;
        this.blendedAsUnderlay = builder.blendedAsOpposite;
        this.hue = builder.hue;
        this.shiftHue = builder.shiftHue;
        this.saturation = builder.saturation;
        this.shiftSaturation = builder.shiftSaturation;
        this.lightness = builder.lightness;
        this.shiftLightness = builder.shiftLightness;
    }

    private static final ListMultimap<Integer, Overlay> GROUND_MATERIAL_MAP;

    static {
        GROUND_MATERIAL_MAP = ArrayListMultimap.create();
        for (Overlay overlay : values()) {
            if (overlay.ids.length == 0) {
                GROUND_MATERIAL_MAP.put(null, overlay);
            } else {
                for (Integer id : overlay.ids) {
                    GROUND_MATERIAL_MAP.put(id, overlay);
                }
            }
        }
    }

    public static Overlay getOverlay(@Nullable Integer overlayId, Tile tile, Client client, HdPluginConfig pluginConfig) {
        WorldPoint worldPoint = tile.getWorldLocation();

        if (client.isInInstancedRegion()) {
            LocalPoint localPoint = tile.getLocalLocation();
            worldPoint = WorldPoint.fromLocalInstance(client, localPoint);
        }

        int worldX = worldPoint.getX();
        int worldY = worldPoint.getY();
        int worldZ = worldPoint.getPlane();

        List<Overlay> anyMatchOverlays = GROUND_MATERIAL_MAP.get(null);
        Overlay anyOverlay = anyMatchOverlays.stream()
            .filter(o -> o.area.containsPoint(worldX, worldY, worldZ))
            .findFirst()
            .orElse(Overlay.NONE);

        List<Overlay> specificOverlays = GROUND_MATERIAL_MAP.get(overlayId);
        Overlay overlay = specificOverlays.stream()
            .filter(o -> o.ordinal() < anyOverlay.ordinal() && o.area.containsPoint(worldX, worldY, worldZ))
            .findFirst()
            .orElse(anyOverlay);

        return overlay.replacementCondition.apply(pluginConfig) ? overlay.replacementOverlay : overlay;
    }
}
