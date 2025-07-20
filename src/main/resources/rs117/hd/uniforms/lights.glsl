#pragma once

#include MAX_LIGHT_COUNT

struct PointLight
{
    vec4 position;
    vec3 color;
};

layout(std140) uniform PointLightUniforms {
    PointLight PointLightArray[MAX_LIGHT_COUNT];
};

#include LIGHT_GETTER
