#pragma once

#include <uniforms/global.glsl>

#include <utils/hash.glsl>
#include <utils/misc.glsl>

// Bias limits in world space units
const float MODEL_SHADOW_BIAS_LIMIT = 115.0;
const float TERRAIN_SHADOW_BIAS_LIMIT = 69.0;

struct ShadowReceiver {
    vec2 texelPos; // Texel position for a particular shadow map
    vec2 depthPerTexel; // Normalized depth per texel X & Y
    float depth; // Unbiased reference depth
    float bias; // Bias in normalized depth units
    float biasLimit; // Bias limit in normalized depth units
};

float limitShadowBias(float bias, float limit) {
    float magnitude = abs(bias);
    // Preserve small offsets exactly, and approach the limit asymptotically
    float knee = limit * 0.5;
    if (magnitude > knee)
        bias = sign(bias) * (limit - knee * (knee / magnitude));
    return bias;
}

vec2 getShadowDitherOffset(int i) {
#if SHADOW_FILTERING == SHADOW_FILTERING_DITHER
    int firstIndex = int(hash12(gl_FragCoord.xy) * POISSON_DISK_LENGTH);
    int index = (firstIndex + i) % POISSON_DISK_LENGTH;
    return getPoissonDisk(index) * 1.25;
#else
    return vec2(0.0);
#endif
}

float fetchShadowTexel(sampler2D tex, bool hasTransparency, ShadowReceiver receiver, int i, ivec2 texelPos) {
    texelPos += ivec2(getShadowDitherOffset(i));
    #if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
        // texelFetch does not apply the texture's clamp-to-border setting.
        if (any(lessThan(texelPos, ivec2(0))) || any(greaterThanEqual(texelPos, textureSize(tex, 0))))
            return 0.0;
    #endif
    float bias = dot(vec2(texelPos) + 0.5 - receiver.texelPos, receiver.depthPerTexel);
    float fragDepth = receiver.depth + limitShadowBias(bias - receiver.bias, receiver.biasLimit);

    if (hasTransparency) {
        int alphaDepth = int(texelFetch(tex, texelPos, 0).r * SHADOW_COMBINED_MAX);
        float depth = float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
        float alpha = 1 - float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
        return depth < fragDepth ? alpha : 0.f;
    }

    return texelFetch(tex, texelPos, 0).r < fragDepth ? 1.f : 0.f;
}

float sampleShadowPCF1x1(sampler2D tex, bool hasTransparency, ShadowReceiver receiver, vec3 fragPos, vec4 shadowPos) {
    ivec2 texelPos = ivec2(shadowPos.xy * textureSize(tex, 0));
    return fetchShadowTexel(tex, hasTransparency, receiver, 0, texelPos);
}

