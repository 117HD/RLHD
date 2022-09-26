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
import net.runelite.api.Constants;
import rs117.hd.utils.AABB;

import java.util.Arrays;

@Getter
public enum Area
{
	// items higher on the list take precedent over those below
	EVIL_BOB_ISLAND(2495, 4805, 2559, 4747),
	// Tutorial Island
	TUTORIAL_ISLAND_WIZARD_BUILDING(3136, 3097, 3144, 3076),
	TUTORIAL_ISLAND_CHURCH(3114, 3111, 3129, 3102),
	TUTORIAL_ISLAND_BANK(3113, 3131, 3130, 3117),
	TUTORIAL_ISLAND_UNDERGROUND(3120, 9534, 3067, 9491),
	TUTORIAL_ISLAND_QUEST_BUILDING(3079, 3118, 3090, 3126),
	TUTORIAL_ISLAND_KITCHEN(3072, 3092, 3079, 3080),
	TUTORIAL_ISLAND_START_BUILDING(3086, 3113, 3098, 3099),
	TUTORIAL_ISLAND_THE_NODE(3084, 3048, 3124, 3006),
	TUTORIAL_ISLAND(
		new AABB(3052, 3137, 3155, 3057),
		new AABB(3084, 3048, 3124, 3006), // the node
		new AABB(1600, 6015, 1792, 6207) // some kind of instance
	),

	// Lumbridge
	RFD_QUIZ(2589, 4618, 2566, 4642),
	LUM_BRIDGE(3240, 3226, 3250, 3225),
	LUMBRIDGE_CASTLE_BASEMENT(3205, 9613, 3220, 9626),
	LUMBRIDGE_CASTLE_ENTRYWAY(3213, 3212, 3216, 3225),
	LUMBRIDGE_CASTLE_DINING_ROOM(3205, 3218, 3212, 3226),
	HAM_HIDEOUT(3137, 9661, 3192, 9602),
	LUMBRIDGE_CASTLE(3216, 3230, 3204, 3207),
	LUMBRIDGE_CASTLE_ENTRANCE(3218, 3219, 3217, 3218),
	LUMBRIDGE_DRAYNOR_PATH_BLEND_1(3135,3293, 3135,3295),
	LUMBRIDGE_DRAYNOR_PATH_BLEND_2(3136,3293, 3136,3296),
	LUMBRIDGE_SWAMP_PATH_FIX(3242,3184, 3244,3186),
	LUMBRIDGE_VARROCK_PATH_FIX(3267,3328,3269,3329),
	LUMBRIDGE_TOWER_FLOOR(
			new AABB(3230, 3225, 3227, 3221,0),
			new AABB(3230, 3216, 3227, 3212,0),
			new AABB(3230, 3225, 3227, 3212, 2)
	),
	LUMBRIDGE(
		new AABB(3136, 3137, 3254, 3327),
		new AABB(3254, 3189, 3263, 3200),
		new AABB(3266, 3200, 3247, 3327),
		new AABB(3271, 3330, 3264, 3322),
		new AABB(3400, 4807, 3448, 4847) // clan wars arena
	),

	// Dorgesh-Kaan
	DORGESHKAAN(
		new AABB(2687, 5376, 2752, 5247), // lower level
		new AABB(2751, 5440, 2816, 5311), // middle level
		new AABB(2815, 5504, 2880, 5375) // upper level
	),

	// Varrock
	VARROCK_MUSEUM_BASEMENT(
		new AABB(1729, 4929, 1790, 4990),
		new AABB(1601, 4929, 1663, 4990)
	),
	VARROCK_MUSEUM(3253, 3442, 3267, 3455),
	VARROCK_CASTLE(3200, 3458, 3226, 3500),
	VARROCK_JULIETS_HOUSE_FLOWER_BED(3161, 3450, 3171, 3444),
	VARROCK_JULIETS_HOUSE(3164, 3441, 3149, 3427),
	VARROCK_JOLLY_BOAR_INN(3272, 3486, 3288, 3510),
	VARROCK_SARADOMIN_CHURCH(
			new AABB(3259, 3488,3252, 3471),
			new AABB(3251, 3483, 3249, 3476)
	),
	VARROCK_ANVILS(3185, 3420, 3190, 3427),
	VARROCK_BUILDING_RUINS(
		new AABB(3185, 3416, 3194, 3427),
		new AABB(3193, 3410, 3197, 3416),
		new AABB(3254, 3406, 3263, 3411)
	),
	VARROCK_EAST_BANK_CENTER(3251, 3420, 3256, 3422),
	VARROCK_EAST_BANK(
			new AABB(3250, 3416, 3257, 3423)
	),
	VARROCK_EAST_BANK_OUTSIDE_1(
			new AABB(3250 ,3424 ,3257 ,3424)
	),
	VARROCK_MOON_INN_BALCONY(3217, 3401, 3214, 3397),
	VARROCK_MOON_INN_FLOOR(
			new AABB(3229, 3396, 3228, 3394),
			new AABB(3217, 3396, 3216,3394),
			new AABB(3227, 3402, 3218, 3394),
			new AABB(3228, 3402, 3227, 3401),
			// South bumpout
			new AABB(3229, 3393, 3226, 3393),
			new AABB(3220, 3393,3217, 3393),
			// Under fireplace
			new AABB(3221, 3403, 3219, 3403)
	),
	VARROCK_MOON_INN_FLOOR_FIX(
			new AABB(3225, 3393, 3225, 3393),
			new AABB(3221, 3393, 3221, 3393),
			new AABB(3216, 3393, 3216, 3393)
	),
	VARROCK_MUSEUM_SOUTH_PATH_FIX(3264,3439,3265,3441),
	VARROCK_WEST_BANK_SOUTH_PATH_FIX(3182,3432,3183,3432),
	VARROCK_WILDERNESS_DITCH_PATH_FIX(3242,3519,3275,3526),
	VARROCK(
			new AABB(3136, 3397, 3290, 3518),
			new AABB(3177, 3371, 3291, 3410)
	),

	// Barbarian Village
	BARBARIAN_VILLAGE_EAST_PATH_FIX(3111,3420,3112,3421),

	// A Soul's Bane
	TOLNA_DUNGEON_ANGER(
		new AABB(3008, 5216, 3039, 5247),
		new AABB(2963, 5228, 2995, 5198),
		new AABB(3264, 9823, 3298, 9855)
	),
	TOLNA_DUNGEON_FEAR(
		new AABB(3040, 5216, 3071, 5247),
		new AABB(3072, 5184, 3103, 5215),
		new AABB(3008, 5184, 3039, 5215),
		new AABB(3264, 9792, 3296, 9822),
		new AABB(3299, 9823, 3327, 9855)
	),
	TOLNA_DUNGEON_CONFUSION(
		new AABB(3040, 5184, 3071, 5215),
		new AABB(3297, 9792, 3327, 9822)
	),

	// Digsite
	DIGSITE_EXAM_CENTRE(
		new AABB(3367,3348,3357,3332),
		new AABB(3356, 3337,3348, 3332)
	),
	DIGSITE_DOCK(3348, 3460, 3404, 3444),

