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
import java.util.List;
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
import rs117.hd.scene.tile_overrides.TileOverride;

import static rs117.hd.scene.SceneUploader.SCENE_OFFSET;
import static rs117.hd.utils.HDUtils.MAX_SNOW_LIGHTNESS;
import static rs117.hd.utils.HDUtils.clamp;
import static rs117.hd.utils.HDUtils.localToWorld;

@Slf4j
@Deprecated
public enum Underlay {
	// Seasonal Winter Textures
	WINTER_SAND(p -> p
		.ids()
		.groundMaterial(GroundMaterial.SNOW_2)
		.hue(0)
		.saturation(0)
		.shiftLightness(40)
		.maxLightness(MAX_SNOW_LIGHTNESS)
		.blended(true)),
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
	WINTER_GRUNGE(p -> p
		.ids()
		.groundMaterial(GroundMaterial.SNOW_2)
		.hue(0)
		.saturation(0)
		.shiftLightness(40)
		.maxLightness(MAX_SNOW_LIGHTNESS)
		.blended(true)),
	WINTER_EDGEVILLE_PATH(p -> p
		.ids()
		.blendedAsOpposite(true)
		.hue(0)
		.shiftLightness(8)
		.saturation(0)
		.groundMaterial(GroundMaterial.WINTER_JAGGED_STONE_TILE_LIGHT)
	),

	// Default underlays; these are referenced when using resolved to replace tiles.
	DEFAULT_SAND(p -> p.ids().groundMaterial(GroundMaterial.SAND)),
	DEFAULT_GRASS(p -> p.ids().groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)),
	DEFAULT_DIRT(p -> p.ids().groundMaterial(GroundMaterial.DIRT)),
	DEFAULT_GRUNGE(p -> p.ids().groundMaterial(GroundMaterial.GRUNGE)),
	DEFAULT_ROCKY_GROUND(p -> p.ids().groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)),
	DEFAULT_OVERWORLD_ROCK(p -> p.ids().groundMaterial(GroundMaterial.OVERWORLD_ROCKY)),
	DEFAULT_PACKED_EARTH(p -> p.ids().groundMaterial(GroundMaterial.PACKED_EARTH)),

	// Underlays which change based on seasonal theme
	SEASONAL_SAND(p -> p
		.ids()
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_SAND)
		.fallBackTo(DEFAULT_SAND)
	),
	SEASONAL_GRASS(p -> p
		.ids()
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)
		.fallBackTo(DEFAULT_GRASS)
	),
	SEASONAL_DIRT(p -> p
		.ids()
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
		.fallBackTo(DEFAULT_DIRT)
	),
	SEASONAL_GRUNGE(p -> p
		.ids()
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRUNGE)
		.fallBackTo(DEFAULT_GRUNGE)
	),
	SEASONAL_ROCKY_GROUND(p -> p
		.ids()
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRUNGE)
		.fallBackTo(DEFAULT_ROCKY_GROUND)
	),
	SEASONAL_OVERWORLD_ROCK(p -> p
		.ids()
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRUNGE)
		.fallBackTo(DEFAULT_OVERWORLD_ROCK)
	),

	GREEN_SAND_HUE_CORRECTION(p -> p.ids().groundMaterial(GroundMaterial.SAND).hue(8)),
	VERTICAL_DIRT_FIX(p -> p.ids().groundMaterial(GroundMaterial.VERTICAL_DIRT)),
	SEASONAL_VERTICAL_DIRT(p -> p
		.ids()
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
		.fallBackTo(VERTICAL_DIRT_FIX)
	),

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
		.replaceWithIf(
			SEASONAL_GRUNGE, // Rocky Shoreline
			"s == 0 || h <= 10 && s < 2"
		)
		.replaceWithIf(
			SEASONAL_GRASS,
			"h >= 11 && s == 1",
			"h == 9 && s == 2",
			"h == 9 && s == 3 && l >= 49",
			"h >= 9 && s >= 4",
			"h >= 10 && s >= 2"
		)
		.replaceWithIf(
			SEASONAL_DIRT,
			"h <= 8 && s >= 4 && l <= 71"
		)
		.fallBackTo(DEFAULT_SAND)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				// Rocky Shoreline
