#pragma once

#include NEBULA_CLUSTER_COUNT

#define STAR_MODE_OFF 0
#define STAR_MODE_REALISTIC 1
#define STAR_MODE_ARTISTIC 2
#define STAR_MODE_STATIC 3

layout(std140) uniform UBOSky {
    bool skyGradientEnabled;
    vec3 skyZenithColor;
    vec3 skyHorizonColor;
    vec3 skySunColor;
    vec3 skySunDir;
    vec3 skyCelestialPole;
    float skyCelestialRotation;
    int skyStarRotationMode;

    vec3 skyMoonDir;
    vec3 skyMoonDiskColor;
    float skyMoonIllumination;
    vec3 skyMoonPhaseLightDirection;
    vec2 skyMoonLibration;
    float skyMoonPhaseReversed;

    float skyVisibility;
    float moonVisibility;
    float starVisibility;
    float nebulaVisibility;
    float auroraVisibility;

    float moonSizeMult;

    float starHorizonHeight;

    vec4 nebulaClusters[NEBULA_CLUSTER_COUNT];
};
