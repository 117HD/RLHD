#include MATERIAL_COUNT

struct Material
{
    int diffuseMap;
    float specularStrength;
    float specularGloss;
    float emissiveStrength;
    int normalMap;
    int displacementMap;
    int flowMap;
    float flowMapStrength;
    vec2 flowMapDuration;
    vec2 scrollDuration;
    vec2 textureScale;
};

layout(std140) uniform MaterialUniforms {
    Material MaterialArray[MATERIAL_COUNT];
};

#include MATERIAL_GETTER
