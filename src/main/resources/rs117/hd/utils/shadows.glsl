/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * Copyright (c) 2024, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#pragma once

#include <uniforms/global.glsl>

#include <utils/constants.glsl>
#include <utils/misc.glsl>
#include <utils/shadow_filtering.glsl>

#if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
    #include <utils/shadow_pcss.glsl>
    #define sampleShadow sampleShadowPCSS
#elif SHADOW_FILTERING_KERNEL == 3
    #define sampleShadow sampleShadowPCF3x3
    #define sampleHardwareShadow sampleHardwareShadow3x3
#elif SHADOW_FILTERING_KERNEL == 2
    #define sampleShadow sampleShadowPCF2x2
    #define sampleHardwareShadow sampleHardwareShadow2x2
#else
    #define sampleShadow sampleShadowPCF1x1
    #define sampleHardwareShadow sampleHardwareShadow1x1
#endif

#if SHADOW_MODE != SHADOW_MODE_OFF
float shadowDistanceFade(vec3 fragPos) {
    // Fade out shadows near the shadow map edges
    #if ZONE_RENDERER
        float fadeEnd = max(uboGlobal.shadowDrawDistance, 1.0);
        float fadeStart = max(fadeEnd * .5, fadeEnd - 10.0 * TILE_SIZE);
        return smoothstep(fadeStart, fadeEnd, length(fragPos - uboGlobal.cameraPos));
    #else
        vec4 shadowPos = uboGlobal.lightProjectionMatrix * vec4(fragPos, 1);
        vec2 ndc = shadowPos.xy / shadowPos.w;
        return smoothstep(.75, 1., dot(ndc, ndc));
    #endif
}

float sampleShadowMap(
    vec3 fragPos,
    vec3 surfaceNormal,
    bool preventSelfShadowing,
    bool applyNormalBias,
    bool applyDistanceFade
) {
    if (!uboGlobal.castsShadows)
        return 0.f;

    float fadeOut = applyDistanceFade ? shadowDistanceFade(fragPos) : 0.0;
    if (fadeOut >= 1)
        return 0.f;

    vec4 shadowPos = uboGlobal.lightProjectionMatrix * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    // NDC to texture space
    shadowPos.xyz += 1;
    shadowPos.xyz /= 2;
    vec2 shadowMapSize = vec2(textureSize(shadowMap, 0));
    float bias = 0.0;
    float depthPrecisionBias = 0.0;
    vec3 receiverNormal = surfaceNormal * mat3(uboGlobal.invLightProjectionMatrix);
    // A parallel receiver has unbounded slope. Keep it finite before
    // the per-tap correction is bounded, including zero-offset taps.
    receiverNormal.z = (receiverNormal.z < 0.0 ? -1.0 : 1.0) * max(abs(receiverNormal.z), 1e-7);
    // Filtering follows the geometric receiver plane
    vec2 receiverDepthPerTexel = -receiverNormal.xy / receiverNormal.z / shadowMapSize;

    if (preventSelfShadowing) {
        // Move the receiver plane toward the light along its normal.
        // Shift XY as well as depth so every filter tap evaluates the displaced plane.
        if (applyNormalBias) {
            const float worldSpaceBias = 3.f;
            vec3 lightAxis = uboGlobal.invLightProjectionMatrix[2].xyz;
            vec3 normalOffset = -normalize(surfaceNormal) * sign(dot(surfaceNormal, lightAxis));
            shadowPos.xyz += (mat3(uboGlobal.lightProjectionMatrix) * normalOffset * worldSpaceBias) * 0.5;
        }

        // Bound only the extra safety margin to limit detached shadows at grazing angles.
        bias = clamp(length(receiverDepthPerTexel), uboGlobal.shadowBiasScale, uboGlobal.shadowBiasScale * 16.0);
        // Both the depth texture and packed transparent shadows retain 16 depth bits
        // Cover one truncated depth step plus a step of rounding margin, independently of resolution
        depthPrecisionBias = 2.0 / float(SHADOW_DEPTH_MAX);
    }
    shadowPos.xy = clamp(shadowPos.xy, 0, 1);
    // The inverse projection's Z column spans half the camera's depth range.
    float depthRange = 2.0 * length(uboGlobal.invLightProjectionMatrix[2].xyz);
    ShadowReceiver receiver = ShadowReceiver(
        shadowPos.xy * shadowMapSize,
        receiverDepthPerTexel,
        shadowPos.z,
        bias + depthPrecisionBias,
        MODEL_SHADOW_BIAS_LIMIT / depthRange
    );

    float shadow = sampleShadow(shadowMap, SHADOW_TRANSPARENCY == 1, receiver, fragPos, shadowPos);

    #if TERRAIN_SHADOWS
        if (shadow < 1.0) {
            vec2 terrainMapSize = vec2(textureSize(terrainShadowMap, 0));
            vec2 texelScale = shadowMapSize / terrainMapSize;
            ShadowReceiver terrainReceiver = ShadowReceiver(
                shadowPos.xy * terrainMapSize,
                receiverDepthPerTexel * texelScale,
                shadowPos.z,
                bias * texelScale.x + depthPrecisionBias,
                TERRAIN_SHADOW_BIAS_LIMIT / depthRange
            );
            #if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
                float terrainShadow = sampleShadowPCSS(terrainShadowMap, false, terrainReceiver, fragPos, shadowPos);
            #else
                // Hardware PCF shares one reference depth across its bilinear footprint
                if (preventSelfShadowing)
                    terrainReceiver.bias += dot(abs(terrainReceiver.depthPerTexel), vec2(1.0));
                float terrainShadow = sampleHardwareShadow(terrainShadowMap, terrainReceiver, shadowPos);
            #endif
            shadow = max(shadow, terrainShadow);
        }
    #endif

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, surfaceNormal, preventSelfShadowing, applyNormalBias, applyDistanceFade) 0.0
#endif
