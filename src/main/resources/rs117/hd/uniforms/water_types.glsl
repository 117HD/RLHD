#pragma once

#include WATER_TYPE_COUNT

struct WaterType {
    bool isFlat;
    float specularStrength;
    float specularGloss;
    float normalStrength;
    float baseOpacity;
    int hasFoam;
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
