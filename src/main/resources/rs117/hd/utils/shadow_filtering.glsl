#pragma once

#include <utils/misc.glsl>

vec2 getShadowDitherOffset(vec3 fragPos, int i) {
#if SHADOW_FILTERING == SHADOW_FILTERING_DITHER
    int index = int(hash(vec4(floor(fragPos.xyz), i)) * POISSON_DISK_LENGTH) % POISSON_DISK_LENGTH;
    return getPoissonDisk(index) * 1.25;
#else
    return vec2(0.0);
#endif
}

float fetchShadowTexel(sampler2D tex, bool hasTransparency, ivec2 pixelCoord, float fragDepth, vec3 fragPos, int i) {
    pixelCoord += ivec2(getShadowDitherOffset(fragPos, i));

    if(hasTransparency) {
        int alphaDepth = int(texelFetch(tex, pixelCoord, 0).r * SHADOW_COMBINED_MAX);
        float depth = float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
        float alpha = 1 - float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
        return depth < fragDepth ? alpha : 0.f;
    }

    return texelFetch(tex, pixelCoord, 0).r < fragDepth ? 1.f : 0.f;
}

float sampleShadowPCF1x1(sampler2D tex, bool hasTransparency, float fragDepth, vec4 shadowPos, vec3 fragPos) {
    ivec2 pixelCoord = ivec2(shadowPos.xy * textureSize(tex, 0));
    return fetchShadowTexel(tex, hasTransparency, pixelCoord, fragDepth, fragPos, 0);
}

float sampleShadowPCF2x2(sampler2D tex, bool hasTransparency, float fragDepth, vec4 shadowPos, vec3 fragPos) {
    shadowPos.xy *= textureSize(tex, 0);
    shadowPos.xy -= .5; // Shift so the 2x2 kernel straddles the sample point

    ivec2 kernelOffset = ivec2(floor(shadowPos.xy));

    #if SHADOW_FILTERING == SHADOW_FILTERING_AVERAGE
        const float kernelAreaReciprocal = .25;
    #else
        const float kernelAreaReciprocal = 1.0;
        vec2 lerp = fract(shadowPos.xy);
    #endif

    float c00 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(0, 0), fragDepth, fragPos, 0);
    float c10 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(1, 0), fragDepth, fragPos, 1);
    float c01 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(0, 1), fragDepth, fragPos, 2);
    float c11 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(1, 1), fragDepth, fragPos, 3);

    if (c00 == c10 && c10 == c01 && c01 == c11)
        return c00;

    float shadow;
    #if SHADOW_FILTERING == SHADOW_FILTERING_AVERAGE
        shadow = c00 + c10 + c01 + c11;
    #else
        shadow =
            c00 * (1 - lerp.x) * (1 - lerp.y) +
            c10 * lerp.x       * (1 - lerp.y) +
            c01 * (1 - lerp.x) * lerp.y       +
            c11 * lerp.x       * lerp.y;
    #endif
    return shadow * kernelAreaReciprocal;
}

