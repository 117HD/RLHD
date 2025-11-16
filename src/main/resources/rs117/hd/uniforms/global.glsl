#pragma once

#include <utils/constants.glsl>

struct Camera {
    mat4 viewProj;
    mat4 invViewProj;
    mat4 viewMatrix;
    float nearPlane;
    float farPlane;
    vec3 position;
};

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

    ivec2 sceneResolution;
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

    vec3 waterColorLight;
    vec3 waterColorMid;
    vec3 waterColorDark;

    bool underwaterEnvironment;
    bool underwaterCaustics;
    vec3 underwaterCausticsColor;
    float underwaterCausticsStrength;

    vec3 lightDir;

    int pointLightsCount;

    Camera sceneCamera;
    Camera directionalCamera;

    float lightningBrightness;
    float elapsedTime;
};
