#pragma once

// Needs to match GlobalUniforms.java
layout(std140) uniform GlobalUniforms {
    vec3 cameraPos;
    int expandedMapLoadingChunks;
    float drawDistance;
    float elapsedTime;
    int viewportWidth;
    int viewportHeight;

    float colorBlindnessIntensity;
    float gammaCorrection;
    float saturation;
	float contrast;

    vec3 ambientColor;
    float ambientStrength;

    vec3 lightColor;
    float lightStrength;

    vec3 underglowColor;
    float underglowStrength;

    int tileXCount;
    int tileYCount;

    mat4 invProjectionMatrix;
    mat4 projectionMatrix;
    mat4 lightProjectionMatrix;

    int useFog;
    float fogDepth;
    vec3 fogColor;
    float groundFogStart;
    float groundFogEnd;
    float groundFogOpacity;

    int pointLightsCount;
    float lightningBrightness;
    vec3 lightDir;

    float shadowMaxBias;
    int shadowsEnabled;

    vec3 waterColorLight;
    vec3 waterColorMid;
    vec3 waterColorDark;

    bool underwaterEnvironment;
    bool underwaterCaustics;
    vec3 underwaterCausticsColor;
    float underwaterCausticsStrength;

    int colorFilterPrevious;
    int colorFilter;
    float colorFilterFade;
};