float sampleShadowPCF2x2(sampler2D tex, bool hasTransparency, ShadowReceiver receiver, vec3 fragPos, vec4 shadowPos) {
    shadowPos.xy *= textureSize(tex, 0);
    shadowPos.xy -= .5; // Shift so the 2x2 kernel straddles the sample point

    ivec2 offset = ivec2(floor(shadowPos.xy));
    float c00 = fetchShadowTexel(tex, hasTransparency, receiver, 0, offset + ivec2(0, 0));
    float c10 = fetchShadowTexel(tex, hasTransparency, receiver, 1, offset + ivec2(1, 0));
    float c01 = fetchShadowTexel(tex, hasTransparency, receiver, 2, offset + ivec2(0, 1));
    float c11 = fetchShadowTexel(tex, hasTransparency, receiver, 3, offset + ivec2(1, 1));

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

float sampleShadowPCF3x3(sampler2D tex, bool hasTransparency, ShadowReceiver receiver, vec3 fragPos, vec4 shadowPos) {
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

    float c00 = fetchShadowTexel(tex, hasTransparency, receiver, 0, offset + ivec2(0, 0));
    float c02 = fetchShadowTexel(tex, hasTransparency, receiver, 1, offset + ivec2(0, kernelSize - 1));
    float c20 = fetchShadowTexel(tex, hasTransparency, receiver, 2, offset + ivec2(kernelSize - 1, 0));
    float c22 = fetchShadowTexel(tex, hasTransparency, receiver, 3, offset + ivec2(kernelSize - 1, kernelSize - 1));

    if ((c00 == 0.0 || c00 == 1.0) && c00 == c02 && c00 == c20 && c00 == c22)
        return c00;

    float s01 = fetchShadowTexel(tex, hasTransparency, receiver, 4, offset + ivec2(0, 1));
    float s10 = fetchShadowTexel(tex, hasTransparency, receiver, 5, offset + ivec2(1, 0));
    float s11 = fetchShadowTexel(tex, hasTransparency, receiver, 6, offset + ivec2(1, 1));
    float s12 = fetchShadowTexel(tex, hasTransparency, receiver, 7, offset + ivec2(1, 2));
    float s21 = fetchShadowTexel(tex, hasTransparency, receiver, 8, offset + ivec2(2, 1));

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

float fetchHardwareShadowTexel(sampler2DShadow tex, ShadowReceiver receiver, int i, vec2 shadowPos) {
    vec2 texelPos = shadowPos * textureSize(tex, 0) + getShadowDitherOffset(i);
    float bias = dot(texelPos - receiver.texelPos, receiver.depthPerTexel);
    float fragDepth = receiver.depth + limitShadowBias(bias - receiver.bias, receiver.biasLimit);
    return texture(tex, vec3(texelPos / textureSize(tex, 0), fragDepth));
}

float sampleHardwareShadow1x1(sampler2DShadow tex, ShadowReceiver receiver, vec4 shadowPos) {
    return fetchHardwareShadowTexel(tex, receiver, 0, shadowPos.xy);
}

float sampleHardwareShadow2x2(sampler2DShadow tex, ShadowReceiver receiver, vec4 shadowPos) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));
    vec2 offset = shadowPos.xy - .5 * texelSize;

    float c00 = fetchHardwareShadowTexel(tex, receiver, 0, offset + vec2(0, 0) * texelSize);
    float c10 = fetchHardwareShadowTexel(tex, receiver, 1, offset + vec2(1, 0) * texelSize);
    float c01 = fetchHardwareShadowTexel(tex, receiver, 2, offset + vec2(0, 1) * texelSize);
    float c11 = fetchHardwareShadowTexel(tex, receiver, 3, offset + vec2(1, 1) * texelSize);

    return (c00 + c10 + c01 + c11) * 0.25;
}

float sampleHardwareShadow3x3(sampler2DShadow tex, ShadowReceiver receiver, vec4 shadowPos) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));

    const int kernelSize = 3;
    vec2 offset = shadowPos.xy - float(kernelSize / 2) * texelSize;

    float c00 = fetchHardwareShadowTexel(tex, receiver, 0, offset + vec2(0, 0) * texelSize);
    float c02 = fetchHardwareShadowTexel(tex, receiver, 1, offset + vec2(0, kernelSize - 1) * texelSize);
    float c20 = fetchHardwareShadowTexel(tex, receiver, 2, offset + vec2(kernelSize - 1, 0) * texelSize);
    float c22 = fetchHardwareShadowTexel(tex, receiver, 3, offset + vec2(kernelSize - 1, kernelSize - 1) * texelSize);

    if ((c00 == 0.0 || c00 == 1.0) && c00 == c02 && c00 == c20 && c00 == c22)
        return c00;

    float s01 = fetchHardwareShadowTexel(tex, receiver, 4, offset + vec2(0, 1) * texelSize);
    float s10 = fetchHardwareShadowTexel(tex, receiver, 5, offset + vec2(1, 0) * texelSize);
    float s11 = fetchHardwareShadowTexel(tex, receiver, 6, offset + vec2(1, 1) * texelSize);
    float s12 = fetchHardwareShadowTexel(tex, receiver, 7, offset + vec2(1, 2) * texelSize);
    float s21 = fetchHardwareShadowTexel(tex, receiver, 8, offset + vec2(2, 1) * texelSize);

    float shadow =
        c00 + s01 + c02 +
        s10 + s11 + s12 +
        c20 + s21 + c22;

    return shadow / 9.0;
}