	// Draynor
	DRAYNOR_MANOR_INTERIOR(
		new AABB(3091, 3363, 3096, 3353),
		new AABB(3097, 3374, 3119, 3353),
		new AABB(3120, 3360, 3126, 3353)
	),
	DRAYNOR_MANOR(3083, 3386, 3129, 3329),
	DRAYNOR_MANOR_BASEMENT(
		new AABB(3073, 9780, 3082, 9766)
	),
	DRAYNOR_NORTHERN_HOUSE_FLOOR(
		new AABB(3102, 3281, 3097, 3277)
	),
	DRAYNOR_AGGIES_HOUSE(3088, 3261, 3083, 3256),
	DRAYNOR_WOM_HOUSE_FRONT(3094, 3250, 3088, 3250),
	DRAYNOR_BANK(
		new AABB(3097, 3246, 3088, 3240),
		new AABB(2127, 4910, 2137, 4903)
	),
	DRAYNOR_BANK_FRONT_PATH(3093, 3247, 3092, 3247),
	DRAYNOR_MARKET_PATH_FIX(
			new AABB(3095, 3247, 3094, 3247), // east of bank path
			new AABB(3087, 3246, 3085, 3243),
			new AABB(3091, 3247, 3087, 3247)
	),
	DRAYNOR_BANK_PATH_FIX_DARK(
			new AABB(3088, 3248, 3088, 3248), // middle of path to fix blending weirdness
			new AABB(3087, 3247, 3087, 3247),
			new AABB(3086, 3246, 3086, 3246),
			new AABB(3084, 3246, 3084, 3246)
	),
	DRAYNOR_BANK_PATH_FIX_LIGHT(
			new AABB(3095, 3248, 3095, 3248), // East of bank fix
			new AABB(3095, 3250, 3095, 3250),
			new AABB(3096, 3251, 3096, 3251)
	),
	DRAYNOR(
		new AABB(3071, 3226, 3133, 3292),
		new AABB(2112, 4893, 2166, 4930) // bank robbery cutscene
	),

	// Misthalin Mystery
	MISTHALIN_MYSTERY_MANOR(1600, 4863, 1727, 4779),

	// Falador
	FALADOR_EAST_BANK_PATH_FIX_2(3006, 3348, 3006, 3346),
	FALADOR_EAST_BANK_PATH_FIX_1(3006, 3346, 3006, 3344),
	FALADOR_HAIRDRESSER(
		new AABB(2941, 3389, 2946, 3376),
		new AABB(2946, 3376, 2949, 3382)
	),
	FALADOR_PARTY_ROOM(3034, 3387, 3057, 3369),
	FALADOOR_PARTY_ROOM_STAIRS_FIX(
			new AABB(3054, 3084, 3053, 3383),
			new AABB(3038, 3084, 3037, 3383)
	),
	FALADOR_TRIANGLE_PATH_FIX_1(2973,3413,2974,3415),
	FALADOR_TRIANGLE_PATH_FIX_2(2965,3406,2968,3407),
	FALADOR_SOUTH_PATH_FIX(3006,3320,3008,3321),
	FALADOR(
		new AABB(2932, 3306, 3068, 3401),
		new AABB(3456, 4734, 3528, 4783),
		new AABB(2964,3401,2969,3405) // path northwards
	),

	MOTHERLODE_MINE(
		new AABB(3713, 5696, 3776, 5633),
		new AABB(3827, 5692, 3868, 5652)
	),

	// Edgeville
	EDGEVILLE_PATH_OVERLAY(
		new AABB(3087, 3501, 3099, 3502), // path north of bank
		new AABB(3079, 3502, 3085, 3503), // path between bank and general store
		new AABB(3105, 3508, 3113, 3507), // path south of the prison
		new AABB(3112, 3514, 3119, 3515), // path east of prison
		new AABB(3120, 3517, 3129, 3516), // path west of bridge
		new AABB(3119, 3516, 3120, 3515), // prison-bridge path join
		new AABB(3107, 3508, 3108, 3502), // path to north side of furnace
		new AABB(3079, 3502, 3080, 3501), // path to dave's house
		new AABB(3079, 3506, 3080, 3504), // path to general store
		new AABB(3100, 3496, 3099, 3501), // path east of bank
		new AABB(3104, 3498, 3103, 3499), // path west side of furnace 1
		new AABB(3103, 3499, 3102, 3500), // path west side of furnace 2
		new AABB(3101, 3500, 3102, 3501), // path west side of furnace 3
		new AABB(3084, 3502, 3088, 3501), // between well and bank
		new AABB(3113, 3514, 3114, 3509), // south prison join 1
		new AABB(3112, 3509, 3114, 3508), // south prison join 2
		new AABB(3101, 3510, 3103, 3509), // path to central building 1
		new AABB(3104, 3509, 3103, 3508), // path to central building 2
		new AABB(3104, 3507, 3105, 3506), // diagonal 1
		new AABB(3103, 3506, 3104, 3505), // diagonal 2
		new AABB(3102, 3505, 3103, 3504), // diagonal 3
		new AABB(3101, 3504, 3102, 3503), // diagonal 4
		new AABB(3100, 3503, 3101, 3502)  // diagonal 5
	),
	// Edgeville Bank overhaul
	EDGEVILLE_BANK_PERIMETER_FIX(
			new AABB(3090, 3497, 3090, 3494), // bumpout for window
			new AABB(3091, 3497, 3091, 3493) // bumpout border correction
	),
	EDGEVILLE_BANK_TILING(
			new AABB(3098, 3498, 3098, 3498),
			new AABB(3098, 3496, 3098, 3496),
			new AABB(3098, 3494, 3098, 3494),
			new AABB(3098, 3492, 3098, 3492),
			new AABB(3098, 3490, 3098, 3490),
			new AABB(3098, 3488, 3098, 3488),
			new AABB(3097, 3499, 3097, 3499),
			new AABB(3096, 3488, 3096, 3488),
			new AABB(3095, 3499, 3095, 3499),
			new AABB(3094, 3488, 3094, 3488),
			new AABB(3093, 3499, 3093, 3499),
			new AABB(3092, 3488, 3092, 3488),
			new AABB(3091, 3489, 3091, 3489),
			new AABB(3091, 3491, 3091, 3491),
			new AABB(3091, 3493, 3091, 3493),
			new AABB(3091, 3495, 3091, 3495),
			new AABB(3091, 3497, 3091, 3497),
			new AABB(3091, 3499, 3091, 3499),
			new AABB(3090, 3494, 3090, 3494),
			new AABB(3090, 3496, 3090, 3496)
	),
	EDGEVILLE_BANK(3098, 3499, 3091, 3488),
	EDGEVILLE_BANK_SURROUNDING_PATH(
			new AABB(3091, 3501, 3089, 3500), // north part of bank
			new AABB(3093, 3502, 3089, 3492),// path north of bank
			new AABB(3093, 3500, 3091, 3498), // path west of bank (north part)
			new AABB(3088, 3502, 3086, 3485), // path west of bank
			new AABB(3090, 3492, 3089, 3490), // west path to bank
			new AABB(3092, 3489, 3088, 3483) // path south of bank
	),
	// Edgeville buildings
	EDGEVILLE_DORIS_HOUSE(3077, 3496, 3081, 3489),
	EDGEVILLE_MONASTERY(3041, 3509, 3062, 3471),
	EDGEVILLE_GUARD_TOWER_FLOOR(
			new AABB(3111, 3517, 3107, 3511)
	),
	EDGEVILLE_FURNACE_FLOOR(
			new AABB(3110, 3501, 3105, 3496)
	),
	EDGEVILLE_MANS_HOUSE_FLOOR(
			new AABB(3100, 3513, 3091, 3507)
	),
	EDGEVILLE_GENERAL_STORE_FLOOR_FIX(
			new AABB(3082, 3507, 3078, 3507)
	),
	EDGEVILLE_GENERAL_STORE_FLOOR(
			new AABB(3084, 3513, 3076, 3507)
	),

