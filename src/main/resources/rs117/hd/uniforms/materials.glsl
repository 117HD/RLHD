#include MATERIAL_COUNT

struct Material
{
    int colorMap;
    int normalMap;
    int displacementMap;
    int roughnessMap;
    int ambientOcclusionMap;
    int flowMap;
    bool overrideBaseColor;
    int unlit;
    float displacementScale;
    float specularStrength;
    float specularGloss;
    float flowMapStrength;
    vec2 flowMapDuration;
    vec2 scrollDuration;
    vec2 textureScale;
};

layout(std140) uniform MaterialUniforms {
    Material MaterialArray[MATERIAL_COUNT];
};

#include MATERIAL_GETTER
