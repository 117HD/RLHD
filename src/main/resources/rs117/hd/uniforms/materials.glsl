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
    int flags; // hexTilingEnabled << 5 | hexTilingMode (2 bits) << 3 | overrideBaseColor << 2 | unlit << 1 | hasTransparency
    float subsurface;
    float brightness;
    float displacementScale;
    float specularStrength;
    float specularGloss;
    float flowMapStrength;
    vec2 flowMapDuration;
    vec2 scrollDuration;
    vec3 uvData; // texture scale, or hex tiling scale & blend
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

bool getMaterialHasHexTiling(const Material material) {
    return (material.flags >> 5 & 1) == 1;
}

// The texture scale is replaced by the hex tiling values when hex tiling is enabled
vec3 getMaterialTextureScale(const Material material) {
    return getMaterialHasHexTiling(material) ? vec3(1) : material.uvData;
}

// Hex tiling scale & blend, zero when hex tiling is disabled
vec2 getMaterialHexTiling(const Material material) {
    return getMaterialHasHexTiling(material) ? material.uvData.xy : vec2(0);
}

bool isHexSamplingSame(const Material materialA, const Material materialB) {
    return
        getMaterialHexTiling(materialA) == getMaterialHexTiling(materialB) &&
        getMaterialHexTilingMode(materialA) == getMaterialHexTilingMode(materialB);
}