float sampleShadowPCF3x3(sampler2D tex, bool hasTransparency, float fragDepth, vec4 shadowPos, vec3 fragPos) {
    shadowPos.xy *= textureSize(tex, 0);
    shadowPos.xy += .5; // Shift to texel center

    const int kernelSize = 3;
    ivec2 kernelOffset = ivec2(shadowPos.xy - kernelSize / 2);

    #if SHADOW_FILTERING == SHADOW_FILTERING_AVERAGE
        const float kernelAreaReciprocal = 1. / (kernelSize * kernelSize);
    #else
        const float kernelAreaReciprocal = .25;
        vec2 lerp = fract(shadowPos.xy);
        vec3 lerpX = vec3(1 - lerp.x, 1, lerp.x);
        vec3 lerpY = vec3(1 - lerp.y, 1, lerp.y);
    #endif

    float c00 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(0, 0),              fragDepth, fragPos, 0);
    float c02 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(0, kernelSize - 1), fragDepth, fragPos, 1);
    float c20 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(kernelSize - 1, 0), fragDepth, fragPos, 2);
    float c22 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(kernelSize - 1, kernelSize - 1), fragDepth, fragPos, 3);

    bool allShadowed = (c00 == 0.0 && c02 == 0.0 && c20 == 0.0 && c22 == 0.0);
    bool allLit      = (c00 == 1.0 && c02 == 1.0 && c20 == 1.0 && c22 == 1.0);

    if (allShadowed || allLit)
        return (c00 + c02 + c20 + c22) * 0.25;

    float s01 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(0, 1), fragDepth, fragPos, 4);
    float s10 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(1, 0), fragDepth, fragPos, 5);
    float s11 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(1, 1), fragDepth, fragPos, 6);
    float s12 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(1, 2), fragDepth, fragPos, 7);
    float s21 = fetchShadowTexel(tex, hasTransparency, kernelOffset + ivec2(2, 1), fragDepth, fragPos, 8);

    float shadow;
    #if SHADOW_FILTERING == SHADOW_FILTERING_AVERAGE
        shadow =
            c00 + s01 + c02 +
            s10 + s11 + s12 +
            c20 + s21 + c22;
    #else
        shadow =
            c00 * lerpX[0] * lerpY[0] +
            s01 * lerpX[0] * lerpY[1] +
            c02 * lerpX[0] * lerpY[2] +
            s10 * lerpX[1] * lerpY[0] +
            s11 * lerpX[1] * lerpY[1] +
            s12 * lerpX[1] * lerpY[2] +
            c20 * lerpX[2] * lerpY[0] +
            s21 * lerpX[2] * lerpY[1] +
            c22 * lerpX[2] * lerpY[2];
    #endif
    return shadow * kernelAreaReciprocal;
}

float fetchHardwareShadowTexel(sampler2DShadow tex, vec2 uv, float fragDepth, vec3 fragPos, int i) {
    uv += getShadowDitherOffset(fragPos, i) / vec2(textureSize(tex, 0));
    return texture(tex, vec3(uv, fragDepth));
}

float sampleHardwareShadow1x1(sampler2DShadow tex, float fragDepth, vec4 shadowPos, vec3 fragPos) {
    return fetchHardwareShadowTexel(tex, shadowPos.xy, fragDepth, fragPos, 0);
}

float sampleHardwareShadow2x2(sampler2DShadow tex, float fragDepth, vec4 shadowPos, vec3 fragPos) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));
    vec2 kernelOrigin = shadowPos.xy - .5 * texelSize;

    float c00 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(0, 0) * texelSize, fragDepth, fragPos, 0);
    float c10 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(1, 0) * texelSize, fragDepth, fragPos, 1);
    float c01 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(0, 1) * texelSize, fragDepth, fragPos, 2);
    float c11 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(1, 1) * texelSize, fragDepth, fragPos, 3);

    return (c00 + c10 + c01 + c11) * 0.25;
}

float sampleHardwareShadow3x3(sampler2DShadow tex, float fragDepth, vec4 shadowPos, vec3 fragPos) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));

    const int kernelSize = 3;
    vec2 kernelOrigin = shadowPos.xy - float(kernelSize / 2) * texelSize;

    float c00 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(0, 0) * texelSize,                           fragDepth, fragPos, 0);
    float c02 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(0, kernelSize - 1) * texelSize,              fragDepth, fragPos, 1);
    float c20 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(kernelSize - 1, 0) * texelSize,              fragDepth, fragPos, 2);
    float c22 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(kernelSize - 1, kernelSize - 1) * texelSize, fragDepth, fragPos, 3);

    bool allShadowed = (c00 == 0.0 && c02 == 0.0 && c20 == 0.0 && c22 == 0.0);
    bool allLit      = (c00 == 1.0 && c02 == 1.0 && c20 == 1.0 && c22 == 1.0);

    if (allShadowed || allLit)
        return (c00 + c02 + c20 + c22) * 0.25;

    float s01 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(0, 1) * texelSize, fragDepth, fragPos, 4);
    float s10 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(1, 0) * texelSize, fragDepth, fragPos, 5);
    float s11 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(1, 1) * texelSize, fragDepth, fragPos, 6);
    float s12 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(1, 2) * texelSize, fragDepth, fragPos, 7);
    float s21 = fetchHardwareShadowTexel(tex, kernelOrigin + vec2(2, 1) * texelSize, fragDepth, fragPos, 8);

    float shadow =
        c00 + s01 + c02 +
        s10 + s11 + s12 +
        c20 + s21 + c22;

    return shadow / 9.0;
}