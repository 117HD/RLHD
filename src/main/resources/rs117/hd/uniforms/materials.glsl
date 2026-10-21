#pragma once

#include <utils/misc.glsl>

#include MATERIAL_COUNT

struct Material {
    int colorMap;
    int normalMap;
    int displacementMap;
    int roughnessMap;
    int ambientOcclusionMap;
    int flowMap;
    int shadowAlphaMap;
    int flags; // hexTilingMode (3 bits) << 3 | overrideBaseColor << 2 | unlit << 1 | hasTransparency
    float subsurface;
    float brightness;
    float displacementScale;
    float specularStrength;
    float specularGloss;
    float hexTilingScale;
    float hexTilingBlend;
    float flowMapStrength;
    vec2 flowMapDuration;
    vec2 scrollDuration;
    vec3 textureScale;
};

layout(std140) uniform UBOMaterials {
    Material Array[MATERIAL_COUNT];
} uboMaterials;

#include MATERIAL_GETTER

bool getMaterialShouldOverrideBaseColor(const Material material) {
    return (material.flags >> 2 & 1) == 1;
}

int getMaterialIsUnlit(const Material material) {
    return material.flags >> 1 & 1;
}

bool getMaterialHasTransparency(const Material material) {
    return (material.flags & 1) == 1;
}

int getMaterialHexTilingMode(const Material material) {
    return (material.flags >> 3) & 0x3;
}

bool isHexSamplingSame(const Material materialA, const Material materialB) {
    return
        materialA.hexTilingScale == materialB.hexTilingScale &&
        materialA.hexTilingBlend == materialB.hexTilingBlend &&
        getMaterialHexTilingMode(materialA) == getMaterialHexTilingMode(materialB);
}
