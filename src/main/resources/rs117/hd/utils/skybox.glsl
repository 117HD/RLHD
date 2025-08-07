#pragma once

#include <uniforms/global.glsl>
#include <uniforms/skybox.glsl>

#include <utils/color_utils.glsl>
#include <utils/misc.glsl>

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

    vec3 result = hslToSrgb(hsl);

    if (Config.tintColor.r > 0.1) {
        result.r *= Config.tintColor.r;
    }
    if (Config.tintColor.g > 0.1) {
        result.g *= Config.tintColor.g;
    }
    if (Config.tintColor.b > 0.1) {
        result.b *= Config.tintColor.b;
    }
    return result;
}

vec3 blurSkybox(samplerCubeArray skyboxArray, vec3 dir, int index, float rotation, SkyboxConfig config) {
    vec3 color = vec3(0.0);
    float total = 0.0;
    float blurStrength = 0.04;

    // Center sample
    vec3 rotatedDir = rotateSkyDirection(dir, rotation);
    color += texture(skyboxArray, vec4(rotatedDir, index)).rgb;
    total += 1.0;

    for (int i = 0; i < 8; ++i) {
        float angle = 3.14159 * 0.25 * float(i);
        vec3 offset = vec3(cos(angle), 0.0, sin(angle)) * blurStrength;
        vec3 sampleDir = normalize(rotatedDir + offset);
        color += texture(skyboxArray, vec4(sampleDir, index)).rgb;
        total += 1.0;
    }

    color /= total;

    if (config.applyPostProcessing == 1) {
        color = applyPostProcessing(color, config);
    }
    return color;
}

vec3 sampleSky(vec3 viewDir, vec3 baseColor) {
    vec3 skyboxDir = viewDir;
    skyboxDir.y = -viewDir.y;

    float blendFactor = smoothstep(0.0, 1.0, skyboxBlend);
    float speedFactor = 0.9;
    float activeRotation = activeSkybox.rotation + elapsedTime * activeSkybox.rotationSpeed * speedFactor;
    float nextRotation = nextSkybox.rotation + elapsedTime * nextSkybox.rotationSpeed * speedFactor;

    // Convert baseColor from sRGB to linear (fogColor is usually sRGB)
    vec3 baseLinear = srgbToLinear(baseColor);

    vec3 activeSkyColor = baseLinear;
    if (activeSkybox.index >= 0) {
        // Texture fetch gives linear color usually, but if stored sRGB, convert
        activeSkyColor = blurSkybox(skyboxArray, skyboxDir, activeSkybox.index, activeRotation, activeSkybox);
    }

    vec3 nextSkyColor = baseLinear;
    if (nextSkybox.index >= 0) {
        nextSkyColor = blurSkybox(skyboxArray, skyboxDir, nextSkybox.index, nextRotation, nextSkybox);
    }

    vec3 skyboxColor = clamp(hermite(activeSkyColor, nextSkyColor, blendFactor), 0.0, 1.0);

    float skyboxInfluence = 0.95;
    float baseInfluence = 0.15;

    vec3 finalColor = skyboxColor * skyboxInfluence + baseLinear * baseInfluence;
    finalColor = clamp(finalColor, 0.0, 1.0);
    
    return finalColor;
}
