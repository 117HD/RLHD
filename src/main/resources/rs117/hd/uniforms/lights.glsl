#pragma once

#include MAX_LIGHT_COUNT
#include TILED_LIGHTING_USE_SUBGROUP

#define TILED_LIGHTING_USE_SUBGROUP 0

#if TILED_LIGHTING_USE_SUBGROUP
#extension GL_KHR_shader_subgroup_basic : enable
#extension GL_KHR_shader_subgroup_ballot : enable
#endif

struct PointLight
{
    vec4 position;
    vec3 color;
};

layout(std140) uniform PointLightUniforms {
    PointLight PointLightArray[MAX_LIGHT_COUNT];
};

#include LIGHT_GETTER