	// Seers
	SEERS_BANK(2719, 3497, 2730, 3487),
	SEERS_HOUSES(
		new AABB(2716, 3482, 2709, 3476),
		new AABB(2705, 3476, 2699, 3470),
		new AABB(2715, 3473,2710,3470),
		new AABB(2709, 3473, 2706, 3471 , 1),
		new AABB(2716, 3473, 2716, 3470, 1)
	),
	SEERS_CHURCH(
		new AABB(2703, 3466, 2690, 3459)
	),

	// White Wolf Mountain
	WHITE_WOLF_MOUNTAIN(
		new AABB(2789, 3530, 2879, 3488),
		new AABB(2832, 3439, 2879, 3502),
		new AABB(2431, 5374, 2496, 5439) // instance
	),

	// Keep Le Faye
	KEEP_LE_FAYE(
		new AABB(2747, 3415, 2782, 3389),
		new AABB(1670, 4228, 1722, 4279) // instance
	),

	// Catherby
	CATHERBY_BEACH_OBELISK_WATER_FIX(2843, 3423, 2845, 3421),
	CATHERBY_BEACH_LADDER_FIX(2842, 3424, 2842, 3424),
	CATHERBY_BEACH_SHORELINE_FIX(
		new AABB(2870, 3414, 2869, 3416),
		new AABB(2865, 3420, 2864, 3423),
		new AABB(2863, 3423, 2851, 3426),
		new AABB(2848, 3427, 2848, 3428),
		new AABB(2847, 3429, 2847, 3429),
		new AABB(2845, 3430, 2843, 3430)
	),
	CATHERBY_BANK(2806, 3445, 2812, 3438),
	CATHERBY(
		new AABB(2788, 3407, 2864, 3435),
		new AABB(2855, 3435, 2788, 3447),
		new AABB(2788, 3447, 2839, 3474)
	),

	// South Falador
	RIMMINGTON(
		new AABB(2905, 3265, 2995, 3195),
		new AABB(2989, 3195, 2945, 3186)
	),
	PORT_SARIM_BETTYS_HOUSE(3016, 3261,3011, 3256),
	PORT_SARIM(3005, 3265, 3064, 3174),
	MUDSKIPPER_POINT(2977, 3132, 3008, 3102),
	SOUTH_FALADOR_FARM(3011, 3322, 3069, 3279),
	CRAFTING_GUILD(2910, 3296, 2945, 3265),
	// entire area south of falador, encompassing all of the above
	SOUTH_FALADOR(
		new AABB(2900, 3308, 3069, 3195),
		new AABB(3060, 3195, 2971, 3100),
		new AABB(2980, 3185, 2924, 3202)
	),
	ASGARNIA_ICE_DUNGEON_SNOWY(3085, 9531, 3020, 9605),

	// Karamja
	BRIMHAVEN_DOCKS_TEXTURED(2773, 3235, 2771, 3223),

	// Burthorpe
	HEROES_GUILD(
		new AABB(2892, 3507, 2898, 3514),
		new AABB(2894, 3504, 2896, 3517),
		new AABB(2899, 3509, 2901, 3512)
	),
	WARRIORS_GUILD(2837, 3557, 2877, 3533),
	WARRIORS_GUILD_FLOOR_2(2837, 3557, 2877, 3533, 1),
	BURTHORPE(
		new AABB(2830, 3533, 2938, 3576),
		new AABB(2938, 3576, 2880, 3581),
		new AABB(2838, 3538, 2928, 3553),
		new AABB(2873, 3533, 2935, 3521)
	),
	GAMES_ROOM_INNER(2221, 4973, 2194, 4946),
	GAMES_ROOM(2179, 4990, 2239, 4929),

	// Ardougne
	WEST_ARDOUGNE(
		new AABB(2460, 3335, 2558, 3279),
		new AABB(2558, 3279, 2510, 3264),
		new AABB(2429, 3323, 2466, 3305)
	),
	WEST_ARDOUGNE_CARPET_FIX(
			new AABB(2544, 3289, 2451, 3286),
			new AABB(2526, 3317, 2526, 3314)
	),
	EAST_ARDOUGNE(
		new AABB(2558, 3342, 2686, 3257),
		new AABB(3328, 5887, 3392, 5951) // SOTE cutscene
	),
	EAST_ARDOUGNE_CASTLE_DIRT_FIX(
		new AABB(2565, 3279, 2592, 3313)
	),
	EAST_ARDOUGNE_CASTLE_PATH_FIX(
		new AABB(2585, 3298, 2593, 3314)
	),
	EAST_ARDOUGNE_BANK(
			new AABB(2658, 3287, 2652, 3280, 0),
			new AABB(2651, 3287, 2649, 3285,0),
			new AABB(2651, 3282, 2649, 3280,0)
	),
	EAST_ARDOUGNE_BANK_NORTH(2621, 3335, 2612, 3330),

	// Yanille
	YANILLE_BANK(2609, 3088, 2616, 3097),
	YANILLE_WATCHTOWER_TOP(2934, 4718, 2927, 4711, 2),
	YANILLE_WATCHTOWER_MIDDLE(2550,3118, 2543, 3111, 1),
	YANNILLE_WATCHTOWER_BOTTOM_DOORWAY(2550, 3115, 2550, 3114, 0),
	YANILLE_WATCHTOWER_BOTTOM(
		new AABB(2549, 3118, 2543, 3111, 0), // Main area
		new AABB(2550, 3113, 2550, 3111, 0), // South of doorway
		new AABB(2550, 3118, 2550, 3116,0) // North of doorway

	),

	YANILLE_MAGIC_GUILD_FLOORS(
		new AABB(2596, 3094, 2585, 3081, 1),
		new AABB(2596, 3094, 2585, 3081, 2)
	),
	YANILLE(
		new AABB(2531, 3127, 2622, 3070),
		new AABB(2880, 4671, 2944, 4735) // instance
	),
	GUTANOTH_CAVE(2560, 9408, 2626, 9475),
	// Nightmare Zone
	NIGHTMARE_ZONE(2241, 4676, 2303, 4722),