//				if (s == 0 ||
//					h <= 10 && s < 2)
//					return SEASONAL_GRUNGE;
//
//				// Grass
//				if (h >= 11 && s == 1 ||
//					h == 9 && s == 2 ||
//					h == 9 && s == 3 && l >= 49 ||
//					h >= 9 && s >= 4 ||
//					h >= 10 && s >= 2) {
//					return SEASONAL_GRASS;
//				}
//
//				// Dirt
//				if (h <= 8 && s >= 4 && l <= 71) {
//					return SEASONAL_DIRT;
//				}
//
//				return DEFAULT_SAND;
//			}
//		)
	),
	// Edgeville
	EDGEVILLE_PATH_OVERLAY_48_CUSTOM_PATH(GroundMaterial.VARROCK_PATHS, p -> p
		.ids()
		.blendedAsOpposite(true)
		.hue(0)
		.shiftLightness(8)
		.saturation(0)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_EDGEVILLE_PATH)
	),
	EDGEVILLE_PATH_OVERLAY_48(Area.EDGEVILLE_PATH_OVERLAY, GroundMaterial.VARROCK_PATHS, p -> p
		.replaceWithIf(EDGEVILLE_PATH_OVERLAY_48_CUSTOM_PATH, "blending")
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)
		.fallBackTo(DEFAULT_GRASS)
	),

    // Varrock
    VARROCK_JULIETS_HOUSE_UPSTAIRS(8, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.NONE, p -> p.blended(false)),
	VARROCK_SEWERS_DIRT(p -> p
		.ids(10, 63, 64)
		.area(Area.VARROCK_SEWERS)
		.groundMaterial(GroundMaterial.PACKED_EARTH)
		.replaceWithIf(DEFAULT_GRASS, "h >= 9")
		.fallBackTo(DEFAULT_PACKED_EARTH)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				// Grass
//				if (h >= 9)
//					return DEFAULT_GRASS;
//
//				return DEFAULT_PACKED_EARTH;
//			}
//		)
	),
	VARROCK_SEWERS_GRASS(p -> p.ids(49).area(Area.VARROCK_SEWERS).groundMaterial(GroundMaterial.GRASS_1)),
	STRONGHOLD_OF_SECURITY_OOZE(Area.STRONGHOLD_OF_SECURITY_PESTILENCE, GroundMaterial.OOZE_FLOOR, p -> p.ids(48, 49, 61, 93)),
    STRONGHOLD_OF_SECURITY_GRASS(Area.STRONGHOLD_OF_SECURITY, GroundMaterial.GRASS_1, p -> p.ids(48, 49, 58, 59, 124)),
	STRONGHOLD_OF_SECURITY_WAR_GRAVEL(Area.STRONGHOLD_OF_SECURITY, GroundMaterial.GRAVEL, p -> p.ids(148)),
	STRONGHOLD_OF_SECURITY_FAMINE_DIRT(Area.STRONGHOLD_OF_SECURITY_FAMINE, GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p.ids(72, 118, 126)),
	STRONGHOLD_OF_SECURITY_WAR_DIRT(Area.STRONGHOLD_OF_SECURITY_WAR, GroundMaterial.GRAVEL, p -> p.ids(72, 118, 126)),
    // A Soul's Bane
    TOLNA_DUNGEON_ANGER_FLOOR(Area.TOLNA_DUNGEON_ANGER, GroundMaterial.DIRT, p -> p.ids(58, 58)),

	// Asgarnia region
	ASGARNIA_SNOWY_MOUNTAINS_COMPLEX_TILES(p -> p
		.area(Area.ASGARNIA_MOUNTAINS)
		.ids(58, 64)
		.replaceWithIf(SEASONAL_GRASS, "s >= 5")
		.replaceWithIf(SEASONAL_VERTICAL_DIRT, "s >= 1")
		.fallBackTo(WINTER_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				if (s >= 5)
//					return SEASONAL_GRASS;
//
//				if (s >= 1)
//					return SEASONAL_VERTICAL_DIRT;
//
//				return WINTER_DIRT;
//			}
//		)
	),

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
		.replaceWithIf(SEASONAL_GRASS, "h >= 9 || h == 8 && s > 5")
		.replaceWithIf(SEASONAL_DIRT, "h < 8 && s > 4 && l < 45")
		.fallBackTo(DEFAULT_SAND)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				if (h >= 9)
//					return SEASONAL_GRASS;
//
//				LocalPoint localLocation = tile.getLocalLocation();
//				int tileExX = localLocation.getSceneX() + SCENE_OFFSET;
//				int tileExY = localLocation.getSceneY() + SCENE_OFFSET;
//				short overlayId = scene.getOverlayIds()[tile.getRenderLevel()][tileExX][tileExY];
//
//				if (h == 8 && s > 5 && overlayId != 6)
//					return SEASONAL_GRASS;
//
//				if (h < 8 && s > 4 && l < 45 && overlayId != 6)
//					return SEASONAL_DIRT;
//
//				return DEFAULT_SAND;
//			}
//		)
	),

	// Al Kharid
	MAGE_TRAINING_ARENA_FLOOR_PATTERN(56, Area.MAGE_TRAINING_ARENA, GroundMaterial.TILES_2X2_2_GLOSS, p -> p.blended(false)),
	PVP_ARENA_PITFLOOR_SAND_REMOVAL(GroundMaterial.DIRT, p -> p
		.area(Area.PVP_ARENA)
		.ids(66, 68)
	),
	SORCERESS_GUARDEN_GRASS(P -> P.ids(7, 48, 51, 66, 93).area(Area.SORCERESS_GARDEN).groundMaterial(GroundMaterial.GRASS_1)),
	SORCERESS_GUARDEN_SNOW(P -> P.ids(9, 30, 111).area(Area.SORCERESS_GARDEN).groundMaterial(GroundMaterial.SNOW_2)),
	DESERT_TREASURE_INTERIOR_FLOOR(GroundMaterial.SANDY_STONE_FLOOR, p -> p
		.area(Area.DESERT_TREASURE_PYRAMID)
		.ids(61, 64)
	),

	// Fix waterfall by entrance to River Elid Dungeon
	RIVER_ELID_WATERFALL(p -> p.area(Area.RIVER_ELID_WATERFALL).waterType(WaterType.WATER).blended(false)),

	SOPHANEM_TRAPDOOR(Area.SOPHANEM_TRAPDOOR, GroundMaterial.NONE, p -> {}),
	SOPHANEM_UNDERGROUND_BANK(p -> p.ids(61,64).area(Area.SOPHANEM_TEMPLE_BANK).groundMaterial(GroundMaterial.FALADOR_PATHS).hue(7).saturation(3).shiftLightness(5)),
	KHARID_SAND_1(Area.KHARID_DESERT_REGION, GroundMaterial.SAND, p -> p
		.saturation(3)
		.hue(6)
		.ids(-127, 45, 49, 50, 58, 61, 62, 63, 64, 67, 68, 69, 126)),
	NECROPOLIS_SAND(Area.NECROPOLIS, GroundMaterial.DIRT, p -> p.ids(124)),
	SMOKE_DUNGEON(Area.SMOKE_DUNGEON, GroundMaterial.ROCKY_CAVE_FLOOR, p -> p.ids(56)),
	SCARAB_LAIR_TILE_FLOOR(p -> p.ids(61, 64).area(Area.SCARAB_LAIR_TEMPLE).groundMaterial(GroundMaterial.FALADOR_PATHS).hue(7).saturation(3).shiftLightness(5)),
	SCARAB_LAIR_ROCKY_FLOOR(p -> p.ids(141).area(Area.SCARAB_LAIR_BOTTOM).groundMaterial(GroundMaterial.STONE_CAVE_FLOOR).shiftLightness(4)),


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
		.ids(48, 49, 50, 51, 52, 53, 55, 57, 61, 62, 63, 64, 65, 66, 67, 68, 72, 100)
		.area(Area.KARAMJA)
		.replaceWithIf(
			DEFAULT_GRASS,
			"h >= 13",
			"h >= 10 && s >= 3",
			"h == 9 && s >= 4",
			"h == 9 && s == 3 && l <= 45", // Fixes the southernmost beach
			"h == 8 && s > 5 && l >= 30"
		)
		.replaceWithIf(
			DEFAULT_DIRT,
			"h <= 8 && s >= 4 && l <= 71",
			"h == 9 && s == 2 && l <= 44",
			"h == 8 && s == 3 && l <= 34" // Breaks Sand if higher than 34; Can be fixed with tile averages or medians
		)
		.replaceWithIf(DEFAULT_ROCKY_GROUND, "s <= 2 && l <= 40")
		.fallBackTo(DEFAULT_SAND)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				LocalPoint localLocation = tile.getLocalLocation();
