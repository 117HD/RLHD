#pragma once

#include <utils/constants.glsl>

#if DYNAMIC_LIGHTS
struct PointLight {
    vec4 position;
    vec3 color;
};

layout(std140) uniform UBOLights {
    PointLight PointLightArray[MAX_LIGHT_COUNT];
};
#endif
