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

#if SHADOW_QUALITY == 0
    #define MIN_SHADOW_BIAS -0.00125f
#elif SHADOW_QUALITY == 1
    #define MIN_SHADOW_BIAS -0.0007f
#elif SHADOW_QUALITY == 2
    #define MIN_SHADOW_BIAS -0.00035
#elif SHADOW_QUALITY == 3
    #define MIN_SHADOW_BIAS -0.0003
#elif SHADOW_QUALITY >= 4
    #define MIN_SHADOW_BIAS -0.00025
#endif

#if SHADOW_MODE != SHADOW_MODE_OFF
float fetchShadowTexel(ivec2 pixelCoord, float fragDepth, vec3 fragPos, int i) {
    #if SHADOW_FILTERING == SHADOW_DITHERED_SHADING
        int index = int(hash(vec4(floor(fragPos.xyz), i)) * float(POISSON_DISK_LENGTH)) % POISSON_DISK_LENGTH;
        pixelCoord += ivec2(getPoissonDisk(index) * 1.25);
    #endif

    #if SHADOW_TRANSPARENCY
        int alphaDepth = int(texelFetch(shadowMap, pixelCoord, 0).r * SHADOW_COMBINED_MAX);
        float depth = float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
        float alpha = 1 - float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
        return depth < fragDepth ? alpha : 0.f;
    #else
        return texelFetch(shadowMap, pixelCoord, 0).r < fragDepth ? 1.f : 0.f;
    #endif
}

float sampleShadowMap(vec3 fragPos, vec2 distortion, float lightDotNormals) {
    vec4 shadowPos = lightProjectionMatrix * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    // Fade out shadows near shadow texture edges
    float fadeOut = smoothstep(.85, 1., dot(shadowPos.xy, shadowPos.xy));
    if (fadeOut >= 1)
        return 0.f;

    // NDC to texture space
    ivec2 shadowRes = textureSize(shadowMap, 0);
    shadowPos.xyz += 1;
    shadowPos.xyz /= 2;
    shadowPos.xy += distortion;
    shadowPos.xy = clamp(shadowPos.xy, 0, 1);
    shadowPos.xy *= shadowRes;
    shadowPos.xy += .5; // Shift to texel center

    float shadowBias = MIN_SHADOW_BIAS * max(1, (1.0 - lightDotNormals));
    float fragDepth = shadowPos.z + shadowBias;

    const int kernelSize = 3;
    ivec2 kernelOffset = ivec2(shadowPos.xy - kernelSize / 2);
    #if SHADOW_FILTERING == SHADOW_PIXELATED_SHADING
        const float kernelAreaReciprocal = 1. / (kernelSize * kernelSize);
    #else
        const float kernelAreaReciprocal = .25; // This is effectively a 2x2 kernel
        vec2 lerp = fract(shadowPos.xy);
        vec3 lerpX = vec3(1 - lerp.x, 1, lerp.x);
        vec3 lerpY = vec3(1 - lerp.y, 1, lerp.y);
    #endif

    // Sample 4 corners first
    float c00 = fetchShadowTexel(kernelOffset + ivec2(0, 0), fragDepth, fragPos, 0);
    float c02 = fetchShadowTexel(kernelOffset + ivec2(0, kernelSize - 1), fragDepth, fragPos, 1);
    float c20 = fetchShadowTexel(kernelOffset + ivec2(kernelSize - 1, 0), fragDepth, fragPos, 2);
    float c22 = fetchShadowTexel(kernelOffset + ivec2(kernelSize - 1, kernelSize - 1), fragDepth, fragPos, 3);

    // Early exit if all corners are the same (fully shadowed or fully lit)
    bool allShadowed = (c00 == 0.0 && c02 == 0.0 && c20 == 0.0 && c22 == 0.0);
    bool allLit      = (c00 == 1.0 && c02 == 1.0 && c20 == 1.0 && c22 == 1.0);

    float shadow = 0.0;
    if (allShadowed || allLit) {
        shadow = (c00 + c02 + c20 + c22) * 0.25;
    } else {
        // Finish sampling the reset of the kernal
        float s01 = fetchShadowTexel(kernelOffset + ivec2(0, 1), fragDepth, fragPos, 4);
        float s10 = fetchShadowTexel(kernelOffset + ivec2(1, 0), fragDepth, fragPos, 5);
        float s11 = fetchShadowTexel(kernelOffset + ivec2(1, 1), fragDepth, fragPos, 6);
        float s12 = fetchShadowTexel(kernelOffset + ivec2(1, 2), fragDepth, fragPos, 7);
        float s21 = fetchShadowTexel(kernelOffset + ivec2(2, 1), fragDepth, fragPos, 8);

        #if SHADOW_FILTERING == SHADOW_PIXELATED_SHADING
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
        shadow *= kernelAreaReciprocal;
    }

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, distortion, lightDotNormals) 0
#endif