//				int tileExX = localLocation.getSceneX() + SCENE_OFFSET;
//				int tileExY = localLocation.getSceneY() + SCENE_OFFSET;
//				short overlayId = scene.getOverlayIds()[tile.getRenderLevel()][tileExX][tileExY];
//
//				// Grass
//				if (h >= 13 ||
//					h >= 10 && s >= 3 ||
//					h == 9 && s >= 4 ||
//					h == 9 && s == 3 && l <= 45 || // Fixes the southernmost beach
//					h == 8 && s > 5 && l >= 30 && overlayId != 6)
//					return DEFAULT_GRASS;
//
//				// Dirt
//				if (h <= 8 && s >= 4 && l <= 71 ||
//					h == 9 && s == 2 && l <= 44 ||
//					h == 8 && s == 3 && l <= 34) // Breaks Sand if higher than 34; Can be fixed with tile averages or medians
//					return DEFAULT_DIRT;
//
//				// Stone
//				if (s <= 2 && l <= 40)
//					return DEFAULT_ROCKY_GROUND;
//
//				return DEFAULT_SAND;
//			}
//		)
	),

	// Crandor
	CRANDOR_SAND(-110, Area.CRANDOR, GroundMaterial.SAND, p -> p.saturation(3).hue(6)),

	// God Wars Dungeon (GWD)
	GOD_WARS_DUNGEON_SNOW_1(Area.GOD_WARS_DUNGEON, GroundMaterial.SNOW_1, p -> p.ids(58, 59)),

	// TzHaar
	INFERNO_1(Area.THE_INFERNO, GroundMaterial.VARIED_DIRT, p -> p.ids(-118, 61, -115, -111, -110, 1, 61, 62, 72, 118, 122)),

	TZHAAR(72, Area.TZHAAR, GroundMaterial.VARIED_DIRT_SHINY, p -> p.shiftLightness(2)),

	// Morytania
	CROMBWICK_MANOR_FLOOR(p -> p.ids(10).area(Area.CROMBWICK_MANOR).groundMaterial(GroundMaterial.HD_WOOD_PLANKS_1)),
	SLEPE_CHURCH_FLOOR(p -> p.ids(94).area(Area.SLEPE_CHURCH).groundMaterial(GroundMaterial.HD_WOOD_PLANKS_1)),
	SLEPE_HOUSES_WOODEN_FLOOR(p -> p.ids(10).area(Area.SLEPE_HOUSES).groundMaterial(GroundMaterial.HD_WOOD_PLANKS_1)),
	VER_SINHAZA_WATER_FIX(p -> p.ids(54).area(Area.VER_SINHAZA_WATER_FIX).waterType(WaterType.WATER).blended(false)),
	MEIYERDITCH_MINES(111, Area.MEIYERDITCH_MINES, GroundMaterial.ROCKY_CAVE_FLOOR),
	BARROWS_DIRT(GroundMaterial.DIRT, p -> p
		.ids(96)
		.area(Area.BARROWS)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
	),
	BARROWS_CRYPT_FLOOR(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.ids(96, 103)
		.area(Area.BARROWS_CRYPTS)
	),
	BARROWS_TUNNELS_FLOOR(GroundMaterial.EARTHEN_CAVE_FLOOR, p -> p
		.ids(96, 103)
		.area(Area.BARROWS_TUNNELS)
	),
	TEMPLE_TREKKING_GROUND_COMPLEX(p -> p
		.ids(48, 53, 54, 64, 103)
		.area(Area.TEMPLE_TREKKING_INSTANCES)
		.replaceWithIf(SEASONAL_GRASS, "h >= 9 && s >= 3")
		.fallBackTo(SEASONAL_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				if (h >= 9 && s >= 3)
//					return SEASONAL_GRASS;
//
//				return SEASONAL_DIRT;
//			}
//		)
	),

	// Mos Le Harmless
	MOS_LE_HARMLESS_COMPLEX_TILES(p -> p
		.ids(48, 49, 50, 51, 52, 61, 62, 63, 67, 68)
		.area(Area.MOS_LE_HARMLESS_ALL)
		.replaceWithIf(DEFAULT_GRASS, "h >= 11 && s >= 4 && l <= 39")
		.replaceWithIf(
			DEFAULT_SAND,
			"h <= 9 && s <= 3 && l >= 34",
			"h == 8 && s == 3 && l >= 20"
		)
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				// Grass
//				if (h >= 11 && s >= 4 && l <= 39)
//					return DEFAULT_GRASS;
//
//				// Sand
//				if (h <= 9 && s <= 3 && l >= 34 ||
//					h == 8 && s == 3 && l >= 20)
//					return DEFAULT_SAND;
//
//				return DEFAULT_DIRT;
//			}
//		)
	),

	// Fremennik
	COMPLEX_TILES_ISLE_OF_STONE(p -> p
		.ids(58, 97, 112)
		.area(Area.ISLAND_OF_STONE)
		.replaceWithIf(SEASONAL_DIRT, "h == 7 && s >= 1 && l <= 71")
		.replaceWithIf(SEASONAL_ROCKY_GROUND, "h < 13 && s == 0 && l <= 40")
		.fallBackTo(WINTER_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				// Dirt
//				if (h == 7 && s >= 1 && l <= 71)
//					return SEASONAL_DIRT;
//
//				// Stone
//				if (h < 13 && s == 0 && l <= 40)
//					return SEASONAL_ROCKY_GROUND;
//
//				return WINTER_DIRT;
//			}
//		)
	),

	FREMENNIK_SLAYER_DUNGEON(p -> p.ids(48, 63, 92).area(Area.FREMENNIK_SLAYER_DUNGEON).groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR)),

	// Ardougne
	SHADOW_DUNGEON_FLOOR(63, Area.SHADOW_DUNGEON, GroundMaterial.EARTHEN_CAVE_FLOOR),
	WITCHAVEN_DIRT(p -> p
		.ids(50)
		.area(Area.WITCHAVEN)
		.groundMaterial(GroundMaterial.VARIED_DIRT)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)),
	WITCHAVEN_COMPLEX(p -> p
		.area(Area.WITCHAVEN)
		.ids(94, 129)
		.replaceWithIf(SEASONAL_GRUNGE, "s == 0 || h <= 10 && s < 2")
		.replaceWithIf(SEASONAL_SAND, "h == 8 && s == 2")
		.replaceWithIf(SEASONAL_GRASS, "h >= 10 && s >= 2")
		.replaceWithIf(
			SEASONAL_DIRT,
			"h == 8 && s == 3",
			"h == 8 && s == 4",
			"h == 9 && s <= 4"
		)
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				if (s == 0 || (h <= 10 && s < 2))
//					return SEASONAL_GRUNGE;
//
//				if ((h == 8 && s == 2))
//					return SEASONAL_SAND;
//
//				if (h >= 10 && s >= 2)
//					return SEASONAL_GRASS;
//
//				if ((h == 8 && s == 3) ||
//					(h == 8 && s == 4) ||
//					(h == 9 && s <= 4))
//					return SEASONAL_DIRT;
//
//				return DEFAULT_DIRT;
//			}
//		)
	),
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

	// Feldip Hills
	FELDIP_HILLS_SOUTH_COMPLEX_TILES(p -> p
		.area(Area.FELDIP_HILLS_SOUTHERN_REGION)
		.ids(48, 49, 50, 51, 52, 53, 56, 61, 62, 63, 64, 65, 67, 68, 69, 70, 97, 98, 99, 100)
		.replaceWithIf(DEFAULT_GRUNGE, "s == 0 || h <= 10 && s < 2")
		.replaceWithIf(
			DEFAULT_SAND,
			"h == 8 && s == 4 && l >= 71",
			"h == 8 && s == 3 && l >= 21"
		)
		.replaceWithIf(
			DEFAULT_GRASS,
			"h >= 11 && s == 1",
			"h >= 9 && s >= 4",
			"h >= 10 && s >= 2",
			"h == 8 && s == 5 && l >= 15",
			"h == 8 && s >= 6 && l >= 2"
		)
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				if (s == 0 || h <= 10 && s < 2)
//					return DEFAULT_GRUNGE;
//
//				if (h == 8 && s == 4 && l >= 71 ||
//					h == 8 && s == 3 && l >= 21)
//					return DEFAULT_SAND;
//
//				if (h >= 11 && s == 1 ||
//					h >= 9 && s >= 4 ||
//					h >= 10 && s >= 2 ||
//					h == 8 && s == 5 && l >= 15 ||
//					h == 8 && s >= 6 && l >= 2)
//					return DEFAULT_GRASS;
//
//				return DEFAULT_DIRT;
//			}
//		)
	),
	FELDIP_HILLS_COMPLEX_TILES(p -> p
		.area(Area.FELDIP_HILLS)
		.ids(48, 50, 52, 62, 63, 67, 68, 69, 70, 97, 99, 100)
		.replaceWithIf(SEASONAL_GRUNGE, "s == 0 || h <= 10 && s < 2")
		.replaceWithIf(
			DEFAULT_SAND,
			"h == 8 && s == 4 && l >= 71",
			"h == 8 && s == 3 && l >= 21"
		)
		.replaceWithIf(
			SEASONAL_GRASS,
			"h >= 11 && s == 1",
			"h >= 9 && s >= 4",
			"h >= 10 && s >= 2",
			"h == 8 && s == 5 && l >= 15",
			"h == 8 && s >= 6 && l >= 2"
		)
		.replaceWithIf(
			SEASONAL_DIRT,
			"h == 8 && s <= 4 && l <= 71",
			"h <= 7 && s <= 5 && l <= 57",
			"h <= 7 && s <= 7 && l <= 28",
			"h == 8 && s == 5",
			"h == 9"
		)
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				if (s == 0 ||
//					h <= 10 && s < 2)
//					return SEASONAL_GRUNGE;
//
//				if ((h == 8 && s == 4 && l >= 71) ||
//					(h == 8 && s == 3 && l >= 21))
//					return DEFAULT_SAND;
//
//				if (h >= 11 && s == 1 ||
//					h >= 9 && s >= 4 ||
//					h >= 10 && s >= 2 ||
//					h == 8 && s == 5 && l >= 15 ||
//					h == 8 && s >= 6 && l >= 2)
//					return SEASONAL_GRASS;
//
//				if (h == 8 && s <= 4 && l <= 71 ||
//					h <= 7 && s <= 5 && l <= 57 ||
//					h <= 7 && s <= 7 && l <= 28 ||
//					h == 8 && s == 5 ||
//					h == 9)
//					return SEASONAL_DIRT;
//
//				return DEFAULT_DIRT;
//			}
//		)
	),

	// Iceberg
	ICEBERG_TEXTURE(p -> p
		.area(Area.ICEBERG)
		.groundMaterial(GroundMaterial.SNOW_2)
		.ids(59)
		.shiftLightness(5)
	),

	// Ape Atoll
	APE_ATOLL_COMPLEX_TILES(p -> p
		.ids(48, 50, 56, 61, 62, 63, 65, 67, 68, 99, 100)
		.area(Area.APE_ATOLL)
		.replaceWithIf(DEFAULT_GRASS, "h >= 11 && s >= 4 && l <= 39")
		.replaceWithIf(
			DEFAULT_DIRT,
			"h == 8 && s >= 6 && l <= 30",
			"h == 10 && s >= 3 && l <= 35"
		)
		.replaceWithIf(
			DEFAULT_SAND,
			"h == 9 && s <= 3 && l >= 34",
			"h == 8 && (s == 3 || s == 4) && l >= 20"
		)
		.replaceWithIf(
			GREEN_SAND_HUE_CORRECTION, // Ugly green sand fix
			"h == 9 && (s == 3 || s == 4) && l >= 34"
		)
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				// Grass
//				if (h >= 11 && s >= 4 && l <= 39)
//					return DEFAULT_GRASS;
//
//				// Dirt
//				if (h == 8 && s >= 6 && l <= 30 ||
//					h == 10 && s >= 3 && l <= 35)
//					return DEFAULT_DIRT;
//
//				// Sand
//				if (h == 9 && s <= 3 && l >= 34 ||
//					h == 8 && (s == 3 || s == 4) && l >= 20)
//					return DEFAULT_SAND;
//
//				// Ugly green sand fix
//				if (h == 9 && (s == 3 || s == 4) && l >= 34)
//					return GREEN_SAND_HUE_CORRECTION;
//
//				return DEFAULT_DIRT;
//			}
//		)
	),

	// Fossil Island
	FOSSIL_ISLAND_WYVERN_DIRT(p -> p.ids(17).area(Area.FOSSIL_ISLAND_WYVERN_TASK_CAVE).groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR)),
	FOSSIL_ISLAND_VOLCANO_SAND(p -> p
		.ids(138)
		.area(Area.FOSSIL_ISLAND_VOLCANO)
		.groundMaterial(GroundMaterial.SAND)
	),
	FOSSIL_ISLAND_VOLCANO_ROCK(p -> p
		.ids(56, 72, 148, 150)
		.area(Area.FOSSIL_ISLAND_VOLCANO)
		.groundMaterial(GroundMaterial.OVERWORLD_ROCKY)
	),
	FOSSIL_ISLAND_ROCK(p -> p
		.ids(72, 148, 150)
		.area(Area.FOSSIL_ISLAND)
		.groundMaterial(GroundMaterial.OVERWORLD_ROCKY)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRUNGE)
	),
	FOSSIL_ISLAND_GRASS(p -> p.ids(48, 54, 103).area(Area.FOSSIL_ISLAND).groundMaterial(GroundMaterial.OVERWORLD_GRASS_1).seasonalReplacement(SeasonalTheme.WINTER, WINTER_GRASS)),
	FOSSIL_ISLAND_COMPLEX(p -> p
		.ids(56, 63, 66, 68, 138)
		.area(Area.FOSSIL_ISLAND)
		.groundMaterial(GroundMaterial.OVERWORLD_GRASS_1)
		.replaceWithIf(SEASONAL_OVERWORLD_ROCK, "s == 0 && l <= 44")
		.replaceWithIf(
			SEASONAL_DIRT,
			"h <= 8 && s >= 4 && l <= 71",
			"h <= 11 && s == 1",
			"h <= 4 && s <= 3 && s >= 1 && l <= 36"
		)
		.replaceWithIf(
			DEFAULT_SAND,
			"h >= 4 && h <= 9 && s <= 3 && l >= 29 && l <= 60",
			"h >= 5 && h <= 6 && s == 3 && l >= 50"
		)
		.replaceWithIf(
			SEASONAL_GRASS,
			"h >= 8 && s >= 5 && l >= 20",
			"h >= 9 && s >= 2",
			"h > 20",
			"h >= 12 && s == 1",
			"(h == 7 || h == 8) && s == 3 && l >= 60"
		)
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				// Rock
//				if (s == 0 && l <= 44)
//					return SEASONAL_OVERWORLD_ROCK;
//
//				// Dirt
//				if ((h <= 8 && s >= 4 && l <= 71) ||
//					h <= 11 && s == 1 ||
//					h <= 4 && s <= 3 && s >= 1 && l <= 36)
//					return SEASONAL_DIRT;
//
//				// Sand
//				if (h >= 4 && h <= 9 && s <= 3 && l >= 29 && l <= 60 ||
//					h >= 5 && h <= 6 && s == 3 && l >= 50)
//					return DEFAULT_SAND;
//
//				// Grass
//				if (h >= 8 && s >= 5 && l >= 20 ||
//					h >= 9 && s >= 2 ||
//					h > 20 ||
//					h >= 12 && s == 1 ||
//					(h == 7 || h == 8) && s == 3 && l >= 60)
//					return SEASONAL_GRASS;
//
//				return DEFAULT_DIRT;
//			}
//		)
	),

	// Zeah
	LOVAKENGJ_GROUND(p -> p.ids(10, 55, 56, 57, 63, 64, 67, 68, 72, 96, 111, 112, 149, 150).area(Area.LOVAKENGJ).groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR)),
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
	GIANTS_FOUNDRY_DESATURATION(p -> p.ids().groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR).shiftSaturation(-1)),
	GIANTS_FOUNDRY(p -> p
		.ids(91, 101)
		.area(Area.GIANTS_FOUNDRY)
		.groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR)
		.replaceWithIf(GIANTS_FOUNDRY_DESATURATION, "s > 1")
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
	// Mournings End 2 Areas
	EAST_ARDOUGNE_ROCKY_SLOPE(p -> p.ids(57).area(Area.EAST_ARDOUGNE_UNDERGROUND).groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)),
	EAST_ARDOUGNE_CAVE_FLOOD(p -> p.ids(96, 98).area(Area.EAST_ARDOUGNE_UNDERGROUND).groundMaterial(GroundMaterial.EARTHEN_CAVE_FLOOR)),
	TEMPLE_OF_LIGHT_MARBLE(p -> p.ids(68).area(Area.TEMPLE_OF_LIGHT).groundMaterial(GroundMaterial.MARBLE_1_GLOSS).blended(false).lightness(52)),

	// Isle of Souls Dungeon
	ISLE_OF_SOULS_DUNGEON_FLOOR(p -> p.ids(98).area(Area.ISLE_OF_SOULS_DUNGEON).groundMaterial(GroundMaterial.STONE_CAVE_FLOOR)),

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
		.replaceWithIf(TEMPLE_OF_THE_EYE_ROCK_SHADE_FIX, "blending")
	),
	TEMPLE_OF_THE_EYE_ROCK(p -> p
		.ids(87, 88, 89)
		.area(Area.TEMPLE_OF_THE_EYE)
		.groundMaterial(GroundMaterial.TEMPLE_OF_THE_EYE_FLOOR)
	),

	ARCEUUS_GROUND(Area.ARCEUUS, GroundMaterial.DIRT, p -> p
		.ids(2, 3, 21, 23, 24, 27)
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
	GIANT_MOLE_LAIR_DIRT(p -> p.ids(63, 65).area(Area.GIANT_MOLE_LAIR).groundMaterial(GroundMaterial.VARIED_DIRT)),
	PEST_CONTROL(p -> p
		.ids(59, 121, 142)
		.area(Area.PEST_CONTROL)
		.groundMaterial(GroundMaterial.DIRT)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)),
	ABYSSAL_FLOOR(p -> p
		.ids(2, 121, 122)
		.area(Area.ABYSS)
		.groundMaterial(GroundMaterial.ABYSSAL_FLOOR)
		.uvScale(1.85f)
		.uvOrientation(39)
		.hue(1) // fixes baked in lighting effects
		//.lightness(22) // removes baked in shading
	),
	LAW_ALTAR_FLOOR(p -> p
		.ids(56, 57, 95)
		.area(Area.LAW_ALTAR)
		.groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)
	),

	// Cutscenes
	CANOE_CUTSCENE_GRASS(Area.CANOE_CUTSCENE, GroundMaterial.GRASS_SCROLLING, p -> p.ids(48, 50, 63)),

	ISLE_OF_SOULS_HOT_ZONE_COMPLEX(p -> p
		.ids(8, 27, 33, 35, 36, 37, 38, 63, 72, 118, 143, 144, 145, 146, 147, 148, 149, 150, 152)
		.area(Area.ISLE_OF_SOULS_HOT_ZONES)
		.replaceWithIf(DEFAULT_ROCKY_GROUND, "h < 10 && s <= 1 && l <= 20") // Ash or stone
		.replaceWithIf(DEFAULT_DIRT, "h <= 8 && s >= 1 && l <= 71")
		.replaceWithIf(DEFAULT_GRASS, "h >= 8 && s >= 5 && l >= 20 || h >= 9 && s >= 3 || h > 20")
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				// Ash or stone
//				if (h < 10 && s <= 1 && l <= 20) {
//					return DEFAULT_ROCKY_GROUND;
//				}
//
//				// Dirt
//				if (h <= 8 && s >= 1 && l <= 71) {
//					return DEFAULT_DIRT;
//				}
//
//				// Grass
//				if (h >= 8 && s >= 5 && l >= 20 || h >= 9 && s >= 3 || h > 20) {
//					return DEFAULT_GRASS;
//				}
//
//				return DEFAULT_DIRT;
//			}
//		)
	),
	ISLE_OF_SOULS_COMPLEX(p -> p
		.ids(27, 35, 36, 37, 38, 63, 72, 143, 144, 145, 146, 147, 148, 149, 150, 152)
		.area(Area.ISLE_OF_SOULS)
		.replaceWithIf(DEFAULT_ROCKY_GROUND, "h < 10 && s <= 1 && l <= 20") // Ash or stone
		.replaceWithIf(SEASONAL_DIRT, "h <= 8 && s >= 1 && l <= 71")
		.replaceWithIf(SEASONAL_GRASS, "h >= 8 && s >= 5 && l >= 20 || h >= 9 && s >= 3 || h > 20")
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				// Ash or stone
//				if (h < 10 && s <= 1 && l <= 20) {
//					return DEFAULT_ROCKY_GROUND;
//				}
//
//				// Dirt
//				if (h <= 8 && s >= 1 && l <= 71) {
//					return SEASONAL_DIRT;
//				}
//
//				// Grass
//				if (h >= 8 && s >= 5 && l >= 20 || h >= 9 && s >= 3 || h > 20) {
//					return SEASONAL_GRASS;
//				}
//
//				return DEFAULT_DIRT;
//			}
//		)
	),

	// Items that cannot properly be fixed unless we can first detect the hue of the tile to set a texture.
	TILE_NEEDS_HUE_DEFINED(Area.OVERWORLD, GroundMaterial.VARIED_DIRT, p -> p
		.ids(26)
		.seasonalReplacement(SeasonalTheme.WINTER, WINTER_DIRT)
	),

	DEFENDER_OF_VARROCK_CAVE_FLOOR(p -> p
		.ids(189, 191)
		.area(Area.DEFENDER_OF_VARROCK_DUNGEON)
		.groundMaterial(GroundMaterial.ROCKY_CAVE_FLOOR)
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
		.replaceWithIf(SEASONAL_GRASS, "s >= 2")
		.replaceWithIf(SEASONAL_DIRT, "s == 1")
		.fallBackTo(WINTER_DIRT)
//		.replacementResolver((plugin, scene, tile, override) -> {
//			int[] hsl = getSouthWesternMostTileColor(tile);
//			if (hsl == null)
//				return override;
//			int s = hsl[1];
//
//			if (s >= 2)
//				return SEASONAL_GRASS;
//
//			if (s == 1)
//				return SEASONAL_DIRT;
//
//			return WINTER_DIRT;
//		})
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
		.ids(13, 55, 61, 62, 63, 64, 65, 68, 69, 94, 96)
		.replaceWithIf(SEASONAL_GRUNGE, "s == 0 || h <= 10 && s < 2")
		.replaceWithIf(DEFAULT_SAND, "h == 8 && s == 4 && l >= 71 || h == 8 && s == 3 && l >= 48")
		.replaceWithIf(
			SEASONAL_GRASS,
			"h >= 11 && s == 1",
			"h == 9 && s == 2",
			"h == 9 && s == 3 && l >= 49",
			"h >= 9 && s >= 4",
			"h == 9 && l <= 38",
			"h >= 10 && s >= 2",
			"h == 8 && s == 5 && l >= 15",
			"h == 8 && s >= 6 && l >= 2"
		)
		.replaceWithIf(
			SEASONAL_DIRT,
			"h == 8 && s <= 4 && l <= 71",
			"h <= 7 && s <= 5 && l <= 57",
			"h <= 7 && s <= 7 && l <= 34",
			"h == 8 && s == 5"
		)
		.fallBackTo(DEFAULT_DIRT)
//		.replacementResolver(
//			(plugin, scene, tile, override) -> {
//				int[] hsl = getSouthWesternMostTileColor(tile);
//				if (hsl == null)
//					return override;
//				int h = hsl[0], s = hsl[1], l = hsl[2];
//
//				if (s == 0 ||
//					h <= 10 && s < 2)
//					return SEASONAL_GRUNGE;
//
//				if ((h == 8 && s == 4 && l >= 71) || (h == 8 && s == 3 && l >= 48))
//					return DEFAULT_SAND;
//
//				if (
//					h >= 11 && s == 1 ||
//					h == 9 && s == 2 ||
//					h == 9 && s == 3 && l >= 49 ||
//					h >= 9 && s >= 4 ||
//					h == 9 && l <= 38 ||
//					h >= 10 && s >= 2 ||
//					h == 8 && s == 5 && l >= 15 ||
//					h == 8 && s >= 6 && l >= 2)
//					return SEASONAL_GRASS;
//
//				if (
//					h == 8 && s <= 4 && l <= 71 ||
//					h <= 7 && s <= 5 && l <= 57 ||
//					h <= 7 && s <= 7 && l <= 34 ||
//					h == 8 && s == 5)
//					return SEASONAL_DIRT;
//
//				return DEFAULT_DIRT;
//			}
//		)
	),

	NONE(GroundMaterial.DIRT, p -> {});

	@Nullable
	public final Integer[] filterIds;
	public final Area area;
	public final GroundMaterial groundMaterial;
	public final WaterType waterType;
	public final boolean blended;
	public final boolean blendedAsOverlay;
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
	public final List<TileOverride.IReplacement<Underlay>> replacements;

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
		builder.normalize();
		this.filterIds = builder.ids;
		this.replacements = builder.replacements;
		this.area = builder.area;
		this.groundMaterial = builder.groundMaterial;
		this.waterType = builder.waterType;
		this.blended = builder.blended;
		this.blendedAsOverlay = builder.blendedAsOpposite;
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
		return getUnderlayBeforeReplacements(scene, tile).resolveReplacements(scene, tile, plugin);
	}

	@NonNull
	public static Underlay getUnderlayBeforeReplacements(Scene scene, Tile tile) {
		LocalPoint localLocation = tile.getLocalLocation();
		int[] worldPoint = localToWorld(scene, localLocation.getX(), localLocation.getY(), tile.getRenderLevel());

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

		return match;
	}

	public Underlay resolveReplacements(Scene scene, Tile tile, HdPlugin plugin) {
		if (replacements != null) {
			for (var resolver : replacements) {
				var replacement = resolver.resolve(plugin, scene, tile, this);
				if (replacement == null)
					return NONE;
				if (replacement != this)
					return replacement;
			}
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
