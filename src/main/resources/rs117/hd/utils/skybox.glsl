#pragma once

#include uniforms/skybox.glsl
#include utils/color_utils.glsl

bool canSampleSky() {
    return activeSkybox.index >= 0 || nextSkybox.index >= 0;
}

vec3 rotateSkyDirection(vec3 dir, float angle) {
    float cosA = cos(angle);
    float sinA = sin(angle);

    mat3 rotY = mat3(
        cosA, 0.0, -sinA,
        0.0,  1.0,  0.0,
        sinA, 0.0,  cosA
    );

    return rotY * dir;
}

vec3 applyPostProcessing(vec3 skyboxColor, SkyboxConfig Config) {
    vec3 hsl = srgbToHsl(skyboxColor);

    hsl.z = (hsl.z + Config.brightness) * Config.contrast;
    hsl.y *= Config.saturation;

    hsl.x = fract(hsl.x + Config.hueShift / 360.0);

    return hslToSrgb(hsl);
}

vec3 sampleSky(vec3 viewDir, vec3 baseColor) {
    vec3 skyboxDir = viewDir;
    skyboxDir.y = -viewDir.y;

    skyboxDir = rotateSkyDirection(skyboxDir, activeSkybox.rotation);

    // Convert baseColor from sRGB to linear (fogColor is usually sRGB)
    vec3 baseLinear = srgbToLinear(baseColor);

    vec3 activeSkyColor = baseLinear;
    if (activeSkybox.index >= 0) {
        // Texture fetch gives linear color usually, but if stored sRGB, convert
        activeSkyColor = texture(skyboxArray, vec4(skyboxDir, activeSkybox.index)).rgb;
        if(activeSkybox.applyPostProcessing == 1) {
            activeSkyColor = applyPostProcessing(activeSkyColor, activeSkybox);
        }
    }

    vec3 nextSkyColor = baseLinear;
    if (nextSkybox.index >= 0) {
        nextSkyColor = texture(skyboxArray, vec4(skyboxDir, nextSkybox.index)).rgb;

        if(nextSkybox.applyPostProcessing == 1) {
            nextSkyColor = applyPostProcessing(nextSkyColor, nextSkybox);
        }
    }

    return clamp(mix(activeSkyColor, nextSkyColor, skyboxBlend), 0.0, 1.0);
}