	// Castle Wars
	CASTLE_WARS_LOBBY(2434, 3104, 2448, 3080),
	CASTLE_WARS_ARENA_SARADOMIN_SIDE(
		new AABB(2435, 3068, 2373, 3086),
		new AABB(3282, 3086, 2435, 3091),
		new AABB(2435, 3091, 2388, 3093),
		new AABB(2404, 3093, 2435, 3095),
		new AABB(2435, 3095, 2407, 3098),
		new AABB(2409, 3098, 2435, 3117),
		new AABB(2435, 3117, 2414, 3123),
		new AABB(2420, 3123, 2430, 3127),
		new AABB(2400, 3104, 2411, 3092),
		new AABB(2400, 3113, 2411, 3103)
	),
	CASTLE_WARS_ARENA_ZAMORAK_SIDE(
		new AABB(2364, 3139, 2423, 3125),
		new AABB(2417, 3125, 2364, 3120),
		new AABB(2364, 3120, 2412, 3114),
		new AABB(2400, 3114, 2364, 3104),
		new AABB(2364, 3105, 2399, 3093),
		new AABB(2384, 3093, 2354, 3087),
		new AABB(2364, 3087, 2374, 3082)
	),
	CASTLE_WARS_ARENA(2364, 3139, 2435, 3068),
	CASTLE_WARS_UNDERGROUND(2444, 9544, 2361, 9473),
	CASTLE_WARS(
		new AABB(2364, 3139, 2435, 3068),
		new AABB(2434, 3104, 2448, 3080)
	),

	// Last Man Standing
	LMS_ARENA_WILD_VARROCK(
		new AABB(3455, 6015, 3647, 6205),
		new AABB(3520, 5886, 3648, 6015)
	),
	LMS_ARENA_DESERTED_ISLAND(3391, 5759, 3520, 5951),

	// Kharidian desert
	SMOKE_DUNGEON(3198, 9409, 3329, 9341),
	DUEL_ARENA(3326, 3290, 3407, 3199),
	SHANTAY_PASS(3294, 3135, 3311, 3114),
	MAGE_TRAINING_ARENA(3347, 3327, 3374, 3288),
	AL_KHARID_WELL(3293, 3183, 3293, 3183),
	AL_KHARID_BUILDINGS(
		new AABB(3265, 3173, 3272, 3161),
		new AABB(3270, 3194, 3279, 3179),
		new AABB(3285, 3192, 3290, 3187),
		new AABB(3289, 3206, 3296, 3202),
		new AABB(3297, 3194, 3306, 3185),
		new AABB(3319, 3197, 3323, 3191),
		new AABB(3312, 3186, 3318, 3173),
		new AABB(3313, 3165, 3318, 3160),
		new AABB(3282, 3177, 3303, 3159)
	),
	AL_KHARID(
		new AABB(3276, 3265, 3337, 3195),
		new AABB(3259, 3201, 3345, 3135),
		new AABB(3253, 3182, 3265, 3155)
	),
	EAST_AL_KHARID(
		new AABB(3344, 3200, 3384, 3129),
		new AABB(3384, 3141, 3391, 3203),
		new AABB(3391, 3203, 3412, 3164),
		new AABB(3412, 3170, 3426, 3197)
	),
	AL_KHARID_MINE(3270, 3322, 3337, 3258),
	DESERT_MINING_CAMP(3272, 3042, 3306, 3011),
	KHARIDIAN_DESERT_DEEP(
		new AABB(3198, 2989, 3322, 2817),
		new AABB(3315, 2928, 3469, 2812)
	),
	KHARIDIAN_DESERT_MID(
		new AABB(3135, 3051, 3524, 2885)
	),
	KHARIDIAN_DESERT(
		new AABB(3196, 3134, 3526, 2997),
		new AABB(3134, 3069, 3565, 2600),
		new AABB(3114, 2974, 3216, 2786),
		new AABB(3008, 4671, 3072, 4734) // agility pyramid instance
	),
	KHARID_DESERT_REGION(
		new AABB(3268, 3326, 3345, 3178),
		new AABB(3255, 3188, 3433, 3119),
		new AABB(3319, 3288, 3411, 3196),
		new AABB(3432, 3202, 3338, 3122),
		new AABB(3376, 3321, 3401, 3200),
		new AABB(3421, 3263, 3382, 3191),
		new AABB(3200, 3134, 3533, 3044),
		new AABB(3484, 3164, 3399, 3129),
		new AABB(3399, 3129, 3450, 3179),
		new AABB(3169, 3072, 3527, 3023),
		new AABB(3142, 3063, 3527, 2842),
		new AABB(3127, 3021, 3538, 2590)
	),
	DESERT_TREASURE_PYRAMID(
		new AABB(3198, 9339, 3267, 9275),
		new AABB(2755, 4934, 2812, 4982),
		new AABB(2825, 4976, 2873, 4938),
		new AABB(2894, 4972, 2933, 4941)
	),
	PYRAMID_PLUNDER(
		new AABB(1922, 4470, 1945, 4447),
		new AABB(1955, 4441, 1980, 4417),
		new AABB(1955, 4471, 1980, 4445),
		new AABB(1922, 4440, 1946, 4416),
		new AABB(1919, 4481, 1988, 4414)
	),

	//Sophanem and Menaphos
	SOPHANEM_FLOORS(
		new AABB(3316, 2803, 3308, 2796, 0),
		new AABB(3307, 2803, 3307, 2801, 0),
		new AABB(3307, 2798, 3307, 2796, 0),
		new AABB(3285, 2777, 3277, 2765, 0),
		new AABB(3277, 2764, 3279, 2764, 0),
		new AABB(3285, 2764, 3283, 2764, 0)
	),

