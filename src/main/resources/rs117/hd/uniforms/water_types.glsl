#pragma once

#include WATER_TYPE_COUNT

struct WaterType
{
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
    float causticsStrength;
    int normalMap;
    int foamMap;
    int flowMap;
    int underwaterFlowMap;
};

layout(std140) uniform WaterTypeUniforms {
    WaterType WaterTypeArray[WATER_TYPE_COUNT];
};

#include WATER_TYPE_GETTER
