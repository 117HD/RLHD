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
float sampleShadowMap(vec3 fragPos, vec2 distortion, vec3 surfaceNormal, bool applyBias, bool applyNormalBias) {
    if (!uboGlobal.castsShadows)
        return 0.f;

    vec4 shadowPos = uboGlobal.lightProjectionMatrix * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    // Fade out shadows near the shadow map edges
    #if ZONE_RENDERER
        float fadeEnd = max(uboGlobal.shadowDrawDistance, 1.0);
        float fadeStart = max(fadeEnd * .5, fadeEnd - 10.0 * TILE_SIZE);
        float fadeOut = smoothstep(fadeStart, fadeEnd, length(fragPos - uboGlobal.cameraPos));
    #else
        float fadeOut = smoothstep(.75, 1., dot(shadowPos.xy, shadowPos.xy));
    #endif
    if (fadeOut >= 1)
        return 0.f;

    // NDC to texture space
    shadowPos.xyz += 1;
    shadowPos.xyz /= 2;
    vec2 shadowMapSize = vec2(textureSize(shadowMap, 0));
    float bias = 0.0;
    float depthPrecisionBias = 0.0;
    vec2 receiverDepthPerTexel = vec2(0.0);
    if (applyBias) {
        vec3 receiverNormal = surfaceNormal * mat3(uboGlobal.invLightProjectionMatrix);
        // A parallel receiver has unbounded slope. Keep it finite before
        // the per-tap correction is bounded, including zero-offset taps.
        receiverNormal.z = (receiverNormal.z < 0.0 ? -1.0 : 1.0) * max(abs(receiverNormal.z), 1e-7);
        // Keep the actual plane slope for each filter tap; clipping it creates self-shadowing.
        receiverDepthPerTexel = -receiverNormal.xy / receiverNormal.z / shadowMapSize;

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
    shadowPos.xy += distortion;
    shadowPos.xy = clamp(shadowPos.xy, 0, 1);
    vec4 receiverPlane = vec4(shadowPos.xy * shadowMapSize, receiverDepthPerTexel);

    float shadow = sampleShadow(
        shadowMap,
        SHADOW_TRANSPARENCY == 1,
        shadowPos.z - (bias + depthPrecisionBias),
        shadowPos,
        receiverPlane,
        fragPos
    );

    #if TERRAIN_SHADOWS
        if (shadow < 1.0) {
            float terrainBias = bias * 3.15;
            vec2 terrainMapSize = vec2(textureSize(terrainShadowMap, 0));
            vec4 terrainReceiverPlane = vec4(
                shadowPos.xy * terrainMapSize,
                receiverDepthPerTexel * shadowMapSize / terrainMapSize
            );
            #if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
                float terrainShadow = sampleShadowPCSS(
                    terrainShadowMap, false, shadowPos.z + terrainBias, shadowPos, terrainReceiverPlane, fragPos);
            #else
                // Hardware PCF shares one reference depth across its bilinear footprint
                terrainBias -= dot(abs(terrainReceiverPlane.zw), vec2(1.0));
                float terrainShadow = sampleHardwareShadow(
                    terrainShadowMap, shadowPos.z + terrainBias, shadowPos, terrainReceiverPlane);
            #endif
            shadow = max(shadow, terrainShadow);
        }
    #endif

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, distortion, surfaceNormal, applyBias, applyNormalBias) 0
#endif
