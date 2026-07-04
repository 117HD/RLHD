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
#elif SHADOW_FILTERING_KERNAL == 2
    #define sampleShadow sampleShadowPCF2x2
#else
    #define sampleShadow sampleShadowPCF1x1
#endif

#if SHADOW_MODE != SHADOW_MODE_OFF
float sampleShadowMap(vec3 fragPos, vec2 distortion, float lightDotNormals) {
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

    // Scale bias with surface angle to light - steeper angles need more bias.
    // tan(acos(x)) == sqrt(1 - x*x) / x (algebraic identity), avoiding two SFU ops.
    // Lower-clamp x by 1e-3 to avoid divide-by-zero; that region already saturates to 16.
    float c = clamp(lightDotNormals, 1e-3, 1.0);
    float slopeBias = clamp(sqrt(1.0 - c * c) / c, 1.0, 16.0);
    float shadowBias = MIN_SHADOW_BIAS * slopeBias;

    float shadow = sampleShadow(shadowMap, SHADOW_TRANSPARENCY == 1, shadowPos.z + shadowBias, shadowPos, fragPos);

    #if TERRAIN_SHADOWS
        if(shadow < 1.0) {
            // Sample terrain shadow map and combine
            const float terrainBias = 0.0005;
            float terrainShadow = sampleHardwareShadow2x2(terrainShadowMap, shadowPos.z + terrainBias, shadowPos, fragPos);
            shadow = max(shadow, terrainShadow);
        }
    #endif

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, distortion, lightDotNormals) 0
#endif
