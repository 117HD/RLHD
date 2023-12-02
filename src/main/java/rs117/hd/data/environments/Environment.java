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
package rs117.hd.data.environments;

import lombok.Getter;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.ColorUtils.rgb;

@Getter
public enum Environment
{
	EVIL_BOB_ISLAND(Area.EVIL_BOB_ISLAND, new Properties()
		.setFogColor("#B8D6FF")
		.setFogDepth(70)
		.setAmbientColor("#C0AE94")
		.setAmbientStrength(3.0f)
		.setDirectionalColor("#F5BC67")
		.setDirectionalStrength(1.0f)
	),

	// Wilderness
	REVENANT_CAVES(Area.REVENANT_CAVES, new Properties()
		.setFogColor("#081F1C")
		.setFogDepth(20)
		.setAmbientColor("#AECFC9")
		.setAmbientStrength(3.0f)
		.setDirectionalColor("#AECFC9")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),
	BLACK_ROOMS(Area.BLACK_ROOMS, new Properties()
		.setFogColor("#000000")
		.setFogDepth(65)
		.setDirectionalStrength(0)
		.setAmbientStrength(3)
		.setAllowSkyOverride(false)
		.setLightDirection(-128, 55)
	),
	FROZEN_WASTE_PLATEAU(Area.FROZEN_WASTE_PLATEAU, new Properties()
		.setFogColor("#252C37")
		.setFogDepth(80)
		.setAmbientStrength(0.4f)
		.setAmbientColor("#3B87E4")
		.setDirectionalStrength(2.5f)
		.setDirectionalColor("#8A9EB6")
	),
	WILDERNESS_HIGH(Area.WILDERNESS_HIGH, new Properties()
		.setFogColor("#101012")
		.setFogDepth(30)
		.setAmbientStrength(0.75f)
		.setAmbientColor(215, 210, 210)
		.setDirectionalStrength(1.75f)
		.setDirectionalColor("#C5B8B6")
		.enableLightning()
		.setGroundFog(-0, -250, 0.3f)
	),
	WILDERNESS_LOW(Area.WILDERNESS_LOW, new Properties()
		.setFogColor("#3E3E46")
		.setFogDepth(20)
		.setAmbientStrength(0.75f)
		.setAmbientColor(215, 210, 210)
		.setDirectionalStrength(2.5f)
		.setDirectionalColor(138, 158, 182)
	),
	WILDERNESS(Area.WILDERNESS, new Properties()
		.setFogColor("#25252A")
		.setFogDepth(30)
		.setAmbientStrength(0.75f)
		.setAmbientColor(215, 210, 210)
		.setDirectionalStrength(2.f)
		.setDirectionalColor("#C5B8B6")
		.setGroundFog(-0, -250, 0.3f)
	),

