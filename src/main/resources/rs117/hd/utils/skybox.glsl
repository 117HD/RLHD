#pragma once

#include utils/color_utils.glsl

uniform samplerCubeArray skybox;
uniform vec4 skyboxProperties; // x: currentIdx, y: blend, z: targetIdx, w: cameraNearPlane

int getCurrentSkyboxIdx() { return int(skyboxProperties.x); }
float getSkyboxBlend() { return skyboxProperties.y; }
int getTargetSkyboxIdx() { return int(skyboxProperties.z); }
float getSkyboxOffset() { return skyboxProperties.w; }

bool canSampleSky() {
    return getCurrentSkyboxIdx() >= 0 || getTargetSkyboxIdx() >= 0;
}

// Hardcoded post-processing values (for testing)
const float BRIGHTNESS = 0.0001;
const float CONTRAST = 1.2;
const float SATURATION = 1.2;
const float HUE_SHIFT = 160.0;

vec3 applyPostProcessing(vec3 skyboxDir)
{
    vec3 texColor = linearToSrgb(texture(skybox, vec4(skyboxDir, getCurrentSkyboxIdx())).rgb);
    vec3 hsl = srgbToHsl(texColor);

    hsl.z = (hsl.z + BRIGHTNESS) * CONTRAST;
    hsl.y *= SATURATION;

    hsl.x = fract(hsl.x + HUE_SHIFT / 360.0);

    texColor = hslToSrgb(hsl);
    return texColor;
}
vec3 sampleSky(vec3 viewDir, vec3 baseColor) {
    vec3 skyboxDir = viewDir;
    skyboxDir.y = -viewDir.y;

    // Convert baseColor from sRGB to linear (fogColor is usually sRGB)
    vec3 baseLinear = srgbToLinear(baseColor);

    vec3 currentSkyColor = baseLinear;
    if (getCurrentSkyboxIdx() >= 0) {
        // Texture fetch gives linear color usually, but if stored sRGB, convert
        currentSkyColor = texture(skybox, vec4(skyboxDir, getCurrentSkyboxIdx())).rgb;
    }

    vec3 targetSkyColor = baseLinear;
    if (getTargetSkyboxIdx() >= 0) {
        targetSkyColor = texture(skybox, vec4(skyboxDir, getTargetSkyboxIdx())).rgb;
    }

    // Blend skybox colors and base in linear space
    vec3 blended = mix(currentSkyColor, targetSkyColor, getSkyboxBlend());

    // Apply post-processing in linear space
    blended = applyPostProcessing(blended);

    // Clamp and convert back to sRGB for output
    return clamp(blended, 0.0, 1.0);
}