	// Morytania
	// Hallowed Sepulchre
	HALLOWED_SEPULCHRE_LOBBY(2380, 5958, 2420, 6000),
	HALLOWED_SEPULCHRE_FLOOR_1(2220, 5938, 2325, 6032),
	HALLOWED_SEPULCHRE_FLOOR_2(2475, 5938, 2580, 6032),
	HALLOWED_SEPULCHRE_FLOOR_3(2350, 5800, 2455, 5906),
	HALLOWED_SEPULCHRE_FLOOR_4(2475, 5800, 2580, 5906),
	HALLOWED_SEPULCHRE_FLOOR_5(2220, 5800, 2325, 5906),
	VER_SINHAZA_WATER_FIX(
		new AABB(3682, 3257, 3682, 3257),
		new AABB(3681, 3256, 3681, 3256),
		new AABB(3684, 3259, 3678, 3263),
		new AABB(3683, 3258, 3683, 3258)
	),
	VER_SINHAZA(
		new AABB(3641, 3236, 3684, 3202),
		new AABB(2087, 4903, 2064, 4880) // cutscene
	),
	MEIYERDITCH(3583, 3331, 3648, 3169),
	CASTLE_DRAKAN(3520, 3388, 3594, 3328),
	DARKMEYER(
		new AABB(3590, 3399, 3636, 3330),
		new AABB(3636, 3330, 3662, 3392),
		new AABB(3662, 3384, 3669, 3335)
	),
	PORT_PHASMATYS(
		new AABB(3649, 3508, 3688, 3456),
		new AABB(3688, 3456, 3710, 3484),
		new AABB(3670, 3503, 3684, 3514)
	),
	FENKENSTRAINS_CASTLE(3527, 3576, 3564, 3533),
	MORYTANIA_SLAYER_TOWER(3405, 3531, 3452, 3579),
	CANIFIS(13878),
	MORTTON(13875),
	BARROWS_CRYPTS(3586, 9726, 3524, 9669, 3),
	BARROWS_TUNNELS(3586, 9726, 3524, 9669, 0),
	BARROWS(14131),
	BURGH_DE_ROTT(3468, 3258, 3583, 3164),
	ABANDONED_MINE(3423, 3261, 3461, 3201),
	MORYTANIA(
		new AABB(3422, 3202, 3782, 3467),
		new AABB(3775, 3467, 3426, 3603),
		new AABB(3426, 3603, 3399, 3511),
		new AABB(3410, 3524, 3457, 3500),
		new AABB(3457, 3500, 3416, 3495),
		new AABB(3420, 3495, 3451, 3481),
		new AABB(3451, 3481, 3424, 3435),
		new AABB(3424, 3435, 3403, 3462),
		new AABB(3422, 3442, 3399, 3321),
		new AABB(3414, 3327, 3435, 3255),
		new AABB(3469, 3209, 3726, 3161),
		new AABB(2087, 4903, 2064, 4880), // ver sinhaza cutscene
		new AABB(1987, 4996, 2105, 5054), // temple trekking
		new AABB(2118, 4994, 2171, 5036), // temple trekking
		new AABB(2178, 4996, 2478, 5054), // temple trekking
		new AABB(1670, 4546, 1724, 4600) // slayer tower roof (grotesque guardians)
	),

	// TzHaar
	THE_INFERNO(
		new AABB(2257, 5363, 2292, 5319),
		new AABB(2466, 5053, 2523, 4994)
	),
	TZHAAR(2367, 5184, 2559, 4993),

	TREE_GNOME_STRONGHOLD(
		new AABB(2368, 3525, 2496, 3387),
		new AABB(2404, 3547, 2431, 3511),
		new AABB(2484, 3406, 2507, 3387),
		new AABB(1920, 5502, 2048, 5631) // instance
	),

	// Wilderness
	REVENANT_CAVES(3265, 10243, 3134, 10050),
	FROZEN_WASTE_PLATEAU(
		new AABB(2939, 3970, 2988, 3904),
		new AABB(2988, 3907, 3002, 3940),
		new AABB(2980, 3909, 2939, 3865),
		new AABB(2939, 3865, 2958, 3834)
	),
	WILDERNESS_HIGH(2939, 3974, 3391, 3903),
	WILDERNESS_MID_HIGH(2939, 3903, 3391, 3806),
	WILDERNESS_MID(2939, 3806, 3391, 3730),
	WILDERNESS_MID_LOW(2939, 3730, 3391, 3558),
	WILDERNESS_LOW(2939, 3558, 3391, 3522),
	WILDERNESS(2939, 3974, 3391, 3522),
	MAGE_ARENA_BANK(2527, 4725, 2549, 4708),
	GIELINOR_SNOWY_NORTHERN_REGION(
		new AABB(2942, 3711, 2748, 3980),
		new AABB(2696, 3839, 2741, 3799),
		new AABB(2714, 3803, 2757, 3767),
		new AABB(2723, 3743, 2758, 3710)
	),

	// Fremennik Province
	MOUNTAIN_CAMP_LAKE(
		new AABB(2754, 3707, 2789, 3675),
		new AABB(2789, 3682, 2802, 3708)
	),
	MOUNTAIN_CAMP_ENTRY_PATH(2780, 3673, 2766, 3655),
	MOUNTAIN_CAMP(
		new AABB(2750, 3711, 2815, 3671),
		new AABB(2815, 3671, 2779, 3652)
	),
	RELLEKKA(2594, 3646, 2692, 3747),
	FREMENNIK_PROVINCE(
		new AABB(2574, 3750, 2800, 3585),
		new AABB(2800, 3642, 2822, 3717),
		new AABB(2686, 3811, 2751, 3724)
	),

	// Tirannwn
	GWENITH(2187, 3424, 2229, 3397),
	PRIFDDINAS(3136, 5952, 3391, 6207),
	MYNYDD(2119, 3453, 22112, 3384),
	LLETYA(2313, 3147, 2363, 3194),
	POISON_WASTE(
		new AABB(2166, 3119, 2315, 3025),
		new AABB(2181, 3117, 2280, 3131)
	),
	ARANDAR(2309, 3337, 2394, 3234),
	SOTE_GRAND_LIBRARY(2752, 6080, 2879, 6207),
	SOTE_LLETYA_ON_FIRE(2881, 6206, 2937, 6152),
	SOTE_LLETYA_SMALL_FIRES(2730, 6080, 2820, 6160),
	SOTE_TEMPLE_OF_LIGHT_SEREN_CUTSCENE(3237, 5913, 3219, 5933),
	SOTE_FRAGMENT_OF_SEREN_ARENA(3264, 5887, 3328, 5951),
	TIRANNWN(
		new AABB(2116, 3455, 2320, 3021),
		new AABB(2295, 3202, 2365, 3140),
		new AABB(2688, 6015, 2944, 6207) // SOTE cutscene
	),

	ZANARIS(2315, 4345, 2500, 4485),

	GOD_WARS_DUNGEON(
		new AABB(2816, 5375, 2971, 5216),
		new AABB(2848, 5199, 2948, 5153), // ancient prison
		new AABB(2848, 5246, 2948, 5185), // ancient prison
		new AABB(3008, 10178, 3072, 10112) // wilderness dungeon
	),

	// Soul Wars
	ISLE_OF_SOULS(2079, 3014, 2348, 2763),
	SOUL_WARS_ARENA(2113, 2946, 2302, 2878),
	SOUL_WARS_RED_BASE(
		new AABB(2272, 2946, 2302, 2910),
		new AABB(2297, 2910, 2268, 2884),
		new AABB(2273, 2891, 2240, 2938),
		new AABB(2240, 2938, 2260, 2882)
	),
	SOUL_WARS_BLUE_BASE(
		new AABB(2116, 2881, 2145, 2941),
		new AABB(2145, 2941, 2177, 2889)
	),

	ISLE_OF_SOULS_TUTORIAL(1855, 5823, 2175, 6078),
	SOUL_WARS_ARENA_TUTORIAL(1921, 6018, 2110, 5950),
	SOUL_WARS_RED_BASE_TUTORIAL(
		new AABB(2080, 6018, 2110, 5982),
		new AABB(2105, 5982, 2076, 5956),
		new AABB(2081, 5963, 2048, 6010),
		new AABB(2048, 6010, 2068, 5954))
	,
	SOUL_WARS_BLUE_BASE_TUTORIAL(
		new AABB(1924, 5953, 1953, 6013),
		new AABB(1953, 6013, 1985, 5961)
	),

