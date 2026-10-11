// Based on: https://developer.download.nvidia.com/shaderlibrary/docs/shadow_PCSS.pdf
#pragma once

#include <uniforms/global.glsl>

#include <utils/constants.glsl>
#include <utils/hash.glsl>

const float SUN_SHADOW_RADIUS = radians(0.53 * 0.5);
const int PCSS_BLOCKER_SAMPLES = 4;
const int PCSS_FILTER_SAMPLES = 8;

vec2 pcssDiskOffset(int i, int sampleCount) {
    // Equal-area sunflower pattern; rotate it per scene-space cell below.
    float radius = sqrt((i + 0.5) / sampleCount);
    float angle = i * 2.39996323;
    return radius * vec2(cos(angle), sin(angle));
}

// Return normalized depth and opacity. Terrain uses ordinary depth; the main
// map may pack transparency into the high bits, which must not enter distances.
vec2 pcssBlocker(sampler2D tex, bool hasTransparency, ivec2 pixel) {
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, textureSize(tex, 0))))
        return vec2(1.0, 0.0);
    float value = texelFetch(tex, pixel, 0).r;
    if (hasTransparency) {
        int alphaDepth = int(value * SHADOW_COMBINED_MAX);
        return vec2(
            float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX,
            1.0 - float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX
        );
    }
    return vec2(value, value < 1.0 ? 1.0 : 0.0);
}

float sampleShadowPCSS(
    sampler2D tex,
    bool hasTransparency,
    float fragDepth,
    vec4 shadowPos,
    vec4 receiverPlane,
    vec3 receiverPosition
) {
    vec2 mapSize = vec2(textureSize(tex, 0));
    float depthRange = 2.0 * length(uboGlobal.invLightProjectionMatrix[2].xyz);
    vec2 mapExtent = 2.0 * vec2(
        length(uboGlobal.invLightProjectionMatrix[0].xyz),
        length(uboGlobal.invLightProjectionMatrix[1].xyz)
    );
    vec2 radiusPerDepth = depthRange * tan(SUN_SHADOW_RADIUS) / mapExtent;
    // The light's near plane bounds the possible blocker distance. This is a
    // conservative search cone, not an arbitrary world-space softness limit.
    vec2 searchRadius = max(shadowPos.z, 0.0) * radiusPerDepth;
    if (max(searchRadius.x * mapSize.x, searchRadius.y * mapSize.y) <= 0.5)
        return sampleShadowPCF2x2(tex, hasTransparency, fragDepth, shadowPos, receiverPlane, receiverPosition);

    vec3 cell = floor(receiverPosition * 0.5);
    float angle = TAU * hash13(cell);
    float c = cos(angle), s = sin(angle);
    mat2 rotation = mat2(c, s, -s, c);

    float separationSum = 0.0;
    float blockerWeight = 0.0;
    float correctionLimit = 64.0 / depthRange; // Match fetchShadowTexel's receiver-plane bound.
    // Include the center so a contact shadow cannot disappear between search taps.
    for (int i = -1; i < PCSS_BLOCKER_SAMPLES; i++) {
        vec2 offset = i < 0 ? vec2(0.0) : (rotation * pcssDiskOffset(i, PCSS_BLOCKER_SAMPLES)) * searchRadius;
        ivec2 pixel = ivec2(floor((shadowPos.xy + offset) * mapSize));
        vec2 blocker = pcssBlocker(tex, hasTransparency, pixel);
        float correction = clamp(dot(vec2(pixel) + 0.5 - receiverPlane.xy, receiverPlane.zw),
            -correctionLimit, correctionLimit);
        if (blocker.y > 0.0 && blocker.x < fragDepth + correction) {
            // Bias decides visibility, but must not shorten the physical separation.
            separationSum += max(0.0, shadowPos.z + correction - blocker.x) * blocker.y;
            blockerWeight += blocker.y;
        }
    }
    if (blockerWeight == 0.0)
        return sampleShadowPCF2x2(tex, hasTransparency, fragDepth, shadowPos, receiverPlane, receiverPosition);

    vec2 filterRadius = radiusPerDepth * (separationSum / blockerWeight);
    if (max(filterRadius.x * mapSize.x, filterRadius.y * mapSize.y) <= 0.5)
        return sampleShadowPCF2x2(tex, hasTransparency, fragDepth, shadowPos, receiverPlane, receiverPosition);

    // Decorrelate visibility sampling from the search that estimated its radius,
    // while retaining the same stable scene-space anchor.
    angle = TAU * hash13(cell + vec3(17, 59, 113));
    c = cos(angle);
    s = sin(angle);
    rotation = mat2(c, s, -s, c);

    float shadow = 0.0;
    for (int i = 0; i < PCSS_FILTER_SAMPLES; i++) {
        vec4 tap = shadowPos;
        tap.xy += (rotation * pcssDiskOffset(i, PCSS_FILTER_SAMPLES)) * filterRadius;
        // Bilinear comparison filtering stabilizes sub-texel motion. Retain the
        // original receiver plane so displaced taps compare at the proper depth.
        shadow += sampleShadowPCF1x1(tex, hasTransparency, fragDepth, tap, receiverPlane, receiverPosition);
    }
    return shadow / float(PCSS_FILTER_SAMPLES);
}
