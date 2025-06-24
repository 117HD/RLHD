#pragma once

// Needs to match GlobalUniforms.java
layout(std140) uniform GlobalUniforms {
    vec3 cameraPos;
    int expandedMapLoadingChunks;
    float drawDistance;
    float elapsedTime;

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

    bool underwaterEnvironment;
    bool underwaterCaustics;
    vec3 underwaterCausticsColor;

    int colorFilterPrevious;
    int colorFilter;
    float colorFilterFade;
};
