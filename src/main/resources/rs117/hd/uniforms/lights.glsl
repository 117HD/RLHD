#pragma once

#include <utils/constants.glsl>

const float CUTOFF_BEGIN = .02;
const float CUTOFF_END = .1;

#if DYNAMIC_LIGHTS
struct PointLight {
    vec4 position;
    vec4 color;
};

layout(std140) uniform UBOLights {
    float attenuationFactor;
    PointLight PointLightArray[MAX_LIGHT_COUNT];
};

layout(std140) uniform UBOLightsCulling {
    vec4 PointLightPositionsArray[MAX_LIGHT_COUNT];
};
#endif
