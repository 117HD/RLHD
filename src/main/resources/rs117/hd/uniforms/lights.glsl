#pragma once

#include MAX_LIGHT_COUNT

struct PointLight {
    vec4 position;
    vec3 color;
};

layout(std140) uniform UBOLights {
    PointLight PointLightArray[MAX_LIGHT_COUNT];
};