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

#if SHADOW_MODE != SHADOW_MODE_OFF

#extension GL_EXT_shadow_samplers : enable
uniform sampler2D shadowMap;

#if SHADOW_TRANSPARENCY
    uniform isampler2D shadowTransparencyMap;
#endif

#if ZONE_RENDERER && GROUND_SHADOWS
    uniform isampler2D shadowGroundMask;
#endif

vec4 fetchShadowTexel(vec3 worldPos, int i, ivec2 pixelCoord, float fragDepth, bool isGroundPlane) {
#if SHADOW_SHADING == SHADOW_DITHERED_SHADING
    int index = int(hash(vec4(floor(worldPos.xyz), i)) * float(POISSON_DISK_LENGTH)) % POISSON_DISK_LENGTH;
    pixelCoord += ivec2(getPoissonDisk(index) * 1.25);
#endif

    vec2 shadowMapSize = vec2(textureSize(shadowMap, 0));
    vec2 shadowUV = vec2(pixelCoord) / shadowMapSize;
    float shadowDepth = texelFetch(shadowMap, pixelCoord, 0).r;
#if ZONE_RENDERER
    float shadow = shadowDepth > fragDepth ? 1.0 : 0.0;
#else
    float shadow = shadowDepth < fragDepth ? 1.0 : 0.0;
#endif
    vec3 tint = vec3(0.0);

#if ZONE_RENDERER && GROUND_SHADOWS
    if(isGroundPlane && shadow > 0.0 && abs(shadowDepth - fragDepth) < 0.0125) {
        int shadowGroundTileXY = texelFetch(shadowGroundMask, pixelCoord, 0).r;
        if(shadowGroundTileXY != 0) {
            int tileExX = int(worldPos.x / 128.0) % 255;
            int tileExY = int(worldPos.z / 128.0) % 255;

            int shadowGroundTileExX = shadowGroundTileXY & 0xFF;
            int shadowGroundTileExY = (shadowGroundTileXY >> 8) & 0xFF;

            for(int x = -1; x < 2 && shadow > 0; x++) {
                for(int y = -1; y < 2 && shadow > 0; y++) {
                    if(((tileExX + x) % 255) == shadowGroundTileExX && ((tileExY + y) % 255) == shadowGroundTileExY) {
                        shadow = 0; // Ignore Shadow, since its coming from a nearby tile
                    }
                }
            }
        }
    }
#endif

#if SHADOW_TRANSPARENCY
    if(shadow == 0) {
        // Not in shadow, so lets check the transparency map
        ivec2 encoded = texelFetch(shadowTransparencyMap, pixelCoord, 0).rg;
        float depth = float(encoded.r & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
    #if ZONE_RENDERER
        if(depth > fragDepth)
    #else
        if(depth < fragDepth)
    #endif
        {
            float alpha = float(encoded.r >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
        #if SHADOW_TRANSPARENCY == SHADOW_TRANSPARENCY_ENABLED_WITH_TINT
            tint += srgbToLinear(packedHslToSrgb(encoded.g)) * alpha;
        #endif
            shadow = alpha;
        }
    }
#endif

    return vec4(shadow, tint);
}

float sampleShadowMap(vec3 fragPos, vec2 distortion, float lightDotNormals, bool isGroundPlane, out vec3 tint) {
    vec4 shadowPos = directionalCamera.viewProj * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    float fadeOut = 0.0;
#if ZONE_RENDERER
    vec4 camShadowPos = directionalCamera.viewProj * vec4(sceneCamera.position, 1);
    camShadowPos.xyz /= camShadowPos.w;

    float camShadowFade = length(shadowPos - camShadowPos);
    // Fade out shadows in the distance
    fadeOut = clamp((camShadowFade - 0.95) / 0.05, 0.0, 1.0);
#else
    // Fade out shadows near shadow texture edges
    fadeOut = smoothstep(.75, 1., dot(shadowPos.xy, shadowPos.xy));
#endif
    if (fadeOut >= 1)
        return 0.f;

    // NDC to texture space
    ivec2 shadowRes = textureSize(shadowMap, 0);
    #if ZONE_RENDERER
    shadowPos.xy += 1; // Zone Renderer uses 0 -> +1
    shadowPos.xy /= 2;
    #else
    shadowPos.xyz += 1; // Legacy uses -1 -> +1
    shadowPos.xyz /= 2;
    #endif
    shadowPos.xy += distortion;
    shadowPos.xy = clamp(shadowPos.xy, 0, 1);
    shadowPos.xy *= shadowRes;
    shadowPos.xy += .5; // Shift to texel center

    #if ZONE_RENDERER
    vec2 texelSize = 1.0 / vec2(shadowRes);

    vec3 shadowTexDDX = dFdx(shadowPos.xyz);
    vec3 shadowTexDDY = dFdy(shadowPos.xyz);

    shadowTexDDX.xy *= texelSize.x;
    shadowTexDDY.xy *= texelSize.y;

    float receiverBias = abs(dot(shadowTexDDX, vec3(1.0))) + abs(dot(shadowTexDDY, vec3(1.0)));
    float slopeBias = (1.0 - lightDotNormals) * SHADOW_SLOPE_BIAS;

    float shadowBias = SHADOW_CONSTANT_BIAS + slopeBias + receiverBias;
    float fragDepth = shadowPos.z + shadowBias;
    #else
    float shadowMinBias = -0.0009f;
    float shadowBias = shadowMinBias * max(1, (1.0 - lightDotNormals));
    float fragDepth = shadowPos.z + shadowBias;
    #endif

    const int kernelSize = 3;
    ivec2 kernelOffset = ivec2(shadowPos.xy - kernelSize / 2);
    #if SHADOW_SHADING == SHADOW_PIXELATED_SHADING
        const float kernelAreaReciprocal = 1. / (kernelSize * kernelSize);
    #else
        const float kernelAreaReciprocal = .25; // This is effectively a 2x2 kernel
        vec2 lerp = fract(shadowPos.xy);
        vec3 lerpX = vec3(1 - lerp.x, 1, lerp.x);
        vec3 lerpY = vec3(1 - lerp.y, 1, lerp.y);
    #endif

    // Sample 4 corners first
    vec4 c00 = fetchShadowTexel(fragPos, 0, kernelOffset + ivec2(0, 0), fragDepth, isGroundPlane);
    vec4 c02 = fetchShadowTexel(fragPos, 1, kernelOffset + ivec2(0, kernelSize - 1), fragDepth, isGroundPlane);
    vec4 c20 = fetchShadowTexel(fragPos, 2, kernelOffset + ivec2(kernelSize - 1, 0), fragDepth, isGroundPlane);
    vec4 c22 = fetchShadowTexel(fragPos, 3, kernelOffset + ivec2(kernelSize - 1, kernelSize - 1), fragDepth, isGroundPlane);

    // Early exit if all corners are the same (fully shadowed or fully lit)
    bool allShadowed = (c00.r == 0.0 && c02.r == 0.0 && c20.r == 0.0 && c22.r == 0.0);
    bool allLit      = (c00.r == 1.0 && c02.r == 1.0 && c20.r == 1.0 && c22.r == 1.0);

    vec4 combined = vec4(0.0);
    if (allShadowed || allLit) {
        combined = (c00 + c02 + c20 + c22) * 0.25;;
    } else {
        // Finish sampling the reset of the kernal
        vec4 s01 = fetchShadowTexel(fragPos, 4, kernelOffset + ivec2(0, 1), fragDepth, isGroundPlane);
        vec4 s10 = fetchShadowTexel(fragPos, 5, kernelOffset + ivec2(1, 0), fragDepth, isGroundPlane);
        vec4 s11 = fetchShadowTexel(fragPos, 6, kernelOffset + ivec2(1, 1), fragDepth, isGroundPlane);
        vec4 s12 = fetchShadowTexel(fragPos, 7, kernelOffset + ivec2(1, 2), fragDepth, isGroundPlane);
        vec4 s21 = fetchShadowTexel(fragPos, 8, kernelOffset + ivec2(2, 1), fragDepth, isGroundPlane);

        #if SHADOW_SHADING == SHADOW_PIXELATED_SHADING
            combined =
                c00 + s01 + c02 +
                s10 + s11 + s12 +
                c20 + s21 + c22;
        #else
            combined =
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
        combined *= kernelAreaReciprocal;
    }
    combined *= (1.0 - fadeOut);
    tint = combined.gba;
    return combined.r;
}
#else
#define sampleShadowMap(fragPos, distortion, lightDotNormals, tint) 0
#endif
