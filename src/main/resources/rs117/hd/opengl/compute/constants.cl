// This is kept separate from constants.glsl to avoid accidentally breaking compatibility with macOS
#pragma once

#define MATERIAL_FLAG_WIND_SWAYING 7
#define MATERIAL_FLAG_TERRAIN_VERTEX_SNAPPING 6
#define MATERIAL_FLAG_VANILLA_UVS 1

#include UNDO_VANILLA_SHADING
#include LEGACY_GREY_COLORS

#include WIND_DISPLACEMENT_NOISE_RESOLUTION
#include WIND_DISPLACEMENT
#include CHARACTER_DISPLACEMENT
