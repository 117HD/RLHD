#pragma once

#include NEBULA_CLUSTER_COUNT
#include <utils/constants.glsl>

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

#define STAR_MODE_OFF 0
#define STAR_MODE_REALISTIC 1
#define STAR_MODE_ARTISTIC 2
#define STAR_MODE_STATIC 3

vec3 rotateStarfield(vec3 direction, float artisticRotationSpeed) {
    if (skyStarRotationMode == STAR_MODE_ARTISTIC) {
        float rotY = elapsedTime * (TAU / 3600.0) * artisticRotationSpeed;
        float rotX = elapsedTime * (TAU / 10800.0) * artisticRotationSpeed;
        float cosY = cos(rotY);
        float sinY = sin(rotY);
        float cosX = cos(rotX);
        float sinX = sin(rotX);
        direction = vec3(cosY * direction.x + sinY * direction.z, direction.y, -sinY * direction.x + cosY * direction.z);
        return vec3(direction.x, cosX * direction.y - sinX * direction.z, sinX * direction.y + cosX * direction.z);
    }

    if (skyStarRotationMode == STAR_MODE_REALISTIC) {
        float cosRotation = cos(skyCelestialRotation);
        float sinRotation = sin(skyCelestialRotation);
        return direction * cosRotation + cross(skyCelestialPole, direction) * sinRotation +
            skyCelestialPole * dot(skyCelestialPole, direction) * (1.0 - cosRotation);
    }

    return direction;
}

vec3 inverseRotateStarfield(vec3 direction, float artisticRotationSpeed) {
    if (skyStarRotationMode == STAR_MODE_ARTISTIC) {
        float rotY = -elapsedTime * (TAU / 3600.0) * artisticRotationSpeed;
        float rotX = -elapsedTime * (TAU / 10800.0) * artisticRotationSpeed;
        float cosY = cos(rotY);
        float sinY = sin(rotY);
        float cosX = cos(rotX);
        float sinX = sin(rotX);
        direction = vec3(direction.x, cosX * direction.y - sinX * direction.z, sinX * direction.y + cosX * direction.z);
        return vec3(cosY * direction.x + sinY * direction.z, direction.y, -sinY * direction.x + cosY * direction.z);
    }

    if (skyStarRotationMode == STAR_MODE_REALISTIC) {
        float cosRotation = cos(skyCelestialRotation);
        float sinRotation = -sin(skyCelestialRotation);
        return direction * cosRotation + cross(skyCelestialPole, direction) * sinRotation +
            skyCelestialPole * dot(skyCelestialPole, direction) * (1.0 - cosRotation);
    }

    return direction;
}

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
