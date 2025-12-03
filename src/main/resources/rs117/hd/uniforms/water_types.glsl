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
    WaterType WaterTypeArray[WATER_TYPE_COUNT];
};

#include WATER_TYPE_GETTER

#define WATER_TYPE_NONE              0
#define WATER_TYPE_WATER             1
#define WATER_TYPE_WATER_FLAT        2
#define WATER_TYPE_SWAMP_WATER       3
#define WATER_TYPE_MLM_WATER         4
#define WATER_TYPE_SWAMP_WATER_FLAT  5
#define WATER_TYPE_POISON_WASTE      6
#define WATER_TYPE_BLACK_TAR_FLAT    7
#define WATER_TYPE_BLOOD             8
#define WATER_TYPE_ICE               9
#define WATER_TYPE_ICE_FLAT         10
#define WATER_TYPE_MUDDY_WATER      11
#define WATER_TYPE_SCAR_SLUDGE      12
#define WATER_TYPE_ABYSS_BILE       13
#define WATER_TYPE_PLAIN_WATER      14
#define WATER_TYPE_DARK_BLUE_WATER  15
#define WATER_TYPE_ARAXXOR_WASTE    16
#define WATER_TYPE_CYAN_WATER       17
#define WATER_TYPE_GREEN_CAVE_WATER 18
#define WATER_TYPE_CAVE_WATER       19
