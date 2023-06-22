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
import java.util.function.Function;
import javax.annotation.Nullable;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;
import rs117.hd.utils.HDUtils;

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
	// Lumbridge
	LUMBRIDGE_CASTLE_TILE(56, Area.LUMBRIDGE_CASTLE_BASEMENT, GroundMaterial.MARBLE_2_SEMIGLOSS, p -> p.blended(false)),

	// Draynor
	DRAYNOR_SEWERS(63, Area.DRAYNOR_SEWERS, GroundMaterial.DIRT),

	// Edgeville
	EDGEVILLE_PATH_OVERLAY_48(Area.EDGEVILLE_PATH_OVERLAY, GroundMaterial.VARROCK_PATHS, p -> p
		.blendedAsOpposite(true)
		.hue(0)
		.shiftLightness(8)
		.saturation(0)
		.ids(48, 50, 64)
		.replaceWithIf(WINTER_EDGEVILLE_PATH, plugin -> plugin.configWinterTheme)
	),

	// Varrock
	VARROCK_JULIETS_HOUSE_UPSTAIRS(8, Area.VARROCK_JULIETS_HOUSE, GroundMaterial.NONE, p -> p.blended(false)),
	// A Soul's Bane
	TOLNA_DUNGEON_ANGER_FLOOR(Area.TOLNA_DUNGEON_ANGER, GroundMaterial.DIRT, p -> p.ids(58, 58)),

	// Burthorpe
	WARRIORS_GUILD_FLOOR_1(Area.WARRIORS_GUILD, GroundMaterial.VARROCK_PATHS, p -> p.ids(55, 56)),

	// Catherby
	CATHERBY_BEACH_SAND(62, Area.CATHERBY, GroundMaterial.SAND),

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

	SOPHANEM_TRAPDOOR(Area.SOPHANEM_TRAPDOOR, GroundMaterial.NONE, p -> {}),
	KHARID_SAND_1(Area.KHARID_DESERT_REGION, GroundMaterial.SAND, p -> p
		.saturation(3)
		.hue(6)
		.ids(61, 62, 67, 68, -127, 126, 49, 58, 63, 64, 50, 45)),
	NECROPOLIS_SAND(Area.NECROPOLIS, GroundMaterial.DIRT, p -> p.ids(124)),
	SMOKE_DUNGEON(Area.SMOKE_DUNGEON, GroundMaterial.ROCKY_CAVE_FLOOR, p -> p.ids(56)),

	// Burthorpe games room
	GAMES_ROOM_INNER_FLOOR(64, Area.GAMES_ROOM_INNER, GroundMaterial.CARPET, p -> p.blended(false)),
	GAMES_ROOM_FLOOR(64, Area.GAMES_ROOM, GroundMaterial.WOOD_PLANKS_1, p -> p.blended(false)),

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

	// Ardougne
	SOUTH_OF_ZOO_FIX(61, Area.ARDOUGNE_SOUTH_OF_ZOO, GroundMaterial.OVERWORLD_GRASS_1),
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
	COX_SNOW_1(16, Area.COX_SNOW, GroundMaterial.SNOW_1),
	COX_SNOW_2(59, Area.COX_SNOW, GroundMaterial.SNOW_2),

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

	TEMPLE_OF_THE_EYE(Area.TEMPLE_OF_THE_EYE, GroundMaterial.GRUNGE, p -> p.ids(87, 88, 89)),
	ARCEUUS_GROUND(Area.ARCEUUS, GroundMaterial.DIRT, p -> p.ids(2, 3, 17, 23, 24)),

	// Cutscenes
	CANOE_CUTSCENE_GRASS(Area.CANOE_CUTSCENE, GroundMaterial.GRASS_SCROLLING, p -> p.ids(48, 50, 63)),

	// Default underlays
	OVERWORLD_GRASS(Area.OVERWORLD, GroundMaterial.OVERWORLD_GRASS_1, p -> p
		.ids(25, 33, 34, 40, 48, 49, 50, 51, 52, 53, 54, 62, 63, 67, 70, 71, 75, 93, 96, 97, 99, 100, 103, 114, 115, 126)
		.replaceWithIf(WINTER_GRASS, plugin -> plugin.configWinterTheme)),
	OVERWORLD_DIRT(Area.OVERWORLD, GroundMaterial.DIRT, p -> p
		.ids(-111, -110, 19, 56, 57, 64, 65, 66, 80, 92, 94, 111, 118, 122, 139, 150)
		.replaceWithIf(WINTER_DIRT, plugin -> plugin.configWinterTheme)),
	OVERWORLD_SAND(Area.OVERWORLD, GroundMaterial.SAND, p -> p.ids(-127, -118, 61, 68)),
	UNDERLAY_PACKED_EARTH(GroundMaterial.PACKED_EARTH, p -> p.ids(15)),

	UNDERLAY_SNOW(GroundMaterial.SNOW_1, p -> p.ids(58, 59)),
	UNDERLAY_72(GroundMaterial.VARIED_DIRT, p -> p
		.ids(72, 73, 98, 112, 113) //112 == Lovakengj
		.replaceWithIf(WINTER_DIRT, plugin -> plugin.configWinterTheme)
	),
	UNDERLAY_OVERWORLD_GRUNGE(GroundMaterial.GRUNGE, p -> p
		.ids(8, 10, 55, 60, 92) // 8 = Jatizso, 60 = GotR, 92 = Eadgars Cave
		.replaceWithIf(WINTER_GRUNGE, plugin -> plugin.configWinterTheme)
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
	public final Underlay replacementUnderlay;
	public final Function<HdPlugin, Boolean> replacementCondition;

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
		this.replacementUnderlay = builder.replacement;
		this.replacementCondition = builder.replacementCondition;
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

	public static Underlay getUnderlay(Scene scene, Tile tile, HdPlugin plugin) {
		LocalPoint localLocation = tile.getLocalLocation();
		WorldPoint worldPoint = WorldPoint.fromLocalInstance(scene, tile.getLocalLocation(), tile.getPlane());

		Underlay match = Underlay.NONE;
		for (Underlay underlay : ANY_MATCH) {
			if (underlay.area.containsPoint(worldPoint)) {
				match = underlay;
				break;
			}
		}

		short underlayId = scene.getUnderlayIds()[tile.getRenderLevel()][localLocation.getSceneX()][localLocation.getSceneY()];
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

		return match.replacementCondition.apply(plugin) ? match.replacementUnderlay : match;
	}

	public int[] modifyColor(int[] colorHSL) {
		colorHSL[0] = hue >= 0 ? hue : colorHSL[0];
		colorHSL[0] += shiftHue;
		colorHSL[0] = HDUtils.clamp(colorHSL[0], 0, 63);

		colorHSL[1] = saturation >= 0 ? saturation : colorHSL[1];
		colorHSL[1] += shiftSaturation;
		colorHSL[1] = HDUtils.clamp(colorHSL[1], 0, 7);

		colorHSL[2] = lightness >= 0 ? lightness : colorHSL[2];
		colorHSL[2] += shiftLightness;
		colorHSL[2] = HDUtils.clamp(colorHSL[2], 0, 127);

		return colorHSL;
	}
}
