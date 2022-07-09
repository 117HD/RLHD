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
import rs117.hd.utils.Rect;

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
		new Rect(3052, 3137, 3155, 3057),
		new Rect(3084, 3048, 3124, 3006), // the node
		new Rect(1600, 6015, 1792, 6207) // some kind of instance
	),

	// Lumbridge
	RFD_QUIZ(2589, 4618, 2566, 4642),
	LUM_BRIDGE(3240, 3226, 3250, 3225),
	LUMBRIDGE_CASTLE_BASEMENT(3205, 9613, 3220, 9626),
	LUMBRIDGE_CASTLE_ENTRYWAY(3213, 3212, 3216, 3225),
	LUMBRIDGE_CASTLE_DINING_ROOM(3205, 3218, 3212, 3226),
	HAM_HIDEOUT(3137, 9661, 3192, 9602),
	LUMBRIDGE_CASTLE(3216, 3230, 3204, 3207),
	LUMBRIDGE(
		new Rect(3136, 3137, 3254, 3327),
		new Rect(3254, 3189, 3263, 3200),
		new Rect(3266, 3200, 3247, 3327),
		new Rect(3271, 3330, 3264, 3322),
		new Rect(3400, 4807, 3448, 4847) // clan wars arena
	),

	// Dorgesh-Kaan
	DORGESHKAAN(
		new Rect(2687, 5376, 2752, 5247), // lower level
		new Rect(2751, 5440, 2816, 5311), // middle level
		new Rect(2815, 5504, 2880, 5375) // upper level
	),

	// Varrock
	VARROCK_MUSEUM_BASEMENT(
		new Rect(1729, 4929, 1790, 4990),
		new Rect(1601, 4929, 1663, 4990)
	),
	VARROCK_MUSEUM(3253, 3442, 3267, 3455),
	VARROCK_CASTLE(3200, 3458, 3226, 3500),
	VARROCK_JULIETS_HOUSE_FLOWER_BED(3161, 3450, 3171, 3444),
	VARROCK_JULIETS_HOUSE(3164, 3441, 3149, 3427),
	VARROCK_JOLLY_BOAR_INN(3272, 3486, 3288, 3510),
	VARROCK_CHURCH(3249, 3471, 3259, 3488),
	VARROCK_ANVILS(3185, 3420, 3190, 3427),
	VARROCK_BUILDING_RUINS(
		new Rect(3185, 3416, 3194, 3427),
		new Rect(3193, 3410, 3197, 3416),
		new Rect(3254, 3406, 3263, 3411)
	),
	VARROCK_EAST_BANK_CENTER(3251, 3420, 3256, 3422),
	VARROCK_EAST_BANK(3250, 3416, 3257, 3423),
	VARROCK_EAST_BANK_OUTSIDE_1(3250 ,3424 ,3257 ,3424),
	VARROCK(
		new Rect(3136, 3397, 3290, 3518),
		new Rect(3177, 3371, 3291, 3410)
	),
	// A Soul's Bane
	TOLNA_DUNGEON_ANGER(
		new Rect(3008, 5216, 3039, 5247),
		new Rect(2963, 5228, 2995, 5198),
		new Rect(3264, 9823, 3298, 9855)
	),
	TOLNA_DUNGEON_FEAR(
		new Rect(3040, 5216, 3071, 5247),
		new Rect(3072, 5184, 3103, 5215),
		new Rect(3008, 5184, 3039, 5215),
		new Rect(3264, 9792, 3296, 9822),
		new Rect(3299, 9823, 3327, 9855)
	),
	TOLNA_DUNGEON_CONFUSION(
		new Rect(3040, 5184, 3071, 5215),
		new Rect(3297, 9792, 3327, 9822)
	),

	// Digsite
	DIGSITE_DOCK(3348, 3460, 3404, 3444),

	// Draynor
	DRAYNOR_MANOR_INTERIOR(
		new Rect(3091, 3363, 3096, 3353),
		new Rect(3097, 3374, 3119, 3353),
		new Rect(3120, 3360, 3126, 3353)
	),
	DRAYNOR_MANOR(3083, 3386, 3129, 3329),
	DRAYNOR_MANOR_BASEMENT(
		new Rect(3073, 9780, 3082, 9766)
	),
	LUMBRIDGE_DRAYNOR_PATH_BLENDING_FIX_1(3138,3304,3186,3202),
	DRAYNOR(
		new Rect(3071, 3226, 3133, 3292),
		new Rect(2112, 4893, 2166, 4930) // bank robbery cutscene
	),

	// Misthalin Mystery
	MISTHALIN_MYSTERY_MANOR(1600, 4863, 1727, 4779),

	// Falador
	FALADOR_EAST_BANK_PATH_FIX_2(3006, 3348, 3006, 3346),
	FALADOR_EAST_BANK_PATH_FIX_1(3006, 3346, 3006, 3344),
	FALADOR_HAIRDRESSER(
		new Rect(2941, 3389, 2946, 3376),
		new Rect(2946, 3376, 2949, 3382)
	),
	FALADOR_PARTY_ROOM(3034, 3387, 3057, 3369),
	FALADOR(
		new Rect(2932, 3306, 3068, 3401),
		new Rect(3456, 4734, 3528, 4783)
	),

	MOTHERLODE_MINE(
		new Rect(3713, 5696, 3776, 5633),
		new Rect(3827, 5692, 3868, 5652)
	),

	// Edgeville
	EDGEVILLE_PATH_OVERLAY(
		new Rect(3087, 3501, 3099, 3502), // path north of bank
		new Rect(3079, 3502, 3085, 3503), // path between bank and general store
		new Rect(3105, 3508, 3113, 3507), // path south of the prison
		new Rect(3112, 3514, 3119, 3515), // path east of prison
		new Rect(3120, 3517, 3129, 3516), // path west of bridge
		new Rect(3119, 3516, 3120, 3515), // prison-bridge path join
		new Rect(3107, 3508, 3108, 3502), // path to north side of furnace
		new Rect(3079, 3502, 3080, 3501), // path to dave's house
		new Rect(3079, 3506, 3080, 3504), // path to general store
		new Rect(3100, 3496, 3099, 3501), // path east of bank
		new Rect(3104, 3498, 3103, 3499), // path west side of furnace 1
		new Rect(3103, 3499, 3102, 3500), // path west side of furnace 2
		new Rect(3101, 3500, 3102, 3501), // path west side of furnace 3
		new Rect(3084, 3502, 3088, 3501), // between well and bank
		new Rect(3113, 3514, 3114, 3509), // south prison join 1
		new Rect(3112, 3509, 3114, 3508), // south prison join 2
		new Rect(3101, 3510, 3103, 3509), // path to central building 1
		new Rect(3104, 3509, 3103, 3508), // path to central building 2
		new Rect(3104, 3507, 3105, 3506), // diagonal 1
		new Rect(3103, 3506, 3104, 3505), // diagonal 2
		new Rect(3102, 3505, 3103, 3504), // diagonal 3
		new Rect(3101, 3504, 3102, 3503), // diagonal 4
		new Rect(3100, 3503, 3101, 3502)  // diagonal 5
	),
	EDGEVILLE_BANK(3098, 3499, 3090, 3488),
	EDGEVILLE_BANK_SURROUNDING(3087, 3502, 3098, 3483),
	EDGEVILLE_DORIS_HOUSE(3077, 3496, 3081, 3489),
	EDGEVILLE_MONASTERY(3041, 3509, 3062, 3471),

	// Seers
	SEERS_BANK(2719, 3497, 2730, 3487),

	// White Wolf Mountain
	WHITE_WOLF_MOUNTAIN(
		new Rect(2789, 3530, 2879, 3488),
		new Rect(2832, 3439, 2879, 3502),
		new Rect(2431, 5374, 2496, 5439) // instance
	),

	// Keep Le Faye
	KEEP_LE_FAYE(
		new Rect(2747, 3415, 2782, 3389),
		new Rect(1670, 4228, 1722, 4279) // instance
	),

	// Catherby
	CATHERBY_BEACH_OBELISK_WATER_FIX(2843, 3423, 2845, 3421),
	CATHERBY_BEACH_LADDER_FIX(2842, 3424, 2842, 3424),
	CATHERBY_BEACH_SHORELINE_FIX(
		new Rect(2870, 3414, 2869, 3416),
		new Rect(2865, 3420, 2864, 3423),
		new Rect(2863, 3423, 2851, 3426),
		new Rect(2848, 3427, 2848, 3428),
		new Rect(2847, 3429, 2847, 3429),
		new Rect(2845, 3430, 2843, 3430)
	),
	CATHERBY_BANK(2806, 3445, 2812, 3438),
	CATHERBY(
		new Rect(2788, 3407, 2864, 3435),
		new Rect(2855, 3435, 2788, 3447),
		new Rect(2788, 3447, 2839, 3474)
	),

	// South Falador
	RIMMINGTON(
		new Rect(2905, 3265, 2995, 3195),
		new Rect(2989, 3195, 2945, 3186)
	),
	PORT_SARIM(3005, 3265, 3064, 3174),
	MUDSKIPPER_POINT(2977, 3132, 3008, 3102),
	SOUTH_FALADOR_FARM(3011, 3322, 3069, 3279),
	CRAFTING_GUILD(2910, 3296, 2945, 3265),
	// entire area south of falador, encompassing all of the above
	SOUTH_FALADOR(
		new Rect(2900, 3308, 3069, 3195),
		new Rect(3060, 3195, 2971, 3100),
		new Rect(2980, 3185, 2924, 3202)
	),

	// Burthorpe
	HEROES_GUILD(
		new Rect(2892, 3507, 2898, 3514),
		new Rect(2894, 3504, 2896, 3517),
		new Rect(2899, 3509, 2901, 3512)
	),
	WARRIORS_GUILD(2837, 3557, 2877, 3533),
	WARRIORS_GUILD_FLOOR_2(2837, 3557, 2877, 3533, 1),
	BURTHORPE(
		new Rect(2830, 3533, 2938, 3576),
		new Rect(2938, 3576, 2880, 3581),
		new Rect(2838, 3538, 2928, 3553),
		new Rect(2873, 3533, 2935, 3521)
	),
	GAMES_ROOM_INNER(2221, 4973, 2194, 4946),
	GAMES_ROOM(2179, 4990, 2239, 4929),

	// Ardougne
	WEST_ARDOUGNE(
		new Rect(2460, 3335, 2558, 3279),
		new Rect(2558, 3279, 2510, 3264),
		new Rect(2429, 3323, 2466, 3305)
	),
	EAST_ARDOUGNE(
		new Rect(2558, 3342, 2686, 3257),
		new Rect(3328, 5887, 3392, 5951) // SOTE cutscene
	),
	EAST_ARDOUGNE_CASTLE_DIRT_FIX(
		new Rect(2565, 3279, 2592, 3313)
	),
	EAST_ARDOUGNE_CASTLE_PATH_FIX(
		new Rect(2585, 3298, 2593, 3314)
	),

	// Yanille
	YANILLE_BANK(2609, 3088, 2616, 3097),
	YANILLE(
		new Rect(2531, 3127, 2622, 3070),
		new Rect(2880, 4671, 2944, 4735) // instance
	),
	GUTANOTH_CAVE(2560, 9408, 2626, 9475),
	// Nightmare Zone
	NIGHTMARE_ZONE(2241, 4676, 2303, 4722),

	// Castle Wars
	CASTLE_WARS_LOBBY(2434, 3104, 2448, 3080),
	CASTLE_WARS_ARENA_SARADOMIN_SIDE(
		new Rect(2435, 3068, 2373, 3086),
		new Rect(3282, 3086, 2435, 3091),
		new Rect(2435, 3091, 2388, 3093),
		new Rect(2404, 3093, 2435, 3095),
		new Rect(2435, 3095, 2407, 3098),
		new Rect(2409, 3098, 2435, 3117),
		new Rect(2435, 3117, 2414, 3123),
		new Rect(2420, 3123, 2430, 3127),
		new Rect(2400, 3104, 2411, 3092),
		new Rect(2400, 3113, 2411, 3103)
	),
	CASTLE_WARS_ARENA_ZAMORAK_SIDE(
		new Rect(2364, 3139, 2423, 3125),
		new Rect(2417, 3125, 2364, 3120),
		new Rect(2364, 3120, 2412, 3114),
		new Rect(2400, 3114, 2364, 3104),
		new Rect(2364, 3105, 2399, 3093),
		new Rect(2384, 3093, 2354, 3087),
		new Rect(2364, 3087, 2374, 3082)
	),
	CASTLE_WARS_ARENA(2364, 3139, 2435, 3068),
	CASTLE_WARS_UNDERGROUND(2444, 9544, 2361, 9473),
	CASTLE_WARS(
		new Rect(2364, 3139, 2435, 3068),
		new Rect(2434, 3104, 2448, 3080)
	),

	// Last Man Standing
	LMS_ARENA_WILD_VARROCK(
		new Rect(3455, 6015, 3647, 6205),
		new Rect(3520, 5886, 3648, 6015)
	),
	LMS_ARENA_DESERTED_ISLAND(3391, 5759, 3520, 5951),

	// Kharidian desert
	SMOKE_DUNGEON(3198, 9409, 3329, 9341),
	DUEL_ARENA(3326, 3290, 3407, 3199),
	SHANTAY_PASS(3294, 3135, 3311, 3114),
	MAGE_TRAINING_ARENA(3347, 3327, 3374, 3288),
	AL_KHARID_BUILDINGS(
		new Rect(3265, 3173, 3272, 3161),
		new Rect(3270, 3194, 3279, 3179),
		new Rect(3285, 3192, 3290, 3187),
		new Rect(3289, 3206, 3296, 3202),
		new Rect(3297, 3194, 3306, 3185),
		new Rect(3319, 3197, 3323, 3191),
		new Rect(3312, 3186, 3318, 3173),
		new Rect(3313, 3165, 3318, 3160),
		new Rect(3282, 3177, 3303, 3159)
	),
	AL_KHARID(
		new Rect(3276, 3265, 3337, 3195),
		new Rect(3259, 3201, 3345, 3135),
		new Rect(3253, 3182, 3265, 3155)
	),
	EAST_AL_KHARID(
		new Rect(3344, 3200, 3384, 3129),
		new Rect(3384, 3141, 3391, 3203),
		new Rect(3391, 3203, 3412, 3164),
		new Rect(3412, 3170, 3426, 3197)
	),
	AL_KHARID_MINE(3270, 3322, 3337, 3258),
	DESERT_MINING_CAMP(3272, 3042, 3306, 3011),
	KHARIDIAN_DESERT_DEEP(
		new Rect(3198, 2989, 3322, 2817),
		new Rect(3315, 2928, 3469, 2812)
	),
	KHARIDIAN_DESERT_MID(
		new Rect(3135, 3051, 3524, 2885)
	),
	KHARIDIAN_DESERT(
		new Rect(3196, 3134, 3526, 2997),
		new Rect(3134, 3069, 3565, 2600),
		new Rect(3114, 2974, 3216, 2786),
		new Rect(3008, 4671, 3072, 4734) // agility pyramid instance
	),
	KHARID_DESERT_REGION(
		new Rect(3268, 3326, 3345, 3178),
		new Rect(3255, 3188, 3433, 3119),
		new Rect(3319, 3288, 3411, 3196),
		new Rect(3432, 3202, 3338, 3122),
		new Rect(3376, 3321, 3401, 3200),
		new Rect(3421, 3263, 3382, 3191),
		new Rect(3200, 3134, 3533, 3044),
		new Rect(3484, 3164, 3399, 3129),
		new Rect(3399, 3129, 3450, 3179),
		new Rect(3169, 3072, 3527, 3023),
		new Rect(3142, 3063, 3527, 2842),
		new Rect(3127, 3021, 3538, 2590)
	),
	DESERT_TREASURE_PYRAMID(
		new Rect(3198, 9339, 3267, 9275),
		new Rect(2755, 4934, 2812, 4982),
		new Rect(2825, 4976, 2873, 4938),
		new Rect(2894, 4972, 2933, 4941)
	),
	PYRAMID_PLUNDER(
		new Rect(1922, 4470, 1945, 4447),
		new Rect(1955, 4441, 1980, 4417),
		new Rect(1955, 4471, 1980, 4445),
		new Rect(1922, 4440, 1946, 4416),
		new Rect(1919, 4481, 1988, 4414)
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
		new Rect(3682, 3257, 3682, 3257),
		new Rect(3681, 3256, 3681, 3256),
		new Rect(3684, 3259, 3678, 3263),
		new Rect(3683, 3258, 3683, 3258)
	),
	VER_SINHAZA(
		new Rect(3641, 3236, 3684, 3202),
		new Rect(2087, 4903, 2064, 4880) // cutscene
	),
	MEIYERDITCH(3583, 3331, 3648, 3169),
	CASTLE_DRAKAN(3520, 3388, 3594, 3328),
	DARKMEYER(
		new Rect(3590, 3399, 3636, 3330),
		new Rect(3636, 3330, 3662, 3392),
		new Rect(3662, 3384, 3669, 3335)
	),
	PORT_PHASMATYS(
		new Rect(3649, 3508, 3688, 3456),
		new Rect(3688, 3456, 3710, 3484),
		new Rect(3670, 3503, 3684, 3514)
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
		new Rect(3422, 3202, 3782, 3467),
		new Rect(3775, 3467, 3426, 3603),
		new Rect(3426, 3603, 3399, 3511),
		new Rect(3410, 3524, 3457, 3500),
		new Rect(3457, 3500, 3416, 3495),
		new Rect(3420, 3495, 3451, 3481),
		new Rect(3451, 3481, 3424, 3435),
		new Rect(3424, 3435, 3403, 3462),
		new Rect(3422, 3442, 3399, 3321),
		new Rect(3414, 3327, 3435, 3255),
		new Rect(3469, 3209, 3726, 3161),
		new Rect(2087, 4903, 2064, 4880), // ver sinhaza cutscene
		new Rect(1987, 4996, 2105, 5054), // temple trekking
		new Rect(2118, 4994, 2171, 5036), // temple trekking
		new Rect(2178, 4996, 2478, 5054), // temple trekking
		new Rect(1670, 4546, 1724, 4600) // slayer tower roof (grotesque guardians)
	),

	// TzHaar
	THE_INFERNO(
		new Rect(2257, 5363, 2292, 5319),
		new Rect(2466, 5053, 2523, 4994)
	),
	TZHAAR(2367, 5184, 2559, 4993),

	TREE_GNOME_STRONGHOLD(
		new Rect(2368, 3525, 2496, 3387),
		new Rect(2404, 3547, 2431, 3511),
		new Rect(2484, 3406, 2507, 3387),
		new Rect(1920, 5502, 2048, 5631) // instance
	),

	// Wilderness
	REVENANT_CAVES(3265, 10243, 3134, 10050),
	FROZEN_WASTE_PLATEAU(
		new Rect(2939, 3970, 2988, 3904),
		new Rect(2988, 3907, 3002, 3940),
		new Rect(2980, 3909, 2939, 3865),
		new Rect(2939, 3865, 2958, 3834)
	),
	WILDERNESS_HIGH(2939, 3974, 3391, 3903),
	WILDERNESS_MID_HIGH(2939, 3903, 3391, 3806),
	WILDERNESS_MID(2939, 3806, 3391, 3730),
	WILDERNESS_MID_LOW(2939, 3730, 3391, 3558),
	WILDERNESS_LOW(2939, 3558, 3391, 3522),
	WILDERNESS(2939, 3974, 3391, 3522),
	MAGE_ARENA_BANK(2527, 4725, 2549, 4708),
	GIELINOR_SNOWY_NORTHERN_REGION(
		new Rect(2942, 3711, 2748, 3980),
		new Rect(2696, 3839, 2741, 3799),
		new Rect(2714, 3803, 2757, 3767),
		new Rect(2723, 3743, 2758, 3710)
	),

	// Fremennik Province
	MOUNTAIN_CAMP_LAKE(
		new Rect(2754, 3707, 2789, 3675),
		new Rect(2789, 3682, 2802, 3708)
	),
	MOUNTAIN_CAMP_ENTRY_PATH(2780, 3673, 2766, 3655),
	MOUNTAIN_CAMP(
		new Rect(2750, 3711, 2815, 3671),
		new Rect(2815, 3671, 2779, 3652)
	),
	RELLEKKA(2594, 3646, 2692, 3747),
	FREMENNIK_PROVINCE(
		new Rect(2574, 3750, 2800, 3585),
		new Rect(2800, 3642, 2822, 3717),
		new Rect(2686, 3811, 2751, 3724)
	),

	// Tirannwn
	GWENITH(2187, 3424, 2229, 3397),
	PRIFDDINAS(3136, 5952, 3391, 6207),
	MYNYDD(2119, 3453, 22112, 3384),
	LLETYA(2313, 3147, 2363, 3194),
	POISON_WASTE(
		new Rect(2166, 3119, 2315, 3025),
		new Rect(2181, 3117, 2280, 3131)
	),
	ARANDAR(2309, 3337, 2394, 3234),
	SOTE_GRAND_LIBRARY(2752, 6080, 2879, 6207),
	SOTE_LLETYA_ON_FIRE(2881, 6206, 2937, 6152),
	SOTE_LLETYA_SMALL_FIRES(2730, 6080, 2820, 6160),
	SOTE_TEMPLE_OF_LIGHT_SEREN_CUTSCENE(3237, 5913, 3219, 5933),
	SOTE_FRAGMENT_OF_SEREN_ARENA(3264, 5887, 3328, 5951),
	TIRANNWN(
		new Rect(2116, 3455, 2320, 3021),
		new Rect(2295, 3202, 2365, 3140),
		new Rect(2688, 6015, 2944, 6207) // SOTE cutscene
	),

	ZANARIS(2315, 4345, 2500, 4485),

	GOD_WARS_DUNGEON(
		new Rect(2816, 5375, 2971, 5216),
		new Rect(2848, 5199, 2948, 5153), // ancient prison
		new Rect(2848, 5246, 2948, 5185), // ancient prison
		new Rect(3008, 10178, 3072, 10112) // wilderness dungeon
	),

	// Soul Wars
	ISLE_OF_SOULS(2079, 3014, 2348, 2763),
	SOUL_WARS_ARENA(2113, 2946, 2302, 2878),
	SOUL_WARS_RED_BASE(
		new Rect(2272, 2946, 2302, 2910),
		new Rect(2297, 2910, 2268, 2884),
		new Rect(2273, 2891, 2240, 2938),
		new Rect(2240, 2938, 2260, 2882)
	),
	SOUL_WARS_BLUE_BASE(
		new Rect(2116, 2881, 2145, 2941),
		new Rect(2145, 2941, 2177, 2889)
	),

	ISLE_OF_SOULS_TUTORIAL(1855, 5823, 2175, 6078),
	SOUL_WARS_ARENA_TUTORIAL(1921, 6018, 2110, 5950),
	SOUL_WARS_RED_BASE_TUTORIAL(
		new Rect(2080, 6018, 2110, 5982),
		new Rect(2105, 5982, 2076, 5956),
		new Rect(2081, 5963, 2048, 6010),
		new Rect(2048, 6010, 2068, 5954))
	,
	SOUL_WARS_BLUE_BASE_TUTORIAL(
		new Rect(1924, 5953, 1953, 6013),
		new Rect(1953, 6013, 1985, 5961)
	),

	// Zeah
	KARUULM_SLAYER_DUNGEON(1112, 10295, 1384, 10124),
	MOUNT_KARUULM(1245,3765,1358,3860),
	LIZARDMAN_TEMPLE(1280, 10047, 1341, 10109),
	XERICS_LOOKOUT(1580, 3526, 1596, 3534),
	SHAYZIEN_COMBAT_RING(1539, 3627, 1548, 3618),
	SHAYZIEN_ENCAMPMENT(
		new Rect(1467, 3678, 1540, 3607),
		new Rect(1540, 3613, 1559, 3650)
	),
	SHAYZIEN_SHAYZIA_RUIN(1577, 3587, 1594, 3604),
	SHAYZIEN_GRAVEYARD_OF_HEROES(1472, 3583, 1515, 3541),
	SHAYZIEN(
		new Rect(1467, 3678, 1540, 3607), // encampment
		new Rect(1540, 3613, 1559, 3650), // encampment
		new Rect(1477, 3616, 1525, 3584),
		new Rect(1460, 3599, 1574, 3537),
		new Rect(1574, 3537, 1535, 3523),
		new Rect(1595, 3584, 1567, 3606) // shayzia ruin
	),
	HOSIDIUS(1720, 3641, 1802, 3560),
	KOUREND_CATACOMBS(1600, 10111, 1732, 9977),
	BLOOD_ALTAR(
		new Rect(1703, 3844, 1739, 3815),
		new Rect(1739, 3817, 1751, 3839),
		new Rect(1751, 3833, 1791, 3815)
	),
	DARK_ALTAR(1660, 3904, 1747, 3864),
	ARCEUUS(
		new Rect(1572, 3838, 1597, 3799),
		new Rect(1566, 3832, 1586, 3805),
		new Rect(1562, 3823, 1570, 3811),
		new Rect(1588, 3838, 1608, 3778),
		new Rect(1598, 3854, 1730, 3731),
		new Rect(1730, 3731, 1623, 3710),
		new Rect(1616, 3874, 1658, 3842),
		new Rect(1625, 3898, 1856, 3817)
	),
	ZEAH_SNOWY_NORTHERN_REGION(
		new Rect(1493, 3905, 1870, 4061),
		new Rect(1518, 3908, 1608, 3875),
		new Rect(1595, 3875, 1532, 3859),
		new Rect(1532, 3859, 1582, 3847),
		new Rect(1633, 3895, 1591, 3910)
	),
	LOVAKENGJ(
		new Rect(1416, 3899, 1521, 3835),
		new Rect(1560, 3840, 1420, 3787),
		new Rect(1434, 3787, 1586, 3755),
		new Rect(1595, 3767, 1448, 3737),
		new Rect(1460, 3737, 1598, 3719)
	),
	MOUNT_QUIDAMORTEM(
		new Rect(1194, 3594, 1292, 3520),
		new Rect(1287, 3556, 1300, 3596),
		new Rect(1300, 3596, 1308, 3580),
		new Rect(1230, 3611, 1269, 3588)
	),
	KEBOS_LOWLANDS(
		new Rect(1167, 3664, 1345, 3582),
		new Rect(1292, 3588, 1355, 3526),
		new Rect(1275, 3692, 1329, 3631)
	),
	MESS_HALL_KITCHEN(1643, 3631, 1649, 3622),
	ZEAH(1152, 4078, 1938, 3270),

	// Fossil Island
	FOSSIL_ISLAND_CENTRAL_BANK_FIX(3744, 3805, 3742, 3802),
	FOSSIL_ISLAND_HILL_HOUSE_FIX(3799, 3885, 3747, 3858),
	TAR_SWAMP(
		new Rect(3712, 3800, 3632, 3694),
		new Rect(3697, 3809, 3631, 3782)
	),
	FOSSIL_ISLAND(3626, 3908, 3851, 3693),

	// Karamja
	KARAMJA_VOLCANO_DUNGEON(2821, 9663, 2875, 9541),
	KARAMJA(
		new Rect(2686, 3257, 2809, 3124),
		new Rect(2809, 3124, 2917, 3209),
		new Rect(2917, 3184, 2964, 3132),
		new Rect(2746, 3151, 2973, 2873),
		new Rect(2973, 2873, 3012, 3081),
		new Rect(2496, 4542, 2642, 4606) // instance
	),

	// Zanaris
	COSMIC_ENTITYS_PLANE(2048, 4863, 2111, 4800),

	// islands
	VOID_KNIGHTS_OUTPOST(10537),
	PEST_CONTROL(10536),
	CRASH_ISLAND(11562),
	ENTRANA_GLASS_BUILDING_FIX(
		new Rect(2829,3347,2835,3353),
		new Rect(2829,3346,2829,3346),
		new Rect(2833,3346,2833,3346)
	),
	ENTRANA(
		new Rect(2798, 3396, 2873, 3326),
		new Rect(2873, 3326, 2882, 3344)
	),
	CRANDOR(2810, 3314, 2869, 3221),
	FISHING_PLATFORM(2756, 3295, 2799, 3268),
	MOS_LE_HARMLESS(3643, 3075, 3858, 2923),
	BRAINDEATH_ISLAND(
		new Rect(2112, 5041, 2176, 5182),
		new Rect(2176, 5118, 2240, 5182)
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
		new Rect(1728, 5055, 1792, 5120),
		new Rect(1800, 5066, 1850, 5109)
	),
	LUNAR_ISLE(2050, 3965, 2174, 3841),

	// Ape Atoll
	// Monkey Madness 2
	MM2_AIRSHIP_PLATFORM(2056, 5375, 2109, 5442),
	APE_ATOLL(2685, 2819, 2819, 2684),

	// Fishing Trawler
	FISHING_TRAWLER_BOAT_PORT_KHAZARD(2669, 3183, 2673, 3166),
	FISHING_TRAWLER_BOAT_FLOODED(2012, 4826, 2021, 4824),
	FISHING_TRAWLER(
		new Rect(1792, 4863, 1855, 4734),
		new Rect(1855, 4893, 1920, 4798),
		new Rect(1920, 4863, 1990, 4798),
		new Rect(1990, 4917, 2047, 4798)
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
		new Rect(1528, 5125, 1606, 5052),
		new Rect(3571, 10498, 3527, 10400)
	),
	LITHKREN(3519, 4032, 3602, 3967),
	DS2_FLASHBACK_PLATFORM(1800, 5277, 1814, 5250),
	DS2_FLEET_ATTACKED(
		new Rect(1648,5480, 1750,5582),
		new Rect(8166, 16360, 8206, 16400)
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

	// Chambers of Xeric
	COX_SNOW(3262, 5341, 3360, 5373),
	CHAMBERS_OF_XERIC(
		new Rect(3120, 5694, 3360, 5760),
		new Rect(3262, 5118, 3360, 5694)
	),

	// Nightmare of Ashihama
	NIGHTMARE_OF_ASHIHAMA_ARENA(3840, 9920, 3903, 9983),

	// Pest Control
	PEST_CONTROL_LANDER_WATER_FIX(
		new Rect(2660, 2644, 2663, 2638),
		new Rect(2638, 2642, 2641, 2648),
		new Rect(2632, 2649, 2635, 2655),
		new Rect(2656, 2615, 2659, 2609)
	),

	// Barbarian Assault
	BARBARIAN_ASSAULT_WAITING_ROOMS(2571, 5252, 2616, 5305),

	TARNS_LAIR(
		new Rect(3136, 4544, 3391, 4608),
		new Rect(3168, 4639, 3196, 4604)
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
		new Rect(2493, 3606, 2594, 3648),
		new Rect(2564, 3582, 2600, 3613)
	),
	SORCERESSS_GARDEN(2879, 5438, 2943, 5503),
	PURO_PURO(2561, 4350, 2622, 4289),
	RATCATCHERS_HOUSE(2821, 5059, 2875, 5114),
	CANOE_CUTSCENE(1791, 4479, 1856, 4543),
	FISHER_KINGS_REALM(2575, 4623, 2816, 4798),
	ENCHANTED_VALLEY(3010, 4478, 3073, 4540),
	GIANTS_FOUNDRY(3331,11456,3393,11520),
	ELID_CAVE(3325,9520,3395,9605),



	UNKNOWN_OVERWORLD_SNOWY(),
	UNKNOWN_OVERWORLD(
		new Rect(2303, 4542, 2368, 4607),
		new Rect(3392, 4927, 3456, 5055),
		new Rect(1920, 4990, 1984, 5055),
		new Rect(2111, 5502, 2176, 5567),
		new Rect(1729, 5440, 1790, 5501),
		new Rect(3647, 6014, 3775, 6142),
		new Rect(2111, 4931, 2166, 4990),
		new Rect(1599, 4778, 1728, 4863)
	),

	OVERWORLD(700, 2300, 4200, 4095),
	ALL(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE),
	NONE(0, 0, 0, 0),
	;

	private final Rect[] rects;

	Area(Rect... rects)
	{
		this.rects = rects;
	}

	Area(int pointAX, int pointAY, int pointBX, int pointBY)
	{
		this.rects = new Rect[]{new Rect(pointAX, pointAY, pointBX, pointBY)};
	}

	Area(int pointAX, int pointAY, int pointBX, int pointBY, int plane)
	{
		this.rects = new Rect[]{new Rect(pointAX, pointAY, pointBX, pointBY, plane)};
	}

	Area(int regionId)
	{
		final int REGIONS_PER_COLUMN = 256;

		int baseX = (int)Math.floor((float)regionId / REGIONS_PER_COLUMN) * Constants.REGION_SIZE;
		int baseY = (regionId % REGIONS_PER_COLUMN) * Constants.REGION_SIZE;
		int oppositeX = baseX + Constants.REGION_SIZE;
		int oppositeY = baseY + Constants.REGION_SIZE;
		this.rects = new Rect[]{new Rect(baseX, baseY, oppositeX, oppositeY)};
	}

	public boolean containsPoint(int pointX, int pointY, int pointZ)
	{
		for (Rect rect : this.getRects())
		{
			if (rect.containsPoint(pointX, pointY, pointZ))
			{
				return true;
			}
		}
		return false;
	}
}
