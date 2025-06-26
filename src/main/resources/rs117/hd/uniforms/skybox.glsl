#pragma once

struct SkyboxConfig
{
    int Index;
    int ApplyPostPro;
    float Brightness;
    float Contrast;
    float Saturation;
    float HueShift;
};

layout(std140) uniform SkyboxUniforms {
    SkyboxConfig ActiveSkybox;
    SkyboxConfig NextSkybox;
    float SkyboxBlend;
    float SkyboxOffset;
    mat4 SkyboxViewProj;
};

uniform samplerCubeArray skyboxArray;