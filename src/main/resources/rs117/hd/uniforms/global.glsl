#pragma once

layout(std140) uniform UBOGlobal {
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
