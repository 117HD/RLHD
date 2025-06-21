#pragma once

#include uniforms/skybox.glsl
#include utils/color_utils.glsl

bool canSampleSky() {
    return ActiveSkybox.Index >= 0 || NextSkybox.Index >= 0;
}

vec3 applyPostProcessing(vec3 skyboxColor, SkyboxConfig Config) {
    vec3 hsl = srgbToHsl(skyboxColor);

    hsl.z = (hsl.z + Config.Brightness) * Config.Contrast;
    hsl.y *= Config.Saturation;

    hsl.x = fract(hsl.x + Config.HueShift / 360.0);

    return hslToSrgb(hsl);
}

vec3 sampleSky(vec3 viewDir, vec3 baseColor) {
    vec3 skyboxDir = viewDir;
    skyboxDir.y = -viewDir.y;

    // Convert baseColor from sRGB to linear (fogColor is usually sRGB)
    vec3 baseLinear = srgbToLinear(baseColor);

    vec3 activeSkyColor = baseLinear;
    if (ActiveSkybox.Index >= 0) {
        // Texture fetch gives linear color usually, but if stored sRGB, convert
        activeSkyColor = texture(skyboxArray, vec4(skyboxDir, ActiveSkybox.Index)).rgb;
        if(ActiveSkybox.ApplyPostPro == 1) {
            activeSkyColor = applyPostProcessing(activeSkyColor, ActiveSkybox);
        }
    }

    vec3 nextSkyColor = baseLinear;
    if (NextSkybox.Index >= 0) {
        nextSkyColor = texture(skyboxArray, vec4(skyboxDir, NextSkybox.Index)).rgb;
        if(NextSkybox.ApplyPostPro == 1) {
            nextSkyColor = applyPostProcessing(nextSkyColor, NextSkybox);
        }
    }

    return clamp(mix(activeSkyColor, nextSkyColor, SkyboxBlend), 0.0, 1.0);
}