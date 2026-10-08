#pragma once

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

#define RED linearToSrgb(uboGlobal.colorPicker.r)
#define GREEN linearToSrgb(uboGlobal.colorPicker.g)
#define BLUE linearToSrgb(uboGlobal.colorPicker.b)
#define OPACITY uboGlobal.colorPicker.a

layout(std140) uniform UBOGlobal {
    vec4 colorPicker;

    bool orthographicProjection;

    int expandedMapLoadingChunks;
    float drawDistance;

    float colorBlindnessIntensity;
    float gammaCorrection;
    float saturation;
	float contrast;
    int colorFilterPrevious;
    int colorFilter;
    float colorFilterFade;

    vec3 highContrastColors[8];
    ivec2 viewportSize;
    ivec2 sceneResolution;
    ivec2 tiledLightingResolution;
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

    ivec2 sceneBase;

    bool underwaterEnvironment;
    bool underwaterCaustics;
    vec3 underwaterCausticsColor;
    float underwaterCausticsStrength;

    vec3 lightDir;

    int pointLightsCount;

    vec3 cameraPos;
    mat4 viewMatrix;
    mat4 projectionMatrix;
    mat4 invProjectionMatrix;
    mat4 lightProjectionMatrix;
    mat4 invLightProjectionMatrix;
    float shadowBiasScale;
    float shadowDrawDistance;

    float lightningBrightness;
    float elapsedTime;
} uboGlobal;