	// Varrock
	VARROCK_MUSEUM_BASEMENT(Area.VARROCK_MUSEUM_BASEMENT, new Properties()
		.setFogColor("#131B26")
		.setFogDepth(20)
		.setAmbientColor("#B59B79")
		.setAmbientStrength(2.0f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(0.5f)
		.setLightDirection(260f, 10f)
	),
	// Stronghold of Security
	STRONGHOLD_OF_SECURITY_WAR(Area.STRONGHOLD_OF_WAR, new Properties()
		.setFogColor("#000000")
		.setFogDepth(45)
		.setAmbientStrength(1.5f)
		.setAmbientColor("#AAAFB6")
		.setDirectionalStrength(1.0f)
		.setDirectionalColor("#FFFFFF")
		.setLightDirection(260f, 10f)
	),
	STRONGHOLD_OF_SECURITY_FAMINE(Area.STRONGHOLD_OF_FAMINE, new Properties()
		.setFogColor("#544222")
		.setFogDepth(50)
		.setAmbientStrength(1.3f)
		.setAmbientColor("#C0AE94")
		.setDirectionalStrength(1.0f)
		.setDirectionalColor("#F5BC67")
		.setLightDirection(260f, 10f)
	),
	STRONGHOLD_OF_SECURITY_PESTILENCE(Area.STRONGHOLD_OF_PESTILENCE, new Properties()
		.setFogColor("#525e20")
		.setFogDepth(50)
		.setAmbientStrength(1.5f)
		.setAmbientColor("#a2c35d")
		.setDirectionalStrength(1.0f)
		.setDirectionalColor("#FFFFFF")
		.setLightDirection(260f, 10f)
	),
	STRONGHOLD_OF_SECURITY_DEATH(Area.STRONGHOLD_OF_DEATH, new Properties()
		.setFogColor("#000000")
		.setFogDepth(45)
		.setAmbientStrength(1.5f)
		.setAmbientColor("#542d22")
		.setDirectionalStrength(1.0f)
		.setDirectionalColor("#FFFFFF")
		.setLightDirection(260f, 10f)
	),
	// A Soul's Bane
	TOLNA_DUNGEON_ANGER(Area.TOLNA_DUNGEON_ANGER, new Properties()
		.setFogColor("#290000")
		.setFogDepth(40)
		.setAmbientColor("#AE7D46")
		.setAmbientStrength(1.3f)
		.setDirectionalColor("#CB4848")
		.setDirectionalStrength(1.8f)
		.setLightDirection(260f, 10f)
	),
	TOLNA_DUNGEON_FEAR(Area.TOLNA_DUNGEON_FEAR, new Properties()
		.setFogColor("#000B0F")
		.setFogDepth(40)
		.setAmbientColor("#77A0FF")
		.setAmbientStrength(1.3f)
		.setDirectionalColor("#4C78B6")
		.setDirectionalStrength(1.5f)
		.setLightDirection(260f, 10f)
	),
	TOLNA_DUNGEON_CONFUSION(Area.TOLNA_DUNGEON_CONFUSION, new Properties()
		.setFogColor("#2E0C23")
		.setFogDepth(40)
		.setAmbientColor("#77A0FF")
		.setAmbientStrength(1.3f)
		.setDirectionalColor("#4E9DD0")
		.setDirectionalStrength(1.5f)
		.setLightDirection(260f, 10f)
	),

	// Dorgesh-Kaan
	DORGESHKAAN(Area.DORGESHKAAN, new Properties()
		.setFogColor("#190D02")
		.setFogDepth(40)
		.setAmbientColor("#FFFFFF")
		.setAmbientStrength(1.0f)
		.setDirectionalColor("#A29B71")
		.setDirectionalStrength(1.5f)
		.setLightDirection(260f, 10f)
	),

	THE_INFERNO(Area.THE_INFERNO, new Properties()
		.setUnderglowColor(255, 0, 0)
		.setUnderglowStrength(2f)
		.setFogColor(23, 11, 7)
		.setFogDepth(20)
		.setAmbientColor(240, 184, 184)
		.setAmbientStrength(1.7f)
		.setDirectionalColor(255, 246, 202)
		.setDirectionalStrength(0.7f)
		.setLightDirection(260f, 10f)
	),
	TZHAAR(Area.TZHAAR, new Properties()
		.setFogColor("#1A0808")
		.setFogDepth(15)
		.setAmbientColor("#FFEACC")
		.setAmbientStrength(0.8f)
		.setDirectionalColor("#FFA400")
		.setDirectionalStrength(1.8f)
		.setLightDirection(260f, 10f)
	),


	// Morytania
	// Hallowed Sepulchre
	HALLOWED_SEPULCHRE_LOBBY(Area.HALLOWED_SEPULCHRE_LOBBY, new Properties()
		.setFogColor("#0D1012")
		.setFogDepth(50)
		.setAmbientStrength(0.7f)
		.setAmbientColor("#C4D5EA")
		.setDirectionalStrength(1.0f)
		.setDirectionalColor("#A0BBE2")
		.setLightDirection(260f, 10f)
	),
	HALLOWED_SEPULCHRE_FLOOR_1(Area.HALLOWED_SEPULCHRE_FLOOR_1, new Properties()
		.setFogColor(17, 28, 26)
		.setFogDepth(50)
		.setAmbientStrength(0.9f)
		.setAmbientColor(155, 187, 177)
		.setDirectionalStrength(1.8f)
		.setDirectionalColor(117, 231, 255)
		.setLightDirection(260f, 10f)
	),
	HALLOWED_SEPULCHRE_FLOOR_2(Area.HALLOWED_SEPULCHRE_FLOOR_2, new Properties()
		.setFogColor(17, 28, 27)
		.setFogDepth(50)
		.setAmbientStrength(0.875f)
		.setAmbientColor(160, 191, 191)
		.setDirectionalStrength(1.5f)
		.setDirectionalColor(116, 214, 247)
		.setLightDirection(260f, 10f)
	),
	HALLOWED_SEPULCHRE_FLOOR_3(Area.HALLOWED_SEPULCHRE_FLOOR_3, new Properties()
		.setFogColor(18, 28, 29)
		.setFogDepth(50)
		.setAmbientStrength(0.85f)
		.setAmbientColor(165, 195, 205)
		.setDirectionalStrength(1.5f)
		.setDirectionalColor(115, 196, 240)
		.setLightDirection(260f, 10f)
	),
	HALLOWED_SEPULCHRE_FLOOR_4(Area.HALLOWED_SEPULCHRE_FLOOR_4, new Properties()
		.setFogColor(18, 27, 31)
		.setFogDepth(50)
		.setAmbientStrength(0.825f)
		.setAmbientColor(170, 199, 220)
		.setDirectionalStrength(1.5f)
		.setDirectionalColor(114, 178, 233)
		.setLightDirection(260f, 10f)
	),
	HALLOWED_SEPULCHRE_FLOOR_5(Area.HALLOWED_SEPULCHRE_FLOOR_5, new Properties()
		.setFogColor(19, 27, 33)
		.setFogDepth(50)
		.setAmbientStrength(0.8f)
		.setAmbientColor(175, 202, 234)
		.setDirectionalStrength(1.5f)
		.setDirectionalColor(113, 160, 226)
		.setLightDirection(260f, 10f)
	),
	VER_SINHAZA(Area.VER_SINHAZA, new Properties()
		.setFogColor("#1E314B")
		.setFogDepth(40)
		.setAmbientColor("#5A8CC0")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#67A8F8")
		.setDirectionalStrength(5.0f)
		.setGroundFog(-150, -350, 0.5f)
	),
	TOB_ROOM_VAULT(Area.TOB_ROOM_VAULT, new Properties()
		.setFogColor("#0E081A")
		.setFogDepth(40)
		.setAmbientStrength(3.0f)
		.setAmbientColor("#7575EA")
		.setDirectionalStrength(1.0f)
		.setDirectionalColor("#DDA6A6")
		.setLightDirection(260f, 10f)
	),
	THEATRE_OF_BLOOD(Area.THEATRE_OF_BLOOD, new Properties()
		.setFogColor("#0E0C2C")
		.setFogDepth(40)
		.setAmbientStrength(3.0f)
		.setAmbientColor("#8282B0")
		.setDirectionalStrength(5.0f)
		.setDirectionalColor("#DFC0C0")
		.setLightDirection(-128, 55)
	),
	TOA_LOOT_ROOM(Area.TOA_LOOT_ROOM, new Properties()
		.setFogColor("#050505")
		.setFogDepth(20)
		.setAmbientStrength(1.2f)
		.setAmbientColor("#ffffff")
		.setDirectionalStrength(.5f)
		.setDirectionalColor("#ffffff")
		.setLightDirection(-128, 55)
	),
	TOMBS_OF_AMASCUT(Area.TOMBS_OF_AMASCUT, new Properties()
		.setFogColor("#050505")
		.setFogDepth(20)
		.setAmbientStrength(1)
		.setAmbientColor("#ffffff")
		.setDirectionalStrength(.75f)
		.setDirectionalColor("#ffffff")
		.setLightDirection(-128, 55)
	),
	BARROWS_CRYPTS(Area.BARROWS_CRYPTS, new Properties()
		.setFogColor(0, 0, 0)
		.setFogDepth(20)
		.setAmbientColor(181, 143, 124)
		.setAmbientStrength(3.5f)
		.setDirectionalColor(255, 200, 117)
		.setDirectionalStrength(0.0f)
		.setLightDirection(260f, 10f)
	),
	BARROWS_TUNNELS(Area.BARROWS_TUNNELS, new Properties()
		.setFogColor(0, 0, 0)
		.setFogDepth(20)
		.setAmbientColor(181, 143, 124)
		.setAmbientStrength(3.0f)
		.setDirectionalColor(255, 200, 117)
		.setDirectionalStrength(0.5f)
		.setLightDirection(260f, 10f)
	),
	BARROWS(Area.BARROWS, new Properties()
		.setFogColor("#242D3A")
		.setFogDepth(50)
		.setAmbientColor("#5B83B3")
		.setAmbientStrength(2.0f)
		.setDirectionalColor("#526E8B")
		.setDirectionalStrength(8.0f)
		.enableLightning()
		.setGroundFog(-300, -500, 0.5f)
	),
	DARKMEYER(Area.DARKMEYER, new Properties()
		.setFogColor("#1E314B")
		.setFogDepth(40)
		.setAmbientColor("#8AABD5")
		.setAmbientStrength(1.0f)
		.setDirectionalColor("#62A3FF")
		.setDirectionalStrength(4.0f)
		.setGroundFog(-150, -350, 0.5f)
	),
	MEIYERDITCH(Area.MEIYERDITCH, new Properties()
		.setFogColor("#1E314B")
		.setFogDepth(40)
		.setAmbientColor("#dad8ce")
		.setAmbientStrength(2.0f)
		.setDirectionalColor("#ced6da")
		.setDirectionalStrength(1.8f)
		.setGroundFog(-150, -350, 0.5f)
	),
	MEIYERDITCH_MYREQUE_HIDEOUT(Area.MEIYERDITCH_MYREQUE_HIDEOUT, new Properties()
		.setFogColor(0, 0, 0)
		.setFogDepth(69)
		.setAmbientColor("#dad8ce")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#ced6da")
		.setDirectionalStrength(0.5f)
		.setLightDirection(260f, 10f)
	),
	MEIYERDITCH_MINES(Area.MEIYERDITCH_MINES, new Properties()
		.setFogColor(0, 0, 0)
		.setFogDepth(70)
		.setAmbientColor("#dad8ce")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#ced6da")
		.setDirectionalStrength(0.5f)
		.setLightDirection(260f, 10f)
	),
	MORYTANIA(Area.MORYTANIA, new Properties()
		.setFogColor("#1E314B")
		.setFogDepth(40)
		.setAmbientColor("#5A8CC0")
		.setAmbientStrength(2.0f)
		.setDirectionalColor("#F8BF68")
		.setDirectionalStrength(2.0f)
		.setGroundFog(-150, -350, 0.5f)
	),

	LUMBRIDGE_BASEMENT(Area.LUMBRIDGE_CASTLE_BASEMENT, new Properties()
			.setFogColor("#070606")
			.setFogDepth(84)
			.setAmbientColor("#FFFFFF")
			.setAmbientStrength(1.0f)
			.setDirectionalColor("#A29B71")
			.setDirectionalStrength(1.5f)
			.setLightDirection(260f, 10f)
	),
	GOBLIN_MAZE(Area.GOBLIN_MAZE, new Properties()
			.setFogColor("#050D02")
			.setFogDepth(60)
			.setAmbientColor("#FFFFFF")
			.setAmbientStrength(0.75f)
			.setDirectionalColor("#A29B71")
			.setDirectionalStrength(0.75f)
			.setLightDirection(260f, 10f)
	),
	LUMBRIDGE_SWAMP_CAVES(Area.LUMBRIDGE_SWAMP_CAVES, new Properties()
			.setFogColor("#040D02")
			.setFogDepth(50)
			.setAmbientColor(198, 201, 194)
			.setAmbientStrength(0.9f)
			.setDirectionalColor(168, 171, 144)
			.setDirectionalStrength(0.85f)
			.setLightDirection(260f, 10f)
	),

	DRAYNOR_MANOR(Area.DRAYNOR_MANOR, new Properties()
		.setFogColor("#0c0b0a")
		.setFogDepth(45)
		.setAmbientColor("#615C57")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFBCB7")
		.setDirectionalStrength(2.0f)
		.enableLightning()
	),
	DRAYNOR_MANOR_FOREST(Area.DRAYNOR_MANOR_FOREST, new Properties()
		.setFogColor(71, 64, 85)
		.setFogDepth(20)
		.setAmbientColor("#615C57")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFBCB7")
		.setDirectionalStrength(2.0f)
		.enableLightning()
	),
	DRAYNOR_MANOR_BASEMENT(Area.DRAYNOR_MANOR_BASEMENT, new Properties()
		.setFogColor("#190D02")
		.setFogDepth(40)
		.setAmbientColor("#7891B5")
		.setAmbientStrength(1.0f)
		.setDirectionalColor(76, 120, 182)
		.setDirectionalStrength(0.0f)
		.setLightDirection(260f, 10f)
	),

	MISTHALIN_MYSTERY_MANOR(Area.MISTHALIN_MYSTERY_MANOR, new Properties()
		.setFogColor(15, 14, 13)
		.setFogDepth(30)
		.setAmbientColor("#615C57")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFBCB7")
		.setDirectionalStrength(2.0f)
		.enableLightning()
	),

	MOTHERLODE_MINE(Area.MOTHERLODE_MINE, new Properties()
		.setFogColor("#241809")
		.setFogDepth(40)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(4.0f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),

	GAMES_ROOM(Area.GAMES_ROOM, new Properties()
		.setFogColor("#190D02")
		.setFogDepth(20)
		.setAmbientColor(181, 155, 121)
		.setAmbientStrength(1.5f)
		.setDirectionalColor(162, 151, 148)
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),

	SOUL_WARS_RED_TEAM(Area.SOUL_WARS_RED_BASE, new Properties()
		.setFogColor(28, 21, 13)
	),

	SMOKE_DUNGEON(Area.SMOKE_DUNGEON, new Properties()
		.setFogColor(0, 0, 0)
		.setFogDepth(80)
		.setAmbientColor(171, 171, 171)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(86, 86, 86)
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),
	KHARIDIAN_DESERT_DEEP(Area.KHARIDIAN_DESERT_DEEP, new Properties()
		.setFogColor("#CDAF7A")
		.setFogDepth(50)
		.setAmbientColor("#C0AE94")
		.setAmbientStrength(3.0f)
		.setDirectionalColor("#F5BC67")
		.setDirectionalStrength(1.0f)
	),
	KHARIDIAN_DESERT_MID(Area.KHARIDIAN_DESERT_MID, new Properties()
		.setFogColor("#C8B085")
		.setFogDepth(40)
		.setAmbientColor("#C0AE94")
		.setAmbientStrength(3.0f)
		.setDirectionalColor("#F5BC67")
		.setDirectionalStrength(1.0f)
	),
	KHARIDIAN_DESERT(Area.KHARIDIAN_DESERT, new Properties()
		.setFogColor("#C7B79B")
		.setFogDepth(25)
		.setAmbientColor("#A6AFC2")
		.setAmbientStrength(2.5f)
		.setDirectionalColor("#EDCFA3")
		.setDirectionalStrength(2.5f)
	),
	DESERT_TREASURE_PYRAMID(Area.DESERT_TREASURE_PYRAMID, new Properties()
		.setFogColor(39, 23, 4)
		.setFogDepth(40)
		.setAmbientColor(192, 159, 110)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(138, 158, 182)
		.setDirectionalStrength(0.25f)
		.setLightDirection(-128, 55)
	),
	PYRAMID_PLUNDER(Area.PYRAMID_PLUNDER, new Properties()
		.setFogColor("#190D02")
		.setFogDepth(40)
		.setAmbientColor(181, 155, 121)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(138, 158, 182)
		.setDirectionalStrength(0.75f)
		.setLightDirection(260f, 10f)
	),
	KALPHITE_LAIR(Area.KALPHITE_LAIR, new Properties()
		.setFogColor("#161101")
		.setFogDepth(35)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
		.setWaterColor(102, 234, 255)
	),

	GIELINOR_SNOWY_NORTHERN_REGION(Area.GIELINOR_SNOWY_NORTHERN_REGION, new Properties()
		.setFogColor("#AEBDE0")
		.setFogDepth(70)
		.setAmbientColor("#6FB0FF")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#F4E5C9")
		.setDirectionalStrength(2.5f)
	),

	MOUNTAIN_CAMP_ENTRY_PATH(Area.MOUNTAIN_CAMP_ENTRY_PATH, new Properties()
		.setFogColor(178, 187, 197)
		.setFogDepth(50)
		.setAmbientStrength(0.9f)
		.setDirectionalStrength(1.0f)
		.setGroundFog(-600, -900, 0.4f)
	),
	MOUNTAIN_CAMP(Area.MOUNTAIN_CAMP, new Properties()
		.setFogColor(178, 187, 197)
		.setFogDepth(50)
		.setAmbientStrength(0.9f)
		.setDirectionalStrength(1.0f)
		.setGroundFog(-1200, -1600, 0.5f)
	),
	FREMENNIK_PROVINCE(Area.FREMENNIK_PROVINCE, new Properties()
		.setFogColor("#969CA2")
		.setFogDepth(40)
		.setAmbientStrength(0.9f)
		.setAmbientColor("#96A3CB")
		.setDirectionalStrength(2.0f)
		.setDirectionalColor("#ABC2D3")
		.setGroundFog(-200, -400, 0.3f)
	),

	PENGUIN_BASE(Area.PENGUIN_BASE, new Properties()
		.setFogColor("#090808")
		.setFogDepth(40)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(.75f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(.75f)
		.setLightDirection(260f, 10f)
		.setWaterColor(102, 234, 255)
	),

	// Karamja
	KARAMJA_VOLCANO_DUNGEON(Area.KARAMJA_VOLCANO_DUNGEON, new Properties()
		.setFogColor("#190D02")
		.setFogDepth(40)
		.setAmbientColor("#7891B5")
		.setAmbientStrength(0.5f)
		.setDirectionalColor(76, 120, 182)
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),
	BRIMHAVEN_AGILITY_ARENA(Area.BRIMHAVEN_AGILITY_ARENA, new Properties()
		.setFogColor("#1A0808")
		.setFogDepth(25)
		.setAmbientColor("#FFEACC")
		.setAmbientStrength(1.2f)
		.setDirectionalColor("#FFA400")
		.setDirectionalStrength(1.0f)
		.setLightDirection(240f, 190f)
	),

	UNGAEL(Area.UNGAEL, new Properties()
		.setFogColor(226, 230, 237)
		.setFogDepth(40)
		.setAmbientColor(234, 226, 205)
		.setAmbientStrength(0.6f)
		.setDirectionalColor(130, 172, 224)
		.setDirectionalStrength(1.5f)
	),

	GOD_WARS_DUNGEON(Area.GOD_WARS_DUNGEON, new Properties()
		.setFogColor(14, 59, 89)
		.setFogDepth(30)
		.setAmbientColor(181,215,255)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(146, 209, 250)
		.setDirectionalStrength(1.8f)
		.setLightDirection(260f, 10f)
		.setWaterColor(56, 188, 255)
	),

	TAR_SWAMP(Area.TAR_SWAMP, new Properties()
		.setFogColor(42, 49, 36)
		.setFogDepth(50)
		.setAmbientColor(248, 224, 172)
		.setAmbientStrength(0.8f)
		.setDirectionalColor(168, 171, 144)
		.setDirectionalStrength(1.25f)
	),

	SOTE_LLETYA_SMALL_FIRES(Area.SOTE_LLETYA_MOSTLY_DONE_BURNING, new Properties()
		.setFogColor(91, 139, 120)
		.setFogDepth(30)
		.setAmbientStrength(1.0f)
		.setDirectionalStrength(0.0f)
		.setAllowSkyOverride(false)
	),
	SOTE_LLETYA_ON_FIRE(Area.SOTE_LLETYA_ON_FIRE, new Properties()
		.setFogColor(91, 139, 120)
		.setFogDepth(50)
		.setAmbientStrength(0.9f)
		.setDirectionalStrength(0.0f)
		.setAllowSkyOverride(false)
	),
	POISON_WASTE(Area.POISON_WASTE, new Properties()
		.setFogColor(50, 55, 47)
		.setFogDepth(30)
		.setAmbientColor(192, 219, 173)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(173, 176, 139)
		.setDirectionalStrength(2.0f)
	),
	TIRANNWN(Area.TIRANNWN_MAINLAND, new Properties()
		.setFogColor("#99D8C8")
		.setFogDepth(15)
	),
	PRIFDDINAS(Area.PRIFDDINAS, new Properties()
		.setFogColor("#99D8C8")
		.setFogDepth(15)
		.setLightDirection(-128, 55)
	),
	ZALCANO(Area.ZALCANO, new Properties()
		.setFogColor(0.8f, 0.6f, 0.6f)
		.setFogDepth(40)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),
	SOTE_GRAND_LIBRARY(Area.SOTE_GRAND_LIBRARY, new Properties()
		.setFogColor(18, 64, 83)
		.setAmbientStrength(0.3f)
		.setDirectionalStrength(1.0f)
		.setAllowSkyOverride(false)
		.setLightDirection(-128, 55)
	),
	SOTE_FRAGMENT_OF_SEREN_ARENA(Area.SOTE_FRAGMENT_OF_SEREN_ARENA, new Properties()
		.setFogColor(0, 0, 0)
		.setAllowSkyOverride(false)
		.setLightDirection(-128, 55)
	),

	// Ardougne
	Shadow_DUNGEON(Area.SHADOW_DUNGEON, new Properties()
		.setFogColor(0, 0, 0)
		.setFogDepth(60)
		.setAmbientColor(171, 171, 171)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(86, 86, 86)
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),

	// Yanille
	// Nightmare Zone
	NIGHTMARE_ZONE(Area.NIGHTMARE_ZONE, new Properties()
		.setFogColor("#190D02")
		.setFogDepth(40)
		.setAmbientColor("#F2B979")
		.setAmbientStrength(0.9f)
		.setDirectionalColor("#97DDFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),

	// Castle Wars
	CASTLE_WARS_UNDERGROUND(Area.CASTLE_WARS_UNDERGROUND, new Properties()
		.setFogColor("#190D02")
		.setFogDepth(40)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),

	// Last Man Standing
	LMS_ARENA_WILD_VARROCK(Area.LMS_ARENA_WILD_VARROCK, new Properties()
		.setFogColor("#695B6B")
		.setFogDepth(30)
		.setAmbientStrength(0.6f)
		.setAmbientColor(215, 210, 210)
		.setDirectionalStrength(2.5f)
		.setDirectionalColor("#C5B8B6")
		.setGroundFog(-0, -250, 0.3f)
	),

	// Zeah
	MOUNT_KARUULM(Area.MOUNT_KARUULM, new Properties()
		.setAmbientStrength(1.0f)
		.setDirectionalStrength(3.0f)
	),
	KARUULM_SLAYER_DUNGEON(Area.KARUULM_SLAYER_DUNGEON, new Properties()
		.setFogColor("#051E22")
		.setFogDepth(40)
		.setAmbientColor("#A4D2E5")
		.setAmbientStrength(2.0f)
		.setDirectionalColor("#9AEAFF")
		.setDirectionalStrength(0.75f)
		.setLightDirection(260f, 10f)
	),
	KOUREND_CATACOMBS(Area.KOUREND_CATACOMBS, new Properties()
		.setFogColor("#0E0022")
		.setFogDepth(40)
		.setAmbientColor("#8B7DDB")
		.setAmbientStrength(3.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(-128, 55)
	),
	KEBOS_LOWLANDS(Area.KEBOS_LOWLANDS, new Properties()
		.setFogColor(41, 44, 16)
		.setFogDepth(50)
		.setAmbientColor(255, 215, 133)
		.setAmbientStrength(0.8f)
		.setDirectionalColor(207, 229, 181)
		.setDirectionalStrength(1.0f)
	),
	BLOOD_ALTAR(Area.BLOOD_ALTAR, new Properties()
		.setFogColor(79, 19, 37)
		.setFogDepth(30)
		.setAmbientColor(190, 72, 174)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(78, 238, 255)
		.setDirectionalStrength(2.5f)
	),
	ZEAH_SNOWY_NORTHERN_REGION(Area.ZEAH_SNOWY_NORTHERN_REGION, new Properties()
		.setFogColor("#AEBDE0")
		.setFogDepth(70)
		.setAmbientColor("#6FB0FF")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#F4E5C9")
		.setDirectionalStrength(2.5f)
	),
	ARCEUUS(Area.ARCEUUS, new Properties()
		.setFogColor(19, 24, 79)
		.setFogDepth(30)
		.setAmbientColor(99, 105, 255)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(78, 238, 255)
		.setDirectionalStrength(3.5f)
	),
	LOVAKENGJ(Area.LOVAKENGJ, new Properties()
		.setFogColor(21, 10, 5)
		.setFogDepth(40)
		.setAmbientColor(255, 215, 133)
		.setAmbientStrength(1.0f)
		.setDirectionalColor(125, 141, 179)
		.setDirectionalStrength(4.0f)
		.setWaterColor(185, 214, 255)
	),
	THE_STRANGLEWOOD(Area.THE_STRANGLEWOOD, new Properties()
		.setFogColor("#af979f")
		.setFogDepth(35)
		.setAmbientColor("#c0bde7")
		.setAmbientStrength(2.f)
		.setDirectionalColor("#f2edf4")
		.setDirectionalStrength(2.5f)
	),
	THE_STRANGLEWOOD_QUEST_UNDERGROUND_AREAS(Area.THE_STRANGLEWOOD_QUEST_UNDERGROUND_AREAS, new Properties()
		.setFogColor("#070707")
		.setFogDepth(20)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(4.0f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(0.0f)
		.setLightDirection(260f, 10f)
		.setAllowSkyOverride(false)
	),
	JUDGE_OF_YAMA_BOSS(Area.JUDGE_OF_YAMA_BOSS, new Properties()
		.setFogColor("#0e1826")
		.setFogDepth(50)
		.setAmbientColor("#8AABD5")
		.setAmbientStrength(1.0f)
		.setDirectionalColor("#62A3FF")
		.setDirectionalStrength(4.0f)
		.setGroundFog(-150, -350, 0.5f)
		.setAllowSkyOverride(false)
	),

	// Zanaris
	COSMIC_ENTITYS_PLANE(Area.COSMIC_ENTITYS_PLANE, new Properties()
		.setFogColor("#000000")
		.setAmbientStrength(1.5f)
		.setAmbientColor("#DB6FFF")
		.setDirectionalStrength(3.0f)
		.setDirectionalColor("#57FF00")
		.setLightDirection(260f, 10f)
		.setAllowSkyOverride(false)
	),
	ZANARIS(Area.ZANARIS, new Properties()
		.setFogColor(22, 63, 71)
		.setFogDepth(30)
		.setAmbientColor(115, 181, 195)
		.setAmbientStrength(0.5f)
		.setDirectionalColor(245, 214, 122)
		.setDirectionalStrength(1.3f)
		.setLightDirection(260f, 10f)
	),

	// The Gauntlet
	THE_GAUNTLET_NORMAL(Area.THE_GAUNTLET_NORMAL, new Properties()
		.setFogColor("#090606")
		.setFogDepth(20)
		.setAmbientColor("#D2C0B7")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#78FFE3")
		.setDirectionalStrength(3.0f)
		.setLightDirection(260f, 10f)
	),
	THE_GAUNTLET_CORRUPTED(Area.THE_GAUNTLET_CORRUPTED, new Properties()
		.setFogColor("#090606")
		.setFogDepth(20)
		.setAmbientColor("#BB9EAE")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#C58C9E")
		.setDirectionalStrength(3.0f)
		.setLightDirection(260f, 10f)
	),
	THE_GAUNTLET_LOBBY(Area.THE_GAUNTLET_LOBBY, new Properties()
		.setFogColor("#090606")
		.setFogDepth(20)
		.setAmbientColor("#D2C0B7")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#78FFE3")
		.setDirectionalStrength(3.0f)
		.setLightDirection(260f, 10f)
	),

	// POHs
	PLAYER_OWNED_HOUSE_SNOWY(Area.PLAYER_OWNED_HOUSE_SNOWY, new Properties()
		.setFogColor("#AEBDE0")
		.setFogDepth(50)
		.setAmbientColor("#6FB0FF")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#F4E5C9")
		.setDirectionalStrength(2.5f)
	),

	// Blackhole
	BLACKHOLE(Area.BLACKHOLE, new Properties()
		.setFogColor(0, 0, 0)
		.setFogDepth(20)
		.setAmbientStrength(1.2f)
		.setAmbientColor(255, 255, 255)
		.setDirectionalStrength(0.0f)
		.setLightDirection(260f, 10f)
		.setAllowSkyOverride(false)
	),

	// Camdozaal (Below Ice Mountain)
	CAMDOZAAL(Area.CAMDOZAAL, new Properties()
		.setFogColor("#080012")
		.setFogDepth(40)
		.setAmbientStrength(1.5f)
		.setAmbientColor("#C9B9F7")
		.setDirectionalStrength(0.0f)
		.setDirectionalColor("#6DC5FF")
		.setLightDirection(260f, 10f)
	),

	// Tempoross
	TEMPOROSS_COVE(Area.TEMPOROSS_COVE, new Properties()
		.setFogColor("#45474B")
		.setFogDepth(60)
		.setAmbientStrength(2.0f)
		.setAmbientColor("#A5ACBD")
		.setDirectionalStrength(1.0f)
		.setDirectionalColor("#707070")
		.enableLightning()
	),

	// Guardians of the Rift
	TEMPLE_OF_THE_EYE(Area.TEMPLE_OF_THE_EYE, new Properties()
		.setFogColor(0,32,51)
		.setFogDepth(15)
		.setAmbientStrength(1.0f)
		.setAmbientColor(255, 255, 255)
		.setDirectionalStrength(0.3f)
		.setDirectionalColor(230, 244, 255)
		.setLightDirection(-130, 55f)
		.setUnderwater(true)
		.setUnderwaterCausticsStrength(40)
	),

	// Death's office
	DEATHS_OFFICE(Area.DEATHS_OFFICE, new Properties()
		.setFogColor("#000000")
		.setFogDepth(20)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(4.0f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(0.0f)
		.setLightDirection(260f, 10f)
		.setAllowSkyOverride(false)
	),

	// Chambers of Xeric
	CHAMBERS_OF_XERIC(Area.CHAMBERS_OF_XERIC, new Properties()
		.setFogColor("#122717")
		.setFogDepth(35)
		.setAmbientStrength(3.0f)
		.setAmbientColor("#7897C3")
		.setDirectionalStrength(1.0f)
		.setDirectionalColor("#ACFF68")
		.setLightDirection(260f, 10f)
	),

	// Nightmare of Ashihama
	NIGHTMARE_OF_ASHIHAMA_ARENA(Area.NIGHTMARE_OF_ASHIHAMA_ARENA, new Properties()
		.setFogColor("#000000")
		.setFogDepth(30)
		.setAmbientStrength(2.5f)
		.setAmbientColor("#9A5DFD")
		.setDirectionalStrength(2.0f)
		.setDirectionalColor("#00FF60")
		.setLightDirection(260f, 10f)
	),
	SISTERHOOD_SANCTUARY(Area.SISTERHOOD_SANCTUARY, new Properties()
		.setFogColor("#000000")
		.setFogDepth(20)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(4.0f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(0.0f)
		.setLightDirection(260f, 10f)
		.setAllowSkyOverride(false)
	),

	// Underwater areas
	MOGRE_CAMP(Area.MOGRE_CAMP, new Properties()
		.setFogColor("#133156")
		.setFogDepth(60)
		.setAmbientStrength(0.5f)
		.setAmbientColor("#255590")
		.setDirectionalStrength(5.0f)
		.setDirectionalColor("#71A3D0")
		.setGroundFog(0, -500, 0.5f)
		.setUnderwater(true)
	),
	HARMONY_ISLAND_UNDERWATER_TUNNEL(Area.HARMONY_ISLAND_UNDERWATER_TUNNEL, new Properties()
		.setFogColor("#133156")
		.setFogDepth(80)
		.setAmbientStrength(2.0f)
		.setAmbientColor("#255590")
		.setDirectionalStrength(2.5f)
		.setDirectionalColor("#71A3D0")
		.setLightDirection(260f, 10f)
		.setGroundFog(-800, -1100, 0.5f)
		.setUnderwater(true)
	),
	FOSSIL_ISLAND_UNDERWATER_AREA(Area.FOSSIL_ISLAND_UNDERWATER_AREA, new Properties()
		.setFogColor("#133156")
		.setFogDepth(60)
		.setAmbientStrength(0.5f)
		.setAmbientColor("#255590")
		.setDirectionalStrength(5.0f)
		.setDirectionalColor("#71A3D0")
		.setLightDirection(260f, 10f)
		.setGroundFog(-400, -750, 0.5f)
		.setUnderwater(true)
	),

	// Lunar Isle
	LUNAR_DIPLOMACY_DREAM_WORLD(Area.LUNAR_DREAM_WORLD, new Properties()
		.setFogColor("#000000")
		.setFogDepth(40)
		.setAmbientColor("#77A0FF")
		.setAmbientStrength(3.0f)
		.setDirectionalColor("#CAB6CD")
		.setDirectionalStrength(0.7f)
		.setLightDirection(260f, 10f)
		.setAllowSkyOverride(false)
	),

	// Runecrafting altars
	COSMIC_ALTAR(Area.COSMIC_ALTAR, new Properties()
		.setFogColor("#000000")
		.setFogDepth(40)
		.setAmbientColor("#FFFFFF")
		.setAmbientStrength(0.2f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(3.0f)
		.setLightDirection(260f, 10f)
		.setAllowSkyOverride(false)
	),
	TRUE_BLOOD_ALTAR(Area.TRUE_BLOOD_ALTAR, new Properties()
		.setFogColor("#000000")
		.setFogDepth(25)
	),

	TARNS_LAIR(Area.TARNS_LAIR, new Properties()
		.setFogColor("#241809")
		.setFogDepth(40)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),

	// Random events
	DRILL_DEMON(Area.RANDOM_EVENT_DRILL_DEMON, new Properties()
		.setFogColor("#696559")
	),

	// Standalone and miscellaneous areas
	GIANTS_FOUNDRY(Area.GIANTS_FOUNDRY, new Properties()
		.setFogColor(0, 0, 0)
		.setFogDepth(12)
		.setAmbientStrength(1.1f)
		.setAmbientColor(255, 255, 255)
		.setDirectionalStrength(1.0f)
		.setDirectionalColor(255, 193, 153)
		.setLightDirection(-113, -120f)
		.setWaterColor(102, 234, 255)
	),
	ELID_CAVE(Area.ELID_CAVE, new Properties()
		.setFogColor("#000000")
		.setFogDepth(40)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.75f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
		.setWaterColor(102, 234, 255)
	),
	ANCIENT_CAVERN(Area.ANCIENT_CAVERN, new Properties()
		.setFogColor("#000000")
		.setFogDepth(25)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(2.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.5f)
		.setLightDirection(260f, 10f)
		.setWaterColor(79, 178, 255)
	),

	ICY_UNDERGROUND_DARK(Area.ICY_UNDERGROUND_DARK, new Properties()
		.setFogColor("#030303")
		.setFogDepth(25)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(.75f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(.75f)
		.setLightDirection(260f, 10f)
		.setWaterColor(102, 234, 255)
	),
	ICY_UNDERGROUND_BRIGHT(Area.ICY_UNDERGROUND_BRIGHT, new Properties()
		.setFogColor("#ADC5E4")
		.setFogDepth(68)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(.75f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(.75f)
		.setLightDirection(260f, 10f)
		.setWaterColor(102, 234, 255)
	),
	GOBLIN_VLIIAGE_COOKS_CHAMBER(Area.GOBLIN_VILLAGE_COOKS_CHAMBER, new Properties()
		.setFogColor("#030303")
		.setFogDepth(5)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(0.75f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(0.75f)
		.setLightDirection(260f, 10f)
		.setAllowSkyOverride(false)
	),

	MAGE_ARENA_BANK(Area.MAGE_ARENA_BANK, new Properties()
		.setFogDepth(40)
		.setFogColor("#000000")
		.setAmbientStrength(1.5f)
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
	),
	TEARS_OF_GUTHIX(Area.TEARS_OF_GUTHIX_CAVES, new Properties()
		.setFogColor("#060505")
		.setFogDepth(50)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(2.5f)
		.setDirectionalColor("#878474")
		.setDirectionalStrength(1.5f)
		.setLightDirection(260f, 10f)
	),
	BURGH_DE_ROTT_BASEMENT(Area.BURGH_DE_ROTT_BASEMENT, new Properties()
		.setFogColor("#030403")
		.setFogDepth(84)
		.setAmbientColor("#FFFFFF")
		.setAmbientStrength(1.0f)
		.setDirectionalColor("#A29B71")
		.setDirectionalStrength(1.5f)
		.setLightDirection(260f, 10f)
	),
	KEEP_LE_FAYE_JAIL(Area.KEEP_LE_FAYE_JAIL, new Properties()
		.setFogColor("#070606")
		.setFogDepth(84)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.0f)
		.setDirectionalColor("#878474")
		.setDirectionalStrength(1.5f)
		.setLightDirection(260f, 10f)
	),

	POISON_WASTE_DUNGEON(Area.POISON_WASTE_DUNGEON, new Properties()
		.setFogColor("#000000")
		.setFogDepth(40)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
		.setWaterColor(102, 234, 255)
	),

	// Desert Treasure 2 areas
	THE_SCAR(Area.THE_SCAR, new Properties()
		.setFogColor("#080707")
		.setFogDepth(15)
		.setAmbientColor("#ffedec")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#fafaff")
		.setDirectionalStrength(.5f)
		.setLightDirection(270, 0)
	),
	LASSAR_UNDERCITY_WATER_CUTSCENE(Area.LASSAR_UNDERCITY_WATER_CUTSCENE, new Properties()
		.setFogColor("#000000")
		.setFogDepth(50)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(480, -45)
		.setWaterColor(43, 43, 64)
	),
	LASSAR_UNDERCITY(Area.LASSAR_UNDERCITY_NORMAL, new Properties()
		.setFogColor("#000000")
		.setFogDepth(5)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(230, -45)
		.setWaterColor(43, 43, 64)
	),
	LASSAR_UNDERCITY_SHADOW_REALM(Area.LASSAR_UNDERCITY_SHADOW_REALM, new Properties()
		.setFogColor("#030e09")
		.setFogDepth(25)
		.setAmbientColor("#aab6ac")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(230, -45)
		.setWaterColor(43, 43, 64)
	),

	// overrides 'ALL' to provide default daylight conditions for the overworld area
	OVERWORLD(Area.OVERWORLD, new Properties()),
	// used for underground, instances, etc.
	DEFAULT(Area.ALL, new Properties()
		.setFogColor("#000000")
		.setFogDepth(40)
		.setAmbientColor("#AAAFB6")
		.setAmbientStrength(1.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.0f)
		.setLightDirection(260f, 10f)
		.setWaterColor(102, 234, 255)
	),
	NONE(Area.NONE, new Properties()
		.setFogColor("#ff00ff") // never meant to be rendered
	),

	// Seasonal default environments
	AUTUMN(Area.NONE, new Properties()
		.setFogColor("#fddcb4")
		.setFogDepth(18)
		.setAmbientColor(ColorUtils.colorTemperatureToLinearRgb(3200))
		.setAmbientStrength(.3f)
		.setDirectionalColor(ColorUtils.colorTemperatureToLinearRgb(3200))
		.setDirectionalStrength(1.7f)
		.setLightDirection(215, -78)
	),
	WINTER(Area.NONE, new Properties()
		.setFogColor("#B8C5DB")
		.setFogDepth(35)
		.setAmbientColor("#8FCAFF")
		.setAmbientStrength(3.5f)
		.setDirectionalColor("#FFFFFF")
		.setDirectionalStrength(1.5f)
	),
	;

	private final Area area;
	private final int fogDepth;
	private final boolean customFogDepth;
	private final float[] fogColor;
	private final boolean customFogColor;
	private final float ambientStrength;
	private final boolean customAmbientStrength;
	private final float[] ambientColor;
	private final boolean customAmbientColor;
	private final float directionalStrength;
	private final boolean customDirectionalStrength;
	private final float[] directionalColor;
	private final boolean customDirectionalColor;
	private final float underglowStrength;
	private final float[] underglowColor;
	private final boolean lightningEnabled;
	private final int groundFogStart;
	private final int groundFogEnd;
	private final float groundFogOpacity;
	private final float lightPitch;
	private final float lightYaw;
	private final boolean customLightDirection;
	private final boolean allowSkyOverride;
	private final boolean underwater;
	private final float[] underwaterCausticsColor;
	private final float underwaterCausticsStrength;
	private final float[] waterColor;
	private final boolean customWaterColor;

	private static class Properties
	{
		private int fogDepth = 65;
		private boolean customFogDepth = false;
		private float[] fogColor = rgb(185, 214, 255);
		private boolean customFogColor = false;
		private float ambientStrength = 1.0f;
		private boolean customAmbientStrength = false;
		private float[] ambientColor = rgb(151, 186, 255);
		private boolean customAmbientColor = false;
		private float directionalStrength = 4.0f;
		private boolean customDirectionalStrength = false;
		private float[] directionalColor = rgb(255, 255, 255);
		private boolean customDirectionalColor = false;
		private float underglowStrength = 0.0f;
		private float[] underglowColor = rgb(0, 0, 0);
		private boolean lightningEnabled = false;
		private int groundFogStart = -200;
		private int groundFogEnd = -500;
		private float groundFogOpacity = 0;
		private float lightPitch = -128f;
		private float lightYaw = 55f;
		private boolean customLightDirection = false;
		private boolean allowSkyOverride = true;
		private boolean underwater = false;
		private float[] underwaterCausticsColor = null;
		private float underwaterCausticsStrength = 0;
		private float[] waterColor = rgb(185, 214, 255);
		private boolean customWaterColor = false;

		public Properties setFogDepth(int depth) {
			this.fogDepth = depth * 10;
			this.customFogDepth = true;
			return this;
		}

		public Properties setFogColor(String hex) {
			return setFogColor(rgb(hex));
		}

		public Properties setFogColor(float r, float g, float b) {
			return setFogColor(rgb(r, g, b));
		}

		public Properties setFogColor(float[] linearRgb) {
			this.fogColor = linearRgb;
			this.customFogColor = true;
			return this;
		}

		public Properties setAmbientStrength(float str) {
			this.ambientStrength = str;
			this.customAmbientStrength = true;
			return this;
		}

		public Properties setAmbientColor(float r, float g, float b) {
			this.ambientColor = rgb(r, g, b);
			this.customAmbientColor = true;
			return this;
		}

		public Properties setAmbientColor(String hex) {
			return setAmbientColor(ColorUtils.rgb(hex));
		}

		public Properties setAmbientColor(float[] linearRgb) {
			this.ambientColor = linearRgb;
			this.customAmbientColor = true;
			return this;
		}

		public Properties setWaterColor(float r, float g, float b) {
			return setWaterColor(rgb(r, g, b));
		}

		public Properties setWaterColor(float[] linearRgb) {
			this.waterColor = linearRgb;
			this.customWaterColor = true;
			return this;
		}

		public Properties setDirectionalStrength(float str)
		{
			this.directionalStrength = str;
			this.customDirectionalStrength = true;
			return this;
		}

		public Properties setDirectionalColor(float r, float g, float b) {
			return setDirectionalColor(rgb(r, g, b));
		}

		public Properties setDirectionalColor(String hex)
		{
			return setDirectionalColor(ColorUtils.rgb(hex));
		}

		public Properties setDirectionalColor(float[] linearRgb) {
			this.directionalColor = linearRgb;
			this.customDirectionalColor = true;
			return this;
		}

		public Properties setUnderglowStrength(float str)
		{
			this.underglowStrength = str;
			return this;
		}

		public Properties setUnderglowColor(float r, float g, float b) {
			this.underglowColor = rgb(r, g, b);
			return this;
		}

		public Properties setGroundFog(int start, int end, float maxOpacity)
		{
			this.groundFogStart = start;
			this.groundFogEnd = end;
			this.groundFogOpacity = maxOpacity;
			return this;
		}

		public Properties enableLightning()
		{
			this.lightningEnabled = true;
			return this;
		}

		public Properties setLightDirection(float pitch, float yaw)
		{
			this.lightPitch = pitch;
			this.lightYaw = yaw;
			this.customLightDirection = true;
			return this;
		}

		public Properties setAllowSkyOverride(boolean s)
		{
			this.allowSkyOverride = s;
			return this;
		}

		public Properties setUnderwater(boolean underwater)
		{
			this.underwater = underwater;
			return this;
		}

		/**
		 * Use a different color than the directional lighting color for underwater caustic effects
		 */
		public Properties setUnderwaterCausticsColor(float[] linearRgb) {
			this.underwaterCausticsColor = linearRgb;
			return this;
		}

		/**
		 * Use a different light strength than the directional lighting strength
		 * @param strength a float value to replace directional strength
		 * @return the same properties instance
		 */
		public Properties setUnderwaterCausticsStrength(float strength)
		{
			this.underwaterCausticsStrength = strength;
			return this;
		}
	}

	Environment(Area area, Properties properties)
	{
		this.area = area;
		this.fogDepth = properties.fogDepth;
		this.customFogDepth = properties.customFogDepth;
		this.fogColor = properties.fogColor;
		this.customFogColor = properties.customFogColor;
		this.ambientStrength = properties.ambientStrength;
		this.customAmbientStrength = properties.customAmbientStrength;
		this.ambientColor = properties.ambientColor;
		this.customAmbientColor = properties.customAmbientColor;
		this.directionalStrength = properties.directionalStrength;
		this.customDirectionalStrength = properties.customDirectionalStrength;
		this.directionalColor = properties.directionalColor;
		this.customDirectionalColor = properties.customDirectionalColor;
		this.underglowColor = properties.underglowColor;
		this.underglowStrength = properties.underglowStrength;
		this.lightningEnabled = properties.lightningEnabled;
		this.groundFogStart = properties.groundFogStart;
		this.groundFogEnd = properties.groundFogEnd;
		this.groundFogOpacity = properties.groundFogOpacity;
		this.lightPitch = properties.lightPitch;
		this.lightYaw = properties.lightYaw;
		this.customLightDirection = properties.customLightDirection;
		this.allowSkyOverride = properties.allowSkyOverride;
		this.underwater = properties.underwater;
		this.underwaterCausticsColor = properties.underwaterCausticsColor == null ?
			properties.directionalColor : properties.underwaterCausticsColor;
		this.underwaterCausticsStrength = properties.underwaterCausticsStrength == 0 ?
			properties.directionalStrength : properties.underwaterCausticsStrength;
		this.waterColor = properties.waterColor;
		this.customWaterColor = properties.customWaterColor;
	}
}
