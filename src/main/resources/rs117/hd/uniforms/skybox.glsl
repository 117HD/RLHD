#pragma once

struct SkyboxConfig
{
    int index;
    int applyPostProcessing;
    float brightness;
    float contrast;
    float saturation;
    float hueShift;
    float rotation;
    float rotationSpeed;
    vec3 tintColor;
};

layout(std140) uniform SkyboxUniforms {
    SkyboxConfig activeSkybox;
    SkyboxConfig nextSkybox;
    float skyboxBlend;
    float skyboxOffset;
    mat4 skyboxViewProj;
};

uniform samplerCubeArray skyboxArray;