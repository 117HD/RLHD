#pragma once

// Any changes here may need to be reflected in OpenCL's constants.cl
// They are kept separate to avoid accidentally breaking OpenCL compatibility
#define MATERIAL_INDEX_SHIFT 12
#define MATERIAL_SHADOW_OPACITY_THRESHOLD_SHIFT 5
#define MATERIAL_FLAG_DISABLE_SHADOW_RECEIVING 4
#define MATERIAL_FLAG_FLAT_NORMALS 3
#define MATERIAL_FLAG_WORLD_UVS 2
#define MATERIAL_FLAG_IS_VANILLA_TEXTURED 1
#define MATERIAL_FLAG_IS_OVERLAY 0

#include SHADOW_MODE
#define SHADOW_MODE_OFF 0
#define SHADOW_MODE_FAST 1
#define SHADOW_MODE_DETAILED 2

#define SHADOW_DEPTH_BITS 16
#define SHADOW_ALPHA_BITS 8
#define SHADOW_COMBINED_BITS (SHADOW_DEPTH_BITS + SHADOW_ALPHA_BITS)
#define SHADOW_DEPTH_MAX ((1 << SHADOW_DEPTH_BITS) - 1)
#define SHADOW_ALPHA_MAX ((1 << SHADOW_ALPHA_BITS) - 1)
#define SHADOW_COMBINED_MAX ((1 << SHADOW_COMBINED_BITS) - 1)

#include SHADOW_TRANSPARENCY
#if SHADOW_TRANSPARENCY
#define SHADOW_DEFAULT_OPACITY_THRESHOLD 0.01 // Remove shadows from clickboxes
#else
#define SHADOW_DEFAULT_OPACITY_THRESHOLD 0.71 // Lowest while keeping Prifddinas glass walkways transparent
#endif

#include VANILLA_COLOR_BANDING