	// Zeah
	KARUULM_SLAYER_DUNGEON(1112, 10295, 1384, 10124),
	MOUNT_KARUULM(1245,3765,1358,3860),
	LIZARDMAN_TEMPLE(1280, 10047, 1341, 10109),
	XERICS_LOOKOUT(1580, 3526, 1596, 3534),
	SHAYZIEN_COMBAT_RING(1539, 3627, 1548, 3618),
	SHAYZIEN_ENCAMPMENT(
		new AABB(1467, 3678, 1540, 3607),
		new AABB(1540, 3613, 1559, 3650)
	),
	SHAYZIEN_SHAYZIA_RUIN(1577, 3587, 1594, 3604),
	SHAYZIEN_GRAVEYARD_OF_HEROES(1472, 3583, 1515, 3541),
	SHAYZIEN_EAST_ENTRANCE_BLEND_FIX(1556, 3577, 1556, 3575),
	SHAYZIEN(
		new AABB(1467, 3678, 1540, 3607), // encampment
		new AABB(1540, 3613, 1559, 3650), // encampment
		new AABB(1477, 3616, 1525, 3584),
		new AABB(1460, 3599, 1574, 3537),
		new AABB(1574, 3537, 1535, 3523),
		new AABB(1595, 3584, 1567, 3606) // shayzia ruin
	),
	HOSIDIUS(1720, 3641, 1802, 3560),
	KOUREND_CATACOMBS(1600, 10111, 1732, 9977),
	BLOOD_ALTAR(
		new AABB(1703, 3844, 1739, 3815),
		new AABB(1739, 3817, 1751, 3839),
		new AABB(1751, 3833, 1791, 3815)
	),
	DARK_ALTAR(1660, 3904, 1747, 3864),
	ARCEUUS(
		new AABB(1572, 3838, 1597, 3799),
		new AABB(1566, 3832, 1586, 3805),
		new AABB(1562, 3823, 1570, 3811),
		new AABB(1588, 3838, 1608, 3778),
		new AABB(1598, 3854, 1730, 3731),
		new AABB(1730, 3731, 1623, 3710),
		new AABB(1616, 3874, 1658, 3842),
		new AABB(1625, 3898, 1856, 3817)
	),
	ZEAH_SNOWY_NORTHERN_REGION(
		new AABB(1493, 3905, 1870, 4061),
		new AABB(1518, 3908, 1608, 3875),
		new AABB(1595, 3875, 1532, 3859),
		new AABB(1532, 3859, 1582, 3847),
		new AABB(1633, 3895, 1591, 3910)
	),
	LOVAKENGJ(
		new AABB(1416, 3899, 1521, 3835),
		new AABB(1560, 3840, 1420, 3787),
		new AABB(1434, 3787, 1586, 3755),
		new AABB(1595, 3767, 1448, 3737),
		new AABB(1460, 3737, 1598, 3719)
	),
	MOUNT_QUIDAMORTEM(
		new AABB(1194, 3594, 1292, 3520),
		new AABB(1287, 3556, 1300, 3596),
		new AABB(1300, 3596, 1308, 3580),
		new AABB(1230, 3611, 1269, 3588)
	),
	KEBOS_LOWLANDS(
		new AABB(1167, 3664, 1345, 3582),
		new AABB(1292, 3588, 1355, 3526),
		new AABB(1275, 3692, 1329, 3631)
	),
	MESS_HALL_KITCHEN(1643, 3631, 1649, 3622),
	ZEAH(1152, 4078, 1938, 3270),

	// Fossil Island
	TAR_SWAMP(
		new AABB(3712, 3800, 3632, 3694),
		new AABB(3697, 3809, 3631, 3782)
	),
	FOSSIL_ISLAND(3626, 3908, 3851, 3693),
	FOSSIL_ISLAND_CENTRAL_BANK_FIX(
			new AABB(3744, 3805, 3742, 3802)
	),
	FOSSIL_ISLAND_HILL_HOUSE_FIX(
			new AABB(3799, 3885, 3747, 3858)
	),
	FOSSIL_ISLAND_HILL_TEXTURE_FIX(
			new AABB(3845,3900, 3657, 3720)
	),

	// Karamja
	KARAMJA_VOLCANO_DUNGEON(2821, 9663, 2875, 9541),
	KARAMJA(
		new AABB(2686, 3257, 2809, 3124),
		new AABB(2809, 3124, 2917, 3209),
		new AABB(2917, 3184, 2964, 3132),
		new AABB(2746, 3151, 2973, 2873),
		new AABB(2973, 2873, 3012, 3081),
		new AABB(2496, 4542, 2642, 4606) // instance
	),

	// Zanaris
	COSMIC_ENTITYS_PLANE(2048, 4863, 2111, 4800),

	// islands
	VOID_KNIGHTS_OUTPOST(10537),
	PEST_CONTROL(10536),
	CRASH_ISLAND(11562),
	ENTRANA_GLASS_BUILDING_FIX(
		new AABB(2829,3347,2835,3353),
		new AABB(2829,3346,2829,3346),
		new AABB(2833,3346,2833,3346)
	),
	ENTRANA(
		new AABB(2798, 3396, 2873, 3326),
		new AABB(2873, 3326, 2882, 3344)
	),
	CRANDOR(2810, 3314, 2869, 3221),
	FISHING_PLATFORM(2756, 3295, 2799, 3268),
	MOS_LE_HARMLESS(3643, 3075, 3858, 2923),
	BRAINDEATH_ISLAND(
		new AABB(2112, 5041, 2176, 5182),
		new AABB(2176, 5118, 2240, 5182)
	),
	HARMONY(15148),
	DRAGONTOOTH_ISLAND(15159),
	ICEBERG(2622, 4091, 2686, 3974),
	ISLAND_OF_STONE(9790),
	MISCELLANIA(2485, 3923, 2629, 3080),
	WATERBIRTH_ISLAND(10042),
	NEITIZNOT(9275),
	JATIZSO(9531),
	FREMENNIK_ISLES(2299, 3907, 2436, 3769),
	UNGAEL(9023),
	PIRATES_COVE(8763),

	// Lunar Isle
	LUNAR_DIPLOMACY_DREAM_WORLD(
		new AABB(1728, 5055, 1792, 5120),
		new AABB(1800, 5066, 1850, 5109)
	),
	LUNAR_ISLE(2050, 3965, 2174, 3841),

	// Ape Atoll
	// Monkey Madness 2
	MM2_AIRSHIP_PLATFORM(2056, 5375, 2109, 5442),
	APE_ATOLL(2685, 2819, 2819, 2684),


	// Zeah
	KOUREND_CASTLE_ENTRANCE_FIX(1623, 3677, 1623, 3669),
	GREAT_KOUREND_STATUE(1641, 3678, 1631, 3668),
	HOSIDIUS_WELL(1764, 3600, 1761, 3597),
	HOSIDIUS_STAIRS(
		new AABB(1763, 3608, 1762, 3607)
	),
	// Fishing Trawler
	FISHING_TRAWLER_BOAT_PORT_KHAZARD(2669, 3183, 2673, 3166),
	FISHING_TRAWLER_BOAT_FLOODED(2012, 4826, 2021, 4824),
	FISHING_TRAWLER(
		new AABB(1792, 4863, 1855, 4734),
		new AABB(1855, 4893, 1920, 4798),
		new AABB(1920, 4863, 1990, 4798),
		new AABB(1990, 4917, 2047, 4798)
	),

