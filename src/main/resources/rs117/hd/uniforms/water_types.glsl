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
    float pad0;
    vec3 foamColor;
    float pad1;
    vec3 depthColor;
    float pad2;
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
