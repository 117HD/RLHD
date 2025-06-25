// This is kept separate from constants.glsl to avoid accidentally breaking compatibility with macOS
#define MATERIAL_FLAG_VANILLA_UVS 1
#define MATERIAL_FLAG_WIND_SWAYING 6

#define WIND_DISPLACEMENT_NOISE_RESOLUTION 0.04

#include UNDO_VANILLA_SHADING
#include LEGACY_GREY_COLORS

#include WIND_ENABLED