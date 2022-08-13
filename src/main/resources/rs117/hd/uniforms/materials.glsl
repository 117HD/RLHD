#include MATERIAL_COUNT

struct Material
{
    int diffuseMap;
    int normalMap;
    int displacementMap;
    int roughnessMap;
    int flowMap;
    float flowMapStrength;
    vec2 flowMapDuration;
    float specularStrength;
    float specularGloss;
    float emissiveStrength;
    float pad;
    vec2 scrollDuration;
    vec2 textureScale;
};

layout(std140) uniform MaterialUniforms {
    Material MaterialArray[MATERIAL_COUNT];
};

#include MATERIAL_GETTER
