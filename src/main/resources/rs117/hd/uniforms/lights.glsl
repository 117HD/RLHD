#pragma once

#include <utils/constants.glsl>

#if DYNAMIC_LIGHTS
struct PointLight {
    vec4 position;
    vec4 color;
};

layout(std140) uniform UBOLights {
    PointLight PointLightArray[MAX_LIGHT_COUNT];
};

layout(std140) uniform UBOLightsCulling {
    vec4 PointLightPositionsArray[MAX_LIGHT_COUNT];
};

ivec2 decodePackedLight(int packedValue) {
    if ((packedValue & 0x8000) != 0) {
        // Dual-packed: 8-bit idx1, 7-bit idx0
        int idx0 = (packedValue & 0x7F) - 1;
        int idx1 = ((packedValue >> 7) & 0xFF) - 1;
        return ivec2(idx0, idx1);
    } else {
        return ivec2((packedValue & 0x7FFF) - 1, -1);
    }
}
#endif