	// Underwater areas
	MOGRE_CAMP_CUTSCENE(1832, 4776, 1934,4878),
	MOGRE_CAMP(2944, 9535, 3007, 9472),
	HARMONY_ISLAND_UNDERWATER_TUNNEL(3779, 9278, 3839, 9216, 1),
	FOSSIL_ISLAND_UNDERWATER_AREA(3712, 10303, 3839, 10240),

	// Runecrafting altars
	COSMIC_ALTAR(2112, 4799, 2176, 4863),
	DEATH_ALTAR(2176, 4799, 2240, 4863),
	CHAOS_ALTAR(2240, 4799, 2304, 4863),
	WRATH_ALTAR(2304, 4799, 2368, 4863),
	NATURE_ALTAR(2368, 4799, 2427, 4863),
	LAW_ALTAR(2427, 4799, 2495, 4863),
	BODY_ALTAR(2495, 4799, 2553, 4863),
	FIRE_ALTAR(2553, 4799, 2624, 4863),
	EARTH_ALTAR(2624, 4799, 2688, 4863),
	WATER_ALTAR(2688, 4799, 2752, 4863),
	MIND_ALTAR(2752, 4799, 2816, 4863),
	AIR_ALTAR(2816, 4799, 2880, 4863),
	TRUE_BLOOD_ALTAR(3200, 4862, 3262, 4800),

	// Dragon Slayer II
	LITHKREN_DUNGEON(
		new AABB(1528, 5125, 1606, 5052),
		new AABB(3571, 10498, 3527, 10400)
	),
	LITHKREN(3519, 4032, 3602, 3967),
	DS2_FLASHBACK_PLATFORM(1800, 5277, 1814, 5250),
	DS2_FLEET_ATTACKED(
		new AABB(1648,5480, 1750,5582),
		new AABB(8166, 16360, 8206, 16400)
	),
	DS2_SHIPS(1600, 5503, 1727,5758),

	// The Gauntlet
	THE_GAUNTLET(1856, 5632, 1919, 5695),
	THE_GAUNTLET_CORRUPTED(1920, 5632, 1983, 5695),
	THE_GAUNTLET_LOBBY(3025, 6131, 3040, 6116),

	// POHs
	PLAYER_OWNED_HOUSE_SNOWY(1984, 5696, 2047, 5767),
	PLAYER_OWNED_HOUSE(1856, 5696, 2047, 5767),

	// Blackhole
	BLACKHOLE(1616, 4728, 1623, 4735),

	// Camdozaal (Below Ice Mountain)
	CAMDOZAAL(2897, 5848, 3036, 5760),

	// Tempoross
	TEMPOROSS_COVE(3005, 3011, 3066, 2941),

	// Guardians of the Rift
	TEMPLE_OF_THE_EYE(3550, 9525, 3660, 9450),
	TEMPLE_OF_THE_EYE_ENTRANCE_FIX(3613,9471,3617,9481),

	// Death's office
	DEATHS_OFFICE(3166, 5734, 3185, 4288),

	// Theatre of Blood
	TOB_ROOM_MAIDEN(3231, 4468, 3152, 4416),
	TOB_ROOM_BLOAT(3260, 4474, 3327, 4427),
	TOB_ROOM_NYCOLAS(3274, 4226, 3318, 4290),
	TOB_ROOM_SOTETSEG(3295, 4288, 3264, 4336),
	TOB_ROOM_XARPUS(3191, 4406, 3151, 4368),
	TOB_ROOM_VERZIK(3152, 4332, 3186, 4296),
	TOB_ROOM_VAULT(3224, 4334, 3250, 4305),
	THEATRE_OF_BLOOD(3151, 4226, 3327, 4474),

	// Tombs of Amascut
	TOA_ENTRANCE_LOBBY(3375, 9135, 3344, 9102),
	TOA_PATH_HUB(3520, 5183, 3583, 5120),
	TOA_LOOT_ROOM(3648, 5183, 3711, 5120),
	TOA_FINAL_BOSS_PHASE_1(3776, 5183, 3839, 5120),
	TOA_FINAL_BOSS_PHASE_2(3904, 5183, 3967, 5120),
	TOA_FINAL_BOSS(TOA_FINAL_BOSS_PHASE_1, TOA_FINAL_BOSS_PHASE_2),
	TOA_PATH_OF_SCABARAS_PUZZLE(3520, 5311, 3583, 5248),
	TOA_PATH_OF_SCABARAS_BOSS(3520, 5439, 3583, 5376),
	TOA_PATH_OF_SCABARAS(TOA_PATH_OF_SCABARAS_PUZZLE, TOA_PATH_OF_SCABARAS_BOSS),
	TOA_PATH_OF_HET_PUZZLE(3648, 5311, 3711, 5248),
	TOA_PATH_OF_HET_BOSS(3648, 5439, 3711, 5376),
	TOA_PATH_OF_HET(TOA_PATH_OF_HET_PUZZLE, TOA_PATH_OF_HET_BOSS),
	TOA_PATH_OF_APMEKEN_PUZZLE(3776, 5311, 3839, 5248),
	TOA_PATH_OF_APMEKEN_BOSS(3776, 5439, 3839, 5376),
	TOA_PATH_OF_APMEKEN(TOA_PATH_OF_APMEKEN_PUZZLE, TOA_PATH_OF_APMEKEN_BOSS),
	TOA_PATH_OF_CRONDIS_PUZZLE(3904, 5311, 3967, 5248),
	TOA_PATH_OF_CRONDIS_BOSS(3904, 5439, 3967, 5376),
	TOA_PATH_OF_CRONDIS(TOA_PATH_OF_CRONDIS_PUZZLE, TOA_PATH_OF_CRONDIS_BOSS),

	TOA_CRONDIS_ISLAND_1(3942, 5403, 3942, 5413),
	TOA_CRONDIS_ISLAND_2(3941, 5400, 3926, 5416),
	TOA_CRONDIS_ISLAND_3(3939, 5399, 3939, 5417),
	TOA_CRONDIS_ISLAND_4(3938, 5399, 3929, 5418),
	TOA_CRONDIS_ISLAND_5(3925, 5403, 3923, 5413),
	TOA_CRONDIS_ISLAND(
		TOA_CRONDIS_ISLAND_1,
		TOA_CRONDIS_ISLAND_2,
		TOA_CRONDIS_ISLAND_3,
		TOA_CRONDIS_ISLAND_4,
		TOA_CRONDIS_ISLAND_5
	),

	TOA_CRONDIS_ISLAND_SUBMERGED(3944, 5402, 3943, 5415),

