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

#if SHADOW_FILTERING_KERNAL == 3
    #define sampleShadow sampleShadowPCF3x3
    #define sampleHardwareShadow sampleHardwareShadow3x3
#elif SHADOW_FILTERING_KERNAL == 2
    #define sampleShadow sampleShadowPCF2x2
    #define sampleHardwareShadow sampleHardwareShadow2x2
#else
    #define sampleShadow sampleShadowPCF1x1
    #define sampleHardwareShadow sampleHardwareShadow1x1
#endif

#if SHADOW_MODE != SHADOW_MODE_OFF
float sampleShadowMap(vec3 fragPos, vec2 distortion, vec3 surfaceNormal) {
    if (lightStrength <= 0)
        return 0.f;

    vec4 shadowPos = lightProjectionMatrix * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    // Fade out shadows near the shadow map edges
    #if ZONE_RENDERER
        // TODO: Make this configurable if we make the Shadow Distance Variable
        const float fadeStart = 55.0 * TILE_SIZE;
        const float fadeEnd   = 65.0 * TILE_SIZE;
        float fadeOut = smoothstep(fadeStart, fadeEnd, length(fragPos - cameraPos));
    #else
        float fadeOut = smoothstep(.75, 1., dot(shadowPos.xy, shadowPos.xy));
    #endif
    if (fadeOut >= 1)
        return 0.f;

    // NDC to texture space
    shadowPos.xyz += 1;
    shadowPos.xyz /= 2;
    shadowPos.xy += distortion;
    shadowPos.xy = clamp(shadowPos.xy, 0, 1);
    vec2 shadowMapSize = vec2(textureSize(shadowMap, 0));
    float bias = 0.0;
    vec2 receiverDepthPerTexel = vec2(0.0);
    if (dot(surfaceNormal, surfaceNormal) > 0) {
        vec3 receiverNormal = surfaceNormal * mat3(invLightProjectionMatrix);
        if (abs(receiverNormal.z) > length(receiverNormal) * 1e-4)
            receiverDepthPerTexel = -receiverNormal.xy / receiverNormal.z;

        receiverDepthPerTexel /= shadowMapSize;
        // Bound extrapolation when the receiver is nearly edge-on to the light.
        float gradientLimit = shadowBiasScale * 16.0;
        receiverDepthPerTexel *= min(1.0, gradientLimit / max(length(receiverDepthPerTexel), 1e-8));

        // tan(theta) estimates depth variation across a texel. Limit grazing-angle detachment.
        float c = clamp(abs(dot(surfaceNormal, lightDir)), 1e-3, 1.0);
        float slope = clamp(sqrt(1.0 - c * c) / c, 1.0, 16.0);
        bias = shadowBiasScale * slope;
    }
    vec4 receiverPlane = vec4(shadowPos.xy * shadowMapSize, receiverDepthPerTexel);

    float shadow = sampleShadow(
        shadowMap,
        SHADOW_TRANSPARENCY == 1,
        shadowPos.z - bias * (1 + colorPicker.a * 5),
        shadowPos,
        receiverPlane
    );

    #if TERRAIN_SHADOWS
        if (shadow < 1.0) {
            float terrainBias = bias * 3.15;
            // Hardware PCF shares one reference depth across its bilinear footprint.
            vec2 terrainMapSize = vec2(textureSize(terrainShadowMap, 0));
            vec4 terrainReceiverPlane = vec4(
                shadowPos.xy * terrainMapSize,
                receiverDepthPerTexel * shadowMapSize / terrainMapSize
            );
            terrainBias -= dot(abs(terrainReceiverPlane.zw), vec2(1.0));
            float terrainShadow = sampleHardwareShadow(
                terrainShadowMap, shadowPos.z + terrainBias, shadowPos, terrainReceiverPlane);
            shadow = max(shadow, terrainShadow);
        }
    #endif

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, distortion, surfaceNormal) 0
#endif
