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
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;
import rs117.hd.utils.HDUtils;

import static rs117.hd.scene.SceneUploader.SCENE_OFFSET;

@Slf4j
public enum Underlay {
	// Seasonal Winter Textures
	WINTER_GRASS(p -> p.ids().groundMaterial(GroundMaterial.SNOW_1).hue(0).saturation(0).shiftLightness(40).blended(true)),
	WINTER_DIRT(p -> p.ids().groundMaterial(GroundMaterial.DIRT).hue(0).saturation(0).shiftLightness(40).blended(true)),
	WINTER_GRUNGE(p -> p.ids().groundMaterial(GroundMaterial.SNOW_2).hue(0).saturation(0).shiftLightness(40).blended(true)),
	WINTER_EDGEVILLE_PATH(p -> p
		.ids()
		.blendedAsOpposite(true)
		.hue(0)
		.shiftLightness(8)
		.saturation(0)
		.groundMaterial(GroundMaterial.WINTER_JAGGED_STONE_TILE_LIGHT)
	),
	// Default
	DEFAULT_SAND(p -> p.ids().groundMaterial(GroundMaterial.SAND)),
	DEFAULT_GRASS(p -> p.ids().groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)),
	DEFAULT_DIRT(p -> p.ids().groundMaterial(GroundMaterial.DIRT)),
	DEFAULT_SNOW_1(p -> p.ids().groundMaterial(GroundMaterial.SNOW_1)),
	DEFAULT_GRUNGE(p -> p.ids().groundMaterial(GroundMaterial.GRUNGE)),
	DEFAULT_ROCKY_GROUND(p -> p.ids().groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)),
	// Lumbridge
	LUMBRIDGE_CASTLE_TILE(56, Area.LUMBRIDGE_CASTLE_BASEMENT, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),

	// Draynor
	DRAYNOR_SEWERS(63, Area.DRAYNOR_SEWERS, GroundMaterial.DIRT),
	DRAYNOR_72(p -> p
		.ids(72)
		.area(Area.DRAYNOR)
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)),
	WIZARD_TOWER_BASEMENT_DIRT(p -> p.ids(63, 66).area(Area.WIZARD_TOWER_BASEMENT).groundMaterial(GroundMaterial.DIRT)),

	COMPLEX_TILES_IMCANDO_PENINSULA(p -> p
		.ids(55, 61, 62, 63, 68)
		.area(Area.IMCANDO_PENINSULA)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				int[] hsl = HDUtils.getSouthWesternMostTileColor(tile);
				if (hsl == null)
					return override;

				// Rocky Shoreline
				if (hsl[1] == 0 || (hsl[0] <= 10 && hsl[1] < 2)) {
					switch (plugin.configSeasonalTheme) {
						case WINTER:
							return WINTER_GRUNGE;
						case AUTUMN:
						case SUMMER:
							return DEFAULT_GRUNGE;
					}
				}

				// Grass
				if ((hsl[0] >= 11 && hsl[1] == 1) || (hsl[0] == 9 && hsl[1] == 2) ||
					(hsl[0] == 9 && hsl[1] == 3 && hsl[2] >= 49) || (hsl[0] >= 9 && hsl[1] >= 4) ||
					(hsl[0] >= 10 && hsl[1] >= 2)) {
					switch (plugin.configSeasonalTheme) {
						case WINTER:
							return WINTER_GRASS;
						case SUMMER:
							return DEFAULT_GRASS;
					}
				}

				// Dirt
				if (hsl[0] <= 8 && hsl[1] >= 4 && hsl[2] <= 71) {
					switch (plugin.configSeasonalTheme) {
						case WINTER:
							return WINTER_DIRT;
						case AUTUMN:
						case SUMMER:
							return DEFAULT_DIRT;
					}
				}
				return DEFAULT_SAND;
			}
		)
	),
	// Edgeville
	EDGEVILLE_PATH_OVERLAY_48(Area.EDGEVILLE_PATH_OVERLAY, GroundMaterial.VARROCK_PATHS, p -> p
		.blendedAsOpposite(true)
		.hue(0)
		.shiftLightness(8)
		.saturation(0)
		.ids(48, 50, 64)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				if (!plugin.configGroundBlending) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
							return DEFAULT_GRASS;
						case WINTER:
							return WINTER_GRASS;
					}
				} else if (plugin.configSeasonalTheme == SeasonalTheme.WINTER) {
					return WINTER_EDGEVILLE_PATH;
				}
				return override;
			}
		)
	),

    // Varrock
    VARROCK_JULIETS_HOUSE_UPSTAIRS(8, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.NONE, p -> p.blended(false)),
    STRONGHOLD_OF_SECURITY_OOZE(Area.STRONGHOLD_OF_PESTILENCE, GroundMaterial.OOZE_FLOOR, p -> p.ids(48, 49, 61, 93)),
    STRONGHOLD_OF_SECURITY_GRASS(Area.STRONGHOLD_OF_SECURITY, GroundMaterial.GRASS_1, p -> p.ids(48, 49, 58, 59, 124)),
	STRONGHOLD_OF_SECURITY_WAR_GRAVEL(Area.STRONGHOLD_OF_SECURITY, GroundMaterial.GRAVEL, p -> p.ids(148)),
	STRONGHOLD_OF_SECURITY_FAMINE_DIRT(Area.STRONGHOLD_OF_FAMINE, GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p.ids(72, 118, 126)),
	STRONGHOLD_OF_SECURITY_WAR_DIRT(Area.STRONGHOLD_OF_WAR, GroundMaterial.GRAVEL, p -> p.ids(72, 118, 126)),
    // A Soul's Bane
    TOLNA_DUNGEON_ANGER_FLOOR(Area.TOLNA_DUNGEON_ANGER, GroundMaterial.DIRT, p -> p.ids(58, 58)),

	// Burthorpe
	WARRIORS_GUILD_FLOOR_1(Area.WARRIORS_GUILD, GroundMaterial.VARROCK_PATHS, p -> p.ids(55, 56)),

	// Trollweiss Region
	TROLLHEIM_DIRT(p -> p.ids(63, 67).area(Area.TROLLHEIM).groundMaterial(GroundMaterial.DIRT)),
	WEISS_UNDERGROUND_DIRT(94, Area.WEISS_UNDERGROUND, GroundMaterial.EARTHEN_CAVE_FLOOR),
	WEISS_SALTMINE_GROUND(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p.ids(157, 158).area(Area.WEISS_SALT_MINE)),

	// Catherby
	CATHERBY_BEACH_SAND(p -> p
		.ids(62)
		.area(Area.CATHERBY)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				int[] hsl = HDUtils.getSouthWesternMostTileColor(tile);
				if (hsl == null)
					return override;

				LocalPoint localLocation = tile.getLocalLocation();
				int tileExX = localLocation.getSceneX() + SCENE_OFFSET;
				int tileExY = localLocation.getSceneY() + SCENE_OFFSET;
				short overlayId = scene.getOverlayIds()[tile.getRenderLevel()][tileExX][tileExY];

				if (hsl[0] >= 9) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
							return DEFAULT_GRASS;
						case WINTER:
							return WINTER_GRASS;
					}
				}

				if (hsl[0] == 8 && hsl[1] > 5 && overlayId != 6) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
							return DEFAULT_GRASS;
						case WINTER:
							return WINTER_GRASS;
					}
				}

				if (hsl[0] < 8 && hsl[1] > 4 && hsl[2] < 45 && overlayId != 6) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return DEFAULT_DIRT;
						case WINTER:
							return WINTER_DIRT;
					}
				}

				return DEFAULT_SAND;
			}
		)
	),

	// Al Kharid
	MAGE_TRAINING_ARENA_FLOOR_PATTERN(56, Area.MAGE_TRAINING_ARENA, GroundMaterial.TILES_2X2_2_GLOSS, p -> p.blended(false)),
	PVP_ARENA_PITFLOOR_SAND_REMOVAL(GroundMaterial.DIRT, p -> p
		.area(Area.PVP_ARENA)
		.ids(66, 68)
	),
	DESERT_TREASURE_INTERIOR_FLOOR(GroundMaterial.SANDY_STONE_FLOOR, p -> p
		.area(Area.DESERT_TREASURE_PYRAMID)
		.ids(61, 64)
	),

	// Fix waterfall by entrance to River Elid Dungeon
	RIVER_ELID_WATERFALL(p -> p.area(Area.RIVER_ELID_WATERFALL).waterType(WaterType.WATER).blended(false)),

	SOPHANEM_TRAPDOOR(Area.SOPHANEM_TRAPDOOR, GroundMaterial.NONE, p -> {}),
	KHARID_SAND_1(Area.KHARID_DESERT_REGION, GroundMaterial.SAND, p -> p
		.saturation(3)
		.hue(6)
		.ids(-127, 45, 49, 50, 58, 61, 62, 63, 64, 67, 68, 69, 126)),
	NECROPOLIS_SAND(Area.NECROPOLIS, GroundMaterial.DIRT, p -> p.ids(124)),
	SMOKE_DUNGEON(Area.SMOKE_DUNGEON, GroundMaterial.ROCKY_CAVE_FLOOR, p -> p.ids(56)),

	// Burthorpe games room
	GAMES_ROOM_INNER_FLOOR(64, Area.GAMES_ROOM_INNER, GroundMaterial.CARPET, p -> p.blended(false)),
	GAMES_ROOM_FLOOR(64, Area.GAMES_ROOM, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),

	// Karamja
	KARAMJA_VOCALNO_ROCK(p -> p
		.ids(55, 63, 72)
		.area(Area.KARAMJA_VOLCANO)
		.groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR)
	),
	COMPLEX_TILES_KARAMJA(p -> p
		.ids(50, 55, 61, 62, 63, 68)
		.area(Area.KARAMJA)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				int[] hsl = HDUtils.getSouthWesternMostTileColor(tile);
				if (hsl == null)
					return override;

				LocalPoint localLocation = tile.getLocalLocation();
				int tileExX = localLocation.getSceneX() + SCENE_OFFSET;
				int tileExY = localLocation.getSceneY() + SCENE_OFFSET;
				short overlayId = scene.getOverlayIds()[tile.getRenderLevel()][tileExX][tileExY];

				// Grass
				if (hsl[0] >= 13 ||
					hsl[0] >= 10 && hsl[1] >= 3 ||
					hsl[0] == 9 && hsl[1] >= 4 ||
					hsl[0] == 9 && hsl[1] == 3 && hsl[2] <= 45 || // Fixes the southernmost beach
					hsl[0] == 8 && hsl[1] > 5 && hsl[2] >= 30 && overlayId != 6
				) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return DEFAULT_GRASS;
						case WINTER:
							return WINTER_GRASS;
					}
				}

				// Dirt
				if (hsl[0] <= 8 && hsl[1] >= 4 && hsl[2] <= 71 ||
					hsl[0] == 9 && hsl[1] == 2 && hsl[2] <= 44 ||
					hsl[0] == 8 && hsl[1] == 4 && hsl[2] <= 40 ||
					hsl[0] == 8 && hsl[1] == 3 && hsl[2] <= 34 // Breaks Sand if higher than 34; Can be fixed with tile averages or medians


				) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return DEFAULT_DIRT;
						case WINTER:
							return WINTER_DIRT;
					}
				}

				// Stone
				if (hsl[0] < 13 && hsl[1] <= 2 && hsl[2] <= 40) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return DEFAULT_GRUNGE;
						case WINTER:
							return WINTER_GRUNGE;
					}
				}

				return DEFAULT_SAND;
			}
		)
	),

	// Crandor
	CRANDOR_SAND(-110, Area.CRANDOR, GroundMaterial.SAND, p -> p.saturation(3).hue(6)),

	// God Wars Dungeon (GWD)
	GOD_WARS_DUNGEON_SNOW_1(Area.GOD_WARS_DUNGEON, GroundMaterial.SNOW_1, p -> p.ids(58, 59)),

	// TzHaar
	INFERNO_1(Area.THE_INFERNO, GroundMaterial.VARIED_DIRT, p -> p.ids(-118, 61, -115, -111, -110, 1, 61, 62, 72, 118, 122)),

	TZHAAR(72, Area.TZHAAR, GroundMaterial.VARIED_DIRT_SHINY, p -> p.shiftLightness(2)),

	// Morytania
	VER_SINHAZA_WATER_FIX(p -> p.ids(54).area(Area.VER_SINHAZA_WATER_FIX).waterType(WaterType.WATER).blended(false)),
	TEMPLE_TREKKING_GRASS(p -> p.ids(53, 103).area(Area.TEMPLE_TREKKING_INSTANCES)),
	MEIYERDITCH_MINES(111, Area.MEIYERDITCH_MINES, GroundMaterial.ROCKY_CAVE_FLOOR),
	BARROWS_DIRT(GroundMaterial.DIRT, p -> p
		.ids(96)
		.area(Area.BARROWS)
	),
	BARROWS_CRYPT_FLOOR(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.ids(96, 103)
		.area(Area.BARROWS_CRYPTS)
	),
	BARROWS_TUNNELS_FLOOR(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.ids(96, 103)
		.area(Area.BARROWS_TUNNELS)
	),

	// Fremennik
	COMPLEX_TILES_ISLE_OF_STONE(p -> p
		.ids(58, 97, 112)
		.area(Area.ISLAND_OF_STONE)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				int[] hsl = HDUtils.getSouthWesternMostTileColor(tile);
				if (hsl == null)
					return override;

				LocalPoint localLocation = tile.getLocalLocation();
				int tileExX = localLocation.getSceneX() + SCENE_OFFSET;
				int tileExY = localLocation.getSceneY() + SCENE_OFFSET;


				// Dirt
				if (hsl[0] == 7 && hsl[1] >= 1 && hsl[2] <= 71) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return DEFAULT_DIRT;
						case WINTER:
							return WINTER_DIRT;
					}
				}

				// Stone
				if (hsl[0] < 13 && hsl[1] == 0 && hsl[2] <= 40) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return DEFAULT_ROCKY_GROUND;
						case WINTER:
							return WINTER_GRUNGE;
					}
				}

				return DEFAULT_SNOW_1;
			}
		)
	),

	FREMENNIK_SLAYER_DUNGEON(p -> p.ids(48, 63, 92).area(Area.FREMENNIK_SLAYER_DUNGEON).groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR)),

	// Ardougne
	SHADOW_DUNGEON_FLOOR(63, Area.SHADOW_DUNGEON, GroundMaterial.EARTHEN_CAVE_FLOOR),
	// Castle Wars
	CENTER_SARADOMIN_SIDE_DIRT_1(98, Area.CASTLE_WARS_ARENA_SARADOMIN_SIDE, GroundMaterial.DIRT, p -> p
		.hue(7)
		.saturation(4)),
	CENTER_SARADOMIN_SIDE_DIRT_2(56, Area.CASTLE_WARS_ARENA_SARADOMIN_SIDE, GroundMaterial.DIRT, p -> p
		.hue(7)
		.saturation(4)
		.shiftLightness(3)),

	// Yanille
	YANILLE_AGILITY_DUNGEON_ENTRANCE_FIX(63, Area.YANILLE_AGILITY_DUNGEON_ENTRANCE, GroundMaterial.NONE, p -> p.blended(false)),

	// Iceberg
	ICEBERG_TEXTURE(p -> p
		.area(Area.ICEBERG)
		.groundMaterial(GroundMaterial.SNOW_2)
		.ids(59)
		.shiftLightness(5)
	),

	// Zeah
	ZEAH_DIRT(p -> p
		.area(Area.ZEAH)
		.groundMaterial(GroundMaterial.VARIED_DIRT)
		.ids(19, 148, 149)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
	),
	ZEAH_GRAVEL_HILLS(p -> p
		.area(Area.ZEAH)
		.groundMaterial(GroundMaterial.GRAVEL)
		.ids(99)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRUNGE)
	),
	ZEAH_ROCKY_GROUND(p -> p
		.area(Area.ZEAH)
		.groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)
		.ids(27, 29, 129)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRUNGE)
	),
	STRANGLEWOOD_SNOW_DARK(p -> p.area(Area.THE_STRANGLEWOOD_EXTENDED).ids(174).groundMaterial(GroundMaterial.SNOW_1)),
	JUDGE_OF_YAMA_BOSS_WATER(p -> p.ids(72, 76).area(Area.JUDGE_OF_YAMA_BOSS).waterType(WaterType.WATER)),
	JUDGE_OF_YAMA_BOSS_BLACK_TILES(p -> p.ids(150).area(Area.JUDGE_OF_YAMA_BOSS).groundMaterial(GroundMaterial.TRANSPARENT)),

	// Zanaris
	ZANARIS_GRASS(Area.ZANARIS, GroundMaterial.GRASS_1, p -> p.ids(143, 144)),
	ZANARIS_DIRTS(Area.ZANARIS, GroundMaterial.VARIED_DIRT, p -> p.ids(66, 67)),
	COSMIC_ENTITYS_PLANE_ABYSS(Area.COSMIC_ENTITYS_PLANE, GroundMaterial.NONE, p -> p
		.lightness(0)
		.blended(false)
		.ids(2, 72)),

	// Taverley Underground
	ICE_QUEENS_DUNGEON_UNDERLAY(Area.ICE_QUEENS_DUNGEON, GroundMaterial.SNOW_1, p -> p.ids(58).lightness(100).hue(0).saturation(0)),
	TAVERLY_DUNGEON_DIRT(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.area(Area.TAVERLEY_DUNGEON)
		.ids(50, 63, 64, 66, 67)
	),
	TAVERLY_DUNGEON_BLACK_KNIGHTS_BASE(GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p
		.area(Area.TAVERLEY_DUNGEON)
		.ids(56, 57)
	),
	HEROES_GUILD_BASEMENT_CAVE(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.area(Area.HEROES_GUILD_BASEMENT)
		.ids(63)
	),
	HEROES_GUILD_BASEMENT_GRASS(GroundMaterial.GRASS_1, p -> p
		.area(Area.HEROES_GUILD_BASEMENT)
		.ids(48, 49, 50)
	),
	DWARVEN_MINE_DUNGEON(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.area(Area.DWARVEN_MINE_DUNGEON)
		.ids(63, 64, 66)
	),
	MOTHERLODE_MINE(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.area(Area.MOTHERLODE_MINE)
		.ids(63, 64, 71)
	),
	GIANTS_FOUNDRY(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.area(Area.GIANTS_FOUNDRY)
		.ids(91, 101)
	),
	MEIYERDITCH_MYREQUE_HIDEOUT(GroundMaterial.VARIED_DIRT, p -> p
		.area(Area.MEIYERDITCH_MYREQUE_HIDEOUT)
		.ids(96, 103)
	),

	// Goblin Village
	GOBLIN_VILLAGE_TILES_BLEND_FIX(Area.GOBLIN_VILLAGE_COOKS_CHAMBER, GroundMaterial.WORN_TILES, p -> p.ids(56, 57).blended(true)),
	GOBLIN_VILLAGE_TILES(Area.GOBLIN_VILLAGE_COOKS_CHAMBER, GroundMaterial.WORN_TILES, p -> p.ids(56, 57).blended(false)),
	GOBLIN_VILLAGE_COOKS_PIT(118, Area.GOBLIN_VILLAGE_COOKS_CHAMBER, GroundMaterial.VARIED_DIRT_SHINY),

	// Kings Ransom Dungeon
	KEEP_LE_FAYE_JAIL_FLOOR_FIX(58, Area.KEEP_LE_FAYE_JAIL, GroundMaterial.PACKED_EARTH),

	// Penguin Base
	PENGUIN_BASE_FLOOR(p -> p
		.area(Area.PENGUIN_BASE)
		.groundMaterial(GroundMaterial.ICE_4)
		.ids(59)
	),

	// Death's office
	DEATHS_OFFICE_TILE(-110, Area.DEATHS_OFFICE, GroundMaterial.TILES_2X2_1_SEMIGLOSS),

	// Chambers of Xeric
	COX_SNOW_1(16, Area.CHAMBERS_OF_XERIC_ICE_DEMON, GroundMaterial.SNOW_1),
	COX_SNOW_2(59, Area.CHAMBERS_OF_XERIC_ICE_DEMON, GroundMaterial.SNOW_2),

	// Tombs of Amascut
	TOA_CRONDIS_ISLAND(Area.TOA_PATH_OF_CRONDIS_BOSS, GroundMaterial.SAND, p -> p.ids(109, 117)),
	TOA_CRONDIS_WATER_GREEN(p -> p.ids(133, 134).area(Area.TOA_CRONDIS_WATER).waterType(WaterType.POISON_WASTE).blended(false)),
	TOA_CRONDIS_WATER_BLUE(p -> p.area(Area.TOA_CRONDIS_WATER).waterType(WaterType.WATER).blended(false)),

	// Wilderness
	// Mage Arena
	MAGE_ARENA_BANK_FLOOR(p -> p.ids(55, 56, 57).area(Area.MAGE_ARENA_BANK).groundMaterial(GroundMaterial.STONE_CAVE_FLOOR)),
	MAGE_ARENA_STATUE_ROOM_FLOOR(p -> p.ids(55, 56, 57).area(Area.MAGE_ARENA_GOD_STATUES).groundMaterial(GroundMaterial.STONE_CAVE_FLOOR)),

	// Mind Altar
	MIND_ALTAR_TILE(55, Area.MIND_ALTAR, GroundMaterial.MARBLE_1_SEMIGLOSS, p -> p.blended(false)),

	TEMPLE_OF_THE_EYE_CENTER_PLATFORM_DIRT(p -> p
		.ids(60)
		.area(Area.TEMPLE_OF_THE_EYE_CENTER_PLATFORM)
		.groundMaterial(GroundMaterial.VARIED_DIRT_SHINY)
		.hue(4)
		.saturation(1)
		.lightness(64)
	),
	TEMPLE_OF_THE_EYE_DIRT(p -> p
		.ids(60)
		.area(Area.TEMPLE_OF_THE_EYE)
		.groundMaterial(GroundMaterial.VARIED_DIRT_SHINY)
	),
	TEMPLE_OF_THE_EYE_ROCK_SHADE_FIX(p -> p
		.ids()
		.area(Area.TEMPLE_OF_THE_EYE_ENTRANCE_BRIGHTNESS_FIX)
		.groundMaterial(GroundMaterial.TEMPLE_OF_THE_EYE_FLOOR)
		.lightness(38)

	),
	TEMPLE_OF_THE_EYE_ROCK_SHADE_FIX_TOGGLE(p -> p
		.ids(87, 88)
		.area(Area.TEMPLE_OF_THE_EYE_ENTRANCE_BRIGHTNESS_FIX)
		.groundMaterial(GroundMaterial.TEMPLE_OF_THE_EYE_FLOOR)
		.replaceWithIf(TEMPLE_OF_THE_EYE_ROCK_SHADE_FIX, plugin -> plugin.configGroundBlending)

	),
	TEMPLE_OF_THE_EYE_ROCK(p -> p
		.ids(87, 88, 89)
		.area(Area.TEMPLE_OF_THE_EYE)
		.groundMaterial(GroundMaterial.TEMPLE_OF_THE_EYE_FLOOR)
	),

	ARCEUUS_GROUND(Area.ARCEUUS, GroundMaterial.DIRT, p -> p
		.ids(2, 3, 23, 24)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
	),
	ARCEUUS_GRASS(Area.ARCEUUS, GroundMaterial.GRASSY_DIRT, p -> p
		.ids(17, 95)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)
	),

	// Secrets of the North dungeon
	ICY_UNDERGROUND_SNOW(p -> p.area(Area.ICY_UNDERGROUND_DARK).ids(159).groundMaterial(GroundMaterial.SNOW_1)),

	// Desert Treasure 2 areas
	LASSAR_UNDERCITY_SUNKEN_CATHEDRAL(p -> p
		.ids(44, 45, 104, 181, 182)
		.area(Area.LASSAR_UNDERCITY_SUNKEN_CATHEDRAL)
		.groundMaterial(GroundMaterial.LASSAR_UNDERCITY_TILES_SUBMERGED)),
	LASSAR_UNDERCITY_WATER(p -> p
		.ids(292)
		.area(Area.LASSAR_UNDERCITY_WATER)
		.waterType(WaterType.PLAIN_WATER)
		.blended(false)),
	LASSAR_UNDERCITY_MARBLE(p -> p.ids(45, 104).area(Area.LASSAR_UNDERCITY).groundMaterial(GroundMaterial.MARBLE_2_SEMIGLOSS)),
	LASSAR_UNDERCITY_TILES(p -> p
		.ids(182)
		.area(Area.LASSAR_UNDERCITY)
		.groundMaterial(GroundMaterial.LASSAR_UNDERCITY_TILES)
		.blended(false)),
	LASSAR_UNDERCITY_TILES_BLENDED(p -> p
		.ids(46, 150)
		.area(Area.LASSAR_UNDERCITY)
		.groundMaterial(GroundMaterial.LASSAR_UNDERCITY_TILES)
		.blended(true)),

	SHIP_SAILING_WATER(p -> p.area(Area.SHIP_SAILING).ids(75).waterType(WaterType.WATER_FLAT)),

	// Cutscenes
	CANOE_CUTSCENE_GRASS(Area.CANOE_CUTSCENE, GroundMaterial.GRASS_SCROLLING, p -> p.ids(48, 50, 63)),

	// Items that cannot properly be fixed unless we can first detect the hue of the tile to set a texture.
	TILE_NEEDS_HUE_DEFINED(Area.OVERWORLD, GroundMaterial.VARIED_DIRT, p -> p
		.ids(26)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
	),

	// Default underlays
	OVERWORLD_GRASS(Area.OVERWORLD, GroundMaterial.OVERWORLD_GRASS_1, p -> p
		.ids(7, 25, 33, 34, 40, 48, 49, 50, 51, 52, 53, 54, 67, 70, 71, 75, 93, 97, 99, 100, 103, 114, 115, 126)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)
	),
	OVERWORLD_DIRT(Area.OVERWORLD, GroundMaterial.DIRT, p -> p
		.ids(-111, -110, 19, 56, 57, 66, 80, 111, 118, 122, 139, 149, 150)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)),
	OVERWORLD_SAND(Area.OVERWORLD, GroundMaterial.SAND, p -> p.ids(-127, -118)),
	UNDERLAY_PACKED_EARTH(GroundMaterial.PACKED_EARTH, p -> p.ids(15)),

	UNDERLAY_SNOW(p -> p
		.ids(16, 55, 58, 59, 92)
		.area(Area.SNOW_REGIONS)
		.groundMaterial(GroundMaterial.SNOW_1)
		.replacementResolver((plugin, scene, tile, override) -> {
			int[] hsl = HDUtils.getSouthWesternMostTileColor(tile);
			if (hsl == null)
				return override;

			if (hsl[1] >= 2) {
				switch (plugin.configSeasonalTheme) {
					case SUMMER:
						return OVERWORLD_GRASS;
					case WINTER:
						return WINTER_GRASS;
				}
			}

			if (hsl[1] == 1) {
				switch (plugin.configSeasonalTheme) {
					case SUMMER:
					case AUTUMN:
						return OVERWORLD_DIRT;
					case WINTER:
						return WINTER_DIRT;
				}
			}

			return DEFAULT_SNOW_1;
		})
	),
	UNDERLAY_OVERWORLD_DIRT(GroundMaterial.VARIED_DIRT, p -> p
		.area(Area.OVERWORLD)
		.ids(72, 73, 98, 112, 113) // 112 == Lovakengj
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
	),
	UNDERLAY_DIRT(GroundMaterial.VARIED_DIRT, p -> p
		.ids(72, 73, 98, 112, 113) // 112 == Lovakengj
	),
	UNDERLAY_OVERWORLD_GRUNGE(GroundMaterial.GRUNGE, p -> p
		.area(Area.OVERWORLD)
		.ids(8, 10, 58, 60, 92) // 8 = Jatizso, 60 = GotR, 92 = Eadgars Cave
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRUNGE)
	),
	UNDERLAY_GRUNGE(GroundMaterial.GRUNGE, p -> p
		.ids(8, 10, 58, 60, 92) // 8 = Jatizso, 60 = GotR, 92 = Eadgars Cave
	),
	COMPLEX_TILES(p -> p
		.area(Area.OVERWORLD)
		.ids(55, 61, 62, 63, 64, 65, 68, 94, 96)
		.replacementResolver(
			(plugin, scene, tile, override) -> {
				int[] hsl = HDUtils.getSouthWesternMostTileColor(tile);
				if (hsl == null)
					return override;

				if (hsl[1] == 0) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return UNDERLAY_OVERWORLD_GRUNGE;
						case WINTER:
							return WINTER_GRUNGE;
					}
				}
				if (hsl[0] <= 10 && hsl[1] < 2) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return UNDERLAY_OVERWORLD_GRUNGE;
						case WINTER:
							return WINTER_GRUNGE;
					}
				}
				if ((hsl[0] == 8 && hsl[1] == 4 && hsl[2] >= 71) || (hsl[0] == 8 && hsl[1] == 3 && hsl[2] >= 48))
					return DEFAULT_SAND;

				if (
					hsl[0] >= 11 && hsl[1] == 1 ||
					hsl[0] == 9 && hsl[1] == 2 ||
					hsl[0] == 9 && hsl[1] == 3 && hsl[2] >= 49 ||
					hsl[0] >= 9 && hsl[1] >= 4 ||
					hsl[0] == 9 && hsl[1] == 3 && hsl[2] <= 38 ||
					hsl[0] >= 10 && hsl[1] >= 2 ||
					hsl[0] == 8 && hsl[1] == 5 && hsl[2] >= 15 ||
					hsl[0] == 8 && hsl[1] >= 6 && hsl[2] >= 2
				) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return DEFAULT_GRASS;
						case WINTER:
							return WINTER_GRASS;
					}
				}

				if (
					hsl[0] == 8 && hsl[1] <= 4 && hsl[2] <= 71 ||
					hsl[0] <= 7 && hsl[1] <= 5 && hsl[2] <= 57 ||
					hsl[0] <= 7 && hsl[1] <= 7 && hsl[2] <= 28 ||
					hsl[0] == 8 && hsl[1] == 5 && hsl[2] <= 15
				) {
					switch (plugin.configSeasonalTheme) {
						case SUMMER:
						case AUTUMN:
							return DEFAULT_DIRT;
						case WINTER:
							return WINTER_DIRT;
					}
				}
				return DEFAULT_DIRT;
			}
		)
	),

	NONE(GroundMaterial.DIRT, p -> {});

	@Nullable
	public final Integer[] filterIds;
	public final Area area;
	public final GroundMaterial groundMaterial;
	public final WaterType waterType;
	public final boolean blended;
	public final boolean blendedAsOverlay;
	public final int hue;
	public final int shiftHue;
	public final int saturation;
	public final int shiftSaturation;
	public final int lightness;
	public final int shiftLightness;
	public final TileOverrideResolver<Underlay> replacementResolver;

	Underlay(int id, Area area, GroundMaterial material) {
		this(p -> p.ids(id).groundMaterial(material).area(area));
	}

	Underlay(int id, Area area, GroundMaterial material, Consumer<TileOverrideBuilder<Underlay>> consumer) {
		this(p -> p.ids(id).groundMaterial(material).area(area).apply(consumer));
	}

	Underlay(GroundMaterial material, Consumer<TileOverrideBuilder<Underlay>> consumer) {
		this(p -> p.groundMaterial(material).apply(consumer));
	}

	Underlay(Area area, GroundMaterial material, Consumer<TileOverrideBuilder<Underlay>> consumer) {
		this(p -> p.groundMaterial(material).area(area).apply(consumer));
	}

	Underlay(Consumer<TileOverrideBuilder<Underlay>> consumer) {
		TileOverrideBuilder<Underlay> builder = new TileOverrideBuilder<>();
		consumer.accept(builder);
		this.filterIds = builder.ids;
		this.area = builder.area;
		this.groundMaterial = builder.groundMaterial;
		this.waterType = builder.waterType;
		this.blended = builder.blended;
		this.blendedAsOverlay = builder.blendedAsOpposite;
		this.hue = builder.hue;
		this.shiftHue = builder.shiftHue;
		this.saturation = builder.saturation;
		this.shiftSaturation = builder.shiftSaturation;
		this.lightness = builder.lightness;
		this.shiftLightness = builder.shiftLightness;
		this.replacementResolver = builder.replacementResolver;
	}

	private static final Underlay[] ANY_MATCH;
	private static final HashMap<Integer, Underlay[]> FILTERED_MAP = new HashMap<>();

	static {
		ArrayList<Underlay> anyMatch = new ArrayList<>();
		ListMultimap<Integer, Underlay> multiMap = ArrayListMultimap.create();
		for (Underlay underlay : values()) {
			if (underlay.filterIds == null) {
				anyMatch.add(underlay);
			} else {
				for (Integer id : underlay.filterIds) {
					multiMap.put(id, underlay);
				}
			}
		}

		ANY_MATCH = anyMatch.toArray(new Underlay[0]);
		for (Map.Entry<Integer, Collection<Underlay>> entry : multiMap.asMap().entrySet())
			FILTERED_MAP.put(entry.getKey(), entry.getValue().toArray(new Underlay[0]));
	}

	@NonNull
	public static Underlay getUnderlay(Scene scene, Tile tile, HdPlugin plugin) {
		LocalPoint localLocation = tile.getLocalLocation();
		int[] worldPoint = HDUtils.localToWorld(scene, localLocation.getX(), localLocation.getY(), tile.getRenderLevel());

		Underlay match = Underlay.NONE;
		for (Underlay underlay : ANY_MATCH) {
			if (underlay.area.containsPoint(worldPoint)) {
				match = underlay;
				break;
			}
		}

		int tileExX = localLocation.getSceneX() + SCENE_OFFSET;
		int tileExY = localLocation.getSceneY() + SCENE_OFFSET;
		short underlayId = scene.getUnderlayIds()[tile.getRenderLevel()][tileExX][tileExY];
		Underlay[] underlays = FILTERED_MAP.get((int) underlayId);
		if (underlays != null) {
			for (Underlay underlay : underlays) {
				if (underlay.ordinal() >= match.ordinal())
					break;
				if (underlay.area.containsPoint(worldPoint)) {
					match = underlay;
					break;
				}
			}
		}

		if (match.replacementResolver != null)
			return match.replacementResolver.resolve(plugin, scene, tile, match);

		return match;
	}

	public int modifyColor(int jagexHsl) {
		int h = hue != -1 ? hue : jagexHsl >> 10 & 0x3F;
		h += shiftHue;
		h = HDUtils.clamp(h, 0, 0x3F);

		int s = saturation != -1 ? saturation : jagexHsl >> 7 & 7;
		s += shiftSaturation;
		s = HDUtils.clamp(s, 0, 7);

		int l = lightness != -1 ? lightness : jagexHsl & 0x7F;
		l += shiftLightness;
		l = HDUtils.clamp(l, 0, 0x7F);

		return h << 10 | s << 7 | l;
	}
}
