#pragma once

#include <utils/constants.glsl>
#include <utils/misc.glsl>

#if DYNAMIC_LIGHTS
struct PointLight {
    vec3 position;
    int packedRadiusNear;
    vec3 color;
    int packedShadowData;
};

layout(std140) uniform UBOLights {
    PointLight PointLightArray[MAX_LIGHT_COUNT];
};

layout(std140) uniform UBOLightsCulling {
    vec4 PointLightPositionsArray[MAX_LIGHT_COUNT];
};

bool isDualPacked(uint packedValue) {
    return (packedValue & 0x8000u) != 0u;
}

ivec2 decodePackedLight(uint packedValue) {
    if (isDualPacked(packedValue)) {
        int idx0 = int(packedValue & 0x7Fu) - 1;
        int idx1 = int((packedValue >> 7) & 0xFFu) - 1;
        return ivec2(idx0, idx1);
    } else {
        return ivec2(int(packedValue & 0x7FFFu) - 1, -1);
    }
}

float getLightRadius(PointLight light) {
    return unpackFloat2x16(light.packedRadiusNear).x;
}

float getLightRadiusSq(PointLight light) {
    float radius = getLightRadius(light);
    return radius * radius;
}

float getLightNearPlane(PointLight light) {
    return unpackFloat2x16(light.packedRadiusNear).y;
}
#endif
