#pragma once

#include <uniforms/global.glsl>

#include <utils/hash.glsl>
#include <utils/misc.glsl>

vec2 getShadowDitherOffset(int i) {
#if SHADOW_FILTERING == SHADOW_FILTERING_DITHER
    int firstIndex = int(hash12(gl_FragCoord.xy) * POISSON_DISK_LENGTH);
    int index = (firstIndex + i) % POISSON_DISK_LENGTH;
    return getPoissonDisk(index) * 1.25;
#else
    return vec2(0.0);
#endif
}

float fetchShadowTexel(
    sampler2D tex,
    bool hasTransparency,
    ivec2 pixelCoord,
    float fragDepth,
    vec4 receiverPlane,
    int i
) {
    pixelCoord += ivec2(getShadowDitherOffset(i));
    #if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
        // texelFetch does not apply the texture's clamp-to-border setting.
        if (any(lessThan(pixelCoord, ivec2(0))) || any(greaterThanEqual(pixelCoord, textureSize(tex, 0))))
            return 0.0;
    #endif
    float bias = dot(vec2(pixelCoord) + 0.5 - receiverPlane.xy, receiverPlane.zw);
    // The inverse projection's Z column spans half the camera's depth range.
    float correctionLimit = 64. / (2.0 * length(uboGlobal.invLightProjectionMatrix[2].xyz));
    fragDepth += clamp(bias, -correctionLimit, correctionLimit);

    if (hasTransparency) {
        int alphaDepth = int(texelFetch(tex, pixelCoord, 0).r * SHADOW_COMBINED_MAX);
        float depth = float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
        float alpha = 1 - float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
        return depth < fragDepth ? alpha : 0.f;
    }

    return texelFetch(tex, pixelCoord, 0).r < fragDepth ? 1.f : 0.f;
}

float sampleShadowPCF1x1(
    sampler2D tex,
    bool hasTransparency,
    float fragDepth,
    vec4 shadowPos,
    vec4 receiverPlane,
    vec3 receiverPosition
) {
    ivec2 pixelCoord = ivec2(shadowPos.xy * textureSize(tex, 0));
    return fetchShadowTexel(tex, hasTransparency, pixelCoord, fragDepth, receiverPlane, 0);
}

float sampleShadowPCF2x2(
    sampler2D tex,
    bool hasTransparency,
    float fragDepth,
    vec4 shadowPos,
    vec4 receiverPlane,
    vec3 receiverPosition
) {
    shadowPos.xy *= textureSize(tex, 0);
    shadowPos.xy -= .5; // Shift so the 2x2 kernel straddles the sample point

    ivec2 offset = ivec2(floor(shadowPos.xy));
    float c00 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(0, 0), fragDepth, receiverPlane, 0);
    float c10 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(1, 0), fragDepth, receiverPlane, 1);
    float c01 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(0, 1), fragDepth, receiverPlane, 2);
    float c11 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(1, 1), fragDepth, receiverPlane, 3);

    if (c00 == c10 && c10 == c01 && c01 == c11)
        return c00;

    #if SHADOW_FILTERING == SHADOW_FILTERING_AVERAGE
        return (c00 + c10 + c01 + c11) * 0.25;
    #else
        vec2 lerp = fract(shadowPos.xy);
        return mix(
            mix(c00, c10, lerp.x),
            mix(c01, c11, lerp.x),
            lerp.y
        );
    #endif
}

