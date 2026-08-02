#pragma once

#include NEBULA_CLUSTER_COUNT

layout(std140) uniform UBOSkybox {
    bool skyGradientEnabled;
    vec3 skyZenithColor;
    vec3 skyHorizonColor;
    vec3 skySunColor;
    vec3 skySunDir;

    // Moon uniforms for day & night Cycle
    vec3 skyMoonDir;
    vec3 skyMoonColor;
    float skyMoonIllumination;

    // Star visibility (from environment override)
    float starVisibility;

    // Nebula visibility (toggled via config)
    float nebulaVisibility;

    // Moon visibility (from environment override)
    float moonVisibility;

    // Aurora visibility (1 on randomly-selected aurora nights, else 0)
    float auroraVisibility;

    // Moon size multiplier (from environment override). Scales the moon disk, its
    // glow, and the star-occlusion mask around it. 1 = default size.
    float moonSizeMult;

    vec4 nebulaClusters[NEBULA_CLUSTER_COUNT];
};

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
