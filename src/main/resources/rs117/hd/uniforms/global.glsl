#pragma once

#include <utils/constants.glsl>
#include <utils/camera.glsl>

layout(std140) uniform UBOGlobal {
    int expandedMapLoadingChunks;
    float drawDistance;

    float colorBlindnessIntensity;
    float gammaCorrection;
    float saturation;
	float contrast;
    int colorFilterPrevious;
    int colorFilter;
    float colorFilterFade;

    ivec2 tiledLightingResolution;

    vec3 ambientColor;
    float ambientStrength;
    vec3 lightColor;
    float lightStrength;
    vec3 underglowColor;
    float underglowStrength;

    int useFog;
    float fogDepth;
    vec3 fogColor;
    float groundFogStart;
    float groundFogEnd;
    float groundFogOpacity;

    bool underwaterEnvironment;
    bool underwaterCaustics;
    vec3 underwaterCausticsColor;

    int waterHeight;
    vec3 legacyWaterColor;

    int pointLightsCount;

    Camera sceneCamera;
    Camera directionalCamera;
    Camera reflectionCamera; // TODO: Move to a reflection UBO

    float lightningBrightness;
    float elapsedTime;

    vec4 COLOR_PICKER;
};
