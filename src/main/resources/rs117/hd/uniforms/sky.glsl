#pragma once

#include NEBULA_CLUSTER_COUNT

layout(std140) uniform UBOSky {
    bool skyGradientEnabled;
    vec3 skyZenithColor;
    vec3 skyHorizonColor;
    vec3 skySunColor;
    vec3 skySunDir;
    vec3 skyCelestialPole;
    float skyCelestialRotation;

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

// Vertical shift applied to every night-sky horizon fade band (stars, nebula, moon,
// shooting stars), in upAmount units (upAmount = -viewDir.y, so -1 is straight down
// and +1 straight up). starHorizonHeight is 1 at the default position; scaling by
// 1.2 means 0 slides the whole band below -1 (the fade never engages,
// so the night sky wraps the full sphere) and 2 slides it past +1 (fully masked).
float nightHorizonOffset() {
    return (starHorizonHeight - 1.0) * 1.2;
}

float nebulaClusterInfluence(vec3 dir) {
    float influence = 0.0;
    for (int i = 0; i < NEBULA_CLUSTER_COUNT; i++) {
        vec3 c = nebulaClusters[i].xyz;
        float sigma = nebulaClusters[i].w * 6.0;
        float angleSq = max(0.0, (1.0 - dot(dir, c)) * 2.0);
        influence = max(influence, exp(-angleSq / (2.0 * sigma * sigma)));
    }
    return influence;
}
