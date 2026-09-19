#pragma once

#include NEBULA_CLUSTER_COUNT

layout(std140) uniform UBOSky {
    // All color fields are linear sRGB.
    bool gradientEnabled;
    vec3 zenithColor;
    vec3 horizonColor;
    vec3 sunColor;
    float customGradient;
    float horizonWidth;
    vec3 sunDir;
    vec3 celestialPole;
    float celestialRotation;

    vec3 moonDir;
    vec3 moonDiskColor;
    float moonIllumination;
    vec3 moonIlluminationDirection;
    vec2 moonLibration;

    vec3 fogColor;
    float fogDensity;
    float visibility;
    float moonVisibility;
    float starVisibility;
    float nebulaVisibility;
    float auroraVisibility;

    float moonSizeMult;

    float starHorizonHeight;

    vec4 nebulaClusters[NEBULA_CLUSTER_COUNT];
} uboSky;
