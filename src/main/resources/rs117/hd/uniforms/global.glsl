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
    float glareIntensity;
    float gammaCorrection;
    float saturation;
	float contrast;
    int colorFilterPrevious;
    int colorFilter;
    float colorFilterFade;

    ivec2 viewportSize;
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

    bool voidShader;

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
    bool castsShadows;

    float lightningBrightness;
    float elapsedTime;
    vec2 windDirection; // along local X and Z, unit length
    vec2 windDrift; // total drift along local X and Z, in seconds at the default wind speed
    vec4 windHistory[WIND_HISTORY_LENGTH / 4]; // wind angle at the start of each recent second, indexed by the second
    vec4 roughnessHistory[WIND_HISTORY_LENGTH / 4]; // and the water's roughness
    float waterRoughness; // 1 for normal open water
    float waterChurnTime; // seconds, running faster the rougher the water
    // Boat paths from each boat back: position in tiles relative to the scene, age in seconds, and distance back from
    // the stern in tiles
    vec4 boatWakePoints[BOAT_WAKE_MAX_BOATS * BOAT_WAKE_MAX_POINTS];
    vec4 boatWakeBounds[BOAT_WAKE_MAX_BOATS]; // each wake's min x, min z, max x, max z, with its bow and room for its foam
    vec4 boatWakeHeadings[BOAT_WAKE_MAX_BOATS]; // direction each boat faces, x and z, then its stern, x and z
    // Each wake's number of points, seconds since its boat stopped and since it moved off, then 1 going backwards
    vec4 boatWakeStates[BOAT_WAKE_MAX_BOATS];
    vec4 boatWakeSizes[BOAT_WAKE_MAX_BOATS]; // each boat's length and width in tiles, 1 for a square front, then 1 for a big NPC ship
    float detailDrawDistance; // in tiles
    vec2 waveScroll; // how far the ocean waves and lace have scrolled along local X and Z, in tiles
    int waveRegionRepeat; // 1 to repeat the ocean waves and lace in every region
    float waveMaxRoughness; // the most of roughnessHistory
    int waveHistorySteady; // 1 when every second of windHistory and roughnessHistory is the same
} uboGlobal;
