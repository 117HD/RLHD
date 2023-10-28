#pragma once

#include MATERIAL_COUNT

struct Material
{
    int colorMap;
    int normalMap;
    int displacementMap;
    int roughnessMap;
    int ambientOcclusionMap;
    int flowMap;
    int flags; // overrideBaseColor << 2 | unlit << 1 | hasTransparency
    float brightness;
    float displacementScale;
    float specularStrength;
    float specularGloss;
    float flowMapStrength;
    vec2 flowMapDuration;
    vec2 scrollDuration;
    vec2 textureScale;
    vec2 pad;
};

layout(std140) uniform MaterialUniforms {
    Material MaterialArray[MATERIAL_COUNT];
};

#include MATERIAL_GETTER

bool getMaterialShouldOverrideBaseColor(const Material material) {
    return (material.flags >> 2 & 1) == 1;
}

int getMaterialIsUnlit(const Material material) {
    return material.flags >> 1 & 1;
}

int getMaterialHasTransparency(const Material material) {
    return material.flags & 1;
}