	TOA_CRONDIS_WATER_1(3957, 5387, 3948, 5429),
	TOA_CRONDIS_WATER_2(3947, 5389, 3943, 5427),
	TOA_CRONDIS_WATER_3(3942, 5387, 3921, 5429),
	TOA_CRONDIS_WATER_4(3920, 5388, 3920, 5428),
	TOA_CRONDIS_WATER_5(3919, 5389, 3916, 5427),
	TOA_CRONDIS_WATER_6(3915, 5390, 3915, 5426),
	TOA_CRONDIS_WATER_7(3914, 5391, 3914, 5425),
	TOA_CRONDIS_WATER_8(3913, 5392, 3913, 5424),
	TOA_CRONDIS_WATER_9(3912, 5398, 3912, 5418),
	TOA_CRONDIS_WATER_10(3911, 5399, 3906, 5417),
	TOA_CRONDIS_WATER(
		TOA_CRONDIS_WATER_1,
		TOA_CRONDIS_WATER_2,
		TOA_CRONDIS_WATER_3,
		TOA_CRONDIS_WATER_4,
		TOA_CRONDIS_WATER_5,
		TOA_CRONDIS_WATER_6,
		TOA_CRONDIS_WATER_7,
		TOA_CRONDIS_WATER_8,
		TOA_CRONDIS_WATER_9,
		TOA_CRONDIS_WATER_10
	),

//	TOA_RED_REGION_LEFT(3456, 5311, 3519, 5248),
//	TOA_RED_REGION_UPPER_RIGHT(3840, 5439, 3903, 5376),
//	TOA_GREY_REGION_RIGHT_1(3968, 5375, 4031, 5312),
//  TOA_GREY_REGION_RIGHT_2(3968, 5311, 4031, 5248),
//  TOA_GREY_REGION_RIGHT_3(3968, 5247, 4031, 5184),

	TOMBS_OF_AMASCUT(
		TOA_ENTRANCE_LOBBY,
		TOA_PATH_HUB,
		TOA_LOOT_ROOM,
		TOA_FINAL_BOSS_PHASE_1,
		TOA_FINAL_BOSS_PHASE_2,
		TOA_PATH_OF_SCABARAS_PUZZLE,
		TOA_PATH_OF_SCABARAS_BOSS,
		TOA_PATH_OF_HET_PUZZLE,
		TOA_PATH_OF_HET_BOSS,
		TOA_PATH_OF_APMEKEN_PUZZLE,
		TOA_PATH_OF_APMEKEN_BOSS,
		TOA_PATH_OF_CRONDIS_PUZZLE,
		TOA_PATH_OF_CRONDIS_BOSS
	),

	// Chambers of Xeric
	COX_SNOW(3262, 5341, 3360, 5373),
	CHAMBERS_OF_XERIC(
		new AABB(3120, 5694, 3360, 5760),
		new AABB(3262, 5118, 3360, 5694)
	),

	// Nightmare of Ashihama
	NIGHTMARE_OF_ASHIHAMA_ARENA(3840, 9920, 3903, 9983),

	// Pest Control
	PEST_CONTROL_LANDER_WATER_FIX(
		new AABB(2660, 2644, 2663, 2638),
		new AABB(2638, 2642, 2641, 2648),
		new AABB(2632, 2649, 2635, 2655),
		new AABB(2656, 2615, 2659, 2609)
	),

	// Barbarian Assault
	BARBARIAN_ASSAULT_WAITING_ROOMS(2571, 5252, 2616, 5305),

	TARNS_LAIR(
		new AABB(3136, 4544, 3391, 4608),
		new AABB(3168, 4639, 3196, 4604)
	),

	// Random events
	RANDOM_EVENT_CLASSROOM(1894, 5036, 1878, 5014),
	RANDOM_EVENT_FREAKY_FORESTER(2588, 4786, 2615, 4762),
	RANDOM_EVENT_GRAVEDIGGER(1920, 5007, 1935, 4992),
	RANDOM_EVENT_DRILL_DEMON(3136, 4799, 3200, 4863),
	RANDOM_EVENT_FROG_CAVE(2450, 4764, 2480, 4794),
	RANDOM_EVENT_PRISON_PETE(2059, 4479, 2111, 4447),

	// Clan halls
	CLAN_HALL(1730, 5442, 1789, 5501),

	// Standalone and miscellaneous areas
	LIGHTHOUSE(
		new AABB(2493, 3606, 2594, 3648),
		new AABB(2564, 3582, 2600, 3613)
	),
	SORCERESSS_GARDEN(2879, 5438, 2943, 5503),
	PURO_PURO(2561, 4350, 2622, 4289),
	RATCATCHERS_HOUSE(2821, 5059, 2875, 5114),
	CANOE_CUTSCENE(1791, 4479, 1856, 4543),
	FISHER_KINGS_REALM(2575, 4623, 2816, 4798),
	ENCHANTED_VALLEY(3010, 4478, 3073, 4540),
	GIANTS_FOUNDRY(3331,11456,3393,11520),
	ELID_CAVE(3325,9520,3395,9605),
	ANCIENT_CAVERN_UPPER(1728,5273,1790,5374),



	UNKNOWN_OVERWORLD_SNOWY(new AABB[]{}),
	UNKNOWN_OVERWORLD(
		new AABB(2303, 4542, 2368, 4607),
		new AABB(3392, 4927, 3456, 5055),
		new AABB(1920, 4990, 1984, 5055),
		new AABB(2111, 5502, 2176, 5567),
		new AABB(1729, 5440, 1790, 5501),
		new AABB(3647, 6014, 3775, 6142),
		new AABB(2111, 4931, 2166, 4990),
		new AABB(1599, 4778, 1728, 4863)
	),

	OVERWORLD(700, 2300, 4200, 4095),
	ALL(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE),
	NONE(0, 0, 0, 0),
	;

	private final AABB[] aabbs;

	Area(AABB... aabbs)
	{
		this.aabbs = aabbs;
	}

	Area(Area... areas)
	{
		this.aabbs = Arrays.stream(areas)
			.flatMap(a -> Arrays.stream(a.aabbs))
			.toArray(AABB[]::new);
	}

	Area(int pointAX, int pointAY, int pointBX, int pointBY)
	{
		aabbs = new AABB[]{new AABB(pointAX, pointAY, pointBX, pointBY)};
	}

	Area(int pointAX, int pointAY, int pointBX, int pointBY, int plane)
	{
		aabbs = new AABB[]{new AABB(pointAX, pointAY, pointBX, pointBY, plane)};
	}

	Area(int regionId)
	{
		final int REGIONS_PER_COLUMN = 256;

		int baseX = (int)Math.floor((float)regionId / REGIONS_PER_COLUMN) * Constants.REGION_SIZE;
		int baseY = (regionId % REGIONS_PER_COLUMN) * Constants.REGION_SIZE;
		int oppositeX = baseX + Constants.REGION_SIZE;
		int oppositeY = baseY + Constants.REGION_SIZE;
		aabbs = new AABB[]{new AABB(baseX, baseY, oppositeX, oppositeY)};
	}

	public boolean containsPoint(int pointX, int pointY, int pointZ)
	{
		for (AABB aabb : this.getAabbs())
		{
			if (aabb.contains(pointX, pointY, pointZ))
			{
				return true;
			}
		}
		return false;
	}
}
