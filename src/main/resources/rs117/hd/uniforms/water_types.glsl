#pragma once

#include WATER_TYPE_COUNT

struct WaterType {
    int flags;
    float specularStrength;
    float specularGloss;
    float normalStrength;
    float baseOpacity;
    float duration;
    float fresnelAmount;
    vec3 surfaceColor;
    vec3 foamColor;
    vec3 depthColor;
    int normalMap;
};

layout(std140) uniform UBOWaterTypes {
    WaterType Array[WATER_TYPE_COUNT];
} uboWaterTypes;

#include WATER_TYPE_GETTER

bool isWaterFlat(WaterType type) { return (type.flags & 1) != 0; }

bool waterHasFoam(WaterType type) { return (type.flags & 2) != 0; }

bool isWaterSailing(WaterType type) { return (type.flags & 4) != 0; }

bool isWaterHazardous(WaterType type) { return (type.flags & 8) != 0; }