float sampleShadowPCF3x3(
    sampler2D tex,
    bool hasTransparency,
    float fragDepth,
    vec4 shadowPos,
    vec4 receiverPlane,
    vec3 receiverPosition
) {
    shadowPos.xy *= textureSize(tex, 0);

    const int kernelSize = 3;
    ivec2 offset = ivec2(shadowPos.xy) - kernelSize / 2;

    #if SHADOW_FILTERING == SHADOW_FILTERING_AVERAGE
        const float kernelAreaReciprocal = 1. / (kernelSize * kernelSize);
    #else
        const float kernelAreaReciprocal = .25;
        vec2 lerp = fract(shadowPos.xy);
        vec3 lerpX = vec3(1 - lerp.x, 1, lerp.x);
        vec3 lerpY = vec3(1 - lerp.y, 1, lerp.y);
    #endif

    float c00 = fetchShadowTexel(
        tex, hasTransparency, offset + ivec2(0, 0),                           fragDepth, receiverPlane, 0);
    float c02 = fetchShadowTexel(
        tex, hasTransparency, offset + ivec2(0, kernelSize - 1),              fragDepth, receiverPlane, 1);
    float c20 = fetchShadowTexel(
        tex, hasTransparency, offset + ivec2(kernelSize - 1, 0),              fragDepth, receiverPlane, 2);
    float c22 = fetchShadowTexel(
        tex, hasTransparency, offset + ivec2(kernelSize - 1, kernelSize - 1), fragDepth, receiverPlane, 3);

    if ((c00 == 0.0 || c00 == 1.0) && c00 == c02 && c00 == c20 && c00 == c22)
        return c00;

    float s01 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(0, 1), fragDepth, receiverPlane, 4);
    float s10 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(1, 0), fragDepth, receiverPlane, 5);
    float s11 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(1, 1), fragDepth, receiverPlane, 6);
    float s12 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(1, 2), fragDepth, receiverPlane, 7);
    float s21 = fetchShadowTexel(tex, hasTransparency, offset + ivec2(2, 1), fragDepth, receiverPlane, 8);

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

float fetchHardwareShadowTexel(sampler2DShadow tex, vec2 uv, float fragDepth, vec4 receiverPlane, int i) {
    vec2 pixelCoord = uv * textureSize(tex, 0) + getShadowDitherOffset(i);
    fragDepth += dot(pixelCoord - receiverPlane.xy, receiverPlane.zw);
    return texture(tex, vec3(pixelCoord / textureSize(tex, 0), fragDepth));
}

float sampleHardwareShadow1x1(sampler2DShadow tex, float fragDepth, vec4 shadowPos, vec4 receiverPlane) {
    return fetchHardwareShadowTexel(tex, shadowPos.xy, fragDepth, receiverPlane, 0);
}

float sampleHardwareShadow2x2(sampler2DShadow tex, float fragDepth, vec4 shadowPos, vec4 receiverPlane) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));
    vec2 offset = shadowPos.xy - .5 * texelSize;

    float c00 = fetchHardwareShadowTexel(tex, offset + vec2(0, 0) * texelSize, fragDepth, receiverPlane, 0);
    float c10 = fetchHardwareShadowTexel(tex, offset + vec2(1, 0) * texelSize, fragDepth, receiverPlane, 1);
    float c01 = fetchHardwareShadowTexel(tex, offset + vec2(0, 1) * texelSize, fragDepth, receiverPlane, 2);
    float c11 = fetchHardwareShadowTexel(tex, offset + vec2(1, 1) * texelSize, fragDepth, receiverPlane, 3);

    return (c00 + c10 + c01 + c11) * 0.25;
}

float sampleHardwareShadow3x3(sampler2DShadow tex, float fragDepth, vec4 shadowPos, vec4 receiverPlane) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));

    const int kernelSize = 3;
    vec2 offset = shadowPos.xy - float(kernelSize / 2) * texelSize;

    float c00 = fetchHardwareShadowTexel(
        tex, offset + vec2(0, 0) * texelSize,                           fragDepth, receiverPlane, 0);
    float c02 = fetchHardwareShadowTexel(
        tex, offset + vec2(0, kernelSize - 1) * texelSize,              fragDepth, receiverPlane, 1);
    float c20 = fetchHardwareShadowTexel(
        tex, offset + vec2(kernelSize - 1, 0) * texelSize,              fragDepth, receiverPlane, 2);
    float c22 = fetchHardwareShadowTexel(
        tex, offset + vec2(kernelSize - 1, kernelSize - 1) * texelSize, fragDepth, receiverPlane, 3);

    if ((c00 == 0.0 || c00 == 1.0) && c00 == c02 && c00 == c20 && c00 == c22)
        return c00;

    float s01 = fetchHardwareShadowTexel(tex, offset + vec2(0, 1) * texelSize, fragDepth, receiverPlane, 4);
    float s10 = fetchHardwareShadowTexel(tex, offset + vec2(1, 0) * texelSize, fragDepth, receiverPlane, 5);
    float s11 = fetchHardwareShadowTexel(tex, offset + vec2(1, 1) * texelSize, fragDepth, receiverPlane, 6);
    float s12 = fetchHardwareShadowTexel(tex, offset + vec2(1, 2) * texelSize, fragDepth, receiverPlane, 7);
    float s21 = fetchHardwareShadowTexel(tex, offset + vec2(2, 1) * texelSize, fragDepth, receiverPlane, 8);

    float shadow =
        c00 + s01 + c02 +
        s10 + s11 + s12 +
        c20 + s21 + c22;

    return shadow / 9.0;
}

