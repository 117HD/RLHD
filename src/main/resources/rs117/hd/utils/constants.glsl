#pragma once

// Any changes here may need to be reflected in OpenCL's constants.cl
// They are kept separate to avoid accidentally breaking OpenCL compatibility
#define SHADOW_OPACITY_THRESHOLD 0.81
#define MATERIAL_FLAG_BITS 12
#define MATERIAL_FLAG_DISABLE_SHADOW_RECEIVING 5
#define MATERIAL_FLAG_DISABLE_SHADOW_CASTING 4
#define MATERIAL_FLAG_FLAT_NORMALS 3
#define MATERIAL_FLAG_WORLD_UVS 2
#define MATERIAL_FLAG_IS_VANILLA_TEXTURED 1
#define MATERIAL_FLAG_IS_OVERLAY 0