#if POSITIONAL_SHADOWS
// Positional shadows use a hardware comparison sampler, so filtering mirrors the
// hardware-PCF path used for the terrain shadow map. Taps are clamped to the light's
// atlas rect so filters don't pull in neighbouring lights.
float fetchPositionalShadowTexel(
    sampler2DArrayShadow tex,
    vec2 atlasUV,
    int layer,
    float compareDepth,
    vec2 originPx,
    float sizePx,
    int i
) {
    vec2 atlasSize = vec2(textureSize(tex, 0).xy);
    vec2 pixelCoord = atlasUV * atlasSize + getShadowDitherOffset(i);
    pixelCoord = clamp(pixelCoord, originPx + 0.5, originPx + sizePx - 0.5);
    return texture(tex, vec4(pixelCoord / atlasSize, float(layer), compareDepth));
}

float samplePositionalShadowPCF1x1(
    sampler2DArrayShadow tex, vec2 atlasUV, int layer, float compareDepth, vec2 originPx, float sizePx
) {
    return fetchPositionalShadowTexel(tex, atlasUV, layer, compareDepth, originPx, sizePx, 0);
}

float samplePositionalShadowPCF2x2(
    sampler2DArrayShadow tex, vec2 atlasUV, int layer, float compareDepth, vec2 originPx, float sizePx
) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0).xy);
    vec2 offset = atlasUV - .5 * texelSize;

    float c00 = fetchPositionalShadowTexel(tex, offset + vec2(0, 0) * texelSize, layer, compareDepth, originPx, sizePx, 0);
    float c10 = fetchPositionalShadowTexel(tex, offset + vec2(1, 0) * texelSize, layer, compareDepth, originPx, sizePx, 1);
    float c01 = fetchPositionalShadowTexel(tex, offset + vec2(0, 1) * texelSize, layer, compareDepth, originPx, sizePx, 2);
    float c11 = fetchPositionalShadowTexel(tex, offset + vec2(1, 1) * texelSize, layer, compareDepth, originPx, sizePx, 3);

    return (c00 + c10 + c01 + c11) * 0.25;
}

float samplePositionalShadowPCF3x3(
    sampler2DArrayShadow tex, vec2 atlasUV, int layer, float compareDepth, vec2 originPx, float sizePx
) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0).xy);

    const int kernelSize = 3;
    vec2 offset = atlasUV - float(kernelSize / 2) * texelSize;

    float shadow = 0.0;
    for (int y = 0; y < kernelSize; y++)
        for (int x = 0; x < kernelSize; x++)
            shadow += fetchPositionalShadowTexel(
                tex, offset + vec2(x, y) * texelSize, layer, compareDepth, originPx, sizePx, y * kernelSize + x);

    return shadow / float(kernelSize * kernelSize);
}

    #if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
        // PCSS models an area light (the sun); point lights have no angular size here,
        // so fall back to hardware PCF just like sampleShadowMap does for tiny radii.
        #define samplePositionalShadowFiltered samplePositionalShadowPCF2x2
    #elif SHADOW_FILTERING_KERNEL == 3
        #define samplePositionalShadowFiltered samplePositionalShadowPCF3x3
    #elif SHADOW_FILTERING_KERNEL == 2
        #define samplePositionalShadowFiltered samplePositionalShadowPCF2x2
    #else
        #define samplePositionalShadowFiltered samplePositionalShadowPCF1x1
    #endif
#endif
